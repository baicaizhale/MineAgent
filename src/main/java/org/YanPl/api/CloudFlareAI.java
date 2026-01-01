package org.YanPl.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;
import org.YanPl.MineAgent;
import org.YanPl.model.DialogueSession;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * CloudFlare Workers AI API 集成
 */
public class CloudFlareAI {
    private static final String API_RESPONSES_URL = "https://api.cloudflare.com/client/v4/accounts/%s/ai/v1/responses";
    private static final String ACCOUNTS_URL = "https://api.cloudflare.com/client/v4/accounts";
    private final MineAgent plugin;
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();
    private String cachedAccountId = null;

    public CloudFlareAI(MineAgent plugin) {
        this.plugin = plugin;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    public OkHttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * 关闭 HTTP 客户端，释放资源
     */
    public void shutdown() {
        httpClient.dispatcher().executorService().shutdown();
        httpClient.connectionPool().evictAll();
        if (httpClient.cache() != null) {
            try {
                httpClient.cache().close();
            } catch (IOException ignored) {}
        }
    }

    /**
     * 自动获取 CloudFlare Account ID
     */
    private String fetchAccountId() throws IOException {
        if (cachedAccountId != null) return cachedAccountId;

        String cfKey = plugin.getConfigManager().getCloudflareCfKey();
        if (cfKey.isEmpty()) {
            throw new IOException("错误: 请先在配置文件中设置 cloudflare.cf_key。");
        }

        Request request = new Request.Builder()
                .url(ACCOUNTS_URL)
                .addHeader("Authorization", "Bearer " + cfKey)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("获取 Account ID 失败: " + response.code() + " " + response.message());
            }

            String responseBody = response.body().string();
            JsonObject resultJson = gson.fromJson(responseBody, JsonObject.class);
            
            if (resultJson.has("result") && resultJson.getAsJsonArray("result").size() > 0) {
                cachedAccountId = resultJson.getAsJsonArray("result").get(0).getAsJsonObject().get("id").getAsString();
                return cachedAccountId;
            } else {
                throw new IOException("未找到关联的 CloudFlare 账户，请检查 cf_key 权限。");
            }
        }
    }

    /**
     * 发送对话请求
     */
    public String chat(DialogueSession session, String systemPrompt) throws IOException {
        String cfKey = plugin.getConfigManager().getCloudflareCfKey();
        String model = plugin.getConfigManager().getCloudflareModel();

        if (cfKey.isEmpty()) {
            return "错误: 请先在配置文件中设置 CloudFlare cf_key。";
        }

        // 自动获取 Account ID
        String accountId;
        try {
            accountId = fetchAccountId();
        } catch (IOException e) {
            plugin.getLogger().severe("[AI Error] Failed to fetch Account ID: " + e.getMessage());
            throw e;
        }

        // 使用 /ai/v1/responses 接口，这是 gpt-oss-120b 推荐的接口
        String url = String.format(API_RESPONSES_URL, accountId);
        plugin.getLogger().info("[AI Request] URL: " + url);

        JsonArray messagesArray = new JsonArray();

        // 2. 添加历史记录 (role: user/assistant)
        for (DialogueSession.Message msg : session.getHistory()) {
            String content = msg.getContent();
            String role = msg.getRole();
            if (content == null || content.isEmpty() || role == null || role.isEmpty()) continue;
            
            // 跳过可能已经被误加进去的 system 消息
            if ("system".equalsIgnoreCase(role)) continue;
            
            JsonObject m = new JsonObject();
            m.addProperty("role", role);
            m.addProperty("content", content);
            messagesArray.add(m);
        }

        // 如果没有任何消息，至少添加一条占位符消息
        if (messagesArray.size() == 0) {
            JsonObject m = new JsonObject();
            m.addProperty("role", "user");
            m.addProperty("content", "Hello");
            messagesArray.add(m);
        }

        // 构建符合 /ai/v1/responses 接口要求的请求体
        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);
        bodyJson.add("input", messagesArray);
        
        // 1. 添加系统提示词 (对于 gpt-oss-120b，必须使用 instructions 字段而不是 input 数组中的 system 角色)
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            bodyJson.addProperty("instructions", systemPrompt);
        }
        
        // 如果是 gpt-oss 模型，添加推理参数
        if (model.contains("gpt-oss")) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", "medium");
            bodyJson.add("reasoning", reasoning);
        }
        
        String bodyString = gson.toJson(bodyJson);

        plugin.getLogger().info("[AI Request] Model: " + model);
        plugin.getLogger().info("[AI Request] Payload: " + bodyString);

        RequestBody body = RequestBody.create(
                bodyString,
                MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + cfKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            plugin.getLogger().info("[AI Response] Code: " + response.code());
            plugin.getLogger().info("[AI Response] Body: " + responseBody);

            if (!response.isSuccessful()) {
                throw new IOException("AI 调用失败: " + response.code() + " - " + responseBody);
            }

            JsonObject responseJson = gson.fromJson(responseBody, JsonObject.class);
            
            // 1. 处理新的 /ai/v1/responses (Responses API) 格式
            // 格式: { "output": [ { "type": "message", "content": [ { "type": "output_text", "text": "..." } ] } ] }
            if (responseJson.has("output") && responseJson.get("output").isJsonArray()) {
                JsonArray outputArray = responseJson.getAsJsonArray("output");
                for (int i = 0; i < outputArray.size(); i++) {
                    JsonObject item = outputArray.get(i).getAsJsonObject();
                    if (item.has("type") && "message".equals(item.get("type").getAsString())) {
                        if (item.has("content") && item.get("content").isJsonArray()) {
                            JsonArray contents = item.getAsJsonArray("content");
                            for (int j = 0; j < contents.size(); j++) {
                                JsonObject contentObj = contents.get(j).getAsJsonObject();
                                if (contentObj.has("type") && "output_text".equals(contentObj.get("type").getAsString())) {
                                    return contentObj.get("text").getAsString();
                                }
                            }
                        }
                    }
                }
            }

            // 2. 处理标准 /run 接口返回格式 (备选)
            if (responseJson.has("result")) {
                JsonObject result = responseJson.getAsJsonObject("result");
                
                // 格式 1: { "result": { "response": "..." } }
                if (result.has("response")) {
                    return result.get("response").getAsString();
                }
                
                // 格式 2: { "result": { "choices": [ { "message": { "content": "..." } } ] } }
                if (result.has("choices") && result.getAsJsonArray("choices").size() > 0) {
                    JsonObject firstChoice = result.getAsJsonArray("choices").get(0).getAsJsonObject();
                    if (firstChoice.has("message")) {
                        return firstChoice.getAsJsonObject("message").get("content").getAsString();
                    }
                }
            }

            throw new IOException("无法从 API 响应中解析出结果文本: " + responseBody);
        }
    }
}
