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

        JsonObject bodyJson = new JsonObject();
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

        bodyJson.add("messages", messagesArray);
        String jsonPayload = gson.toJson(bodyJson);
        plugin.getLogger().info("[AI Request] Body: " + jsonPayload);

        RequestBody body = RequestBody.create(
                jsonPayload,
                MediaType.get("application/json; charset=utf-8")
        );

        Request request = new Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer " + cfKey)
                .post(body)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (!response.isSuccessful()) {
                plugin.getLogger().severe("[AI Error] Code: " + response.code());
                plugin.getLogger().severe("[AI Error] Response: " + responseBody);
                throw new IOException("API 请求失败: " + response.code() + " " + response.message());
            }

            JsonObject resultJson = gson.fromJson(responseBody, JsonObject.class);
            
            if (resultJson.has("result")) {
                return resultJson.getAsJsonObject("result").get("response").getAsString();
            } else {
                throw new IOException("API 返回结果异常: " + responseBody);
            }
        }
    }
}
