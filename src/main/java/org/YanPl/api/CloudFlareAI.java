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
    private static final String API_BASE_URL = "https://api.cloudflare.com/client/v4/accounts/%s/ai/v1/responses";
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

        String url = String.format(API_BASE_URL, accountId);
        plugin.getLogger().info("[AI Request] URL: " + url);

        JsonArray messagesArray = new JsonArray();

        // 对于 Responses API，通常建议将系统提示词作为 instructions 字段（如果支持）
        // 或者作为 input 数组的第一个消息。这里我们根据模型类型灵活处理。
        
        // 构建请求体
        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);

        if (model.contains("gpt-oss")) {
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

            // 对于 gpt-oss 模型，使用 Responses API 格式
            bodyJson.add("input", messagesArray);

            // 添加推理参数
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", "medium");
            bodyJson.add("reasoning", reasoning);
        } else {
            // 其他标准模型使用 /run 接口和 messages 格式
            // 注意：如果将来要支持其他模型，可能需要改回 /run 接口
            // 但目前用户要求死磕 gpt-oss-120b
            
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
            bodyJson.add("messages", messagesArray);
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
            
            // Responses API 直接在顶层返回结果，不包含 "result" 包装
            // 而 /run API 会包含 "result" 包装
            JsonObject result;
            if (responseJson.has("result")) {
                result = responseJson.getAsJsonObject("result");
            } else if (responseJson.has("output")) {
                result = responseJson; // Responses API 的顶层就是我们需要的结果
            } else {
                throw new IOException("API 返回结果格式未知: " + responseBody);
            }

            // 1. 尝试处理 result 为 JsonPrimitive 的情况 (如直接返回字符串)
            if (result.isJsonPrimitive()) {
                return result.getAsString();
            }

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

            throw new IOException("无法从 API 响应中解析出结果文本: " + responseBody);
        }
    }
}
