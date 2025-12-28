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
    private static final String API_BASE_URL = "https://api.cloudflare.com/client/v4/accounts/%s/ai/run/%s";
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

        String url = String.format(API_BASE_URL, accountId, model);
        plugin.getLogger().info("[AI Request] URL: " + url);

        JsonArray messagesArray = new JsonArray();

        // 添加系统提示词
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        messagesArray.add(systemMsg);

        // 添加历史记录
        for (DialogueSession.Message msg : session.getHistory()) {
            JsonObject m = new JsonObject();
            m.addProperty("role", msg.getRole());
            m.addProperty("content", msg.getContent());
            messagesArray.add(m);
        }

        // 构建请求体 - 适配某些特定模型的 input 对象包装格式
        JsonObject bodyJson = new JsonObject();
        
        if (model.contains("gpt-oss")) {
            // 对于 gpt-oss 模型，使用 Responses API 的字符串 input 格式
            // 这种格式最稳健，能避免 MoE 模型对 role 的解析错误
            StringBuilder promptBuilder = new StringBuilder();
            promptBuilder.append("System: ").append(systemPrompt).append("\n\n");
            for (DialogueSession.Message msg : session.getHistory()) {
                String role = msg.getRole().substring(0, 1).toUpperCase() + msg.getRole().substring(1);
                promptBuilder.append(role).append(": ").append(msg.getContent()).append("\n");
            }
            promptBuilder.append("Assistant: ");
            bodyJson.addProperty("input", promptBuilder.toString());
        } else {
            // 其他模型使用标准的 input: { messages: [...] } 格式
            JsonObject inputObj = new JsonObject();
            inputObj.add("messages", messagesArray);
            bodyJson.add("input", inputObj);
        }
        
        String bodyString = gson.toJson(bodyJson);

        plugin.getLogger().info("[AI Request] URL: " + url);
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

            JsonObject resultJson = gson.fromJson(responseBody, JsonObject.class);
            if (!resultJson.has("result")) {
                throw new IOException("API 返回结果中缺少 'result' 字段: " + responseBody);
            }

            // 1. 尝试处理 result 为 JsonPrimitive 的情况 (如直接返回字符串)
            if (resultJson.get("result").isJsonPrimitive()) {
                return resultJson.get("result").getAsString();
            }

            if (resultJson.get("result").isJsonObject()) {
                JsonObject result = resultJson.getAsJsonObject("result");

                // 2. 尝试解析旧格式 (result.response)
                if (result.has("response")) {
                    return result.get("response").getAsString();
                }

                // 3. 尝试解析新格式 (result.output 数组)
                if (result.has("output") && result.get("output").isJsonArray()) {
                    JsonArray outputArray = result.getAsJsonArray("output");
                    for (int i = 0; i < outputArray.size(); i++) {
                        JsonObject outputItem = outputArray.get(i).getAsJsonObject();
                        if (outputItem.has("type") && "message".equals(outputItem.get("type").getAsString()) && outputItem.has("content")) {
                            JsonArray contentArray = outputItem.getAsJsonArray("content");
                            for (int j = 0; j < contentArray.size(); j++) {
                                JsonObject contentItem = contentArray.get(j).getAsJsonObject();
                                if (contentItem.has("type") && "output_text".equals(contentItem.get("type").getAsString())) {
                                    return contentItem.get("text").getAsString();
                                }
                            }
                        }
                    }
                }
            }

            throw new IOException("无法从 API 响应中解析出结果文本: " + responseBody);
        }
    }
}
