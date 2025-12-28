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

    public void handleConfirm(Player player) {
        UUID uuid = player.getUniqueId();
        if (pendingCommands.containsKey(uuid)) {
            String cmd = pendingCommands.get(uuid);
            if (!"CHOOSING".equals(cmd)) {
                pendingCommands.remove(uuid);
                executeCommand(player, cmd);
            }
        }
    }

    public void handleCancel(Player player) {
        UUID uuid = player.getUniqueId();
        if (pendingCommands.containsKey(uuid)) {
            pendingCommands.remove(uuid);
            player.sendMessage(ChatColor.GRAY + "⇒ 命令已取消");
            isGenerating.put(uuid, false);
        }
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
            if (message.equalsIgnoreCase("exit")) {
                exitCLI(player);
                return true;
            }
            if (message.equalsIgnoreCase("stop")) {
                boolean interrupted = false;
                if (isGenerating.getOrDefault(uuid, false)) {
                    isGenerating.put(uuid, false);
                    player.sendMessage(ChatColor.YELLOW + "⇒ 已打断 Agent 生成");
                    interrupted = true;
                }
                if (pendingCommands.containsKey(uuid)) {
                    pendingCommands.remove(uuid);
                    player.sendMessage(ChatColor.GRAY + "⇒ 已取消当前待处理的操作");
                    isGenerating.put(uuid, false);
                    interrupted = true;
                }
                if (!interrupted) {
                    player.sendMessage(ChatColor.GRAY + "当前没有正在进行的操作。输入 exit 退出 CLI 模式。");
                }
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
                
                if (message.equalsIgnoreCase("y") || message.equalsIgnoreCase("/mineagent confirm")) {
                    String cmd = pendingCommands.remove(uuid);
                    executeCommand(player, cmd);
                } else if (message.equalsIgnoreCase("n") || message.equalsIgnoreCase("/mineagent cancel")) {
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

        plugin.getLogger().info("[CLI] Session " + player.getName() + " - History Size: " + session.getHistory().size() + ", Est. Tokens: " + session.getEstimatedTokens());

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String response = ai.chat(session, promptManager.getBaseSystemPrompt(player));
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

        // 如果生成已被打断，则丢弃响应
        if (!isGenerating.getOrDefault(uuid, false)) {
            plugin.getLogger().info("[CLI] Discarding AI response for " + player.getName() + " due to interruption.");
            return;
        }

        plugin.getLogger().info("[CLI] AI Response received for " + player.getName() + " (Length: " + response.length() + ")");

        // 解析思考内容（如果有的话，通常 AI 会输出 <thought>...</thought> 或类似内容）
        // 根据 Todo.md，如果 AI 进行了思考，删除思考内容。
        String cleanResponse = response.replaceAll("(?s)<thought>.*?</thought>", "").trim();
        
        // 增强的工具调用提取逻辑：使用正则表达式匹配末尾的工具调用
        // 匹配模式：最后一个 # 加上已知的工具名
        String content = cleanResponse;
        String toolCall = "";
        
        // 定义已知工具列表
        List<String> knownTools = Arrays.asList("#over", "#exit", "#run", "#get", "#choose", "#search");
        
        // 从后往前寻找最后一个工具调用
        int lastHashIndex = cleanResponse.lastIndexOf("#");
        if (lastHashIndex != -1) {
            String potentialToolPart = cleanResponse.substring(lastHashIndex).trim();
            for (String tool : knownTools) {
                if (potentialToolPart.toLowerCase().startsWith(tool)) {
                    toolCall = potentialToolPart;
                    content = cleanResponse.substring(0, lastHashIndex).trim();
                    break;
                }
            }
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
        
        // 改进的解析逻辑：兼容冒号和空格分隔符，且只分割第一次出现的标识符
        String toolName;
        String args = "";
        
        // 查找第一个冒号或空格的位置
        int colonIndex = toolCall.indexOf(":");
        int spaceIndex = toolCall.indexOf(" ");
        
        int splitIndex = -1;
        if (colonIndex != -1 && spaceIndex != -1) {
            splitIndex = Math.min(colonIndex, spaceIndex);
        } else if (colonIndex != -1) {
            splitIndex = colonIndex;
        } else if (spaceIndex != -1) {
            splitIndex = spaceIndex;
        }

        if (splitIndex != -1) {
            toolName = toolCall.substring(0, splitIndex).trim();
            args = toolCall.substring(splitIndex + 1).trim();
        } else {
            toolName = toolCall.trim();
        }

        plugin.getLogger().info("[CLI] Executing tool for " + player.getName() + ": " + toolName + " (Args: " + args + ")");
        
        // 统一转换为小写进行匹配
        String lowerToolName = toolName.toLowerCase();
        
        // 展示给玩家时只显示工具名
        player.sendMessage(ChatColor.GRAY + "〇 " + toolName);

        switch (lowerToolName) {
            case "#over":
                isGenerating.put(uuid, false);
                break;
            case "#exit":
                exitCLI(player);
                break;
            case "#run":
                if (args.isEmpty()) {
                    player.sendMessage(ChatColor.RED + "错误: #run 工具需要提供命令参数");
                    isGenerating.put(uuid, false);
                } else {
                    handleRunTool(player, args);
                }
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
        
        // 自动过滤掉领先的斜杠 /
        String cleanCommand = command.startsWith("/") ? command.substring(1) : command;
        pendingCommands.put(uuid, cleanCommand);

        TextComponent message = new TextComponent(ChatColor.GRAY + "⇒ " + cleanCommand + " ");
        
        TextComponent yBtn = new TextComponent(ChatColor.GREEN + "[ Y ]");
        yBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli confirm"));
        yBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("确认执行命令")));

        TextComponent spacer = new TextComponent(" / ");

        TextComponent nBtn = new TextComponent(ChatColor.RED + "[ N ]");
        nBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli cancel"));
        nBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text("取消执行")));

        message.addExtra(yBtn);
        message.addExtra(spacer);
        message.addExtra(nBtn);

        player.spigot().sendMessage(message);
    }

    private void executeCommand(Player player, String command) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            StringBuilder output = new StringBuilder();
            
            // 创建一个临时的 CommandSender 来拦截输出
            // 使用最终变量数组来解决匿名内部类引用问题
            final org.bukkit.command.CommandSender[] interceptorWrapper = new org.bukkit.command.CommandSender[1];
            
            interceptorWrapper[0] = new org.bukkit.command.CommandSender() {
                @Override
                public void sendMessage(String message) {
                    if (message == null) return;
                    if (output.length() > 0) output.append("\n");
                    output.append(org.bukkit.ChatColor.stripColor(message));
                    player.sendMessage(message); // 仍然发送给玩家，保证玩家能看到
                }

                @Override
                public void sendMessage(String... messages) {
                    for (String msg : messages) {
                        sendMessage(msg);
                    }
                }

                // 兼容 1.16+ 的 UUID 发送方法
                public void sendMessage(java.util.UUID uuid, String message) {
                    sendMessage(message);
                }

                public void sendMessage(java.util.UUID uuid, String... messages) {
                    sendMessage(messages);
                }

                @Override
                public org.bukkit.Server getServer() { return player.getServer(); }

                @Override
                public String getName() { return player.getName(); }

                @Override
                public org.bukkit.command.CommandSender.Spigot spigot() {
                    return new org.bukkit.command.CommandSender.Spigot() {
                        @Override
                        public void sendMessage(net.md_5.bungee.api.chat.BaseComponent component) {
                            interceptorWrapper[0].sendMessage(net.md_5.bungee.api.chat.TextComponent.toLegacyText(component));
                        }

                        @Override
                        public void sendMessage(net.md_5.bungee.api.chat.BaseComponent... components) {
                            for (net.md_5.bungee.api.chat.BaseComponent component : components) {
                                sendMessage(component);
                            }
                        }

                        @Override
                        public void sendMessage(java.util.UUID uuid, net.md_5.bungee.api.chat.BaseComponent component) {
                            sendMessage(component);
                        }

                        @Override
                        public void sendMessage(java.util.UUID uuid, net.md_5.bungee.api.chat.BaseComponent... components) {
                            sendMessage(components);
                        }
                    };
                }

                @Override
                public boolean isPermissionSet(String name) { return player.isPermissionSet(name); }

                @Override
                public boolean isPermissionSet(org.bukkit.permissions.Permission perm) { return player.isPermissionSet(perm); }

                @Override
                public boolean hasPermission(String name) { return player.hasPermission(name); }

                @Override
                public boolean hasPermission(org.bukkit.permissions.Permission perm) { return player.hasPermission(perm); }

                @Override
                public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value) { return player.addAttachment(plugin, name, value); }

                @Override
                public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin) { return player.addAttachment(plugin); }

                @Override
                public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, String name, boolean value, int ticks) { return player.addAttachment(plugin, name, value, ticks); }

                @Override
                public org.bukkit.permissions.PermissionAttachment addAttachment(org.bukkit.plugin.Plugin plugin, int ticks) { return player.addAttachment(plugin, ticks); }

                @Override
                public void removeAttachment(org.bukkit.permissions.PermissionAttachment attachment) { player.removeAttachment(attachment); }

                @Override
                public void recalculatePermissions() { player.recalculatePermissions(); }

                @Override
                public java.util.Set<org.bukkit.permissions.PermissionAttachmentInfo> getEffectivePermissions() { return player.getEffectivePermissions(); }

                @Override
                public boolean isOp() { return player.isOp(); }

                @Override
                public void setOp(boolean value) { player.setOp(value); }
            };

            boolean success = false;
            try {
                success = Bukkit.dispatchCommand(interceptorWrapper[0], command);
            } catch (Exception e) {
                // 如果拦截器执行失败（常见于原版命令），退回到使用玩家身份执行
                plugin.getLogger().warning("[CLI] Interceptor failed for command '" + command + "', falling back to player execution. Error: " + e.getMessage());
                success = player.performCommand(command);
                if (output.length() == 0) {
                    output.append("(由于原版命令限制，详细输出已直接发送至您的聊天框)");
                }
            }
            
            // 特殊处理：如果是 list 命令且没有捕获到输出，手动添加玩家列表
            if (command.toLowerCase().startsWith("list") && output.length() <= 30) {
                StringBuilder sb = new StringBuilder("当前在线玩家: ");
                Bukkit.getOnlinePlayers().forEach(p -> sb.append(p.getName()).append(", "));
                output.append("\n").append(sb.toString());
            }
            
            String finalResult;
            if (output.length() > 0) {
                finalResult = output.toString();
            } else {
                finalResult = success ? "命令执行成功" : "命令执行失败";
            }
            
            player.sendMessage(ChatColor.GRAY + "⇒ 反馈已发送至 Agent");
            
            // 将详细结果反馈给 AI
            feedbackToAI(player, "#run_result: " + finalResult);
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
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);
        if (session == null) return;

        session.addMessage("user", feedback);
        isGenerating.put(uuid, true);

        // 工具返回信息不显示给玩家，仅在日志记录并触发 AI 思考
        plugin.getLogger().info("[CLI] Feedback sent to AI for " + player.getName() + ": " + feedback);
        
        // 异步调用 AI，不显示 "Thought..." 提示，因为这是后台自动反馈
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                    String systemPrompt = promptManager.getBaseSystemPrompt(player);
                    String response = ai.chat(session, systemPrompt);

                Bukkit.getScheduler().runTask(plugin, () -> {
                    handleAIResponse(player, response);
                });
            } catch (IOException e) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    player.sendMessage(ChatColor.RED + "AI 调用出错: " + e.getMessage());
                    isGenerating.put(uuid, false);
                });
            }
        });
    }

    private void displayAgentContent(Player player, String content) {
        // 先处理代码块 ```...```
        String[] codeParts = content.split("```");
        TextComponent finalMessage = new TextComponent(ChatColor.WHITE + "◆ ");
        
        for (int i = 0; i < codeParts.length; i++) {
            if (i % 2 == 1) {
                // 代码块部分，亮蓝色显示
                finalMessage.addExtra(ChatColor.AQUA + codeParts[i]);
            } else {
                // 普通文本部分，进一步处理 **...** 高亮
                String text = codeParts[i];
                String[] highlightParts = text.split("\\*\\*");
                
                for (int j = 0; j < highlightParts.length; j++) {
                    if (j % 2 == 1) {
                        // 高亮部分，亮蓝色显示
                        finalMessage.addExtra(ChatColor.AQUA + highlightParts[j]);
                    } else {
                        // 普通部分，白色显示
                        finalMessage.addExtra(ChatColor.WHITE + highlightParts[j]);
                    }
                }
            }
        }
        player.spigot().sendMessage(finalMessage);
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

    public ChatColor getActivePlayersCount() {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'getActivePlayersCount'");
    }
}
