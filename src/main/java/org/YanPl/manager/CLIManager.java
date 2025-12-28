package org.YanPl.manager;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.YanPl.MineAgent;
import org.YanPl.api.CloudFlareAI;
import org.YanPl.model.DialogueSession;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.scheduler.BukkitRunnable;

/**
 * CLI 模式管理器，负责管理玩家的 CLI 状态和对话流
 */
public class CLIManager {
    private final MineAgent plugin;
    private final CloudFlareAI ai;
    private final PromptManager promptManager;
    private final Set<UUID> activeCLIPayers = new HashSet<>();
    private final Set<UUID> pendingAgreementPlayers = new HashSet<>();
    private final Set<UUID> agreedPlayers = new HashSet<>();
    private final File agreedPlayersFile;
    private final Map<UUID, DialogueSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> isGenerating = new ConcurrentHashMap<>();
    private final Map<UUID, String> pendingCommands = new ConcurrentHashMap<>();

    public CLIManager(MineAgent plugin) {
        this.plugin = plugin;
        this.ai = new CloudFlareAI(plugin);
        this.promptManager = new PromptManager(plugin);
        this.agreedPlayersFile = new File(plugin.getDataFolder(), "agreed_players.txt");
        loadAgreedPlayers();
        startTimeoutTask();
    }

    private void loadAgreedPlayers() {
        if (!agreedPlayersFile.exists()) return;
        try {
            List<String> lines = java.nio.file.Files.readAllLines(agreedPlayersFile.toPath());
            for (String line : lines) {
                try {
                    agreedPlayers.add(UUID.fromString(line.trim()));
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (IOException e) {
            plugin.getLogger().warning("无法加载已同意协议的玩家列表: " + e.getMessage());
        }
    }

    private void saveAgreedPlayer(UUID uuid) {
        agreedPlayers.add(uuid);
        try {
            java.nio.file.Files.write(agreedPlayersFile.toPath(), 
                (uuid.toString() + "\n").getBytes(), 
                java.nio.file.StandardOpenOption.CREATE, 
                java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            plugin.getLogger().warning("无法保存已同意协议的玩家: " + e.getMessage());
        }
    }

    private void startTimeoutTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                long timeoutMs = plugin.getConfigManager().getTimeoutMinutes() * 60 * 1000L;
                
                for (UUID uuid : new ArrayList<>(activeCLIPayers)) {
                    DialogueSession session = sessions.get(uuid);
                    if (session != null && (now - session.getLastActivityTime()) > timeoutMs) {
                        Player player = Bukkit.getPlayer(uuid);
                        if (player != null) {
                            player.sendMessage(ChatColor.YELLOW + "由于长时间未活动，已自动退出 CLI Mode。");
                            exitCLI(player);
                        } else {
                            activeCLIPayers.remove(uuid);
                            sessions.remove(uuid);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L * 60, 20L * 60); // 每分钟检查一次
    }

    /**
     * 切换玩家的 CLI 模式
     */
    public void toggleCLI(Player player) {
        UUID uuid = player.getUniqueId();
        if (activeCLIPayers.contains(uuid)) {
            exitCLI(player);
        } else {
            enterCLI(player);
        }
    }

    /**
     * 进入 CLI 模式
     */
    public void enterCLI(Player player) {
        UUID uuid = player.getUniqueId();
        plugin.getLogger().info("[CLI] Player " + player.getName() + " is entering CLI mode.");
        
        // 检查用户协议
        if (!agreedPlayers.contains(uuid)) {
            plugin.getLogger().info("[CLI] Player " + player.getName() + " needs to agree to terms.");
            sendAgreement(player);
            pendingAgreementPlayers.add(uuid);
            return;
        }

        activeCLIPayers.add(uuid);
        sessions.put(uuid, new DialogueSession());
        sendEnterMessage(player);
    }

    /**
     * 退出 CLI 模式
     */
    public void exitCLI(Player player) {
        UUID uuid = player.getUniqueId();
        plugin.getLogger().info("[CLI] Player " + player.getName() + " is exiting CLI mode.");
        activeCLIPayers.remove(uuid);
        pendingAgreementPlayers.remove(uuid);
        sessions.remove(uuid);
        isGenerating.remove(uuid);
        sendExitMessage(player);
    }

    /**
     * 处理玩家发送的消息
     */
    public boolean handleChat(Player player, String message) {
        UUID uuid = player.getUniqueId();

        // 如果玩家在等待协议同意
        if (pendingAgreementPlayers.contains(uuid)) {
            plugin.getLogger().info("[CLI] Player " + player.getName() + " sent agreement message: " + message);
            if (message.equalsIgnoreCase("agree")) {
                pendingAgreementPlayers.remove(uuid);
                saveAgreedPlayer(uuid);
                enterCLI(player);
            } else {
                player.sendMessage(ChatColor.RED + "请发送 agree 以同意协议，或发送 /cli 退出。");
            }
            return true;
        }

        // 如果玩家处于 CLI 模式
        if (activeCLIPayers.contains(uuid)) {
            plugin.getLogger().info("[CLI] Intercepted message from " + player.getName() + ": " + message);
            if (message.equalsIgnoreCase("exit") || message.equalsIgnoreCase("stop")) {
                exitCLI(player);
                return true;
            }

            // 处理待确认的命令或选择
            if (pendingCommands.containsKey(uuid)) {
                String pending = pendingCommands.get(uuid);
                if (pending.equals("CHOOSING")) {
                    pendingCommands.remove(uuid);
                    feedbackToAI(player, "#choose_result: " + message);
                    return true;
                }
                
                if (message.equalsIgnoreCase("y")) {
                    String cmd = pendingCommands.remove(uuid);
                    executeCommand(player, cmd);
                } else if (message.equalsIgnoreCase("n")) {
                    pendingCommands.remove(uuid);
                    player.sendMessage(ChatColor.GRAY + "⇒ 命令已取消");
                    isGenerating.put(uuid, false);
                } else {
                    player.sendMessage(ChatColor.RED + "请确认命令 [Y/N]");
                }
                return true;
            }
            
            if (isGenerating.getOrDefault(uuid, false)) {
                player.sendMessage(ChatColor.RED + "⨀ 请不要在 Agent 生成内容时发送消息，如需打断请输入 stop");
                return true;
            }

            processAIMessage(player, message);
            return true;
        }

        return false;
    }

    private void processAIMessage(Player player, String message) {
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);
        if (session == null) return;

        session.addMessage("user", message);
        isGenerating.put(uuid, true);

        player.sendMessage(ChatColor.GRAY + "◇ " + message);
        player.sendMessage(ChatColor.GRAY + "◆ Thought...");

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String response = ai.chat(session, promptManager.getBaseSystemPrompt());
                Bukkit.getScheduler().runTask(plugin, () -> handleAIResponse(player, response));
            } catch (IOException e) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(ChatColor.RED + "AI 调用出错: " + e.getMessage());
                    isGenerating.put(uuid, false);
                });
            }
        });
    }

    private void handleAIResponse(Player player, String response) {
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);
        if (session == null) return;

        // 解析思考内容（如果有的话，通常 AI 会输出 <thought>...</thought> 或类似内容）
        // 根据 Todo.md，如果 AI 进行了思考，删除思考内容。
        String cleanResponse = response.replaceAll("(?s)<thought>.*?</thought>", "").trim();
        
        // 分离工具调用
        String content = cleanResponse;
        String toolCall = "";
        
        int toolIndex = cleanResponse.lastIndexOf("#");
        if (toolIndex != -1) {
            content = cleanResponse.substring(0, toolIndex).trim();
            toolCall = cleanResponse.substring(toolIndex).trim();
        }

        // 展示 Agent 内容
        if (!content.isEmpty()) {
            displayAgentContent(player, content);
        }

        // 处理工具调用
        if (!toolCall.isEmpty()) {
            executeTool(player, toolCall);
        } else {
            isGenerating.put(uuid, false);
            checkTokenWarning(player, session);
        }

        session.addMessage("assistant", response);
    }

    private void checkTokenWarning(Player player, DialogueSession session) {
        int estimatedTokens = session.getEstimatedTokens();
        // 假设模型窗口较小或我们设置了一个阈值
        int maxTokens = 4000; 
        int remaining = maxTokens - estimatedTokens;
        
        if (remaining < plugin.getConfigManager().getTokenWarningThreshold()) {
            player.sendMessage(ChatColor.YELLOW + "⨀ 剩余 Token 不足 (" + remaining + ")，Agent 可能会遗忘较早的对话内容。");
        }
    }

    private void executeTool(Player player, String toolCall) {
        UUID uuid = player.getUniqueId();
        String toolName = toolCall.split(":")[0].trim();
        String args = toolCall.contains(":") ? toolCall.substring(toolCall.indexOf(":") + 1).trim() : "";

        player.sendMessage(ChatColor.GRAY + "〇 " + toolName);

        switch (toolName.toLowerCase()) {
            case "#over":
                isGenerating.put(uuid, false);
                break;
            case "#exit":
                exitCLI(player);
                break;
            case "#run":
                handleRunTool(player, args);
                break;
            case "#get":
                handleGetTool(player, args);
                break;
            case "#choose":
                handleChooseTool(player, args);
                break;
            case "#search":
                handleSearchTool(player, args);
                break;
            default:
                player.sendMessage(ChatColor.RED + "未知工具: " + toolName);
                isGenerating.put(uuid, false);
                break;
        }
    }

    private void handleRunTool(Player player, String command) {
        UUID uuid = player.getUniqueId();
        pendingCommands.put(uuid, command);

        TextComponent message = new TextComponent(ChatColor.GRAY + "⇒ " + command + " ");
        
        TextComponent yBtn = new TextComponent(ChatColor.GREEN + "[ Y ]");
        yBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "y"));
        yBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("确认执行命令")));

        TextComponent spacer = new TextComponent(" / ");

        TextComponent nBtn = new TextComponent(ChatColor.RED + "[ N ]");
        nBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "n"));
        nBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("取消执行")));

        message.addExtra(yBtn);
        message.addExtra(spacer);
        message.addExtra(nBtn);

        player.spigot().sendMessage(message);
    }

    private void executeCommand(Player player, String command) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            boolean success = Bukkit.dispatchCommand(player, command);
            String result = success ? "命令执行成功" : "命令执行失败";
            player.sendMessage(ChatColor.GRAY + "⇒ " + result);
            
            // 将结果反馈给 AI
            feedbackToAI(player, "#run_result: " + result);
        });
    }

    private void handleGetTool(Player player, String fileName) {
        File presetFile = new File(plugin.getDataFolder(), "preset/" + fileName);
        if (!presetFile.exists()) {
            feedbackToAI(player, "#get_result: 文件不存在");
            return;
        }

        try {
            List<String> lines = java.nio.file.Files.readAllLines(presetFile.toPath());
            String content = String.join("\n", lines);
            feedbackToAI(player, "#get_result: " + content);
        } catch (IOException e) {
            feedbackToAI(player, "#get_result: 读取文件失败 - " + e.getMessage());
        }
    }

    private void handleChooseTool(Player player, String optionsStr) {
        String[] options = optionsStr.split(",");
        TextComponent message = new TextComponent(ChatColor.DARK_RED + "⨂ 【 ");
        
        for (int i = 0; i < options.length; i++) {
            String opt = options[i].trim();
            TextComponent optBtn = new TextComponent(ChatColor.YELLOW + opt);
            optBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, opt));
            optBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("选择 " + opt)));
            
            message.addExtra(optBtn);
            if (i < options.length - 1) {
                message.addExtra(ChatColor.WHITE + " | ");
            }
        }
        message.addExtra(ChatColor.DARK_RED + " 】");
        
        player.spigot().sendMessage(message);
        // 等待玩家输入或点击，这里通过 handleChat 拦截
        // 需要一个状态标记玩家正在进行选择
        pendingCommands.put(player.getUniqueId(), "CHOOSING"); // 借用 pendingCommands 拦截输入
    }

    private void handleSearchTool(Player player, String query) {
        player.sendMessage(ChatColor.GRAY + "〇 #search: " + query);
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String result;
            if (query.toLowerCase().contains("widely")) {
                String q = query.replace("widely", "").trim();
                result = fetchPublicSearchResult(q);
            } else {
                result = fetchWikiResult(query);
            }
            
            final String finalResult = result;
            Bukkit.getScheduler().runTask(plugin, () -> {
                feedbackToAI(player, "#search_result: " + finalResult);
            });
        });
    }

    /**
     * 调用 Minecraft Wiki 公开 API 搜索
     */
    private String fetchWikiResult(String query) {
        try {
            // 使用 Minecraft Wiki 的 MediaWiki API
            String url = "https://minecraft.wiki/api.php?action=query&list=search&srsearch=" + 
                         java.net.URLEncoder.encode(query, "UTF-8") + "&format=json&utf8=1";
            
            okhttp3.Request request = new okhttp3.Request.Builder().url(url).build();
             try (okhttp3.Response response = ai.getHttpClient().newCall(request).execute()) {
                 if (response.isSuccessful() && response.body() != null) {
                    com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(response.body().string()).getAsJsonObject();
                    com.google.gson.JsonArray searchResults = json.getAsJsonObject("query").getAsJsonArray("search");
                    
                    if (searchResults.size() > 0) {
                        StringBuilder sb = new StringBuilder("Minecraft Wiki 搜索结果：\n");
                        for (int i = 0; i < Math.min(3, searchResults.size()); i++) {
                            com.google.gson.JsonObject item = searchResults.get(i).getAsJsonObject();
                            String title = item.get("title").getAsString();
                            String snippet = item.get("snippet").getAsString().replaceAll("<[^>]*>", ""); // 移除 HTML 标签
                            sb.append("- ").append(title).append(": ").append(snippet).append("\n");
                        }
                        return sb.toString();
                    }
                }
            }
        } catch (Exception e) {
            return "Wiki 搜索出错: " + e.getMessage();
        }
        return "未找到相关 Wiki 条目。";
    }

    /**
     * 调用公开搜索接口 (DuckDuckGo Instant Answer)
     */
    private String fetchPublicSearchResult(String query) {
        try {
            // 使用 DuckDuckGo 的公开 API (虽然是 Instant Answer，但对简单查询有效)
            String url = "https://api.duckduckgo.com/?q=" + 
                         java.net.URLEncoder.encode(query, "UTF-8") + "&format=json&no_html=1&skip_disambig=1";
            
            okhttp3.Request request = new okhttp3.Request.Builder().url(url).build();
             try (okhttp3.Response response = ai.getHttpClient().newCall(request).execute()) {
                 if (response.isSuccessful() && response.body() != null) {
                    com.google.gson.JsonObject json = com.google.gson.JsonParser.parseString(response.body().string()).getAsJsonObject();
                    String abstractText = json.get("AbstractText").getAsString();
                    
                    if (!abstractText.isEmpty()) {
                        return "全网搜索摘要 (" + query + "): " + abstractText;
                    }
                    
                    // 如果没有摘要，尝试获取相关话题
                    com.google.gson.JsonArray relatedTopics = json.getAsJsonArray("RelatedTopics");
                    if (searchResultsExist(relatedTopics)) {
                        StringBuilder sb = new StringBuilder("相关搜索结果：\n");
                        int count = 0;
                        for (int i = 0; i < relatedTopics.size() && count < 3; i++) {
                            com.google.gson.JsonObject topic = relatedTopics.get(i).getAsJsonObject();
                            if (topic.has("Text")) {
                                sb.append("- ").append(topic.get("Text").getAsString()).append("\n");
                                count++;
                            }
                        }
                        return sb.toString();
                    }
                }
            }
        } catch (Exception e) {
            return "公开搜索出错: " + e.getMessage();
        }
        return "未找到相关全网搜索结果。";
    }

    private boolean searchResultsExist(com.google.gson.JsonArray topics) {
        return topics != null && topics.size() > 0;
    }

    private void feedbackToAI(Player player, String feedback) {
        processAIMessage(player, feedback);
    }

    private void displayAgentContent(Player player, String content) {
        String[] parts = content.split("```");
        TextComponent message = new TextComponent(ChatColor.WHITE + "◆ ");
        
        for (int i = 0; i < parts.length; i++) {
            if (i % 2 == 1) {
                // 代码块部分，亮蓝色显示
                message.addExtra(ChatColor.AQUA + parts[i]);
            } else {
                // 普通文本部分，白色显示
                message.addExtra(ChatColor.WHITE + parts[i]);
            }
        }
        player.spigot().sendMessage(message);
    }

    private void sendAgreement(Player player) {
        player.sendMessage(ChatColor.GRAY + "===============");
        player.sendMessage(ChatColor.WHITE + "MineAgent 用户协议");
        player.sendMessage(ChatColor.GRAY + "1. 本插件使用 AI 提供服务，可能产生错误信息。");
        player.sendMessage(ChatColor.GRAY + "2. 您的对话内容将被发送至 CloudFlare 进行处理。");
        player.sendMessage(ChatColor.GRAY + "3. 请勿输入敏感信息。");
        player.sendMessage(ChatColor.WHITE + "发送 " + ChatColor.GREEN + "agree" + ChatColor.WHITE + " 表示同意并继续。");
        player.sendMessage(ChatColor.GRAY + "===============");
    }

    private void sendEnterMessage(Player player) {
        player.sendMessage(ChatColor.GRAY + "===============");
        player.sendMessage(ChatColor.WHITE + "MineAgent (CLI Mode)");
        player.sendMessage(ChatColor.GRAY + "===============");
    }

    private void sendExitMessage(Player player) {
        player.sendMessage(ChatColor.GRAY + "===============");
        player.sendMessage(ChatColor.WHITE + "已退出 CLI Mode");
        player.sendMessage(ChatColor.GRAY + "===============");
    }
}
