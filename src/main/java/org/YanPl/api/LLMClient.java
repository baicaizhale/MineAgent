package org.YanPl.api;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.YanPl.FancyHelper;
import org.YanPl.model.AIResponse;
import org.YanPl.model.DialogueSession;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * LLM API 客户端
 * 支持 CloudFlare AI、OpenAI 兼容 API 等多种 provider
 * 负责构建请求、解析响应，以及管理 HttpClient 的生命周期
 */
public class LLMClient {
    private static final String ACCOUNTS_URL = "https://api.cloudflare.com/client/v4/accounts";
    
    private final FancyHelper plugin;
    private final HttpClient httpClient;
    private final Gson gson = new Gson();
    private final ResponseParser responseParser = new ResponseParser();
    private String cachedAccountId = null;
    private BiConsumer<Integer, String> retryCallback = null;

    public LLMClient(FancyHelper plugin) {
        this.plugin = plugin;
        int timeoutSeconds = plugin.getConfigManager().getApiTimeoutSeconds();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .version(HttpClient.Version.HTTP_1_1) // 强制使用 HTTP/1.1 以避免某些 API (如阿里云) 的 HTTP/2 EOF 错误
                .build();
    }

    public HttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * 设置重试回调函数
     * @param callback 当发生重试时的回调，参数1为HTTP状态码，参数2为重试提示消息
     */
    public void setRetryCallback(BiConsumer<Integer, String> callback) {
        this.retryCallback = callback;
    }

    /**
     * 清除重试回调函数
     */
    public void clearRetryCallback() {
        this.retryCallback = null;
    }

    /**
     * 检查配置文件是否加载成功，若加载失败则提前抛出明确错误，
     * 避免下游因配置项取到默认空值而产生误导性报错（如 "请设置 API Key" 而实际是 config.yml 格式错误）。
     */
    private void checkConfigLoaded() throws IOException {
        if (plugin.getConfigManager().isConfigLoadFailed()) {
            throw new IOException("§zFancyHelper§b§r §7> §fconfig.yml 格式错误，无法加载配置文件，请检查控制台输出。");
        }
    }

    /**
     * 发送 HTTP 请求并带有重试机制
     * 解决 java.io.IOException: HTTP/1.1 header parser received no bytes 等偶发性网络问题
     */
    private HttpResponse<String> sendWithRetry(HttpRequest request) throws IOException, InterruptedException {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                String errorMsg = e.getMessage();
                // 常见的偶发性网络错误，值得重试
                if (errorMsg != null && (errorMsg.contains("header parser received no bytes") || 
                    errorMsg.contains("Connection reset") || 
                    errorMsg.contains("EOF reached"))) {
                    
                    plugin.getLogger().warning("[ReTry] 网络请求失败 (尝试 " + (i + 1) + "/" + maxRetries + "): " + errorMsg + "，正在重试...");
                    if (i < maxRetries - 1) {
                        Thread.sleep(500 * (i + 1)); // 指数退避
                        continue;
                    }
                }
                throw e; // 达到最大重试次数或非偶发性错误，抛出异常
            }
        }
        // 理论上不会到达这里，除非 maxRetries <= 0
        throw new IOException("请求失败：超过最大重试次数");
    }

    /**
     * 发送流式 HTTP 请求并带有重试机制（与 sendWithRetry 相同的偶发网络错误判定）。
     * 流式/SSE 请求此前直接 httpClient.send，遇到 "header parser received no bytes" /
     * Connection reset / EOF reached 等偶发错误会直接失败给玩家；此处统一重试，
     * 降低 FancyConsole 链路偶发断连的影响。注意：仅在 send 阶段重试（此时尚未开始
     * processStream，onErrorCallback 未触发，重试是安全的）。
     */
    private <T> HttpResponse<T> sendWithRetryStream(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
        int maxRetries = 3;
        for (int i = 0; i < maxRetries; i++) {
            try {
                return httpClient.send(request, handler);
            } catch (IOException e) {
                String errorMsg = e.getMessage();
                // 常见的偶发性网络错误，值得重试
                if (errorMsg != null && (errorMsg.contains("header parser received no bytes") ||
                    errorMsg.contains("Connection reset") ||
                    errorMsg.contains("EOF reached"))) {
                    plugin.getLogger().warning("[ReTry] 流式网络请求失败 (尝试 " + (i + 1) + "/" + maxRetries + "): " + errorMsg + "，正在重试...");
                    if (i < maxRetries - 1) {
                        Thread.sleep(500 * (i + 1)); // 指数退避
                        continue;
                    }
                }
                throw e; // 达到最大重试次数或非偶发性错误，抛出异常
            }
        }
        // 理论上不会到达这里，除非 maxRetries <= 0
        throw new IOException("请求失败：超过最大重试次数");
    }

    public void shutdown() {
        // Java 标准库的 HttpClient 不需要显式关闭
        // 它使用系统默认的 executor，会随 JVM 退出而终止
        plugin.getLogger().info("[LLMClient] HTTP 客户端已完成关闭（java.net.http.HttpClient 无需特殊操作）。");
    }

    /**
     * 记录交互日志到文件
     * 记录完整的请求和响应内容（排除 API Key，格式化 JSON）
     */
    private void logInteraction(DialogueSession session, String requestBody, String responseBody) {
        try {
            logRequestFormatted(session, requestBody);

            // 记录响应内容
            StringBuilder responseSb = new StringBuilder();
            try {
                JsonElement respEl = gson.fromJson(responseBody, JsonElement.class);
                if (respEl.isJsonObject()) {
                    JsonObject respObj = respEl.getAsJsonObject();
                    
                    // 记录响应内容
                    if (respObj.has("choices") && respObj.get("choices").isJsonArray()) {
                        JsonArray choices = respObj.get("choices").getAsJsonArray();
                        if (!choices.isEmpty()) {
                            JsonObject choice = choices.get(0).getAsJsonObject();
                            if (choice.has("message") && choice.get("message").isJsonObject()) {
                                JsonObject message = choice.get("message").getAsJsonObject();
                                if (message.has("content")) {
                                    String content = message.get("content").getAsString();
                                    responseSb.append(content).append("\n");
                                }
                            }
                            if (choice.has("finish_reason")) {
                                responseSb.append("\nFinish Reason: ").append(choice.get("finish_reason").getAsString()).append("\n");
                            }
                        }
                    }
                    
                    // 记录 token 使用情况
                    if (respObj.has("usage") && respObj.get("usage").isJsonObject()) {
                        JsonObject usage = respObj.get("usage").getAsJsonObject();
                        responseSb.append("\nToken Usage: ");
                        if (usage.has("prompt_tokens")) {
                            responseSb.append("prompt=").append(usage.get("prompt_tokens").getAsInt());
                        }
                        if (usage.has("completion_tokens")) {
                            responseSb.append(", completion=").append(usage.get("completion_tokens").getAsInt());
                        }
                        if (usage.has("total_tokens")) {
                            responseSb.append(", total=").append(usage.get("total_tokens").getAsInt());
                        }
                        // 上下文缓存命中统计（DeepSeek 等返回 prompt_cache_hit_tokens / prompt_cache_miss_tokens）
                        if (usage.has("prompt_cache_hit_tokens") || usage.has("prompt_cache_miss_tokens")) {
                            long cacheHit = usage.has("prompt_cache_hit_tokens") ? usage.get("prompt_cache_hit_tokens").getAsLong() : 0;
                            long cacheMiss = usage.has("prompt_cache_miss_tokens") ? usage.get("prompt_cache_miss_tokens").getAsLong() : 0;
                            long total = cacheHit + cacheMiss;
                            long pct = total > 0 ? cacheHit * 100 / total : 0;
                            responseSb.append(", cache_hit=").append(cacheHit).append(" (").append(pct).append("%), cache_miss=").append(cacheMiss);
                        }
                        responseSb.append("\n");
                    }
                }
            } catch (Exception e) {
                responseSb.append("Raw Response:\n").append(responseBody).append("\n");
            }
            session.logAIResponse(responseSb.toString());
            
        } catch (Exception e) {
            // 异常回退 - 使用原始方法记录
            session.appendLog("AI_RAW_DEBUG", "Request:\n" + requestBody + "\n\nResponse:\n" + responseBody);
        }
    }


    /**
     * 解析请求 JSON 并格式化写入日志（流式和非流式共用）。
     * 只记录本次新增的消息，更新 lastLoggedMessageCount。
     */
    private void logRequestFormatted(DialogueSession session, String requestBody) {
        StringBuilder sb = new StringBuilder();
        try {
            JsonElement reqEl = gson.fromJson(requestBody, JsonElement.class);
            if (reqEl.isJsonObject()) {
                JsonObject reqObj = reqEl.getAsJsonObject();
                if (reqObj.has("model")) {
                    sb.append("Model: ").append(reqObj.get("model").getAsString()).append("\n\n");
                }
                // 支持 "messages" (OpenAI) 和 "input" (CloudFlare Responses API)
                String msgKey = reqObj.has("messages") ? "messages" : "input";
                if (reqObj.has(msgKey) && reqObj.get(msgKey).isJsonArray()) {
                    JsonArray messages = reqObj.get(msgKey).getAsJsonArray();
                    int lastLoggedCount = session.getLastLoggedMessageCount();
                    int newMessageCount = messages.size() - lastLoggedCount;
                    if (newMessageCount > 0) {
                        sb.append("New Messages (").append(newMessageCount).append(" items):\n");
                        for (int i = lastLoggedCount; i < messages.size(); i++) {
                            JsonObject msg = messages.get(i).getAsJsonObject();
                            if (msg.has("role") && msg.has("content")) {
                                String role = msg.get("role").getAsString();
                                String content = msg.get("content").getAsString();
                                if ("system".equals(role)) {
                                    session.logSystemPrompt(content);
                                } else {
                                    sb.append("\n[").append(role.toUpperCase()).append("]:\n");
                                    sb.append(content).append("\n");
                                }
                            }
                        }
                        session.setLastLoggedMessageCount(messages.size());
                    } else {
                        sb.append("No new messages (all already logged)\n");
                    }
                }
                if (reqObj.has("max_tokens")) {
                    sb.append("\nMax Tokens: ").append(reqObj.get("max_tokens").getAsInt()).append("\n");
                }
                if (reqObj.has("temperature")) {
                    sb.append("Temperature: ").append(reqObj.get("temperature").getAsDouble()).append("\n");
                }
            }
        } catch (Exception e) {
            sb.append("Raw Request:\n").append(requestBody).append("\n");
        }
        session.logAIRequest(sb.toString());
    }

    /** 动态尾部标记：附加过一次后不再重复追加（重试场景），保证消息字节稳定 */
    private static final String DYNAMIC_TAIL_MARKER = "[System Info]";

    /**
     * 构建动态尾部内容：玩家名 + 当前时间 + 上次工具报错。
     * 这些内容每次请求都可能变，不能放进 system 前缀（会把其后整段历史的上下文缓存作废）。
     */
    private String buildDynamicTail(org.bukkit.entity.Player player) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n[System Info]\n");
        sb.append("Player: ").append(player.getName()).append("\n");
        sb.append("Current Time: ").append(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append("\n");
        String lastError = plugin.getCliManager().getLastError(player.getUniqueId());
        if (lastError != null && !lastError.isEmpty()) {
            sb.append("\n[Last Action Error]\nYour last tool call failed: ").append(lastError)
              .append("\nCorrect your format in the next attempt.\n");
        }
        return sb.toString();
    }

    /**
     * 把动态尾部追加到最后一条 user 消息尾部并写回历史。
     * 每次请求之间必有新消息入历史，因此上次附加过的消息不会再是最后一条；
     * 唯一例外是失败重试（无新消息），靠 marker 查重跳过（查重与追加在会话锁内原子完成）。
     */
    private void attachDynamicTail(org.bukkit.entity.Player player, DialogueSession session) {
        if (player == null) return;
        session.appendToLastUserMessage(DYNAMIC_TAIL_MARKER, buildDynamicTail(player));
    }

    /**
     * 构建消息数组（OpenAI 和 CloudFlare API 通用）
     * @param systemPrompts 多条独立 system 消息内容（按稳定度排列，静态在前、动态在后，利于前缀缓存命中）。
     *                      空列表或全空元素时回退到默认提示。
     * @param includeReasoningContent true 时把 assistant 消息的思考链作为 reasoning_content 回传。
     *                      DeepSeek V4 思考模式要求：带 tools 的请求必须逐字回传上一轮 reasoning_content，
     *                      否则返回 400。仅 OpenAI 直连路径开启，其他提供商未验证兼容性。
     */
    private JsonArray buildMessagesArray(org.bukkit.entity.Player player, DialogueSession session,
                                         List<String> systemPrompts, boolean includeReasoningContent) {
        // 先把动态信息挂到队尾，再拼数组
        attachDynamicTail(player, session);

        JsonArray messagesArray = new JsonArray();

        boolean addedAnySystem = false;
        if (systemPrompts != null) {
            for (String part : systemPrompts) {
                if (part == null) continue;
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                JsonObject systemMsg = new JsonObject();
                systemMsg.addProperty("role", "system");
                systemMsg.addProperty("content", trimmed);
                messagesArray.add(systemMsg);
                addedAnySystem = true;
            }
        }
        if (!addedAnySystem) {
            JsonObject systemMsg = new JsonObject();
            systemMsg.addProperty("role", "system");
            systemMsg.addProperty("content", "你是一个得力的助手。");
            messagesArray.add(systemMsg);
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[AI 请求] System Prompt 为空，已使用默认提示");
            }
        } else if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[AI 请求] 已添加 " + messagesArray.size() + " 条 System Prompt 消息");
            // 打印每条 system 消息的指纹（内容 hashCode），用于排查缓存命中率：同一会话内指纹应逐条稳定
            for (int i = 0; i < messagesArray.size(); i++) {
                String content = messagesArray.get(i).getAsJsonObject().get("content").getAsString();
                plugin.getLogger().info("[AI 请求] system[" + i + "] 长度=" + content.length() + " 指纹=" + content.hashCode());
            }
        }

        List<DialogueSession.Message> historyCopy = new ArrayList<>(session.getHistory());
        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[AI 请求] 正在处理 " + historyCopy.size() + " 条历史消息");
        }

        // 部分严格网关校验 "system 只能是第一条且后面必须跟 user"（如 reka-flash 上游）。
        // 多条 system 消息同样适用：最后一条 system 后面必须紧跟 user。
        // 进入 CLI 时的欢迎语以 assistant 角色入历史（前面没有 user），会生成 [system..., assistant, ...]
        // 的非法序列。若最后一条 system 后首条历史不是 user，垫一条 user 占位兜底（不写回 history）。
        if (!historyCopy.isEmpty()) {
            String firstRole = historyCopy.get(0).getRole();
            if (firstRole != null && !"user".equalsIgnoreCase(firstRole.trim())) {
                JsonObject placeholder = new JsonObject();
                placeholder.addProperty("role", "user");
                placeholder.addProperty("content", "Continue");
                messagesArray.add(placeholder);
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[AI 请求] system 后首条消息非 user（" + firstRole.trim() + "），已垫 user 占位消息");
                }
            }
        }

        for (DialogueSession.Message msg : historyCopy) {
            String content = msg.getContent();
            String role = msg.getRole();

            if (content == null || role == null) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[AI 请求] 已跳过内容或角色为空的消息 (Role: " + role + ")");
                }
                continue;
            }

            content = content.trim();
            role = role.trim();

            if (content.isEmpty() || role.isEmpty()) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[AI 请求] 已跳过修整后为空的消息 (Role: " + role + ")");
                }
                continue;
            }

            if ("system".equalsIgnoreCase(role)) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[AI 请求] 跳过重复的 system 消息");
                }
                continue;
            }

            JsonObject m = new JsonObject();
            m.addProperty("role", role);
            m.addProperty("content", content);
            if (includeReasoningContent && "assistant".equalsIgnoreCase(role)) {
                String thought = msg.getThought();
                if (thought != null && !thought.trim().isEmpty()) {
                    m.addProperty("reasoning_content", thought);
                }
            }
            messagesArray.add(m);
        }

        // 验证消息数组（显式检查字段存在性与非空，避免 getAsString() 对缺失字段抛 IllegalStateException）
        for (int i = 0; i < messagesArray.size(); i++) {
            JsonObject msg = messagesArray.get(i).getAsJsonObject();
            if (!msg.has("role") || msg.get("role").isJsonNull()
                    || !msg.has("content") || msg.get("content").isJsonNull()) {
                plugin.getLogger().severe("[AI 请求] 在消息数组索引 " + i + " 处检测到空值");
                throw new IllegalArgumentException("消息验证失败: 数组中存在空值");
            }
        }

        // 如果消息数量过少，添加备用用户消息
        if (messagesArray.size() <= 1) {
            JsonObject m = new JsonObject();
            m.addProperty("role", "user");
            m.addProperty("content", "hello");
            messagesArray.add(m);
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[AI 请求] 已添加备用用户消息");
            }
        }

        return messagesArray;
    }

    /**
     * 原生函数调用门控：开关开启 && 模型支持 && 会话未降级时，把 tools 数组挂到请求体。
     * @param responsesFormat true 输出 Responses API 格式（gpt-oss），false 输出 OpenAI chat/completions 格式
     */
    private void attachNativeTools(JsonObject bodyJson, org.bukkit.entity.Player player,
                                   DialogueSession session, String model, boolean responsesFormat) {
        // chatSimple 等一次性调用无玩家，不挂 tools
        if (player == null) {
            return;
        }
        boolean nativeActive = plugin.getConfigManager().isNativeToolCallingEnabled()
                && session != null && !session.isNativeToolsDegraded()
                && org.YanPl.manager.ToolRegistry.isNativeActiveForModel(true, model);
        if (!nativeActive) {
            return;
        }
        bodyJson.add("tools", org.YanPl.manager.ToolRegistry.buildToolsArray(plugin, player, session, responsesFormat));
        // OpenAI chat/completions 格式：显式声明并行调用与自动选择。
        // Cloudflare Workers AI（gemma 等）缺少 parallel_tool_calls 时会退化到单调用，
        // 导致"请同时给我苹果和木头"只返回一个工具调用。Responses API 无此字段。
        if (!responsesFormat) {
            bodyJson.addProperty("parallel_tool_calls", true);
            bodyJson.addProperty("tool_choice", "auto");
        }
    }

    /**
     * 附加主对话采样温度（fancy.temperature 配置）。
     * 配置为 null（未配置）时不发送字段，跟随模型默认；
     * 配置为 0 时发送 temperature=0（完全确定性输出）。
     */
    private void attachTemperature(JsonObject bodyJson) {
        Double temperature = plugin.getConfigManager().getFancyTemperature();
        if (temperature != null) {
            bodyJson.addProperty("temperature", temperature);
        }
    }

    /**
     * 附加"关闭思考"参数（仅降级重试时生效）。
     * gemma-4 等思考模型的 thinking 是能力来源（工具调用、复杂任务依赖它），
     * 正常请求必须保持开启；仅当流式检测到"思考循环"降级重试时，
     * 通过 Workers AI 官方支持的 chat_template_kwargs.enable_thinking=false
     * 让模型跳过内心戏直接回答（牺牲一点能力换可用性）。
     * FancyConsole 是 CF 思考模型路由（fancy.model 常为 default），始终附加；
     * CF 直连仅 gemma 系模型附加，避免影响 gpt-oss 等其他模型。
     */
    private void attachThinkingControl(JsonObject bodyJson, String model, boolean noThinking, boolean fancyConsole) {
        if (!noThinking) {
            return;
        }
        if (fancyConsole || (model != null && model.toLowerCase().contains("gemma"))) {
            JsonObject chatTemplateKwargs = new JsonObject();
            chatTemplateKwargs.addProperty("enable_thinking", false);
            bodyJson.add("chat_template_kwargs", chatTemplateKwargs);
        }
    }

    /**
     * 400/422 硬拒回退：克隆请求体并去掉 tools 字段，重试走文本协议。
     * tool_choice/parallel_tool_calls 与 tools 配对，一并移除，避免无 tools 时残留触发部分 provider 报错。
     */
    private JsonObject stripTools(JsonObject bodyJson) {
        JsonObject clone = new JsonObject();
        for (String key : bodyJson.keySet()) {
            if (!"tools".equals(key) && !"tool_choice".equals(key) && !"parallel_tool_calls".equals(key)) {
                clone.add(key, bodyJson.get(key));
            }
        }
        return clone;
    }

    /**
     * 原生函数调用被 provider 拒绝后的通用回退：去 tools 重试同一条请求。
     * 标记会话降级（后续不再发 tools），返回重试结果；重试仍失败则返回 null（由调用方按原错误处理）。
     */
    private AIResponse retryWithoutTools(String apiUrl, String apiKey, String requestBody, String failedBody,
                                         DialogueSession session) {
        try {
            plugin.getLogger().warning("[AI] provider 拒绝原生函数调用（400/422），降级为文本协议重试...");
            JsonObject bodyJson = gson.fromJson(requestBody, JsonObject.class);
            JsonObject noTools = stripTools(bodyJson);
            session.setNativeToolsDegraded(true);
            String retryBody = gson.toJson(noTools);
            HttpRequest retry = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(plugin.getConfigManager().getApiTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(retryBody, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = sendWithRetry(retry);
            if (resp.statusCode() == 200) {
                logInteraction(session, retryBody, resp.body());
                JsonObject resultJson = gson.fromJson(resp.body(), JsonObject.class);
                return responseParser.parseResponse(resultJson);
            }
            plugin.getLogger().warning("[AI] 降级重试仍失败: " + resp.statusCode() + " " + resp.body());
        } catch (Exception e) {
            plugin.getLogger().warning("[AI] 降级重试异常: " + e.getMessage());
        }
        return null;
    }

    private String fetchAccountId() throws IOException {
        // 从 Cloudflare API 获取 Account ID 并缓存，依赖配置中的 cf_key
        if (cachedAccountId != null) return cachedAccountId;

        String cfKey = plugin.getConfigManager().getCloudflareCfKey();
        if (cfKey.isEmpty()) {
            plugin.getLogger().severe("[AI 错误] 未配置 Cloudflare API Key");
            throw new IOException("§zFancyHelper§b§r §7> §fAPI调用发生未知错误，请查看控制台");
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ACCOUNTS_URL))
                    .header("Authorization", "Bearer " + cfKey)
                    .timeout(Duration.ofSeconds(plugin.getConfigManager().getApiTimeoutSeconds()))
                    .GET()
                    .build();

            HttpResponse<String> response = sendWithRetry(request);

            if (response.statusCode() != 200) {
                plugin.getLogger().warning("[AI 错误] 获取 Account ID 失败: " + response.statusCode());
                plugin.getLogger().warning("[AI 错误] 响应体: " + response.body());
                throw new IOException("§zFancyHelper§b§r §7> §fAPI调用发生未知错误，请查看控制台");
            }

            JsonObject resultJson = gson.fromJson(response.body(), JsonObject.class);

            if (resultJson.has("result") && resultJson.getAsJsonArray("result").size() > 0) {
                cachedAccountId = resultJson.getAsJsonArray("result").get(0).getAsJsonObject().get("id").getAsString();
                return cachedAccountId;
            } else {
                plugin.getLogger().warning("[AI 错误] 未找到关联的 CloudFlare 账户");
                throw new IOException("§zFancyHelper§b§r §7> §fAPI调用发生未知错误，请查看控制台");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("[AI 错误] 获取 Account ID 被中断: " + e.getMessage());
            throw new IOException("获取 Account ID 被中断: " + e.getMessage(), e);
        }
    }

    /**
     * 构建 Cloudflare API 请求 URL
     * 如果配置了 proxy_url，则直接使用代理地址，跳过 Account ID 解析
     * 否则使用标准 Cloudflare API 地址
     */
    private String buildCloudflareApiUrl(String endpoint) throws IOException {
        String proxyUrl = plugin.getConfigManager().getCloudflareProxyUrl();
        if (proxyUrl != null && !proxyUrl.isEmpty()) {
            return proxyUrl + "/v1/" + endpoint;
        }
        String accountId = fetchAccountId();
        return String.format("https://api.cloudflare.com/client/v4/accounts/%s/ai/v1/%s", accountId, endpoint);
    }

    public AIResponse chat(org.bukkit.entity.Player player, DialogueSession session, List<String> systemPrompts) throws IOException {
        checkConfigLoaded();

        // 检测是否启用 FancyConsole 模式
        if (plugin.getConfigManager().isFancyConsoleAi()) {
            return chatWithFancyConsole(player, session, systemPrompts);
        }
        // 检测是否启用 OpenAI 模式
        if ("openai".equalsIgnoreCase(plugin.getConfigManager().getProvider())) {
            return chatWithOpenAI(player, session, systemPrompts);
        }
        // 否则使用 CloudFlare Workers AI
        return chatWithCloudFlare(player, session, systemPrompts);
    }

    /**
     * 使用 FancyConsole 进行对话（OpenAI 兼容格式）
     */
    private AIResponse chatWithFancyConsole(org.bukkit.entity.Player player, DialogueSession session, List<String> systemPrompts) throws IOException {
        String apiUrl = plugin.getConfigManager().getFancyApiUrl();
        String apiKey = plugin.getFancyConsoleManager().getApiKey();
        String model = plugin.getConfigManager().getFancyModel();

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException("§zFancyHelper§b§r §7> §c未绑定 API Key。请先使用 §b/fancyhelper bind <API Key> §c绑定。");
        }

        if (!apiUrl.contains("/v1/chat/completions")) {
            if (apiUrl.endsWith("/")) {
                apiUrl += "v1/chat/completions";
            } else {
                apiUrl += "/v1/chat/completions";
            }
        }

        JsonArray messagesArray = buildMessagesArray(player, session, systemPrompts, false);

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);
        bodyJson.add("messages", messagesArray);
        bodyJson.addProperty("max_tokens", 10000);
        attachTemperature(bodyJson);
        attachNativeTools(bodyJson, player, session, model, false);

        String requestBody = gson.toJson(bodyJson);

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[FancyConsole] 请求: " + apiUrl + " 模型: " + model);
        }

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + apiKey)
                .timeout(Duration.ofSeconds(plugin.getConfigManager().getApiTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response;
        try {
            response = sendWithRetry(request);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IOException("§zFancyHelper§b§r §7> §c请求超时，请稍后重试。", e);
        } catch (java.net.ConnectException e) {
            throw new IOException("§zFancyHelper§b§r §7> §c无法连接到 FancyConsole 服务器。", e);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IOException("§zFancyHelper§b§r §7> §c网络错误: " + e.getMessage(), e);
        }

        String responseBody = response.body();
        if (response.statusCode() != 200) {
            plugin.getLogger().warning("[FancyConsole] API 错误: " + response.statusCode() + " " + responseBody);
            // 原生函数调用被 provider 拒绝（400/422）：去掉 tools 重试，走文本协议
            if (requestBody.contains("\"tools\"") && (response.statusCode() == 400 || response.statusCode() == 422)) {
                AIResponse degraded = retryWithoutTools(apiUrl, apiKey, requestBody, responseBody, session);
                if (degraded != null) {
                    return degraded;
                }
            }
            logInteraction(session, requestBody, responseBody);
            throw new IOException("§zFancyHelper§b§r §7> §cAPI 请求失败 (" + response.statusCode() + ")");
        }

        logInteraction(session, requestBody, responseBody);

        JsonObject resultJson = gson.fromJson(responseBody, JsonObject.class);
        return responseParser.parseResponse(resultJson);
    }

    /**
     * 使用 OpenAI 兼容 API 进行对话
     */
    private AIResponse chatWithOpenAI(org.bukkit.entity.Player player, DialogueSession session, List<String> systemPrompts) throws IOException {
        String apiUrl = plugin.getConfigManager().getOpenAiApiUrl();
        String apiKey = plugin.getConfigManager().getOpenAiApiKey();
        String model = plugin.getConfigManager().getOpenAiModel();

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException("§zFancyHelper§b§r §7> §f请先在配置文件中设置 openai.api_key。");
        }

        if (model == null || model.isEmpty()) {
            model = "gpt-4o";
            plugin.getLogger().warning("[AI] OpenAI 模型名称为空，已回退到默认值: " + model);
        }

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[AI 请求] 使用 OpenAI 兼容 API: " + apiUrl);
            plugin.getLogger().info("[AI 请求] 模型: " + model);
        }

        // 如果 API 地址不包含 /chat/completions，尝试自动补全（针对 OpenAI 兼容 API）
        if (!apiUrl.contains("/chat/completions")) {
            // 针对阿里云通义千问的特殊处理
            if (apiUrl.contains("aliyuncs.com")) {
                if (apiUrl.endsWith("/")) {
                    apiUrl += "compatible-mode/v1/chat/completions";
                } else {
                    apiUrl += "/compatible-mode/v1/chat/completions";
                }
            } else {
                // 通用处理：在 URL 末尾添加 /chat/completions
                if (apiUrl.endsWith("/")) {
                    apiUrl += "chat/completions";
                } else {
                    apiUrl += "/chat/completions";
                }
            }
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[AI 请求] 检测到 OpenAI 兼容 API，已自动补全路径：" + apiUrl);
            }
        }

        // 构建消息数组
        JsonArray messagesArray = buildMessagesArray(player, session, systemPrompts, true);

        // 构建请求体
        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);
        bodyJson.add("messages", messagesArray);
        bodyJson.addProperty("max_tokens", 10000);
        attachTemperature(bodyJson);
        attachNativeTools(bodyJson, player, session, model, false);

        // 对于支持推理参数的模型（如 deepseek-reasoner、o1、qwen-max 等），添加推理参数
        if (model.contains("reasoner") || model.contains("o1") || model.contains("deepseek") || model.contains("qwen")) {
        }

        String bodyString = gson.toJson(bodyJson);
        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[AI 请求] 消息数: " + messagesArray.size());
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(plugin.getConfigManager().getApiTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyString, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = sendWithRetry(request);
            String responseBody = response.body();
            int statusCode = response.statusCode();
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[AI 响应] 状态码: " + statusCode);
            }

            // 处理临时性错误（可重试），包括：429、500、502、503、504
            int maxRetries = 3;
            int retryCount = 0;
            while (isRetryableError(statusCode) && retryCount < maxRetries) {
                retryCount++;
                
                String errorType = getErrorTypeDescription(statusCode);
                long waitSeconds = extractRetryAfter(response);
                
                if (waitSeconds <= 0) {
                    // 使用指数退避策略：2秒、4秒、8秒
                    waitSeconds = (long) Math.pow(2, retryCount);
                }
                
                plugin.getLogger().warning("[AI 重试] 收到 " + statusCode + " " + errorType + "，等待 " + waitSeconds + " 秒后重试 (" + retryCount + "/" + maxRetries + ")...");
                
                // 触发重试回调，通知玩家正在重试
                if (retryCallback != null) {
                    if (statusCode == 429) {
                        // 429 错误使用特殊配色：黄色⁕ 白色
                        retryCallback.accept(statusCode, "请求速率达到上限，正在重试...");
                    } else {
                        // 其他错误使用普通配色
                        retryCallback.accept(statusCode, "服务器繁忙，正在重试...");
                    }
                }
                
                try {
                    Thread.sleep(waitSeconds * 1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    plugin.getLogger().warning("[AI 错误] OpenAI API 调用被中断: " + ie.getMessage());
                    throw new IOException("OpenAI API 调用被中断: " + ie.getMessage(), ie);
                }
                
                // 重新发送请求
                response = sendWithRetry(request);
                responseBody = response.body();
                statusCode = response.statusCode();
                
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[AI 响应] 重试后状态码: " + statusCode);
                }
            }

            // 调试日志：输出响应体前 500 个字符
            if (responseBody != null && plugin.getConfigManager().isDebug()) {
                String debugBody = responseBody.length() > 500 ? responseBody.substring(0, 500) + "..." : responseBody;
                plugin.getLogger().info("[AI 调试] 响应体内容: " + debugBody);
            }

            // 记录原始输入和输出到调试日志文件
            logInteraction(session, bodyString, responseBody);

            if (statusCode != 200) {
                // 特殊处理 Content Exists Risk（内容风控）
                if (statusCode == 400 && responseBody != null && responseBody.contains("Content Exists Risk")) {
                    plugin.getLogger().warning("[AI 错误] 对话内容触发了内容风控 (Content Exists Risk)");
                    throw new IOException("§zFancyHelper§b§r §7> §f对话内容触发了风控，请新建对话后重试");
                }

                // 原生函数调用被 provider 拒绝（400/422）：去掉 tools 重试，走文本协议
                if ((statusCode == 400 || statusCode == 422) && bodyString.contains("\"tools\"")) {
                    AIResponse degraded = retryWithoutTools(apiUrl, apiKey, bodyString, responseBody, session);
                    if (degraded != null) {
                        return degraded;
                    }
                }

                // Cloudflare 429 Neurons 耗尽
                if (statusCode == 429 && "cloudflare".equalsIgnoreCase(plugin.getConfigManager().getProvider())) {
                    String errorMsg = getCloudflare429Message();
                    plugin.getLogger().warning("[AI] CF 429: Neurons 分配已耗尽");
                    throw new IOException(errorMsg);
                }

                String errorPrompt = getErrorPrompt(statusCode);
                String errorLogMsg = getErrorLogMessage(statusCode);
                String errorMsg;
                if (errorPrompt != null) {
                    errorMsg = errorPrompt;
                    plugin.getLogger().warning(errorLogMsg);
                } else {
                    errorMsg = "§zFancyHelper§b§r §7> §fAPI调用发生未知错误，请查看控制台";
                    plugin.getLogger().warning("状态码: " + statusCode);
                    plugin.getLogger().warning("响应体: " + responseBody);
                }
                throw new IOException(errorMsg);
            }

            JsonObject responseJson = gson.fromJson(responseBody, JsonObject.class);
            AIResponse aiResponse = responseParser.parseResponse(responseJson);

            if (aiResponse != null && aiResponse.getContent() != null) {
                String thoughtContent = aiResponse.getThought();
                if (thoughtContent != null && !thoughtContent.isEmpty()) {
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info("[AI] 检测到思考内容 (长度: " + thoughtContent.length() + ")");
                    }
                }
                return aiResponse;
            }

            plugin.getLogger().warning("[AI 错误] 无法解析 OpenAI API 响应: " + responseBody);
            throw new IOException("§zFancyHelper§b§r §7> §fAPI调用发生未知错误，请查看控制台");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("[AI 错误] OpenAI API 调用被中断: " + e.getMessage());
            throw new IOException("OpenAI API 调用被中断: " + e.getMessage(), e);
        }
    }

    /**
     * 从 HTTP 响应头中提取 Retry-After 值
     * @param response HTTP 响应
     * @return 等待秒数，如果未找到则返回 0
     */
    private long extractRetryAfter(HttpResponse<String> response) {
        // 尝试获取 Retry-After 头
        String retryAfter = response.headers().firstValue("Retry-After").orElse(null);
        if (retryAfter != null && !retryAfter.isEmpty()) {
            try {
                // Retry-After 可以是秒数或 HTTP 日期
                return Long.parseLong(retryAfter);
            } catch (NumberFormatException e) {
                // 如果不是数字，可能是 HTTP 日期格式，这里简化处理
                plugin.getLogger().warning("[AI 速率限制] 无法解析 Retry-After 头: " + retryAfter);
            }
        }
        return 0;
    }

    /**
     * 判断 HTTP 状态码是否为可重试的临时性错误
     * @param statusCode HTTP 状态码
     * @return 是否可重试
     */
    private boolean isRetryableError(int statusCode) {
        // 429: 速率限制
        // 500: 服务器内部错误
        // 502: 网关错误
        // 503: 服务不可用
        // 504: 网关超时
        return statusCode == 429 || statusCode == 500 || statusCode == 502 || statusCode == 503 || statusCode == 504;
    }

    /**
     * 获取错误类型的描述
     * @param statusCode HTTP 状态码
     * @return 错误类型描述
     */
    private String getErrorTypeDescription(int statusCode) {
        switch (statusCode) {
            case 400:
                return "请求体有问题";
            case 401:
                return "API-key 不正确";
            case 402:
                return "余额不足";
            case 422:
                return "请求体有问题";
            case 429:
                return "速率限制";
            case 500:
                return "服务器内部错误";
            case 502:
                return "网关错误";
            case 503:
                return "服务不可用";
            case 504:
                return "网关超时";
            default:
                return "错误";
        }
    }

    /**
     * 获取错误的详细提示消息（带颜色，用于玩家显示）
     * @param statusCode HTTP 状态码
     * @return 错误提示消息
     */
    private String getErrorPrompt(int statusCode) {
        switch (statusCode) {
            case 400:
                return "§zFancyHelper§b§r §7> §f构造的请求体有问题，请向开发者报告此错误";
            case 401:
                return "§zFancyHelper§b§r §7> §fAPI-key填写不正确，请检查config.yml [https://blog.baicaizhale.top/post/whyusee2]";
            case 402:
                return "§zFancyHelper§b§r §7> §f开放平台显示您的余额不足，请检查您的开放平台余额";
            case 422:
                return "§zFancyHelper§b§r §7> §f构造的请求体有问题，请向开发者报告此错误";
            case 429:
                return null; // 429 错误会自动重试，不需要提示
            case 500:
                return "§zFancyHelper§b§r §7> §f开放平台出现问题，请等待恢复";
            case 503:
                return "§zFancyHelper§b§r §7> §f开放平台出现问题，请等待恢复";
            default:
                return null;
        }
    }

    /**
     * 获取 Cloudflare 429 错误的提示消息
     * 根据是否使用默认 key 返回不同提示
     */
    private String getCloudflare429Message() {
        String defaultKey = "maF_cBg4UXnWgTaE8t8tdAq-iGZ5osv6CHxm2nH0";
        String cfKey = plugin.getConfigManager().getCloudflareCfKey();
        if (defaultKey.equals(cfKey)) {
            return "§zFancyHelper§b§r §7> §f默认配置的Neurons分配已耗尽，请换用您自己的key继续使用。参见https://blog.baicaizhale.top/post/create-cf-key-for-fhai";
        }
        return "§zFancyHelper§b§r §7> §f今天的Neurons配额已耗尽，明天再来？";
    }

    /**
     * 获取错误的控制台日志消息（纯文本，无颜色）
     * @param statusCode HTTP 状态码
     * @return 错误日志消息
     */
    private String getErrorLogMessage(int statusCode) {
        switch (statusCode) {
            case 400:
                return "构造的请求体有问题，请向开发者报告此错误";
            case 401:
                return "API-key填写不正确，请检查config.yml [了解更多: https://blog.baicaizhale.top/post/whyusee2]";
            case 402:
                return "开放平台显示您的余额不足，请检查您的开放平台余额";
            case 422:
                return "构造的请求体有问题，请向开发者报告此错误";
            case 429:
                return "请求速率达到上限";
            case 500:
                return "开放平台出现问题，请等待恢复";
            case 503:
                return "开放平台出现问题，请等待恢复";
            default:
                return "API调用发生未知错误";
        }
    }

    /**
     * 使用 CloudFlare Workers AI 进行对话
     */
    private AIResponse chatWithCloudFlare(org.bukkit.entity.Player player, DialogueSession session, List<String> systemPrompts) throws IOException {
        // 将会话历史与 systemPrompt 打包为 CloudFlare Responses API 所需的 JSON，发起 HTTP 请求并解析返回
        String cfKey = plugin.getConfigManager().getCloudflareCfKey();
        String model = plugin.getConfigManager().getCloudflareModel();

        if (cfKey == null || cfKey.isEmpty()) {
            throw new IOException("§zFancyHelper§b§r §7> §f配置文件存在问题（未配置 Cloudflare cf_key）。");
        }

        if (model == null || model.isEmpty()) {
            model = "@cf/openai/gpt-oss-120b";
            plugin.getLogger().warning("[AI] 模型名称为空，已回退到默认值: " + model);
        }

        String endpoint = model.contains("gpt-oss") ? "responses" : "chat/completions";
        String url;
        try {
            url = buildCloudflareApiUrl(endpoint);
        } catch (IOException e) {
            plugin.getLogger().severe("[AI 错误] 获取 Account ID 失败: " + e.getMessage());
            plugin.getCloudErrorReport().report(e);
            throw e;
        }
        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[AI 请求] URL: " + url);
        }

        boolean useResponsesApi = model.contains("gpt-oss");
        // 使用公共方法构建消息数组
        JsonArray messagesArray = buildMessagesArray(player, session, systemPrompts, false);

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);
        bodyJson.addProperty("max_tokens", 10000);
        attachTemperature(bodyJson);

        if (useResponsesApi) {
            bodyJson.add("input", messagesArray);
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", "medium");
            reasoning.addProperty("summary", "detailed");
            bodyJson.add("reasoning", reasoning);
            // Responses API 格式的 tools（gpt-oss 原生支持函数调用）
            attachNativeTools(bodyJson, player, session, model, true);
        } else {
            bodyJson.add("messages", messagesArray);
            attachNativeTools(bodyJson, player, session, model, false);
            if (model.contains("gpt") || model.contains("o1") || model.contains("deepseek-reasoner")) {
            }
        }

        String bodyString = gson.toJson(bodyJson);

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[AI 请求] 模型: " + model);
            plugin.getLogger().info("[AI 请求] 数组中的总消息数: " + messagesArray.size());
        }

        if (bodyString.contains("\"content\":null") || bodyString.contains("\"role\":null")) {
            plugin.getLogger().severe("[AI 错误] 严重：载荷中包含空的 content 或 role！");
            plugin.getLogger().severe("[AI 错误] 完整载荷: " + bodyString);
            throw new IOException("§zFancyHelper§b§r §7> §fAPI调用发生未知错误，请查看控制台");
        }

        if (bodyString.matches(".*\"content\":\\s*\"\"\\s*[,}].*")) {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().warning("[AI 请求] 警告：检测到空的内容字符串");
            }
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + cfKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(plugin.getConfigManager().getApiTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyString, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = sendWithRetry(request);
            String responseBody = response.body();
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[AI 响应] 状态码: " + response.statusCode());
            }

            // 记录原始输入和输出到调试日志文件
            logInteraction(session, bodyString, responseBody);

            if (response.statusCode() != 200) {
                plugin.getLogger().warning("[AI 错误] 响应体: " + responseBody);

                // 特殊处理 Content Exists Risk（内容风控），不进行重试
                if (response.statusCode() == 400 && responseBody != null && responseBody.contains("Content Exists Risk")) {
                    plugin.getLogger().warning("[AI 错误] 对话内容触发了内容风控 (Content Exists Risk)");
                    throw new IOException("§zFancyHelper§b§r §7> §f对话内容触发了风控，请新建对话后重试");
                }

                // 如果是 400 (常见于 payload 错误) 或 500 (常见于推理模型参数不兼容)，保留完整上下文重试
                if ((response.statusCode() == 400 || response.statusCode() == 500) && responseBody != null) {
                    plugin.getLogger().warning("[AI] 检测到 CF API 错误 " + response.statusCode() + "，正在尝试使用完整上下文重试...");
                    // 400 且带 tools 时标记会话降级，后续轮次不再发 tools
                    if (response.statusCode() == 400 && bodyString.contains("\"tools\"")) {
                        session.setNativeToolsDegraded(true);
                    }
                    return retryWithSimplifiedPayload(player, session, model, useResponsesApi, url, cfKey, systemPrompts);
                }

                throw new IOException("AI 调用失败: " + response.statusCode() + " - " + responseBody);
            }

            JsonObject responseJson = gson.fromJson(responseBody, JsonObject.class);
            AIResponse aiResponse = responseParser.parseResponse(responseJson);
            
            if (aiResponse != null && aiResponse.getContent() != null) {
                String thoughtContent = aiResponse.getThought();
                if (thoughtContent != null) {
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info("[AI] Detected thought content in API field (length: " + thoughtContent.length() + ")");
                    }
                } else if (aiResponse.getContent().contains("<thought>")) {
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info("[AI] Detected thought tags inside text content");
                    }
                }
                return aiResponse;
            }

            plugin.getLogger().warning("[AI 错误] 无法解析响应: " + responseBody);
            throw new IOException("§zFancyHelper§b§r §7> §fAPI调用发生未知错误，请查看控制台");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().warning("[AI 错误] 调用被中断: " + e.getMessage());
            throw new IOException("AI 调用被中断: " + e.getMessage(), e);
        }
    }

    /**
     * 使用完整上下文重试 API 请求
     * 当 API 返回 400 或 500 错误时，保留完整会话上下文（system 提示 + 全部历史消息）重试，
     * 仅去掉可能触发错误的 tools/reasoning 参数，避免压缩成单条消息导致 AI 丢失上下文答非所问。
     */
    private AIResponse retryWithSimplifiedPayload(org.bukkit.entity.Player player, DialogueSession session, String model, boolean useResponsesApi, 
                                                    String url, String cfKey, List<String> systemPrompts) throws IOException, InterruptedException {
        // 保留完整上下文重建消息数组（与原始请求一致，但不含 tools）
        JsonArray fullMessages = buildMessagesArray(player, session, systemPrompts, false);

        // 构建请求体（不附加 tools / tool_choice / parallel_tool_calls / reasoning）
        JsonObject simpleBody = new JsonObject();
        simpleBody.addProperty("model", model);
        simpleBody.addProperty("max_tokens", 10000);

        if (useResponsesApi) {
            simpleBody.add("input", fullMessages);
        } else {
            simpleBody.add("messages", fullMessages);
        }

        String simpleBodyString = gson.toJson(simpleBody);
        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[AI Request] Retrying with full context payload: " + simpleBodyString);
        }

        HttpRequest simpleRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + cfKey)
                .header("Content-Type", "application/json; charset=utf-8")
                .timeout(Duration.ofSeconds(plugin.getConfigManager().getApiTimeoutSeconds()))
                .POST(HttpRequest.BodyPublishers.ofString(simpleBodyString, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> simpleResp = sendWithRetry(simpleRequest);
        String simpleRespBody = simpleResp.body();
        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[AI Response - Retry] Code: " + simpleResp.statusCode());
        }

        // 记录重试的原始输入和输出到调试日志文件
        session.appendLog("SYSTEM", "Retrying with simplified payload...");
        logInteraction(session, simpleBodyString, simpleRespBody);

        if (simpleResp.statusCode() != 200) {
            plugin.getLogger().warning("[AI Error - Retry] 状态码: " + simpleResp.statusCode());
            plugin.getLogger().warning("[AI Error - Retry] 响应体: " + simpleRespBody);
            throw new IOException("§zFancyHelper§b§r §7> §fAPI调用发生未知错误，请查看控制台");
        }

        JsonObject responseJson = gson.fromJson(simpleRespBody, JsonObject.class);
        AIResponse retryResponse = responseParser.parseResponse(responseJson);
        if (retryResponse != null && retryResponse.getContent() != null) {
            return retryResponse;
        }
        plugin.getLogger().warning("[AI 错误] 无法解析重试响应: " + simpleRespBody);
        throw new IOException("§zFancyHelper§b§r §7> §fAPI调用发生未知错误，请查看控制台");
    }

    /**
     * 简单的单轮对话方法，不使用会话历史
     * @param prompt 用户提示
     * @return AI响应
     */
    public AIResponse chatSimple(String prompt) throws IOException {
        checkConfigLoaded();

        DialogueSession tempSession = new DialogueSession();
        tempSession.addMessage("user", prompt);
        // chatSimple 用于风险评估等一次性调用：不带玩家（无工具门控），直接走当前 provider
        List<String> defaultPrompts = java.util.Collections.singletonList("你是一个得力的助手。");
        if (plugin.getConfigManager().isFancyConsoleAi()) {
            return chatWithFancyConsole(null, tempSession, defaultPrompts);
        }
        if ("openai".equalsIgnoreCase(plugin.getConfigManager().getProvider())) {
            return chatWithOpenAI(null, tempSession, defaultPrompts);
        }
        return chatWithCloudFlare(null, tempSession, defaultPrompts);
    }

    /**
     * 使用 co-model 进行简单的单轮对话
     * @param systemPrompt 系统提示
     * @param userPrompt 用户提示
     * @return AI响应内容
     * @throws IOException 当 API 调用失败时
     */
    public String chatWithCompressionModel(String systemPrompt, String userPrompt) throws IOException {
        String provider = plugin.getConfigManager().getProvider();

        if ("fancy".equalsIgnoreCase(provider)) {
            return chatWithFancyConsoleCompressionModel(systemPrompt, userPrompt);
        }
        if ("openai".equalsIgnoreCase(provider)) {
            return chatWithOpenAICompressionModel(systemPrompt, userPrompt);
        } else {
            return chatWithCloudFlareCompressionModel(systemPrompt, userPrompt);
        }
    }

    /**
     * 使用 CloudFlare co-model 进行对话
     */
    private String chatWithCloudFlareCompressionModel(String systemPrompt, String userPrompt) throws IOException {
        String cfKey = plugin.getConfigManager().getCloudflareCfKey();
        String model = plugin.getConfigManager().getCompressionCloudflareModel();

        if (cfKey == null || cfKey.isEmpty()) {
            throw new IOException("未配置 CloudFlare API Key");
        }

        String url = buildCloudflareApiUrl("chat/completions");

        JsonArray messagesArray = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", systemPrompt);
            messagesArray.add(sysMsg);
        }
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messagesArray.add(userMsg);

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);
        bodyJson.add("messages", messagesArray);
        bodyJson.addProperty("temperature", 0.3);
        bodyJson.addProperty("stream", true);
        JsonObject reasoning = new JsonObject();
        reasoning.addProperty("effort", "low");
        bodyJson.add("reasoning", reasoning);

        String bodyString = gson.toJson(bodyJson);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + cfKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyString, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> response = sendWithRetryStream(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                plugin.getLogger().warning("[co-model] CloudFlare API 错误: " + response.statusCode() + " - " + errorBody);
                throw new IOException("API调用失败: " + response.statusCode());
            }

            // 读取 SSE 流，累积 content
            StringBuilder content = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;
                    try {
                        JsonObject chunk = gson.fromJson(data, JsonObject.class);
                        JsonArray choices = chunk.getAsJsonArray("choices");
                        if (choices == null || choices.size() == 0) continue;
                        JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                        if (delta != null && delta.has("content") && !delta.get("content").isJsonNull()) {
                            content.append(delta.get("content").getAsString());
                        }
                    } catch (Exception ignored) {}
                }
            }

            String result = content.toString().trim();
            if (result.isEmpty()) {
                throw new IOException("co-model 返回空内容");
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("API调用被中断: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 FancyConsole co-model 进行对话
     */
    private String chatWithFancyConsoleCompressionModel(String systemPrompt, String userPrompt) throws IOException {
        String apiUrl = plugin.getConfigManager().getFancyApiUrl();
        String apiKey = plugin.getFancyConsoleManager().getApiKey();
        String model = plugin.getConfigManager().getFancyCoModel();

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException("§zFancyHelper§b§r §7> §c未绑定 API Key。请先使用 §b/fancyhelper bind <API Key> §c绑定。");
        }

        if (!apiUrl.contains("/v1/chat/completions")) {
            if (apiUrl.endsWith("/")) {
                apiUrl += "v1/chat/completions";
            } else {
                apiUrl += "/v1/chat/completions";
            }
        }

        JsonArray messagesArray = new JsonArray();
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JsonObject sysMsg = new JsonObject();
            sysMsg.addProperty("role", "system");
            sysMsg.addProperty("content", systemPrompt);
            messagesArray.add(sysMsg);
        }
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messagesArray.add(userMsg);

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);
        bodyJson.add("messages", messagesArray);
        bodyJson.addProperty("temperature", 0.3);
        bodyJson.addProperty("stream", true);

        String bodyString = gson.toJson(bodyJson);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyString, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> response = sendWithRetryStream(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                plugin.getLogger().warning("[co-model] FancyConsole API 错误: " + response.statusCode() + " - " + errorBody);
                throw new IOException("API调用失败: " + response.statusCode());
            }

            // 读取 SSE 流，累积 content
            StringBuilder content = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;
                    try {
                        JsonObject chunk = gson.fromJson(data, JsonObject.class);
                        JsonArray choices = chunk.getAsJsonArray("choices");
                        if (choices == null || choices.size() == 0) continue;
                        JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                        if (delta != null && delta.has("content") && !delta.get("content").isJsonNull()) {
                            content.append(delta.get("content").getAsString());
                        }
                    } catch (Exception ignored) {}
                }
            }

            String result = content.toString().trim();
            if (result.isEmpty()) {
                throw new IOException("co-model 返回空内容");
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("API调用被中断: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 OpenAI 兼容 co-model 进行对话
     */
    private String chatWithOpenAICompressionModel(String systemPrompt, String userPrompt) throws IOException {
        String apiUrl = plugin.getConfigManager().getOpenAiApiUrl();
        String apiKey = plugin.getConfigManager().getOpenAiApiKey();
        String model = plugin.getConfigManager().getCompressionOpenAiModel();

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException("未配置 OpenAI API Key");
        }

        // 自动补全 API 路径
        if (!apiUrl.contains("/chat/completions")) {
            if (apiUrl.endsWith("/")) {
                apiUrl += "chat/completions";
            } else {
                apiUrl += "/chat/completions";
            }
        }

        // 构建消息数组
        JsonArray messagesArray = new JsonArray();
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", systemPrompt);
        messagesArray.add(systemMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messagesArray.add(userMsg);

        // 构建请求体
        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);
        bodyJson.add("messages", messagesArray);
        bodyJson.addProperty("temperature", 0.3);
        bodyJson.addProperty("stream", true);
        bodyJson.addProperty("reasoning_effort", "low");

        String bodyString = gson.toJson(bodyJson);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyString, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<java.io.InputStream> response = sendWithRetryStream(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                plugin.getLogger().warning("[co-model] OpenAI API 错误: " + response.statusCode() + " - " + errorBody);
                throw new IOException("API调用失败: " + response.statusCode());
            }

            StringBuilder content = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) continue;
                    String data = line.substring(6).trim();
                    if ("[DONE]".equals(data)) break;
                    try {
                        JsonObject chunk = gson.fromJson(data, JsonObject.class);
                        JsonArray choices = chunk.getAsJsonArray("choices");
                        if (choices == null || choices.size() == 0) continue;
                        JsonObject delta = choices.get(0).getAsJsonObject().getAsJsonObject("delta");
                        if (delta != null && delta.has("content") && !delta.get("content").isJsonNull()) {
                            content.append(delta.get("content").getAsString());
                        }
                    } catch (Exception ignored) {}
                }
            }

            String result = content.toString().trim();
            if (result.isEmpty()) {
                throw new IOException("co-model 返回空内容");
            }
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("API调用被中断: " + e.getMessage(), e);
        }
    }

    /**
     * 使用压缩模型对上下文进行智能压缩
     * @param context 需要压缩的上下文内容
     * @return 压缩后的摘要
     * @throws IOException 当 API 调用失败时
     */
    public String compressContext(String context) throws IOException {
        checkConfigLoaded();

        String provider = plugin.getConfigManager().getCompressionModelProvider();

        if ("fancy".equalsIgnoreCase(provider)) {
            return compressWithFancyConsole(context);
        }
        if ("openai".equalsIgnoreCase(provider)) {
            return compressWithOpenAI(context);
        } else {
            return compressWithCloudFlare(context);
        }
    }

    /**
     * 使用 CloudFlare 压缩模型进行上下文压缩
     */
    private String compressWithCloudFlare(String context) throws IOException {
        String cfKey = plugin.getConfigManager().getCloudflareCfKey();
        String model = plugin.getConfigManager().getCompressionCloudflareModel();

        if (cfKey == null || cfKey.isEmpty()) {
            throw new IOException("未配置 CloudFlare API Key");
        }

        String url = buildCloudflareApiUrl("chat/completions");

        // 构建压缩提示 - 使用单个 user prompt 避免模型输出思考过程
        String userPrompt = "请将以下对话历史压缩成简洁的摘要，保留关键信息和用户意图。直接输出摘要内容，不要有任何解释、分析或编号。摘要应该简明扼要，不超过200字。\n\n对话历史：\n" + context + "\n\n摘要：";

        // 构建消息数组 - 只使用 user message，避免模型输出思考过程
        JsonArray messagesArray = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messagesArray.add(userMsg);

        // 构建请求体
        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);
        bodyJson.add("messages", messagesArray);
        bodyJson.addProperty("max_tokens", 300);
        bodyJson.addProperty("temperature", 0.3);

        String bodyString = gson.toJson(bodyJson);


        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + cfKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyString, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = sendWithRetry(request);
            String responseBody = response.body();

            if (response.statusCode() != 200) {
                plugin.getLogger().warning("[压缩] CloudFlare API 错误: " + response.statusCode());
                throw new IOException("压缩失败: " + response.statusCode());
            }

            JsonObject responseJson = gson.fromJson(responseBody, JsonObject.class);
            AIResponse aiResponse = responseParser.parseResponse(responseJson);
            
            if (aiResponse != null && aiResponse.getContent() != null) {
                return aiResponse.getContent().trim();
            }

            throw new IOException("无法解析压缩响应");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("压缩被中断: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 FancyConsole 压缩模型进行上下文压缩
     */
    private String compressWithFancyConsole(String context) throws IOException {
        String apiUrl = plugin.getConfigManager().getFancyApiUrl();
        String apiKey = plugin.getFancyConsoleManager().getApiKey();
        String model = plugin.getConfigManager().getFancyCoModel();

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException("§zFancyHelper§b§r §7> §c未绑定 API Key。请先使用 /fancyhelper bind <API Key> 绑定。");
        }

        if (!apiUrl.contains("/v1/chat/completions")) {
            if (apiUrl.endsWith("/")) {
                apiUrl += "v1/chat/completions";
            } else {
                apiUrl += "/v1/chat/completions";
            }
        }

        // 构建压缩提示 - 使用单个 user prompt 避免模型输出思考过程
        String userPrompt = "请将以下对话历史压缩成简洁的摘要，保留关键信息和用户意图。直接输出摘要内容，不要有任何解释、分析或编号。摘要应该简明扼要，不超过200字。\n\n对话历史：\n" + context + "\n\n摘要：";

        // 构建消息数组 - 只使用 user message，避免模型输出思考过程
        JsonArray messagesArray = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messagesArray.add(userMsg);

        // 构建请求体
        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);
        bodyJson.add("messages", messagesArray);
        bodyJson.addProperty("max_tokens", 300);
        bodyJson.addProperty("temperature", 0.3);

        String bodyString = gson.toJson(bodyJson);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyString, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = sendWithRetry(request);
            String responseBody = response.body();

            if (response.statusCode() != 200) {
                plugin.getLogger().warning("[压缩] FancyConsole API 错误: " + response.statusCode() + " - " + responseBody);
                throw new IOException("压缩失败: " + response.statusCode());
            }

            JsonObject responseJson = gson.fromJson(responseBody, JsonObject.class);
            AIResponse aiResponse = responseParser.parseResponse(responseJson);

            if (aiResponse != null && aiResponse.getContent() != null) {
                return aiResponse.getContent().trim();
            }

            throw new IOException("无法解析压缩响应");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("压缩被中断: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 OpenAI 兼容 API 进行上下文压缩
     * 使用主模型的 API URL 和 API Key，仅使用副模型的模型名称
     */
    private String compressWithOpenAI(String context) throws IOException {
        String apiUrl = plugin.getConfigManager().getOpenAiApiUrl();
        String apiKey = plugin.getConfigManager().getOpenAiApiKey();
        String model = plugin.getConfigManager().getCompressionOpenAiModel();

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException("未配置 OpenAI API Key");
        }

        // 自动补全 API 路径
        if (!apiUrl.contains("/chat/completions")) {
            if (apiUrl.endsWith("/")) {
                apiUrl += "chat/completions";
            } else {
                apiUrl += "/chat/completions";
            }
        }

        // 构建压缩提示 - 使用单个 user prompt 避免模型输出思考过程
        String userPrompt = "请将以下对话历史压缩成简洁的摘要，保留关键信息和用户意图。直接输出摘要内容，不要有任何解释、分析或编号。摘要应该简明扼要，不超过200字。\n\n对话历史：\n" + context + "\n\n摘要：";

        // 构建消息数组 - 只使用 user message，避免模型输出思考过程
        JsonArray messagesArray = new JsonArray();
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userPrompt);
        messagesArray.add(userMsg);

        // 构建请求体
        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);
        bodyJson.add("messages", messagesArray);
        bodyJson.addProperty("max_tokens", 300);
        bodyJson.addProperty("temperature", 0.3);
        bodyJson.addProperty("reasoning_effort", "low");

        String bodyString = gson.toJson(bodyJson);


        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(30))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyString, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = sendWithRetry(request);
            String responseBody = response.body();

            if (response.statusCode() != 200) {
                plugin.getLogger().warning("[压缩] OpenAI API 错误: " + response.statusCode());
                throw new IOException("压缩失败: " + response.statusCode());
            }

            JsonObject responseJson = gson.fromJson(responseBody, JsonObject.class);
            AIResponse aiResponse = responseParser.parseResponse(responseJson);
            
            if (aiResponse != null && aiResponse.getContent() != null) {
                return aiResponse.getContent().trim();
            }

            throw new IOException("无法解析压缩响应");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("压缩被中断: " + e.getMessage(), e);
        }
    }

    /**
     * 使用主模型生成对话标题（不用压缩模型）
     * @param firstMessage 第一条用户消息
     * @return 生成的标题（不超过15字）
     */
    public String generateTitle(String firstMessage) throws IOException {
        checkConfigLoaded();

        String provider = plugin.getConfigManager().getProvider();

        if (plugin.getConfigManager().isFancyConsoleAi()) {
            return generateTitleWithFancyConsole(firstMessage);
        }
        if ("openai".equalsIgnoreCase(provider)) {
            return generateTitleWithOpenAI(firstMessage);
        } else {
            return generateTitleWithCloudFlare(firstMessage);
        }
    }

    /**
     * 使用 FancyConsole 生成标题
     */
    private String generateTitleWithFancyConsole(String firstMessage) throws IOException {
        return generateTitleWithRetryFancyConsole(firstMessage);
    }

    /**
     * 使用 FancyConsole 生成标题（带重试）
     */
    private String generateTitleWithRetryFancyConsole(String firstMessage) throws IOException {
        int maxRetries = 4;
        boolean reachedApi = false;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String title = generateTitleFromFancyConsole(firstMessage);
                if (title != null) return title;
                reachedApi = true;
                plugin.getLogger().warning("[标题生成] 第 " + attempt + " 次尝试 JSON 解析失败");
            } catch (Exception e) {
                plugin.getLogger().warning("[标题生成] 第 " + attempt + " 次尝试失败: " + e.getMessage());
            }
        }

        if (reachedApi) return "";
        return null;
    }

    /**
     * 使用 FancyConsole 生成标题（单次尝试）
     */
    private String generateTitleFromFancyConsole(String firstMessage) throws IOException {
        String apiKey = plugin.getFancyConsoleManager().getApiKey();
        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException("未绑定 API Key");
        }

        String fancyUrl = plugin.getConfigManager().getFancyApiUrl();
        String model = plugin.getConfigManager().getFancyModel();

        // Auto-append path
        String url = fancyUrl;
        if (!url.contains("/chat/completions")) {
            url = url.replaceAll("/+$", "") + "/v1/chat/completions";
        }

        // Log the real URL we're calling
        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[标题生成] 请求 URL: " + url);
        }

        JsonArray messagesArray = new JsonArray();
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", "Title labeling task. Output ONLY: {\"title\": \"topic summary\"}. Describe the TOPIC of the message, do NOT repeat it. Same language.");
        messagesArray.add(systemMsg);

        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", "Label this: " + firstMessage);
        messagesArray.add(userMsg);

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);
        bodyJson.add("messages", messagesArray);
        bodyJson.addProperty("temperature", 0.3);

        String bodyString = gson.toJson(bodyJson);

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[标题生成] 请求体: " + bodyString);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyString, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = sendWithRetry(request);
            String responseBody = response.body();

            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[标题生成] 响应 (" + response.statusCode() + "): " + responseBody);
            }

            if (response.statusCode() != 200) {
                plugin.getLogger().warning("[标题生成] FancyConsole API 错误: " + response.statusCode());
                throw new IOException("标题生成失败: " + response.statusCode());
            }

            return parseTitleFromResponse(responseBody);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("标题生成被中断: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 CloudFlare 生成标题（带重试）
     */
    private String generateTitleWithCloudFlare(String firstMessage) throws IOException {
        return generateTitleWithRetry(firstMessage, true);
    }

    /**
     * 使用 OpenAI 兼容 API 生成标题（带重试）
     */
    private String generateTitleWithOpenAI(String firstMessage) throws IOException {
        return generateTitleWithRetry(firstMessage, false);
    }

    /**
     * 生成标题的通用逻辑（带重试）
     * @param firstMessage 第一条用户消息
     * @param useCloudFlare 是否使用 CloudFlare
     * @return 标题，如果失败返回 null
     */
    private String generateTitleWithRetry(String firstMessage, boolean useCloudFlare) throws IOException {
        int maxRetries = 4;
        boolean reachedApi = false; // 是否有至少一次成功请求到 API

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                String title = useCloudFlare ?
                    generateTitleFromCloudFlare(firstMessage) :
                    generateTitleFromOpenAI(firstMessage);

                if (title != null) {
                    return title;
                }

                // title 为 null 表示 JSON 解析失败，但 API 请求成功
                reachedApi = true;
                plugin.getLogger().warning("[标题生成] 第 " + attempt + " 次尝试 JSON 解析失败");
            } catch (Exception e) {
                plugin.getLogger().warning("[标题生成] 第 " + attempt + " 次尝试失败: " + e.getMessage());
            }
        }

        if (reachedApi) {
            // API 请求成功过，但模型始终没有按要求输出 JSON
            return "";
        }
        // 全是网络/配置异常
        return null;
    }

    /**
     * 使用 CloudFlare 生成标题（单次尝试，使用主模型）
     */
    private String generateTitleFromCloudFlare(String firstMessage) throws IOException {
        String cfKey = plugin.getConfigManager().getCloudflareCfKey();
        String model = plugin.getConfigManager().getCloudflareModel(); // 使用主模型

        if (cfKey == null || cfKey.isEmpty()) {
            throw new IOException("未配置 CloudFlare API Key");
        }

        String url = buildCloudflareApiUrl("chat/completions");

        // 构建消息数组
        JsonArray messagesArray = new JsonArray();

        // system 消息
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", "Title labeling task. Output ONLY: {\"title\": \"topic summary\"}. Describe the TOPIC of the message, do NOT repeat it. Same language.");
        messagesArray.add(systemMsg);

        // user 消息
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", "Label this: " + firstMessage);
        messagesArray.add(userMsg);

        // 构建请求体
        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);
        bodyJson.add("messages", messagesArray);
        bodyJson.addProperty("temperature", 0.3);

        String bodyString = gson.toJson(bodyJson);

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[标题生成] 请求体: " + bodyString);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + cfKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyString, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = sendWithRetry(request);
            String responseBody = response.body();

            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[标题生成] 响应 (" + response.statusCode() + "): " + responseBody);
            }

            if (response.statusCode() != 200) {
                plugin.getLogger().warning("[标题生成] CloudFlare API 错误: " + response.statusCode());
                throw new IOException("标题生成失败: " + response.statusCode());
            }

            return parseTitleFromResponse(responseBody);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("标题生成被中断: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 OpenAI 兼容 API 生成标题（单次尝试，使用主模型）
     */
    private String generateTitleFromOpenAI(String firstMessage) throws IOException {
        String apiUrl = plugin.getConfigManager().getOpenAiApiUrl();
        String apiKey = plugin.getConfigManager().getOpenAiApiKey();
        String model = plugin.getConfigManager().getOpenAiModel(); // 使用主模型

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException("未配置 OpenAI API Key");
        }

        // 自动补全 API 路径
        if (!apiUrl.contains("/chat/completions")) {
            if (apiUrl.endsWith("/")) {
                apiUrl += "chat/completions";
            } else {
                apiUrl += "/chat/completions";
            }
        }

        // 构建消息数组
        JsonArray messagesArray = new JsonArray();

        // system 消息
        JsonObject systemMsg = new JsonObject();
        systemMsg.addProperty("role", "system");
        systemMsg.addProperty("content", "Title labeling task. Output ONLY: {\"title\": \"topic summary\"}. Describe the TOPIC of the message, do NOT repeat it. Same language.");
        messagesArray.add(systemMsg);

        // user 消息
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", "Label this: " + firstMessage);
        messagesArray.add(userMsg);

        // 构建请求体
        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);
        bodyJson.add("messages", messagesArray);
        bodyJson.addProperty("temperature", 0.3);

        String bodyString = gson.toJson(bodyJson);

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[标题生成] 请求体: " + bodyString);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyString, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = sendWithRetry(request);
            String responseBody = response.body();

            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[标题生成] 响应 (" + response.statusCode() + "): " + responseBody);
            }

            if (response.statusCode() != 200) {
                plugin.getLogger().warning("[标题生成] OpenAI API 错误: " + response.statusCode());
                throw new IOException("标题生成失败: " + response.statusCode());
            }

            return parseTitleFromResponse(responseBody);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("标题生成被中断: " + e.getMessage(), e);
        }
    }

    /**
     * 从 API 响应中解析标题
     * 支持多种格式：JSON、key-value、纯文本
     * @return 标题，如果完全失败返回 null
     */
    private String parseTitleFromResponse(String responseBody) {
        JsonObject responseJson = gson.fromJson(responseBody, JsonObject.class);
        AIResponse aiResponse = responseParser.parseResponse(responseJson);

        if (aiResponse == null || aiResponse.getContent() == null) {
            plugin.getLogger().warning("[标题生成] AI 响应内容为 null");
            return null;
        }

        String content = aiResponse.getContent().trim();

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[标题生成] AI 原始返回 (" + content.length() + " chars): " + content);
        }

        // 如果内容为空，返回 null
        if (content.isEmpty()) {
            plugin.getLogger().warning("[标题生成] AI 返回空内容");
            return null;
        }

        // 从最后一个 JSON 中提取 title（避免思考过程中的示例）
        try {
            // 找所有 JSON 块，取最后一个
            int lastJsonStart = content.lastIndexOf("{");
            int lastJsonEnd = content.lastIndexOf("}");
            if (lastJsonStart >= 0 && lastJsonEnd > lastJsonStart) {
                String jsonStr = content.substring(lastJsonStart, lastJsonEnd + 1);
                JsonObject titleJson = gson.fromJson(jsonStr, JsonObject.class);
                if (titleJson.has("title")) {
                    String title = titleJson.get("title").getAsString().trim();
                    // 截取前30个字符
                    if (title.length() > 30) {
                        title = title.substring(0, 30);
                    }
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info("[标题生成] 从最后一个 JSON 提取成功: " + title);
                    }
                    return title;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[标题生成] JSON 解析失败: " + e.getMessage());
        }

        plugin.getLogger().warning("[标题生成] 未找到有效的 JSON 标题");
        return null;
    }

    /**
     * 使用流式输出进行对话（默认保留模型思考）
     * @param player 玩家
     * @param session 对话会话
     * @param systemPrompts 多条系统提示消息（按稳定度排列）
     * @param streamingHandler 流式处理器
     * @return 完整的响应文本
     */
    public String chatStreaming(org.bukkit.entity.Player player, DialogueSession session, List<String> systemPrompts, StreamingHandler streamingHandler) throws IOException {
        return chatStreaming(player, session, systemPrompts, streamingHandler, false);
    }

    /**
     * 使用流式输出进行对话
     * @param player 玩家
     * @param session 对话会话
     * @param systemPrompts 多条系统提示消息（按稳定度排列）
     * @param streamingHandler 流式处理器
     * @param noThinking true 表示降级重试：附加 enable_thinking=false 让思考模型跳过内心戏直接回答
     *                   （仅用于"思考循环"兜底，正常请求必须保持 false 以保留模型能力）
     * @return 完整的响应文本
     */
    public String chatStreaming(org.bukkit.entity.Player player, DialogueSession session, List<String> systemPrompts, StreamingHandler streamingHandler, boolean noThinking) throws IOException {
        checkConfigLoaded();

        if (plugin.getConfigManager().isFancyConsoleAi()) {
            return chatStreamingWithFancyConsole(player, session, systemPrompts, streamingHandler, noThinking);
        }
        if ("openai".equalsIgnoreCase(plugin.getConfigManager().getProvider())) {
            return chatStreamingWithOpenAI(player, session, systemPrompts, streamingHandler);
        }
        return chatStreamingWithCloudFlare(player, session, systemPrompts, streamingHandler, noThinking);
    }

    /**
     * 使用 FancyConsole 进行流式对话（OpenAI 兼容格式）
     */
    private String chatStreamingWithFancyConsole(org.bukkit.entity.Player player, DialogueSession session, List<String> systemPrompts, StreamingHandler streamingHandler, boolean noThinking) throws IOException {
        String apiUrl = plugin.getConfigManager().getFancyApiUrl();
        String apiKey = plugin.getFancyConsoleManager().getApiKey();
        String model = plugin.getConfigManager().getFancyModel();

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException("§zFancyHelper§b§r §7> §c未绑定 API Key。请先使用 /fancyhelper bind <API Key> 绑定。");
        }

        if (!apiUrl.contains("/v1/chat/completions")) {
            if (apiUrl.endsWith("/")) {
                apiUrl += "v1/chat/completions";
            } else {
                apiUrl += "/v1/chat/completions";
            }
        }

        JsonArray messagesArray = buildMessagesArray(player, session, systemPrompts, false);

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);
        bodyJson.add("messages", messagesArray);
        // max_tokens 不能超过模型上下文窗口 - 输入token，否则模型会拒绝请求。
        // 各模型对 max_tokens 还有独立上限（常见 65536），统一钳制避免超限。
        int contextLimit = plugin.getConfigManager().getContextWindowLimit();
        int estimatedInput = DialogueSession.calculateTokens(gson.toJson(messagesArray));
        int maxTokens = Math.max(contextLimit - estimatedInput, 1024);
        maxTokens = Math.min(maxTokens, 65536);
        bodyJson.addProperty("max_tokens", maxTokens);
        bodyJson.addProperty("stream", true);
        attachTemperature(bodyJson);
        attachNativeTools(bodyJson, player, session, model, false);
        attachThinkingControl(bodyJson, model, noThinking, true);

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[FancyConsole Streaming] 请求: " + apiUrl + " 模型: " + model);
        }

        // FancyConsole 是 OpenAI 兼容格式，复用相同的流式处理逻辑
        String bodyString = gson.toJson(bodyJson);
        logRequestFormatted(session, bodyString);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(plugin.getConfigManager().getApiTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyString, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = sendWithRetryStream(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                // 400/422 且携带 chat_template_kwargs（降级关思考参数）被上游拒绝时：
                // 剥掉该字段重试一次，避免代理不认该参数导致降级请求直接失败
                if ((response.statusCode() == 400 || response.statusCode() == 422)
                        && bodyJson.has("chat_template_kwargs")) {
                    plugin.getLogger().warning("[FancyConsole Streaming] 上游拒绝 chat_template_kwargs ("
                            + response.statusCode() + ")，剥掉该字段重试一次");
                    bodyJson.remove("chat_template_kwargs");
                    String retryBody = gson.toJson(bodyJson);
                    HttpRequest retryRequest = HttpRequest.newBuilder()
                            .uri(URI.create(apiUrl))
                            .header("Authorization", "Bearer " + apiKey)
                            .header("Content-Type", "application/json; charset=utf-8")
                            .timeout(Duration.ofSeconds(plugin.getConfigManager().getApiTimeoutSeconds()))
                            .POST(HttpRequest.BodyPublishers.ofString(retryBody, StandardCharsets.UTF_8))
                            .build();
                    HttpResponse<InputStream> retryResponse = sendWithRetryStream(retryRequest, HttpResponse.BodyHandlers.ofInputStream());
                    if (retryResponse.statusCode() != 200) {
                        String retryError = new String(retryResponse.body().readAllBytes(), StandardCharsets.UTF_8);
                        throw new IOException("FancyConsole 流式请求失败: " + retryResponse.statusCode() + " - " + retryError);
                    }
                    return streamingHandler.processStream(retryResponse);
                }
                throw new IOException("FancyConsole 流式请求失败: " + response.statusCode() + " - " + errorBody);
            }

            String fullText = streamingHandler.processStream(response);
            return fullText;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("流式调用被中断: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 OpenAI 兼容 API 进行流式对话
     */
    private String chatStreamingWithOpenAI(org.bukkit.entity.Player player, DialogueSession session, List<String> systemPrompts, StreamingHandler streamingHandler) throws IOException {
        String apiUrl = plugin.getConfigManager().getOpenAiApiUrl();
        String apiKey = plugin.getConfigManager().getOpenAiApiKey();
        String model = plugin.getConfigManager().getOpenAiModel();

        if (apiKey == null || apiKey.isEmpty()) {
            throw new IOException("错误: 请先在配置文件中设置 openai.api_key。");
        }

        if (model == null || model.isEmpty()) {
            model = "gpt-4o";
        }

        if (!apiUrl.contains("/chat/completions")) {
            if (apiUrl.contains("aliyuncs.com")) {
                apiUrl += apiUrl.endsWith("/") ? "compatible-mode/v1/chat/completions" : "/compatible-mode/v1/chat/completions";
            } else {
                apiUrl += apiUrl.endsWith("/") ? "chat/completions" : "/chat/completions";
            }
        }

        JsonArray messagesArray = buildMessagesArray(player, session, systemPrompts, true);

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);
        bodyJson.add("messages", messagesArray);
        bodyJson.addProperty("max_tokens", 10000);
        bodyJson.addProperty("stream", true);
        attachTemperature(bodyJson);
        attachNativeTools(bodyJson, player, session, model, false);

        if (model.contains("reasoner") || model.contains("o1") || model.contains("deepseek") || model.contains("qwen")) {
        }

        String bodyString = gson.toJson(bodyJson);

        // 记录系统提示词和请求（流式模式下也需要）
        logRequestFormatted(session, bodyString);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(plugin.getConfigManager().getApiTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyString, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = sendWithRetryStream(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                // 特殊处理 Content Exists Risk（内容风控）
                if (response.statusCode() == 400 && errorBody.contains("Content Exists Risk")) {
                    plugin.getLogger().warning("[AI 错误] 对话内容触发了内容风控 (Content Exists Risk)");
                    throw new IOException("§zFancyHelper§b§r §7> §f对话内容触发了风控，请新建对话后重试");
                }
                throw new IOException("流式请求失败: " + response.statusCode() + " - " + errorBody);
            }

            String fullText = streamingHandler.processStream(response);
            return fullText;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("流式调用被中断: " + e.getMessage(), e);
        }
    }

    /**
     * 使用 CloudFlare Workers AI 进行流式对话
     */
    private String chatStreamingWithCloudFlare(org.bukkit.entity.Player player, DialogueSession session, List<String> systemPrompts, StreamingHandler streamingHandler, boolean noThinking) throws IOException {
        String cfKey = plugin.getConfigManager().getCloudflareCfKey();
        String model = plugin.getConfigManager().getCloudflareModel();

        if (cfKey == null || cfKey.isEmpty()) {
            throw new IOException("错误: 请先在配置文件中设置 CloudFlare cf_key。");
        }

        if (model == null || model.isEmpty()) {
            model = "@cf/openai/gpt-oss-120b";
        }

        String endpoint = model.contains("gpt-oss") ? "responses" : "chat/completions";
        String url;
        try {
            url = buildCloudflareApiUrl(endpoint);
        } catch (IOException e) {
            plugin.getCloudErrorReport().report(e);
            throw e;
        }

        boolean useResponsesApi = model.contains("gpt-oss");

        JsonArray messagesArray = buildMessagesArray(player, session, systemPrompts, false);

        JsonObject bodyJson = new JsonObject();
        bodyJson.addProperty("model", model);
        bodyJson.addProperty("max_tokens", 10000);
        attachTemperature(bodyJson);
        attachThinkingControl(bodyJson, model, noThinking, false);

        if (useResponsesApi) {
            // gpt-oss 模型通过 Responses API 不支持流式，走非流式请求
            bodyJson.add("input", messagesArray);
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", "medium");
            reasoning.addProperty("summary", "detailed");
            bodyJson.add("reasoning", reasoning);
            // Responses 格式 tools（gpt-oss 走非流式，非流式响应天然带完整 function_call）
            attachNativeTools(bodyJson, player, session, model, true);
        } else {
            bodyJson.addProperty("stream", true);
            bodyJson.add("messages", messagesArray);
            attachNativeTools(bodyJson, player, session, model, false);
            if (model.contains("gpt") || model.contains("o1") || model.contains("deepseek-reasoner")) {
            }
        }

        String bodyString = gson.toJson(bodyJson);

        // 记录系统提示词和请求（流式模式下也需要）
        logRequestFormatted(session, bodyString);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + cfKey)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .timeout(Duration.ofSeconds(plugin.getConfigManager().getApiTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(bodyString, StandardCharsets.UTF_8))
                    .build();

            if (useResponsesApi) {
                // gpt-oss 模型使用非流式请求，通过 responseParser 解析
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) {
                    // 特殊处理 Content Exists Risk（内容风控）
                    if (response.statusCode() == 400 && response.body() != null && response.body().contains("Content Exists Risk")) {
                        plugin.getLogger().warning("[AI 错误] 对话内容触发了内容风控 (Content Exists Risk)");
                        throw new IOException("§zFancyHelper§b§r §7> §f对话内容触发了风控，请新建对话后重试");
                    }
                    if (response.statusCode() == 429) {
                        throw new IOException(getCloudflare429Message());
                    }
                    throw new IOException("非流式请求失败: " + response.statusCode() + " - " + response.body());
                }
                JsonObject responseJson = gson.fromJson(response.body(), JsonObject.class);
                AIResponse aiResponse = responseParser.parseResponse(responseJson);
                String fullText = aiResponse != null ? aiResponse.getContent() : "";
                streamingHandler.feedCompletedText(fullText);
                return fullText;
            }

            HttpResponse<InputStream> response = sendWithRetryStream(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() != 200) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                // 特殊处理 Content Exists Risk（内容风控）
                if (response.statusCode() == 400 && errorBody.contains("Content Exists Risk")) {
                    plugin.getLogger().warning("[AI 错误] 对话内容触发了内容风控 (Content Exists Risk)");
                    throw new IOException("§zFancyHelper§b§r §7> §f对话内容触发了风控，请新建对话后重试");
                }
                if (response.statusCode() == 429) {
                    throw new IOException(getCloudflare429Message());
                }
                // 400/422 且携带 chat_template_kwargs（降级关思考参数）被上游拒绝时：
                // 剥掉该字段重试一次，避免上游不认该参数导致降级请求直接失败
                if ((response.statusCode() == 400 || response.statusCode() == 422)
                        && bodyJson.has("chat_template_kwargs")) {
                    plugin.getLogger().warning("[AI] 上游拒绝 chat_template_kwargs (" + response.statusCode() + ")，剥掉该字段重试一次");
                    bodyJson.remove("chat_template_kwargs");
                    String retryBody = gson.toJson(bodyJson);
                    HttpRequest retryRequest = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("Authorization", "Bearer " + cfKey)
                            .header("Content-Type", "application/json; charset=utf-8")
                            .timeout(Duration.ofSeconds(plugin.getConfigManager().getApiTimeoutSeconds()))
                            .POST(HttpRequest.BodyPublishers.ofString(retryBody, StandardCharsets.UTF_8))
                            .build();
                    HttpResponse<InputStream> retryResponse = sendWithRetryStream(retryRequest, HttpResponse.BodyHandlers.ofInputStream());
                    if (retryResponse.statusCode() != 200) {
                        String retryError = new String(retryResponse.body().readAllBytes(), StandardCharsets.UTF_8);
                        throw new IOException("流式请求失败: " + retryResponse.statusCode() + " - " + retryError);
                    }
                    return streamingHandler.processStream(retryResponse);
                }
                throw new IOException("流式请求失败: " + response.statusCode() + " - " + errorBody);
            }

            String fullText = streamingHandler.processStream(response);
            return fullText;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("流式调用被中断: " + e.getMessage(), e);
        }
    }
}
