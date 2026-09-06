package org.YanPl.manager;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.YanPl.FancyHelper;
import org.YanPl.api.LLMClient;
import org.YanPl.api.StreamingHandler;
import org.YanPl.model.AIResponse;
import org.YanPl.model.DialogueSession;
import org.YanPl.model.NativeToolCall;
import org.YanPl.model.SessionRecord;
import org.YanPl.model.Skill;
import org.YanPl.util.ColorUtil;
import org.YanPl.util.I18n;
import org.YanPl.util.PlayerListFileUtil;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * CLI 模式管理器，负责管理玩家的 CLI 状态和对话流
 */
public class CLIManager {
    private final FancyHelper plugin;
    private final LLMClient ai;
    private final PromptManager promptManager;
    private final ToolExecutor toolExecutor;
    private final Set<UUID> activeCLIPayers = new HashSet<>();
    private final Set<UUID> pendingAgreementPlayers = new HashSet<>();
    private final Set<UUID> agreedPlayers = new HashSet<>();
    private final Set<UUID> yoloAgreedPlayers = new HashSet<>();
    private final Set<UUID> yoloModePlayers = new HashSet<>();
    private final Set<UUID> pendingYoloAgreementPlayers = new HashSet<>();
    private final Set<UUID> smartModePlayers = new HashSet<>();
    private final File agreedPlayersFile;
    private final File yoloAgreedPlayersFile;
    private final File yoloModePlayersFile;
    private final File smartModePlayersFile;
    private final File planModePlayersFile;
    private final Set<UUID> planModePlayers = new HashSet<>();
    private final Set<UUID> pendingPlanContextClear = new HashSet<>();
    private final Set<UUID> pendingPlanStartMode = new HashSet<>();
    private final Map<UUID, PendingSmartAction> pendingSmartActions = new ConcurrentHashMap<>();
    private final Map<UUID, DialogueSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> isGenerating = new ConcurrentHashMap<>();
    private final Map<UUID, GenerationStatus> generationStates = new ConcurrentHashMap<>();
    private final Map<UUID, Long> generationStartTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> messageReceiveTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> wordStartTimes = new ConcurrentHashMap<>();
    private final Map<UUID, String> currentThinkingWords = new ConcurrentHashMap<>();
    private final Map<UUID, Long> streamedOutputTokens = new ConcurrentHashMap<>();
    private final Map<UUID, Long> roundOutputTokens = new ConcurrentHashMap<>();
    // 玩家名缓存：saveSessionToHistory 在玩家离线后（异步任务）仍需要玩家名来定位
    // 会话目录，不能依赖 Bukkit.getPlayer()（离线返回 null 会导致会话保存被跳过）。
    private final Map<UUID, String> playerNameCache = new ConcurrentHashMap<>();

    // 思考状态的随机神经病词列表
    private static final String[] THINKING_WORDS = {        "Accomplishing", "Actioning", "Actualizing", "Architecting", "Baking", "Bamboozling", "Beaming", "Beboppin'", "Befuddling", "Billowing", "Blanching", "Bloviating", "Boogieing", "Boondoggling", "Booping", "Bootstrapping", "Brewing", "Burrowing", "Calculating", "Canoodling", "Caramelizing", "Cascading", "Catapulting", "Catalyzing", "Cerebrating", "Channeling", "Channelling", "Choreographing", "Churning", "Clauding", "Coalescing", "Cogitating", "Colloquializing", "Combobulating", "Composing", "Computing", "Concocting", "Congealing", "Considering", "Contemplating", "Cooking", "Crafting", "Creating", "Crunching", "Crystallizing", "Cultivating", "Deciphering", "Decomposing", "Deliberating", "Determining", "Diffusing", "Dilly-dallying", "Discombobulating", "Dissolving", "Doing", "Doodling", "Drizzling", "Ebbing", "Effecting", "Elucidating", "Enchanting", "Envisioning", "Extrapolating", "Fermenting", "Festering", "Finagling", "Flambeing", "Flibbertigibbeting", "Flummoxing", "Forging", "Forming", "Frosting", "Frolicking", "Furnishing", "Gallivanting", "Galloping", "Garnishing", "Gelatinizing", "Generating", "Germinating", "Hatching", "Herding", "Honking", "Hustling", "Ideating", "Imagining", "Incubating", "Inferring", "Ionizing", "Iridescent", "Jiving", "Jostling", "Julienning", "Kneading", "Leavening", "Lollygagging", "Manifesting", "Marinating", "Meandering", "Moseying", "Moonwalking", "Mulling", "Mustering", "Musing", "Navigating", "Nebulating", "Noodling", "Osmosing", "Percolating", "Perusing", "Philosophising", "Polymerizing", "Pontificating", "Pondering", "Processing", "Proofing", "Puttering", "Puzzling", "Radiating", "Razzle-dazzling", "Reticulating", "Reverberating", "Ricocheting", "Rippling", "Ruminating", "Sauteing", "Scampering", "Scheming", "Schlepping", "Scurrying", "Seasoning", "Shimmying", "Shenaniganing", "Simmering", "Smooshing", "Soldering", "Spelunking", "Spinning", "Spiraling", "Synthesizing", "Synergizing", "Tempering", "Tinkering", "Thinking", "Tomfoolering", "Topsy-turvying", "Transmuting", "Trickling", "Ubiquitizing", "Undulating", "Unfurling", "Unravelling", "Untangling", "Vibing", "Vexing", "Waddling", "Wandering", "Waxing", "Whatchamacalliting", "Whirring", "Whisking", "Wibbling", "Wizarding", "Working", "Wrangling", "Zigzagging", "Zesting"
    };

    // 已知工具列表（顺序敏感：startsWith 前缀匹配，长名在前，否则 #edit_global 会被 #edit 劫持）
    // #edit_memory 必须排在 #edit 之前，否则 #edit_memory: ... 会被 #edit 劫持
    private static final List<String> KNOWN_TOOLS = List.of(
        "#start", "#end", "#exit", "#run", "#ask", "#search", "#skill", "#unloadskill",
        "#list", "#read", "#edit_global", "#edit_memory", "#edit", "#write", "#todo",
        "#remember_global", "#remember", "#forget_global", "#forget",
        "#webfetch", "#mcp_tools", "#mcp");

    // 串行批量工具执行超时（毫秒）：批次中某工具异步异常长时间无反馈时强制终结
    private static final long BATCH_TIMEOUT_MS = 60_000;

    // 状态栏呼吸动画
    private static final long[] BREATHING_PHASE_ENDS = { 500, 800, 1000, 1100, 1300, 1600 };
    private static final String[] BREATHING_HEX = {
        "#FF7800", "#D46700", "#A15100", "#8F5200", "#A15100", "#D46700"
    };
    private static final String[] BREATHING_SYMBOLS = {
        "⁕", "⁕", "⁜", "▪", "⁜", "⁕"
    };
    // 进入CLI时的随机提示语
    private static final String[] ENTER_TIPS = {
        I18n.t("clim.tips.0"),
        I18n.t("clim.tips.1"),
        I18n.t("clim.tips.2"),
        I18n.t("clim.tips.3"),
        I18n.t("clim.tips.4"),
        I18n.t("clim.tips.5"),
        I18n.t("clim.tips.6"),
        I18n.t("clim.tips.7"),
        I18n.t("clim.tips.8"),
        I18n.t("clim.tips.9"),
        I18n.t("clim.tips.10"),
        I18n.t("clim.tips.11"),
        I18n.t("clim.tips.12")
    };
    private static final net.md_5.bungee.api.ChatColor WORD_COLOR_BUNGEE = net.md_5.bungee.api.ChatColor.of("#FF5F00");
    private static final net.md_5.bungee.api.ChatColor STATS_COLOR = net.md_5.bungee.api.ChatColor.GRAY;
    private final Map<UUID, String> pendingCommands = new ConcurrentHashMap<>();
    private final Map<UUID, String> interruptedToolCalls = new ConcurrentHashMap<>();
    private final Map<UUID, RetryInfo> retryInfoMap = new ConcurrentHashMap<>();
    private final Map<UUID, StreamingHandler> activeStreamingHandlers = new ConcurrentHashMap<>();

    // 会话历史持久化相关字段
    private final Map<String, String> generatedTitles = new ConcurrentHashMap<>(); // sessionUUID -> title
    private final Map<String, Boolean> weakModelWarned = new ConcurrentHashMap<>(); // sessionUUID -> 是否已提示过模型能力弱
    private final Map<UUID, String> pendingDeleteSessions = new ConcurrentHashMap<>(); // 待删除确认
    private static final String SESSIONS_DIR = "sessions";
    private static final int MAX_SESSIONS_PER_PLAYER = 40;

    /**
     * 重试信息类
     */
    private static class RetryInfo {
        final DialogueSession session;
        final String lastMessage;
        final boolean isUserMessage;
        final List<Skill> matchedSkills;

        RetryInfo(DialogueSession session, String lastMessage, boolean isUserMessage, List<Skill> matchedSkills) {
            this.session = session;
            this.lastMessage = lastMessage;
            this.isUserMessage = isUserMessage;
            this.matchedSkills = matchedSkills != null ? matchedSkills : Collections.emptyList();
        }
    }

    /**
     * 待处理的SMART操作信息
     */
    private static class PendingSmartAction {
        final String actionType;
        final String actionContent;
        PendingSmartAction(String actionType, String actionContent, RiskAssessmentManager.RiskAssessment assessment) {
            this.actionType = actionType;
            this.actionContent = actionContent;
        }
    }

    public enum GenerationStatus {
        THINKING,
        EXECUTING_TOOL,
        WAITING_CONFIRM,
        WAITING_CHOICE,
        COMPLETED,
        CANCELLED,
        ERROR,
        IDLE
    }

    public CLIManager(FancyHelper plugin) {
        this.plugin = plugin;
        this.ai = new LLMClient(plugin);
        this.promptManager = new PromptManager(plugin);
        this.toolExecutor = new ToolExecutor(plugin, this);
        File runtimeDir = new File(plugin.getDataFolder(), "runtime");
        if (!runtimeDir.exists()) {
            runtimeDir.mkdirs();
        }
        this.agreedPlayersFile = new File(runtimeDir, "agreed_players.json");
        this.yoloAgreedPlayersFile = new File(runtimeDir, "yolo_agreed_players.json");
        this.yoloModePlayersFile = new File(runtimeDir, "yolo_mode_players.json");
        this.smartModePlayersFile = new File(runtimeDir, "smart_mode_players.json");
        this.planModePlayersFile = new File(runtimeDir, "plan_mode_players.json");
        // 旧版本（config.yml 版本 <= 4.1.1）自动迁移：txt -> runtime JSON
        // 兜底：只要检测到旧版 txt 文件存在也执行迁移，避免配置版本已更新但文件未迁移的情况
        if (plugin.getConfigManager().isLegacyPlayerListMigrationNeeded() || hasLegacyPlayerFiles()) {
            migrateLegacyPlayerFiles();
        }
        loadAgreedPlayers();
        loadYoloAgreedPlayers();
        loadYoloModePlayers();
        loadSmartModePlayers();
        loadPlanModePlayers();
        startTimeoutTask();
        startThinkingTask();
        startLogCleanupTask();
        cleanupOldTempHistory();
    }

    public void loadAgreedPlayers() {
        agreedPlayers.clear();
        try {
            agreedPlayers.addAll(PlayerListFileUtil.readJson(agreedPlayersFile));
        } catch (IOException e) {
            plugin.getLogger().warning("无法加载已同意协议的玩家列表: " + e.getMessage());
            plugin.getCloudErrorReport().report(e);
        }
    }

    public void loadYoloAgreedPlayers() {
        yoloAgreedPlayers.clear();
        try {
            yoloAgreedPlayers.addAll(PlayerListFileUtil.readJson(yoloAgreedPlayersFile));
        } catch (IOException e) {
            plugin.getLogger().warning("无法加载已同意 YOLO 协议的玩家列表: " + e.getMessage());
            plugin.getCloudErrorReport().report(e);
        }
    }

    private void saveAgreedPlayer(UUID uuid) {
        agreedPlayers.add(uuid);
        try {
            PlayerListFileUtil.writeJson(agreedPlayersFile, agreedPlayers);
        } catch (IOException e) {
            plugin.getLogger().warning("无法保存已同意协议的玩家: " + e.getMessage());
            plugin.getCloudErrorReport().report(e);
        }
    }

    private void saveYoloAgreedPlayer(UUID uuid) {
        yoloAgreedPlayers.add(uuid);
        try {
            PlayerListFileUtil.writeJson(yoloAgreedPlayersFile, yoloAgreedPlayers);
        } catch (IOException e) {
            plugin.getLogger().warning("无法保存已同意 YOLO 协议的玩家: " + e.getMessage());
            plugin.getCloudErrorReport().report(e);
        }
    }

    public void loadYoloModePlayers() {
        yoloModePlayers.clear();
        try {
            yoloModePlayers.addAll(PlayerListFileUtil.readJson(yoloModePlayersFile));
        } catch (IOException e) {
            plugin.getLogger().warning("无法加载处于 YOLO 模式的玩家列表: " + e.getMessage());
            plugin.getCloudErrorReport().report(e);
        }
    }

    private void saveYoloModeState(UUID uuid, boolean isYolo) {
        if (isYolo) {
            if (yoloModePlayers.add(uuid)) {
                writeYoloModePlayers();
            }
        } else {
            if (yoloModePlayers.remove(uuid)) {
                writeYoloModePlayers();
            }
        }
    }

    private void writeYoloModePlayers() {
        try {
            PlayerListFileUtil.writeJson(yoloModePlayersFile, yoloModePlayers);
        } catch (IOException e) {
            plugin.getLogger().warning("无法保存 YOLO 模式玩家列表: " + e.getMessage());
            plugin.getCloudErrorReport().report(e);
        }
    }

    public void loadSmartModePlayers() {
        smartModePlayers.clear();
        try {
            smartModePlayers.addAll(PlayerListFileUtil.readJson(smartModePlayersFile));
        } catch (IOException e) {
            plugin.getLogger().warning("无法加载处于 SMART 模式的玩家列表: " + e.getMessage());
            plugin.getCloudErrorReport().report(e);
        }
    }

    private void saveSmartModeState(UUID uuid, boolean isSmart) {
        if (isSmart) {
            if (smartModePlayers.add(uuid)) {
                writeSmartModePlayers();
            }
        } else {
            if (smartModePlayers.remove(uuid)) {
                writeSmartModePlayers();
            }
        }
    }

    private void writeSmartModePlayers() {
        try {
            PlayerListFileUtil.writeJson(smartModePlayersFile, smartModePlayers);
        } catch (IOException e) {
            plugin.getLogger().warning("无法保存 SMART 模式玩家列表: " + e.getMessage());
            plugin.getCloudErrorReport().report(e);
        }
    }

    public void loadPlanModePlayers() {
        planModePlayers.clear();
        try {
            planModePlayers.addAll(PlayerListFileUtil.readJson(planModePlayersFile));
        } catch (IOException e) {
            plugin.getLogger().warning("无法加载处于 Plan 模式的玩家列表: " + e.getMessage());
            plugin.getCloudErrorReport().report(e);
        }
    }

    private void savePlanModeState(UUID uuid, boolean isPlan) {
        if (isPlan) {
            if (planModePlayers.add(uuid)) {
                writePlanModePlayers();
            }
        } else {
            if (planModePlayers.remove(uuid)) {
                writePlanModePlayers();
            }
        }
    }

    private void writePlanModePlayers() {
        try {
            PlayerListFileUtil.writeJson(planModePlayersFile, planModePlayers);
        } catch (IOException e) {
            plugin.getLogger().warning("无法保存 Plan 模式玩家列表: " + e.getMessage());
            plugin.getCloudErrorReport().report(e);
        }
    }

    /**
     * 是否仍存在旧版 txt 玩家列表文件
     */
    private boolean hasLegacyPlayerFiles() {
        return new File(plugin.getDataFolder(), "agreed_players.txt").exists()
                || new File(plugin.getDataFolder(), "yolo_agreed_players.txt").exists()
                || new File(plugin.getDataFolder(), "yolo_mode_players.txt").exists()
                || new File(plugin.getDataFolder(), "smart_mode_players.txt").exists()
                || new File(plugin.getDataFolder(), "plan_mode_players.txt").exists();
    }

    /**
     * 迁移旧版玩家列表文件（txt）到 runtime 目录 JSON 格式
     */
    private void migrateLegacyPlayerFiles() {
        migrateLegacyFile(new File(plugin.getDataFolder(), "agreed_players.txt"), agreedPlayersFile);
        migrateLegacyFile(new File(plugin.getDataFolder(), "yolo_agreed_players.txt"), yoloAgreedPlayersFile);
        migrateLegacyFile(new File(plugin.getDataFolder(), "yolo_mode_players.txt"), yoloModePlayersFile);
        migrateLegacyFile(new File(plugin.getDataFolder(), "smart_mode_players.txt"), smartModePlayersFile);
        migrateLegacyFile(new File(plugin.getDataFolder(), "plan_mode_players.txt"), planModePlayersFile);
    }

    /**
     * 迁移单个旧版玩家列表文件：读取 txt，写入 JSON，删除旧文件
     */
    private void migrateLegacyFile(File legacyFile, File jsonFile) {
        if (!legacyFile.exists()) return;
        try {
            Set<UUID> uuids = PlayerListFileUtil.readLegacyTxt(legacyFile);
            PlayerListFileUtil.writeJson(jsonFile, uuids);
            Files.deleteIfExists(legacyFile.toPath());
            plugin.getLogger().info("已迁移旧版玩家列表文件 " + legacyFile.getName() + " -> " + jsonFile.getName()
                    + "（" + uuids.size() + " 名玩家）");
        } catch (IOException e) {
            plugin.getLogger().warning("迁移旧版玩家列表文件失败 " + legacyFile.getName() + ": " + e.getMessage());
            plugin.getCloudErrorReport().report(e);
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
                            player.sendMessage(I18n.t("clim.timeout.exit"));
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
     * 启动日志清理任务，定期删除超过配置天数的日志文件
     */
    private void startLogCleanupTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    Path logDir = plugin.getDataFolder().toPath().resolve("logs");
                    if (!Files.exists(logDir)) {
                        return;
                    }
                    
                     int retentionDays = plugin.getConfigManager().getLogRetentionDays();
                    long cutoffTime = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L);
                    
                    Files.list(logDir)
                        .filter(path -> path.toString().endsWith(".log"))
                        .filter(path -> {
                            try {
                                return Files.getLastModifiedTime(path).toMillis() < cutoffTime;
                            } catch (IOException e) {
                                return false;
                            }
                        })
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                                if (plugin.getConfigManager().isDebug()) {
                                    plugin.getLogger().info("[CLI] 已删除过期日志文件: " + path.getFileName());
                                }
                            } catch (IOException e) {
                                plugin.getLogger().warning("[CLI] 删除日志文件失败: " + path.getFileName() + " - " + e.getMessage());
                            }
                        });
                } catch (IOException e) {
                    plugin.getLogger().warning("[CLI] 日志清理任务出错: " + e.getMessage());
                }
            }
        }.runTaskTimerAsynchronously(plugin, 0L, 20L * 60 * 60); // 每小时检查一次
    }

    /**
     * 清理旧的 temp/history 目录（v2.0.0 之前使用的临时会话存储格式）
     * 升级后自动清理，避免残留文件堆积
     */
    private void cleanupOldTempHistory() {
        try {
            Path historyDir = plugin.getDataFolder().toPath().resolve("temp").resolve("history");
            if (Files.exists(historyDir)) {
                try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(historyDir, "*.json")) {
                    for (Path file : stream) {
                        Files.deleteIfExists(file);
                    }
                }
                Files.deleteIfExists(historyDir);
                // 如果 temp 目录空了也删掉
                Path tempDir = historyDir.getParent();
                if (Files.exists(tempDir) && Files.list(tempDir).count() == 0) {
                    Files.deleteIfExists(tempDir);
                }
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[CLI] 已清理旧 temp/history 目录");
                }
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[CLI] 清理旧 temp/history 目录失败: " + e.getMessage());
        }
    }

    /**
     * 启动 AI 思考状态显示任务
     */
    private void startThinkingTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!plugin.isEnabled()) {
                    this.cancel();
                    return;
                }
                long now = System.currentTimeMillis();
                
                for (UUID uuid : activeCLIPayers) {
                    Player player = Bukkit.getPlayer(uuid);
                    if (player == null || !player.isOnline()) continue;

                    GenerationStatus status = generationStates.getOrDefault(uuid, GenerationStatus.IDLE);
                    if (status == GenerationStatus.IDLE) continue;

                    String message = "";
                    switch (status) {
                        case THINKING:
                            Long startTime = generationStartTimes.get(uuid);
                            if (startTime == null) {
                                continue;
                            }

                            long msgTime = messageReceiveTimes.getOrDefault(uuid, startTime);
                            long elapsedFromMsg = (now - msgTime) / 1000;

                            // 1. 呼吸动画 — 颜色/符号在 1.6s 周期内循环
                            long animPhase = (now - startTime) % 1600;
                            int phaseIdx = 0;
                            for (int i = 0; i < BREATHING_PHASE_ENDS.length; i++) {
                                if (animPhase < BREATHING_PHASE_ENDS[i]) {
                                    phaseIdx = i;
                                    break;
                                }
                            }
                            net.md_5.bungee.api.ChatColor starColor = net.md_5.bungee.api.ChatColor.of(BREATHING_HEX[phaseIdx]);

                            // 2. 随机词 + 打字机效果（每 7 秒切换新词，逐字揭示）
                            Long wordStart = wordStartTimes.get(uuid);
                            String word = currentThinkingWords.get(uuid);
                            if (wordStart == null || word == null || now - wordStart > 7000) {
                                word = THINKING_WORDS[new Random().nextInt(THINKING_WORDS.length)];
                                currentThinkingWords.put(uuid, word);
                                wordStartTimes.put(uuid, now);
                            }

                            // 打字机缓动效果 — ease-out cubic，先快后慢，约 3.5s 打完
                            long wordElapsed = now - wordStart;
                            double typeDuration = 2000.0;
                            double progress = Math.min(wordElapsed / typeDuration, 1.0);
                            double eased = 1 - Math.pow(1 - progress, 3);
                            int revealIdx = Math.max(1, Math.min((int) Math.round(eased * word.length()), word.length()));
                            String typewriterWord = word.substring(0, revealIdx);
                            boolean isFullyRevealed = revealIdx >= word.length();
                            String suffix = isFullyRevealed ? "... " : "_ ";

                            // 3. Token 统计（仅本轮输出，含实时流式累计）
                            DialogueSession session = sessions.get(uuid);
                            String tokensInfo = "";
                            if (session != null) {
                                long streamingTokens = streamedOutputTokens.getOrDefault(uuid, 0L);
                                long roundTokens = roundOutputTokens.getOrDefault(uuid, 0L);
                                long total = roundTokens + streamingTokens;
                                if (total > 0) {
                                    tokensInfo = " · " + total;
                                }
                            }

                            String statsSuffix = " (" + elapsedFromMsg + "s" + tokensInfo + (tokensInfo.isEmpty() ? ")" : " tokens)");

                            // ActionBar: TextComponent.setColor() 直接用 hex
                            TextComponent comp = new TextComponent(BREATHING_SYMBOLS[phaseIdx] + " ");
                            comp.setColor(starColor);
                            TextComponent wordComp = new TextComponent(typewriterWord + suffix);
                            wordComp.setColor(WORD_COLOR_BUNGEE);
                            comp.addExtra(wordComp);
                            TextComponent statsComp = new TextComponent(statsSuffix);
                            statsComp.setColor(STATS_COLOR);
                            comp.addExtra(statsComp);

                            // Subtitle: ChatColor.of(hex) + 字符串，其 toString() 产出 §x§R§G§B 格式，sendTitle 无 bug
                            String subtitleMsg = starColor + BREATHING_SYMBOLS[phaseIdx] + " " + WORD_COLOR_BUNGEE + typewriterWord + suffix
                                + STATS_COLOR + statsSuffix;

                            sendStatusMessage(player, comp, subtitleMsg);
                            break;
                        case EXECUTING_TOOL:
                            message = ChatColor.GRAY + "....";
                            sendStatusMessage(player, message);
                            // 串行批量超时兜底：批次中没有任何工具反馈推进超过 BATCH_TIMEOUT_MS 时强制终结。
                            // 基准为 session.batchLastFeedbackTime（setBatchInProgress 启动 / addPendingToolResult 推进），
                            // 而非 generationStartTimes，避免单工具异步慢（webfetch/mcp 网络延迟）被误杀。
                            DialogueSession batchSession = sessions.get(uuid);
                            if (batchSession != null && batchSession.isBatchInProgress()) {
                                long batchIdle = System.currentTimeMillis() - batchSession.getBatchLastFeedbackTime();
                                if (batchIdle > BATCH_TIMEOUT_MS) {
                                    plugin.getLogger().warning("[CLI] 批次工具执行超时 (" + BATCH_TIMEOUT_MS + "ms 无反馈)，强制终结批次: " + player.getName());
                                    forceFinalizeBatch(player, batchSession);
                                }
                            }
                            break;
                        case WAITING_CONFIRM:
                            message = I18n.t("clim.status.ask.permission");
                            sendStatusMessage(player, message);
                            break;
                        case WAITING_CHOICE:
                            message = I18n.t("clim.status.ask.opinion");
                            sendStatusMessage(player, message);
                            break;
                        case COMPLETED:
                            message = ChatColor.GREEN + "- ✓ -";
                            sendStatusMessage(player, message);
                            // 清除显示，2秒后清除 (40 ticks)
                            if (plugin.isEnabled()) {
                                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                    clearStatusMessage(player);
                                }, 40L);
                            }
                            generationStates.put(uuid, GenerationStatus.IDLE);
                            generationStartTimes.remove(uuid);
                            break;
                        case CANCELLED:
                            message = ChatColor.RED + "- ✕ -";
                            sendStatusMessage(player, message);
                            // 清除显示，2秒后清除 (40 ticks)
                            if (plugin.isEnabled()) {
                                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                    clearStatusMessage(player);
                                }, 40L);
                            }
                            generationStates.put(uuid, GenerationStatus.IDLE);
                            generationStartTimes.remove(uuid);
                            break;
                        case ERROR:
                            message = ChatColor.RED + "- ERROR -";
                            sendStatusMessage(player, message);
                            // 清除显示，2秒后清除 (40 ticks)
                            if (plugin.isEnabled()) {
                                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                                    clearStatusMessage(player);
                                }, 40L);
                            }
                            generationStates.put(uuid, GenerationStatus.IDLE);
                            generationStartTimes.remove(uuid);
                            break;
                        default:
                            break;
                    }
                }
            }
        }.runTaskTimer(plugin, 2L, 2L); // 提高更新频率到 0.1s
    }

    /**
     * 停止当前的思考计时并记录时长
     */
    private void recordThinkingTime(UUID uuid) {
        DialogueSession session = sessions.get(uuid);
        if (session == null) return;

        Long startTime = generationStartTimes.get(uuid);
        GenerationStatus status = generationStates.get(uuid);

        if (startTime != null && status == GenerationStatus.THINKING) {
            long elapsed = System.currentTimeMillis() - startTime;
            session.addThinkingTime(elapsed);
            plugin.getStatsManager().addThinkingTime(elapsed);
        }
    }

    /**
     * 向玩家发送状态消息（根据玩家配置选择 Actionbar 或 Subtitle）
     */
    private void sendStatusMessage(Player player, String message) {
        String position = plugin.getConfigManager().getPlayerDisplayPosition(player);
        if ("subtitle".equalsIgnoreCase(position)) {
            player.sendTitle("", message, 0, 20, 0);
        } else {
            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new TextComponent(message));
        }
    }

    /**
     * 发送含 hex 颜色的状态消息。
     * ActionBar 路径使用已设置好颜色的 TextComponent（避 SPIGOT-5851 §x 解析 bug），
     * Subtitle 路径使用 §x 格式字符串（sendTitle 无此 bug）。
     */
    private void sendStatusMessage(Player player, TextComponent actionBarComp, String subtitleMsg) {
        String position = plugin.getConfigManager().getPlayerDisplayPosition(player);
        if ("subtitle".equalsIgnoreCase(position)) {
            player.sendTitle("", subtitleMsg, 0, 20, 0);
        } else {
            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, actionBarComp);
        }
    }

    /**
     * 清除玩家的状态消息显示
     */
    private void clearStatusMessage(Player player) {
        String position = plugin.getConfigManager().getPlayerDisplayPosition(player);
        if ("subtitle".equalsIgnoreCase(position)) {
            player.sendTitle("", "", 0, 0, 0);
        } else {
            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new TextComponent(""));
        }
    }

    /**
     * 从错误消息中提取 HTTP 状态码
     */
    private String extractStatusCode(String errorMessage) {
        if (errorMessage == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{3})").matcher(errorMessage);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 构建错误消息组件 §zFancyHelper§b§r §7> §f{context}（状态码 xxx）
     */
    private TextComponent buildErrorText(String errorMessage, String defaultContext) {
        TextComponent msg = new TextComponent(TextComponent.fromLegacyText(ColorUtil.translateCustomColors("§zFancyHelper§b§r §7> §f")));

        // Match both "§zFancyHelper§b§r §7> §f" and "§zFancyConsole§b§r §7> §c" etc prefixes
        if (errorMessage != null && errorMessage.startsWith("§zFancyHelper§b§r §7>")) {
            msg.addExtra(new TextComponent(TextComponent.fromLegacyText(ColorUtil.translateCustomColors(errorMessage.substring("§zFancyHelper§b§r §7>".length())))));
            return msg;
        }
        if (errorMessage != null && errorMessage.startsWith("§zFancyConsole§b§r §7>")) {
            msg.addExtra(new TextComponent(TextComponent.fromLegacyText(ColorUtil.translateCustomColors(errorMessage.substring("§zFancyConsole§b§r §7>".length())))));
            return msg;
        }

        String statusCode = extractStatusCode(errorMessage);
        if (statusCode != null) {
            msg.addExtra(new TextComponent(I18n.t("clim.error.api.status", statusCode)));
        } else if (defaultContext != null) {
            msg.addExtra(new TextComponent(defaultContext));
        } else {
            msg.addExtra(new TextComponent(I18n.t("clim.error.api")));
        }
        return msg;
    }

    /**
     * 构建 🔄 重试按钮，hover 显示详细错误信息
     */
    private TextComponent buildRetryButton(String hoverDetail) {
        TextComponent icon = new TextComponent(ChatColor.GREEN + "🔄");
        icon.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli retry"));
        if (hoverDetail != null && !hoverDetail.isEmpty()) {
            icon.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new Text(ChatColor.GRAY + hoverDetail)));
        }
        TextComponent btn = new TextComponent(ChatColor.WHITE + "(");
        btn.addExtra(icon);
        btn.addExtra(new TextComponent(ChatColor.WHITE + ")"));
        return btn;
    }

    /**
     * 检查玩家是否有预加载的会话
     * @param uuid 玩家UUID
     * @return 是否有预加载的会话
     */
    public boolean hasPreloadedSession(UUID playerUUID) {
        // 已经有活跃会话
        if (sessions.containsKey(playerUUID)) return true;

        // 检查 sessions/ 下是否有 30 分钟内的会话文件
        Player player = Bukkit.getPlayer(playerUUID);
        if (player == null) return false;

        try {
            Path playerDir = plugin.getDataFolder().toPath().resolve(SESSIONS_DIR).resolve(player.getName());
            if (!Files.exists(playerDir)) return false;

            long now = System.currentTimeMillis();
            long thirtyMinutesMs = 30 * 60 * 1000;

            // 只取最新的会话文件，与 loadLatestPlayerSession 判定一致：
            // 若最新文件是显式退出的（退出 CLI 后重载），则不应自动恢复。
            Path latestFile = null;
            long latestTime = 0;

            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(playerDir, "*.json")) {
                for (Path file : stream) {
                    long lastMod = Files.getLastModifiedTime(file).toMillis();
                    if (now - lastMod <= thirtyMinutesMs && lastMod > latestTime) {
                        latestTime = lastMod;
                        latestFile = file;
                    }
                }
            }

            if (latestFile == null) return false;

            // 检查最新会话是否显式退出
            try {
                Gson gson = new Gson();
                String json = Files.readString(latestFile, StandardCharsets.UTF_8);
                JsonObject obj = gson.fromJson(json, JsonObject.class);
                if (obj != null && obj.has("explicitExit") && obj.get("explicitExit").getAsBoolean()) {
                    return false;
                }
            } catch (Exception e) {
                // 解析失败就当正常文件处理
            }
            return true;
        } catch (IOException e) {
            // 忽略
        }
        return false;
    }

    /**
     * 从持久化存储加载玩家最近的会话
     * @param player 玩家
     * @return 恢复的对话会话，如果没有有效会话则返回 null
     */
    private DialogueSession loadLatestPlayerSession(Player player) {
        try {
            Path playerDir = plugin.getDataFolder().toPath().resolve(SESSIONS_DIR).resolve(player.getName());
            if (!Files.exists(playerDir)) return null;

            long now = System.currentTimeMillis();
            long thirtyMinutesMs = 30 * 60 * 1000;

            // 找最新的会话文件
            Path latestFile = null;
            long latestTime = 0;

            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(playerDir, "*.json")) {
                for (Path file : stream) {
                    long lastMod = Files.getLastModifiedTime(file).toMillis();
                    if (now - lastMod <= thirtyMinutesMs && lastMod > latestTime) {
                        latestTime = lastMod;
                        latestFile = file;
                    }
                }
            }

            if (latestFile == null) return null;

            // 使用 SessionRecord 加载
            Gson gson = new Gson();
            String json = Files.readString(latestFile, StandardCharsets.UTF_8);
            SessionRecord record = gson.fromJson(json, SessionRecord.class);
            if (record == null) return null;

            // 如果是显式退出的会话，不自动恢复
            if (record.isExplicitExit()) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[CLI] 跳过显式退出的会话: " + latestFile.getFileName());
                }
                return null;
            }

            DialogueSession session = record.toSession();

            // 恢复标题到 generatedTitles
            String savedTitle = record.getTitle();
            if (savedTitle != null && !savedTitle.isEmpty()) {
                generatedTitles.put(session.getSessionUUID(), savedTitle);
            } else {
                // 无标题的恢复会话，异步生成标题
                generateSessionTitle(player.getUniqueId(), session);
            }

            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[CLI] 已从持久化存储恢复会话: " + latestFile.getFileName());
            }

            return session;
        } catch (IOException e) {
            plugin.getLogger().warning("[CLI] 加载最新会话失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 切换玩家的 CLI 模式
     */
    public void toggleCLI(Player player) {
        UUID uuid = player.getUniqueId();
        if (activeCLIPayers.contains(uuid) || pendingAgreementPlayers.contains(uuid)) {
            exitCLI(player);
        } else {
            enterCLI(player);
        }
    }



    /**
     * 保存会话到持久化历史（新格式）
     * @param playerUUID 玩家UUID（用于获取玩家名）
     * @param session 对话会话
     */
    public synchronized void saveSessionToHistory(UUID playerUUID, DialogueSession session) {
        try {
            // 获取玩家名：优先在线玩家，离线时用 enterCLI 记录的缓存（断线/退出场景
            // 异步任务执行时玩家已离线，getPlayer 返回 null，直接跳过会丢会话）。
            Player player = Bukkit.getPlayer(playerUUID);
            String playerName = (player != null) ? player.getName() : playerNameCache.get(playerUUID);
            if (playerName == null) {
                plugin.getLogger().warning("[CLI] 无法获取玩家信息，跳过保存会话历史");
                return;
            }

            // 创建 sessions/玩家名 目录
            Path playerDir = plugin.getDataFolder().toPath().resolve(SESSIONS_DIR).resolve(playerName);
            Files.createDirectories(playerDir);

            // 检查会话数量限制，超过时删除最旧的
            List<Path> existingFiles = new ArrayList<>();
            Files.list(playerDir)
                .filter(path -> path.toString().endsWith(".json"))
                .forEach(existingFiles::add);

            if (existingFiles.size() >= MAX_SESSIONS_PER_PLAYER) {
                // 按最后修改时间排序（最旧的在前）
                existingFiles.sort((a, b) -> {
                    try {
                        return Long.compare(
                            Files.getLastModifiedTime(a).toMillis(),
                            Files.getLastModifiedTime(b).toMillis()
                        );
                    } catch (IOException e) {
                        return 0;
                    }
                });

                // 删除最旧的文件，直到有空间
                int filesToDelete = existingFiles.size() - MAX_SESSIONS_PER_PLAYER + 1;
                for (int i = 0; i < filesToDelete && i < existingFiles.size(); i++) {
                    try {
                        Files.delete(existingFiles.get(i));
                        if (plugin.getConfigManager().isDebug()) {
                            plugin.getLogger().info("[CLI] 已删除旧会话文件（超出限制）: " + existingFiles.get(i).getFileName());
                        }
                    } catch (IOException e) {
                        plugin.getLogger().warning("[CLI] 删除旧会话文件失败: " + e.getMessage());
                    }
                }
            }

            // 获取或生成 sessionUUID
            String sessionUUID = session.getSessionUUID();
            if (sessionUUID == null) {
                sessionUUID = UUID.randomUUID().toString();
                session.setSessionUUID(sessionUUID);
            }

            // 会话文件路径
            Path sessionFile = playerDir.resolve(sessionUUID + ".json");
            Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();

            // 创建新的会话记录
            SessionRecord newRecord = SessionRecord.fromSession(session, sessionUUID);

            // 如果有生成的标题，设置到记录中
            String generatedTitle = generatedTitles.get(sessionUUID);
            if (generatedTitle != null && !generatedTitle.isEmpty()) {
                newRecord.setTitle(generatedTitle);
            }

            // 写入文件（原子写：先写临时文件再替换，避免中断留下半截 JSON 导致后续扫描报错）
            String json = gson.toJson(newRecord);
            atomicWriteSessionFile(sessionFile, json);

            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[CLI] 已保存会话到历史: " + playerName + "/" + sessionUUID + ".json");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[CLI] 保存会话历史失败: " + e.getMessage());
            plugin.getCloudErrorReport().report(e);
        }
    }

    /**
     * 原子写入会话文件：先写同目录临时文件再原子替换。
     * 避免写入中途（进程被杀/插件重载/崩溃）留下半截 JSON——损坏文件会让每次
     * /cli resume 扫描列表时报"读取会话文件失败"，且因解析失败无法从列表删除（隐身坏文件）。
     * 同目录临时文件保证同文件系统，ATOMIC_MOVE 可行；失败时回退普通替换。
     */
    private void atomicWriteSessionFile(Path sessionFile, String json) {
        try {
            Files.createDirectories(sessionFile.getParent());
            Path tmp = sessionFile.resolveSibling(sessionFile.getFileName() + ".tmp");
            Files.write(tmp, json.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, sessionFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, sessionFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("[CLI] 会话文件原子写入失败: " + e.getMessage());
        }
    }

    /**
     * 获取玩家的历史会话列表
     * @param playerUUID 玩家UUID（用于获取玩家名）
     * @return 会话记录列表（按时间戳降序）
     */
    public List<SessionRecord> getSessionHistory(UUID playerUUID) {
        try {
            // 获取玩家名
            Player player = Bukkit.getPlayer(playerUUID);
            if (player == null) {
                return new ArrayList<>();
            }
            String playerName = player.getName();

            Path playerDir = plugin.getDataFolder().toPath().resolve(SESSIONS_DIR).resolve(playerName);
            if (!Files.exists(playerDir)) {
                return new ArrayList<>();
            }

            List<SessionRecord> records = new ArrayList<>();
            Gson gson = new Gson();

            // 读取目录下所有 JSON 文件
            Files.list(playerDir)
                .filter(path -> path.toString().endsWith(".json"))
                .forEach(path -> {
                    try {
                        String json = Files.readString(path, StandardCharsets.UTF_8);
                        SessionRecord record = gson.fromJson(json, SessionRecord.class);
                        if (record != null) {
                            records.add(record);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("[CLI] 读取会话文件失败: " + path.getFileName());
                    }
                });

            // 按时间戳排序（新的在前）
            records.sort((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
            return records;
        } catch (Exception e) {
            plugin.getLogger().warning("[CLI] 读取会话历史失败: " + e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 请求时附加的动态尾部标记，与 LLMClient.DYNAMIC_TAIL_MARKER 保持一致 */
    private static final String DYNAMIC_TAIL_MARKER = "[System Info]";

    /** 剥离消息尾部附加的动态信息块（[System Info] 起至结尾），用于取原始用户输入 */
    private static String stripDynamicTail(String content) {
        if (content == null) return null;
        int idx = content.lastIndexOf(DYNAMIC_TAIL_MARKER);
        if (idx < 0) return content;
        String stripped = content.substring(0, idx);
        // 去掉尾部追加前留下的空行
        return stripped.endsWith("\n\n") ? stripped.substring(0, stripped.length() - 2) : stripped;
    }

    /**
     * 异步生成会话标题
     * @param playerUUID 玩家UUID
     * @param session 对话会话
     */
    public void generateSessionTitle(UUID playerUUID, DialogueSession session) {
        generateSessionTitle(playerUUID, session, false, false);
    }

    public void generateSessionTitle(UUID playerUUID, DialogueSession session, boolean force, boolean useAllMessages) {
        String sessionUUID = session.getSessionUUID();
        if (sessionUUID == null) {
            plugin.getLogger().warning("[CLI] generateSessionTitle: sessionUUID is null");
            return;
        }
        if (!force) {
            if (generatedTitles.containsKey(sessionUUID)) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[CLI] generateSessionTitle: 标题已生成，跳过: " + sessionUUID);
                }
                return;
            }
        } else {
            // 强制模式：如果正在生成中则跳过，避免并发
            String current = generatedTitles.get(sessionUUID);
            if ("".equals(current)) {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[CLI] generateSessionTitle: 标题正在生成中，跳过: " + sessionUUID);
                }
                return;
            }
        }

        // 标记为正在生成（使用空字符串作为占位符，ConcurrentHashMap不允许null）
        generatedTitles.put(sessionUUID, "");

        // 收集用户消息（剥离请求时附加的 [System Info] 动态尾部，避免噪声进入标题生成）
        String textToSummarize;
        if (useAllMessages) {
            StringBuilder sb = new StringBuilder();
            for (DialogueSession.Message msg : session.getHistory()) {
                if ("user".equals(msg.getRole())) {
                    if (sb.length() > 0) sb.append("\n");
                    sb.append(stripDynamicTail(msg.getContent()));
                }
            }
            textToSummarize = sb.toString();
            if (textToSummarize.isEmpty()) {
                plugin.getLogger().warning("[CLI] generateSessionTitle: 未找到用户消息");
                return;
            }
        } else {
            // 只取第一条用户消息（原有逻辑）
            String firstMessage = null;
            for (DialogueSession.Message msg : session.getHistory()) {
                if ("user".equals(msg.getRole())) {
                    firstMessage = msg.getContent();
                    break;
                }
            }
            if (firstMessage == null || firstMessage.isEmpty()) {
                plugin.getLogger().warning("[CLI] generateSessionTitle: firstMessage is null or empty");
                return;
            }
            textToSummarize = stripDynamicTail(firstMessage);
        }

        final String messageToSummarize = textToSummarize;

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[CLI] 正在异步生成会话标题，会话: " + sessionUUID + "，消息: " + messageToSummarize.substring(0, Math.min(20, messageToSummarize.length())) + "...");
        }

        // 异步调用小模型生成标题
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[CLI] 开始调用 AI 生成标题...");
                }
                String title = ai.generateTitle(messageToSummarize);
                if (title != null && !title.isEmpty()) {
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info("[CLI] AI 生成标题成功: " + title);
                    }
                    // 存储标题
                    generatedTitles.put(sessionUUID, title);
                    // 直接更新文件中的标题（不检查session是否还在map中）
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        updateSessionTitle(playerUUID, sessionUUID, title);
                    });
                } else if ("".equals(title)) {
                    // API 请求成功但模型没有按要求输出 JSON — 提醒用户（仅一次）
                    plugin.getLogger().warning("[CLI] 标题生成失败：模型未能正确输出 JSON");
                    if (!weakModelWarned.containsKey(sessionUUID)) {
                        weakModelWarned.put(sessionUUID, true);
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Player player = Bukkit.getPlayer(playerUUID);
                            if (player != null && player.isOnline()) {
                                player.sendMessage(I18n.t("clim.weak.model"));
                            }
                        });
                    }
                } else {
                    // title 为 null，全是网络/配置异常，不提示用户
                    plugin.getLogger().warning("[CLI] 标题生成失败：网络或配置错误导致无法连接到 AI 服务");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("[CLI] 生成会话标题失败: " + e.getMessage());
            }
        });
    }

    /**
     * 更新会话标题
     */
    private synchronized void updateSessionTitle(UUID playerUUID, String sessionUUID, String title) {
        try {
            // 获取玩家名
            Player player = Bukkit.getPlayer(playerUUID);
            if (player == null) {
                // 玩家离线，跳过
                return;
            }
            String playerName = player.getName();

            Path playerDir = plugin.getDataFolder().toPath().resolve(SESSIONS_DIR).resolve(playerName);
            Path sessionFile = playerDir.resolve(sessionUUID + ".json");

            SessionRecord record;
            if (Files.exists(sessionFile)) {
                // 文件已存在，读取并更新
                Gson gson = new Gson();
                String json = Files.readString(sessionFile, StandardCharsets.UTF_8);
                record = gson.fromJson(json, SessionRecord.class);
                if (record == null) {
                    return;
                }
                record.setTitle(title);
            } else {
                // 文件不存在，可能会话还没有保存，跳过
                // （会在 exitCLI 时保存，届时标题会丢失，但这是可以接受的）
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[CLI] 会话文件不存在，跳过标题更新: " + sessionUUID);
                }
                return;
            }

            // 写回文件（使用美化输出，原子写）
            Gson prettyGson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
            String json = prettyGson.toJson(record);
            Files.createDirectories(playerDir);
            atomicWriteSessionFile(sessionFile, json);

            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[CLI] 已更新会话标题: " + title);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[CLI] 更新会话标题失败: " + e.getMessage());
        }
    }

    /**
     * 恢复历史会话
     * @param player 玩家
     * @param sessionUUID 会话UUID
     */
    public void resumeSession(Player player, String sessionUUID) {
        UUID playerUUID = player.getUniqueId();
        String playerName = player.getName();

        try {
            // 读取会话文件
            Path sessionFile = plugin.getDataFolder().toPath().resolve(SESSIONS_DIR).resolve(playerName).resolve(sessionUUID + ".json");
            if (!Files.exists(sessionFile)) {
                player.sendMessage(I18n.t("clim.resume.not.found"));
                return;
            }

            Gson gson = new Gson();
            String json = Files.readString(sessionFile, StandardCharsets.UTF_8);
            SessionRecord record = gson.fromJson(json, SessionRecord.class);

            if (record == null) {
                player.sendMessage(I18n.t("clim.resume.corrupt"));
                return;
            }

            // 重建会话
            DialogueSession restoredSession = record.toSession();

            // 如果玩家已在 CLI 中，先退出
            if (activeCLIPayers.contains(playerUUID)) {
                exitCLI(player);
            }

            // 设置恢复的会话
            sessions.put(playerUUID, restoredSession);
            activeCLIPayers.add(playerUUID);

            // 重建日志文件
            try {
                Path logDir = plugin.getDataFolder().toPath().resolve("logs");
                Files.createDirectories(logDir);
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
                String logFileName = "resume_" + sessionUUID.substring(0, 8) + "_" + timestamp + ".log";
                Path logFilePath = logDir.resolve(logFileName);
                restoredSession.setLogFilePath(logFilePath.toString());
                restoredSession.setVerboseLogging(plugin.getConfigManager().isDebug());
                restoredSession.initLogFile(player.getName());
            } catch (IOException e) {
                plugin.getLogger().warning("[CLI] 创建日志文件失败: " + e.getMessage());
            }

            // 显示恢复消息
            player.sendMessage("");
            player.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "─────────────────────────────");
            player.sendMessage("");

            TextComponent line = new TextComponent();
            TextComponent bar = new TextComponent("▌ ");
            bar.setColor(net.md_5.bungee.api.ChatColor.DARK_GRAY);
            line.addExtra(bar);

            TextComponent brand = new TextComponent("FancyHelper ");
            brand.setColor(net.md_5.bungee.api.ChatColor.of("#30AEE5"));
            line.addExtra(brand);

            TextComponent divider = new TextComponent("──");
            divider.setColor(net.md_5.bungee.api.ChatColor.DARK_GRAY);
            divider.setStrikethrough(true);
            line.addExtra(divider);

            int msgCount = restoredSession.getHistory().size();
            TextComponent status = new TextComponent(I18n.t("clim.resume.loaded", msgCount));
            status.setColor(net.md_5.bungee.api.ChatColor.GREEN);
            line.addExtra(status);

            player.spigot().sendMessage(line);
            player.sendMessage("");
            player.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "─────────────────────────────");

            // 播放音效
            playFeedbackSound(player, "cli_enter");

            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[CLI] 玩家 " + playerName + " 恢复了会话: " + sessionUUID);
            }
        } catch (Exception e) {
            player.sendMessage(I18n.t("clim.resume.failed", e.getMessage()));
            plugin.getLogger().warning("[CLI] 恢复会话失败: " + e.getMessage());
        }
    }

    /**
     * 删除历史会话
     * @param playerUUID 玩家UUID
     * @param sessionUUID 会话UUID
     */
    public synchronized void deleteSession(UUID playerUUID, String sessionUUID) {
        try {
            // 获取玩家名
            Player player = Bukkit.getPlayer(playerUUID);
            if (player == null) {
                return;
            }
            String playerName = player.getName();

            Path sessionFile = plugin.getDataFolder().toPath().resolve(SESSIONS_DIR).resolve(playerName).resolve(sessionUUID + ".json");
            if (!Files.exists(sessionFile)) {
                return;
            }

            // 删除文件
            Files.delete(sessionFile);

            // 清理标题记录
            generatedTitles.remove(sessionUUID);

            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[CLI] 已删除会话: " + playerName + "/" + sessionUUID + ".json");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("[CLI] 删除会话失败: " + e.getMessage());
        }
    }

    /**
     * 发送插件卸载提示（插件重载/重启时调用）：会话已保存并退出，
     * 提示玩家用 /cli resume <sessionUUID> 手动恢复（整条命令可点击执行）。
     * @param player 玩家
     * @param sessionUUID 当前会话 UUID，用于构造恢复命令；为 null 时退化为通用 /cli resume
     */
    public void sendUnloadMessage(Player player, String sessionUUID) {
        // 创建主消息 - 使用自定义颜色
        net.md_5.bungee.api.chat.BaseComponent[] components = net.md_5.bungee.api.chat.TextComponent.fromLegacyText(
            I18n.t("clim.unload.suspended")
        );
        TextComponent message = new TextComponent(components);

        // 显示 /cli resume <uuid>，点击实际执行 /cli resume_confirm <uuid>（直接恢复该会话）
        String label = (sessionUUID != null) ? I18n.t("clim.unload.resume", sessionUUID) : I18n.t("clim.unload.resume.short");
        String cmd = (sessionUUID != null) ? "/cli resume_confirm " + sessionUUID : "/cli resume";

        // 创建恢复命令（下划线，可点击执行）
        TextComponent resumeCmd = new TextComponent(label);
        resumeCmd.setColor(net.md_5.bungee.api.ChatColor.WHITE);
        resumeCmd.setUnderlined(true);
        resumeCmd.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
        resumeCmd.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.unload.resume.hover", cmd))));

        message.addExtra(resumeCmd);
        message.addExtra(new TextComponent(I18n.t("clim.unload.resume.suffix")));
        player.spigot().sendMessage(message);
    }

    /**
     * 获取所有会话
     * @return 会话映射
     */
    public Map<UUID, DialogueSession> getSessions() {
        return sessions;
    }

    /**
     * 关闭管理器，清理资源
     */
    public void shutdown() {
        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[CLI] 正在关闭 CLIManager...");
        }
        
        // 移除所有活跃的CLI玩家
        for (UUID uuid : new ArrayList<>(activeCLIPayers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                DialogueSession currentSession = sessions.get(uuid);
                sendUnloadMessage(player, currentSession != null ? currentSession.getSessionUUID() : null);
            }
            DialogueSession session = sessions.get(uuid);
            if (session != null && session.getHistory().size() > 0) {
                // 保存到持久化存储（用于历史对话列表和重启恢复）
                saveSessionToHistory(uuid, session);
            }
            sessions.remove(uuid);
        }
        activeCLIPayers.clear();
        pendingAgreementPlayers.clear();
        sessions.clear();
        isGenerating.clear();
        pendingCommands.clear();
        generationStates.clear();
        generationStartTimes.clear();

        // 取消所有活跃的流式输出，防止资源泄漏
        if (!activeStreamingHandlers.isEmpty()) {
            plugin.getLogger().info("[CLI] 正在取消 " + activeStreamingHandlers.size() + " 个活跃的流式输出...");
            for (Map.Entry<UUID, StreamingHandler> entry : activeStreamingHandlers.entrySet()) {
                StreamingHandler handler = entry.getValue();
                if (handler != null && !handler.isCancelled()) {
                    handler.cancel();
                }
            }
            activeStreamingHandlers.clear();
        }

        // 关闭AI客户端（这会处理OkHttp的cleanup）
        if (ai != null) {
            ai.shutdown();
        }

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[CLI] CLIManager 已完成关闭。");
        }
    }

    public void enterCLI(Player player) {
        enterCLI(player, false);
    }

    /**
     * 进入 CLI 模式
     * @param autoResume 是否为服务器重启后的自动恢复，为 true 时才从磁盘加载会话
     */
    public void enterCLI(Player player, boolean autoResume) {
        UUID uuid = player.getUniqueId();

        // 已在 CLI 模式中，避免重复进入（启动时双入口可能触发）
        if (activeCLIPayers.contains(uuid)) {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[CLI] 玩家 " + player.getName() + " 已在 CLI 模式，跳过重复进入。");
            }
            return;
        }

        // 检查 EULA 文件状态
        if (!plugin.getEulaManager().isEulaValid()) {
            player.sendMessage(I18n.t("clim.eula.invalid"));
            plugin.getLogger().warning("[CLI] 由于 EULA 文件无效，拒绝了 " + player.getName() + " 的访问。");
            return;
        }

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[CLI] 玩家 " + player.getName() + " 正在进入 FancyHelper。");
        }
        // 记录玩家名供离线保存会话使用（断线/退出时 Bukkit.getPlayer 可能返回 null）
        playerNameCache.put(uuid, player.getName());
        
        // 检查用户协议
        if (!agreedPlayers.contains(uuid)) {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[CLI] 玩家 " + player.getName() + " 需要同意协议。");
            }
            sendAgreement(player);
            pendingAgreementPlayers.add(uuid);
            return;
        }

        // 检查是否已经有预加载的会话（插件重启恢复）
        DialogueSession session = sessions.get(uuid);

        // 如果没有活跃会话，仅在自动恢复时尝试从持久化存储加载
        if (session == null && autoResume) {
            session = loadLatestPlayerSession(player);
        }

        // 如果仍然没有会话，创建新会话
        if (session == null) {
            session = new DialogueSession();
            // 恢复上次的模式
            if (yoloModePlayers.contains(uuid)) {
                session.setMode(DialogueSession.Mode.YOLO);
            } else if (smartModePlayers.contains(uuid)) {
                session.setMode(DialogueSession.Mode.SMART);
            } else if (planModePlayers.contains(uuid)) {
                session.setMode(DialogueSession.Mode.PLAN);
            }

            // 为新会话分配 UUID
            String sessionUUID = UUID.randomUUID().toString();
            session.setSessionUUID(sessionUUID);

            // 先将会话放入 Map，确保后续操作能获取到正确的模式
            sessions.put(uuid, session);

            // 创建日志文件
            try {
                Path logDir = plugin.getDataFolder().toPath().resolve("logs");
                Files.createDirectories(logDir);
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
                String logFileName = timestamp + ".log";
                Path logFilePath = logDir.resolve(logFileName);
                session.setLogFilePath(logFilePath.toString());
                // 根据调试模式设置详细日志级别
                session.setVerboseLogging(plugin.getConfigManager().isDebug());
                session.initLogFile(player.getName());
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[CLI] 创建日志文件: " + logFileName);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("[CLI] 创建日志文件失败: " + e.getMessage());
            }

            // 发送进入消息和问候
            sendEnterMessage(player);
            triggerGreeting(player);
        } else {
            // 有预加载的会话（插件重启恢复），静默进入CLI模式
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[CLI] 玩家 " + player.getName() + " 静默进入CLI模式，会话已恢复");
            }
            // 恢复的会话也需要创建新的日志文件，以便记录恢复后的对话
            try {
                Path logDir = plugin.getDataFolder().toPath().resolve("logs");
                Files.createDirectories(logDir);
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
                String logFileName = timestamp + ".log";
                Path logFilePath = logDir.resolve(logFileName);
                session.setLogFilePath(logFilePath.toString());
                session.setVerboseLogging(plugin.getConfigManager().isDebug());
                session.initLogFile(player.getName());
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[CLI] 为恢复会话创建日志文件: " + logFileName);
                }
            } catch (IOException e) {
                plugin.getLogger().warning("[CLI] 创建日志文件失败: " + e.getMessage());
            }
            // 显示恢复提示
            player.sendMessage(I18n.t("clim.resume.ready"));
        }

        activeCLIPayers.add(uuid);
        plugin.getStatsManager().incrementCliEntry();
        // 注意：新会话已经在上面放入 Map，这里只处理恢复会话的情况
        if (session != null) {
            sessions.put(uuid, session);
        }

        // 进 CLI 1s 后展示公告（每次上线仅首次进入 CLI 时显示一次）
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!plugin.isEnabled() || !player.isOnline()) return;
            if (plugin.getNoticeManager().hasBeenNotified(player)) return;
            // 已手动标为已读的也不再自动展示，除非主动 /fancyhelper notice
            if (plugin.getNoticeManager().hasRead(player)) return;

            plugin.getNoticeManager().markNotified(player);

            NoticeManager.NoticeData cachedNotice = plugin.getNoticeManager().getCurrentNotice();
            if (cachedNotice != null) {
                plugin.getNoticeManager().showNoticeToPlayer(player, cachedNotice);
            } else {
                plugin.getNoticeManager().fetchNoticeAsync().thenAccept(noticeData -> {
                    if (noticeData != null && player.isOnline()) {
                        plugin.getNoticeManager().showNoticeToPlayer(player, noticeData);
                    }
                });
            }
        }, 1 * 20L); // 1s = 1 * 20 ticks

        playFeedbackSound(player, "cli_enter");
    }

    /**
     * 触发硬编码的初始问候语（根据时间、随机短语生成）
     * 
     * @param player 接收问候的玩家
     */
    private void triggerGreeting(Player player) {
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);
        if (session == null) return;

        // 注意：此处不能设置 isGenerating=true。问候语是 0.3s 后延迟展示的，
        // 若进入 CLI 时置 true，玩家紧接着发消息会被"请不要在生成内容时发送消息"误拦
        // （0.3s 窗口竞态，实测进入后立即输入必中招）。问候语只是插件自身展示+写历史，
        // 不需要拦截玩家输入。

        // 进入 CLI 后 0.3s 延迟展示 (约 6 ticks)
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            // 检查玩家是否仍在线且在 CLI 模式中
            if (!plugin.isEnabled() || !activeCLIPayers.contains(uuid) || !player.isOnline()) return;

                // 1. 获取基于时间的问候语
                int hour = java.time.LocalDateTime.now().getHour();
                String timeGreeting;
                if (hour >= 5 && hour < 11) {
                    timeGreeting = I18n.t("clim.greet.morning");
                } else if (hour >= 11 && hour < 14) {
                    timeGreeting = I18n.t("clim.greet.noon");
                } else if (hour >= 14 && hour < 18) {
                    timeGreeting = I18n.t("clim.greet.afternoon");
                } else {
                    timeGreeting = I18n.t("clim.greet.evening");
                }

                // 2. 获取随机帮助短语
                String[] helpPhrases = {
                    I18n.t("clim.greet.help.0"),
                    I18n.t("clim.greet.help.1"),
                    I18n.t("clim.greet.help.2"),
                    I18n.t("clim.greet.help.3")
                };
                String randomHelp = helpPhrases[new Random().nextInt(helpPhrases.length)];

                // 3. 构建并发送消息
                // 格式：◆ [问候语]，[自定义亮蓝色玩家名]。[随机短语]
                TextComponent message = new TextComponent(ChatColor.WHITE + "◆ " + timeGreeting + "，");
                
                TextComponent playerName = new TextComponent(player.getName());
                playerName.setColor(net.md_5.bungee.api.ChatColor.of(ColorUtil.getColorZ())); // 自定义亮蓝色
                
                message.addExtra(playerName);
                message.addExtra(new TextComponent(ChatColor.WHITE + "。" + randomHelp));

                player.spigot().sendMessage(message);

                // 4. 将问候语记录到对话历史中，让 AI 知道已经打过招呼了
                String fullGreeting = timeGreeting + "，" + player.getName() + "。" + randomHelp;
                session.addMessage("assistant", fullGreeting);
        }, 6L);
    }

    /**
     * 退出 CLI 模式
     */
    public void exitCLI(Player player) {
        UUID uuid = player.getUniqueId();
        
        if (!activeCLIPayers.contains(uuid)) {
            // 待同意协议的玩家也允许退出：clim.agree.prompt 文案宣称"发送 /cli 退出"，
            // 若不清除待同意状态，玩家将被困在协议提示循环且 /cli 无效。
            if (pendingAgreementPlayers.remove(uuid)) {
                sendExitMessage(player);
            }
            return;
        }
        
        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[CLI] 玩家 " + player.getName() + " 正在退出 FancyHelper。");
        }
        
        // 退出前自动取消待确认的工具调用
        if (pendingCommands.containsKey(uuid)) {
            pendingCommands.remove(uuid);
            player.sendMessage(I18n.t("clim.cancel.pending"));
        }

        // 清空玩家的待办列表
        plugin.getTodoManager().clearTodos(uuid);

        // 清空玩家已加载的 Skill
        plugin.getSkillManager().clearPlayerSkills(player);

        // 清空重试信息
        retryInfoMap.remove(uuid);

        recordThinkingTime(uuid);

        // 退出前刷入尚未写回 session 的流式 token
        DialogueSession exitSession = sessions.get(uuid);
        if (exitSession != null) {
            Long pendingStreamed = streamedOutputTokens.get(uuid);
            if (pendingStreamed != null && pendingStreamed > 0) {
                exitSession.addOutputTokens(pendingStreamed);
                roundOutputTokens.merge(uuid, pendingStreamed, (a, b) -> a + b);
            }
            // 将本次会话的总 token 汇入全局统计
            long exitInput = exitSession.getTotalInputTokens();
            long exitOutput = exitSession.getTotalOutputTokens();
            if (exitInput > 0 || exitOutput > 0) {
                plugin.getStatsManager().addTokens(exitInput, exitOutput);
            }
        }

        sendExitMessage(player);
        playFeedbackSound(player, "cli_exit");

        // 保存会话历史到持久化存储（只有有用户消息时才保存）
        DialogueSession session = sessions.get(uuid);
        if (session != null) {
            boolean hasUserMessage = false;
            for (DialogueSession.Message msg : session.getHistory()) {
                if ("user".equals(msg.getRole())) {
                    hasUserMessage = true;
                    break;
                }
            }
            if (hasUserMessage) {
                DialogueSession captured = session;
                String playerName = player.getName();
                String sessionUUID = session.getSessionUUID();
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    saveSessionToHistory(uuid, captured);
                    // 标记为显式退出，避免插件重载后自动恢复
                    if (sessionUUID != null) {
                        try {
                            Path sessionFile = plugin.getDataFolder().toPath()
                                .resolve(SESSIONS_DIR).resolve(playerName)
                                .resolve(sessionUUID + ".json");
                            if (Files.exists(sessionFile)) {
                                Gson gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
                                JsonObject json = gson.fromJson(Files.newBufferedReader(sessionFile), JsonObject.class);
                                json.addProperty("explicitExit", true);
                                Files.writeString(sessionFile, gson.toJson(json), StandardCharsets.UTF_8);
                            }
                        } catch (Exception e) {
                            // 标记失败不影响主流程
                        }
                    }
                });
            }
        }
        
        // 退出前取消进行中的流式输出：多轮工具调用（技能/搜索/执行）的反馈轮会发起异步
        // 流式请求，若不 cancel，该请求在会话销毁后仍会执行回调并重新 isGenerating.put(true)，
        // 导致下一场会话首条消息被"请不要在生成内容时发送消息"拦截（约 90s 后自愈）。
        // cancel() 置 isCancelled 并清空回调，processStream 走取消分支静默返回，异步尾巴不再执行。
        StreamingHandler exitingHandler = activeStreamingHandlers.get(uuid);
        if (exitingHandler != null) {
            exitingHandler.cancel();
            activeStreamingHandlers.remove(uuid);
        }

        activeCLIPayers.remove(uuid);
        pendingAgreementPlayers.remove(uuid);
        pendingYoloAgreementPlayers.remove(uuid);
        pendingPlanContextClear.remove(uuid);
        pendingPlanStartMode.remove(uuid);
        sessions.remove(uuid);
        isGenerating.remove(uuid);
        generationStates.remove(uuid);
        generationStartTimes.remove(uuid);
        messageReceiveTimes.remove(uuid);
        wordStartTimes.remove(uuid);
        currentThinkingWords.remove(uuid);
        streamedOutputTokens.remove(uuid);
        roundOutputTokens.remove(uuid);
    }

    public void switchMode(Player player, DialogueSession.Mode targetMode) {
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);

        // 如果玩家不在 CLI 模式中，自动进入 CLI
        boolean wasInCLI = session != null;
        if (session == null) {
            ensureInCLI(player);
            session = sessions.get(uuid);
            if (session == null) return;
        }

        if (targetMode == DialogueSession.Mode.PLAN) {
            enterPlanMode(player);
            return;
        }

        // 退出 Plan 模式
        savePlanModeState(uuid, false);

        if (targetMode == DialogueSession.Mode.YOLO) {
            if (!yoloAgreedPlayers.contains(uuid)) {
                sendYoloWarning(player);
                pendingYoloAgreementPlayers.add(uuid);
                return;
            }
            session.setMode(DialogueSession.Mode.YOLO);
            saveYoloModeState(uuid, true);
            saveSmartModeState(uuid, false);
            player.sendMessage(ChatColor.GRAY + "------------------");
            player.sendMessage(I18n.t("clim.mode.yolo"));
        } else if (targetMode == DialogueSession.Mode.SMART) {
            session.setMode(DialogueSession.Mode.SMART);
            saveSmartModeState(uuid, true);
            saveYoloModeState(uuid, false);
            player.sendMessage(ChatColor.GRAY + "------------------");
            player.sendMessage(I18n.t("clim.mode.smart"));
        } else {
            session.setMode(DialogueSession.Mode.NORMAL);
            saveYoloModeState(uuid, false);
            saveSmartModeState(uuid, false);
            player.sendMessage(ChatColor.GRAY + "------------------");
            player.sendMessage(I18n.t("clim.mode.normal"));
        }
        // 模式切换：已在 CLI 中只重绘模式标题行，避免整段头部（含随机提示语）重复刷屏；
        // 首次经此进入 CLI 时仍展示完整头部
        if (wasInCLI) {
            sendModeLine(player);
        } else {
            sendEnterMessage(player);
        }
    }

    private void sendYoloWarning(Player player) {
        player.sendMessage(ChatColor.RED + "===============");
        player.sendMessage(ChatColor.DARK_RED + "WARNING: You only live once");
        player.sendMessage(I18n.t("clim.yolo.warn1"));
        player.sendMessage(I18n.t("clim.yolo.warn2"));
        player.sendMessage(I18n.t("clim.yolo.warn3"));        TextComponent message = new TextComponent(I18n.t("clim.yolo.send"));
        TextComponent agreeBtn = new TextComponent(ChatColor.RED + "agree");
        agreeBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli agree"));
        agreeBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.yolo.agree.hover"))));
        message.addExtra(agreeBtn);
        message.addExtra(new TextComponent(I18n.t("clim.yolo.agree.suffix")));

        player.spigot().sendMessage(message);
        player.sendMessage(ChatColor.RED + "===============");
    }

    /**
     * 确保玩家已进入 CLI 模式（无问候语，无 sendEnterMessage）。
     * 用于 /cli plan/normal/smart/yolo 等命令在未进入 CLI 时自动进入。
     */
    private void ensureInCLI(Player player) {
        UUID uuid = player.getUniqueId();

        // 检查 EULA 文件状态
        if (!plugin.getEulaManager().isEulaValid()) {
            player.sendMessage(I18n.t("clim.eula.invalid"));
            return;
        }

        // 检查用户协议
        if (!agreedPlayers.contains(uuid)) {
            sendAgreement(player);
            pendingAgreementPlayers.add(uuid);
            return;
        }

        // 检查是否已经有会话
        DialogueSession session = sessions.get(uuid);
        if (session != null) {
            // 已有会话，只需加入活跃列表
            activeCLIPayers.add(uuid);
            return;
        }

        // ensureInCLI 不从磁盘自动恢复，直接创建新会话
        session = new DialogueSession();
        // 恢复上次的模式
        if (yoloModePlayers.contains(uuid)) {
            session.setMode(DialogueSession.Mode.YOLO);
        } else if (smartModePlayers.contains(uuid)) {
            session.setMode(DialogueSession.Mode.SMART);
        } else if (planModePlayers.contains(uuid)) {
            session.setMode(DialogueSession.Mode.PLAN);
        }

        sessions.put(uuid, session);

            // 创建日志文件
            try {
                Path logDir = plugin.getDataFolder().toPath().resolve("logs");
                Files.createDirectories(logDir);
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS"));
                String logFileName = timestamp + ".log";
                Path logFilePath = logDir.resolve(logFileName);
                session.setLogFilePath(logFilePath.toString());
                session.setVerboseLogging(plugin.getConfigManager().isDebug());
                session.initLogFile(player.getName());
            } catch (IOException e) {
                plugin.getLogger().warning("[CLI] 创建日志文件失败: " + e.getMessage());
            }

        activeCLIPayers.add(uuid);
    }

    /**
     * 进入 Plan Mode
     */
    public void enterPlanMode(Player player) {
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);

        // 如果玩家不在 CLI 模式中，自动进入 CLI
        boolean wasInCLI = session != null;
        if (session == null) {
            ensureInCLI(player);
            session = sessions.get(uuid);
            if (session == null) return;
        }

        // 如果已经在 Plan Mode，无需重复进入
        if (session.getMode() == DialogueSession.Mode.PLAN) {
            player.sendMessage(I18n.t("clim.plan.already"));
            return;
        }

        // 如果有用户消息（排除系统自动存储的问候语等），询问是否清空上下文
        boolean hasUserMessages = session.getHistory().stream()
                .anyMatch(msg -> "user".equals(msg.getRole()));
        if (hasUserMessages) {
            pendingPlanContextClear.add(uuid);
            player.sendMessage(ChatColor.DARK_GRAY + "─────────────────────────────");
            player.sendMessage(I18n.t("clim.plan.clear.ask"));

            TextComponent yBtn = new TextComponent(I18n.t("clim.plan.clear.y"));
            yBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli plan_clear_y"));
            yBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.plan.clear.y.hover"))));

            TextComponent spacer = new TextComponent("  ");

            TextComponent nBtn = new TextComponent(I18n.t("clim.plan.clear.n"));
            nBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli plan_clear_n"));
            nBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.plan.clear.n.hover"))));

            TextComponent message = new TextComponent("");
            message.addExtra(yBtn);
            message.addExtra(spacer);
            message.addExtra(nBtn);

            player.spigot().sendMessage(message);
            player.sendMessage(ChatColor.DARK_GRAY + "─────────────────────────────");
            return;
        }

        // fullHeader 与 wasInCLI 语义相反：已在 CLI 中只重绘模式标题行，首次经此进入才显示完整头部
        activatePlanMode(player, !wasInCLI);
    }

    /**
     * 激活 Plan Mode（清空上下文后直接进入）
     * @param fullHeader true 时展示完整进入头部（首次经此进入 CLI），false 仅重绘模式标题行
     */
    private void activatePlanMode(Player player, boolean fullHeader) {
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);
        if (session == null) return;

        session.setMode(DialogueSession.Mode.PLAN);
        savePlanModeState(uuid, true);
        saveYoloModeState(uuid, false);
        saveSmartModeState(uuid, false);

        if (fullHeader) {
            sendEnterMessage(player);
        } else {
            sendModeLine(player);
        }
        player.sendMessage(I18n.t("clim.plan.entered"));

        // 将进入 Plan Mode 的记录存入上下文，等待用户提问，不触发 API 调用
        session.addMessage("assistant", "[系统] 已进入 Plan Mode。等待用户提出问题后进行规划。");
    }

    /**
     * 处理 Plan Mode 上下文清空确认 (Y)
     */
    public void handlePlanClearY(Player player) {
        UUID uuid = player.getUniqueId();
        if (!pendingPlanContextClear.contains(uuid)) return;
        pendingPlanContextClear.remove(uuid);

        DialogueSession session = sessions.get(uuid);
        if (session != null) {
            session.clearHistory();
        }
        activatePlanMode(player, false);
    }

    /**
     * 处理 Plan Mode 上下文清空确认 (N)
     */
    public void handlePlanClearN(Player player) {
        UUID uuid = player.getUniqueId();
        if (!pendingPlanContextClear.contains(uuid)) return;
        pendingPlanContextClear.remove(uuid);

        // 此时玩家已在 CLI 中（有历史才会询问），仅重绘模式标题行
        activatePlanMode(player, false);
    }

    /**
     * 处理 Plan Mode 的 #start 工具：显示执行模式选择 UI
     */
    public void handlePlanStart(Player player) {
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);
        if (session == null || session.getMode() != DialogueSession.Mode.PLAN) return;

        pendingPlanStartMode.add(uuid);
        setGenerating(uuid, false, GenerationStatus.WAITING_CHOICE);

        player.sendMessage(ChatColor.DARK_GRAY + "─────────────────────────────");
        player.sendMessage(I18n.t("clim.plan.done"));
        player.sendMessage(ChatColor.DARK_GRAY + "─────────────────────────────");
        player.sendMessage(I18n.t("clim.plan.how"));

        // Normal 模式
        TextComponent normalBtn = new TextComponent(ChatColor.GREEN + " » Normal ");
        normalBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli plan_start normal"));
        normalBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.plan.mode.normal"))));
        player.spigot().sendMessage(normalBtn);
        player.sendMessage(I18n.t("clim.plan.mode.normal"));

        // Smart 模式
        TextComponent smartBtn = new TextComponent(ChatColor.BLUE + " » Smart ");
        smartBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli plan_start smart"));
        smartBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.plan.mode.smart"))));
        player.spigot().sendMessage(smartBtn);
        player.sendMessage(I18n.t("clim.plan.mode.smart"));

        // Yolo 模式
        TextComponent yoloBtn = new TextComponent(ChatColor.RED + " » Yolo ");
        yoloBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli plan_start yolo"));
        yoloBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.plan.mode.yolo"))));
        player.spigot().sendMessage(yoloBtn);
        player.sendMessage(I18n.t("clim.plan.mode.yolo"));

        player.sendMessage(ChatColor.DARK_GRAY + "─────────────────────────────");
    }

    /**
     * 处理 Plan Mode 执行模式选择
     */
    public void handlePlanStartMode(Player player, String modeStr) {
        UUID uuid = player.getUniqueId();
        if (!pendingPlanStartMode.contains(uuid)) return;

        DialogueSession.Mode targetMode;
        String modeDisplayName;
        switch (modeStr.toLowerCase()) {
            case "yolo":
                // YOLO 需要先同意协议
                if (!yoloAgreedPlayers.contains(uuid)) {
                    sendYoloWarning(player);
                    pendingYoloAgreementPlayers.add(uuid);
                    // 保留 pendingPlanStartMode，等 YOLO 同意后由 handleChat 继续
                    player.sendMessage(I18n.t("clim.plan.yolo.agree"));
                    return;
                }
                targetMode = DialogueSession.Mode.YOLO;
                modeDisplayName = "YOLO";
                break;
            case "smart":
                targetMode = DialogueSession.Mode.SMART;
                modeDisplayName = "Smart";
                break;
            default:
                targetMode = DialogueSession.Mode.NORMAL;
                modeDisplayName = "Normal";
                break;
        }

        pendingPlanStartMode.remove(uuid);

        final DialogueSession.Mode finalMode = targetMode;
        final String finalDisplayName = modeDisplayName;

        // 延迟 0.3 秒 (6 ticks) 后发送确认并开始执行
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!player.isOnline()) return;

            player.sendMessage(I18n.t("clim.plan.start", finalDisplayName));

            DialogueSession session = sessions.get(uuid);
            if (session == null) return;

            // 切换模式
            session.setMode(finalMode);
            savePlanModeState(uuid, false);
            if (finalMode == DialogueSession.Mode.YOLO) {
                saveYoloModeState(uuid, true);
            } else if (finalMode == DialogueSession.Mode.SMART) {
                saveSmartModeState(uuid, true);
            }

            // 反馈给 AI：规划完成，开始执行
            String feedback = "#start_result: 玩家选择了 " + finalDisplayName + " 模式。现在开始执行规划好的任务。";
            session.appendLog("PLAN_START", "Plan mode ended. Execution mode: " + finalDisplayName);

            // 使用 feedbackToAI 触发 AI 响应（复用现有流式/非流式逻辑）
            feedbackToAI(player, feedback);
        }, 6L);
    }

    /**
     * 发送SMART模式风险确认界面
     */
    public void sendSmartRiskConfirm(Player player, String actionType, String actionContent, 
                                    RiskAssessmentManager.RiskAssessment assessment) {
        UUID uuid = player.getUniqueId();
        
        pendingSmartActions.put(uuid, new PendingSmartAction(actionType, actionContent, assessment));
        setGenerating(uuid, false, GenerationStatus.WAITING_CONFIRM);
        
        player.sendMessage(I18n.t("clim.smart.risk.ask"));
        
        ChatColor riskColor;
        if (assessment.level < 25) {
            riskColor = ChatColor.GREEN;
        } else if (assessment.level < 50) {
            riskColor = ChatColor.YELLOW;
        } else if (assessment.level < 75) {
            riskColor = ChatColor.GOLD;
        } else {
            riskColor = ChatColor.RED;
        }
        player.sendMessage(I18n.t("clim.smart.risk.level", riskColor.toString() + assessment.level));
        
        if ("run".equals(actionType)) {
            player.sendMessage(I18n.t("clim.smart.run.cmd", actionContent));
        } else if ("edit".equals(actionType)) {
            String[] parts = actionContent.split("\\|", 3);
            String path = parts.length > 0 ? parts[0].trim() : "";
            player.sendMessage(I18n.t("clim.smart.edit.file", path));
            if (parts.length >= 3) {
                player.sendMessage(ChatColor.WHITE + "  From " + ChatColor.GRAY + parts[1]);
                player.sendMessage(ChatColor.WHITE + "  To " + ChatColor.GRAY + parts[2]);
            }
        }
        
        if (assessment.reason != null && !assessment.reason.isEmpty()) {
            player.sendMessage(I18n.t("clim.smart.reason", assessment.reason));
        }
        
        TextComponent message = new TextComponent("  ");
        
        TextComponent allowBtn = new TextComponent(ChatColor.GREEN + "[ ✓ ]");
        allowBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli smart_allow"));
        allowBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.smart.allow.hover"))));
        
        TextComponent spacer1 = new TextComponent(" ");
        
        TextComponent denyBtn = new TextComponent(ChatColor.RED + "[ ✕ ]");
        denyBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli smart_deny"));
        denyBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.smart.deny.hover"))));
        
        TextComponent spacer2 = new TextComponent(" ");
        
        TextComponent neverAskBtn = new TextComponent(I18n.t("clim.smart.never"));
        neverAskBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli smart_never"));
        neverAskBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.smart.never.hover"))));
        
        message.addExtra(allowBtn);
        message.addExtra(spacer1);
        message.addExtra(denyBtn);
        message.addExtra(spacer2);
        message.addExtra(neverAskBtn);
        
        player.spigot().sendMessage(message);
    }

    private static final java.util.Set<String> FILE_OP_TYPES = java.util.Set.of("LS", "READ", "EDIT", "DIFF", "WRITE");

    public void handleConfirm(Player player) {
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);
        if (session != null && pendingCommands.containsKey(uuid)) {
            session.appendLog("USER_ACTION", "Confirmed command: " + pendingCommands.get(uuid));
        }

        if (pendingCommands.containsKey(uuid)) {
            String cmd = pendingCommands.get(uuid);
            if (!"CHOOSING".equals(cmd)) {
                pendingCommands.remove(uuid);
                generationStates.put(uuid, GenerationStatus.EXECUTING_TOOL);
                // 重置超时起点：批次中确认的 run 可能在按钮上停留很久，避免 EXECUTING_TOOL
                // 异步执行窗口内被批次超时兜底误杀
                generationStartTimes.put(uuid, System.currentTimeMillis());
                int colonIdx = cmd.indexOf(':');
                String prefix = colonIdx > 0 ? cmd.substring(0, colonIdx).toUpperCase() : "";
                if (FILE_OP_TYPES.contains(prefix)) {
                    String type = cmd.substring(0, colonIdx).toLowerCase();
                    String args = cmd.substring(colonIdx + 1);
                    String toolName = mapTypeToToolName(type);
                    checkVerificationAndExecute(player, type, toolName, args);
                } else {
                    toolExecutor.executeCommand(player, cmd);
                }
            }
        }
    }

    private void checkVerificationAndExecute(Player player, String type, String toolName, String args) {
        // 检查是否被冻结
        long freezeRemaining = plugin.getVerificationManager().getPlayerFreezeRemaining(player);
        if (freezeRemaining > 0) {
            player.sendMessage(I18n.t("clim.verify.frozen", freezeRemaining));
            return;
        }

        if (plugin.getConfigManager().isPlayerToolEnabled(player, toolName)) {
            toolExecutor.executeFileOperation(player, type, args);
        } else {
            player.sendMessage(I18n.t("clim.verify.first.use", toolName));
            plugin.getVerificationManager().startVerification(player, toolName, () -> {
                plugin.getConfigManager().setPlayerToolEnabled(player, toolName, true);
                toolExecutor.executeFileOperation(player, type, args);
            });
        }
    }

    /**
     * 将内部类型映射到配置中的工具名称（read/write 两大权限组）
     * @param type 内部类型（ls, read, edit, diff, write）
     * @return 配置中的工具名称（read, write）
     */
    private String mapTypeToToolName(String type) {
        return switch (type.toLowerCase()) {
            case "ls", "read" -> "read";
            case "edit", "diff", "write" -> "write";
            default -> type;
        };
    }

    public void handleCancel(Player player) {
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);
        if (session != null && pendingCommands.containsKey(uuid)) {
            session.appendLog("USER_ACTION", "Cancelled command: " + pendingCommands.get(uuid));
        }

        if (pendingCommands.containsKey(uuid)) {
            pendingCommands.remove(uuid);
            player.sendMessage(I18n.t("clim.cancel.cmd"));
            // 若处于串行批次中，把取消作为该工具的结果回灌，推进到下一工具（否则屏障永久卡死）
            DialogueSession s = sessions.get(uuid);
            if (s != null && s.isBatchInProgress()) {
                s.addPendingToolResult("#error: 用户取消了该命令。");
                executeNativeBatch(player, s);
                return;
            }
            isGenerating.put(uuid, false);
            generationStates.put(uuid, GenerationStatus.CANCELLED);
            generationStartTimes.put(uuid, System.currentTimeMillis());
        }
    }

    /**
     * 处理SMART模式 - 本次允许
     */
    public void handleSmartAllow(Player player) {
        UUID uuid = player.getUniqueId();
        PendingSmartAction action = pendingSmartActions.get(uuid);
        if (action == null) {
            return;
        }
        
        pendingSmartActions.remove(uuid);
        executeSmartAction(player, action);
    }

    /**
     * 处理SMART模式 - 拒绝
     */
    public void handleSmartDeny(Player player) {
        UUID uuid = player.getUniqueId();
        PendingSmartAction action = pendingSmartActions.get(uuid);
        if (action == null) {
            return;
        }
        
        pendingSmartActions.remove(uuid);
        player.sendMessage(I18n.t("clim.smart.denied"));
        // 若处于串行批次中，把拒绝作为该工具的结果回灌，推进到下一工具（否则屏障永久卡死）
        DialogueSession s = sessions.get(uuid);
        if (s != null && s.isBatchInProgress()) {
            s.addPendingToolResult("#error: 用户拒绝了此操作。");
            executeNativeBatch(player, s);
            return;
        }
        isGenerating.put(uuid, false);
        generationStates.put(uuid, GenerationStatus.CANCELLED);
        generationStartTimes.put(uuid, System.currentTimeMillis());

        feedbackToAI(player, "#error: 用户拒绝了此操作。");
    }

    /**
     * 处理SMART模式 - 不再询问
     */
    public void handleSmartNever(Player player) {
        UUID uuid = player.getUniqueId();
        PendingSmartAction action = pendingSmartActions.get(uuid);
        if (action == null) {
            return;
        }
        
        pendingSmartActions.remove(uuid);
        
        // 显式切换到 YOLO 模式
        switchMode(player, DialogueSession.Mode.YOLO);
        
        executeSmartAction(player, action);
    }

    /**
     * 执行SMART模式的操作
     */
    private void executeSmartAction(Player player, PendingSmartAction action) {
        UUID uuid = player.getUniqueId();
        
        if ("run".equals(action.actionType)) {
            player.sendMessage(I18n.t("tool.run.smart", action.actionContent));
            setGenerating(uuid, false, GenerationStatus.EXECUTING_TOOL);
            toolExecutor.executeCommand(player, action.actionContent);
        } else if ("edit".equals(action.actionType)) {
            String pendingStr = "DIFF:" + action.actionContent;
            setPendingCommand(uuid, pendingStr);
            setGenerating(uuid, false, GenerationStatus.WAITING_CONFIRM);
            toolExecutor.sendConfirmButtons(player, "");
        }
    }

    /**
     * 处理玩家发送的消息
     */
    public boolean handleChat(Player player, String message) {
        UUID uuid = player.getUniqueId();

        // 优先处理验证逻辑
        if (plugin.getVerificationManager().isVerifying(player)) {
            if (plugin.getVerificationManager().handleVerification(player, message)) {
                return true;
            }
        }

        // 如果玩家在等待协议同意
        if (pendingAgreementPlayers.contains(uuid)) {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[CLI] 玩家 " + player.getName() + " 发送了协议同意消息: " + message);
            }
            if (message.equalsIgnoreCase("agree")) {
                // 再次检查 EULA 状态
                if (!plugin.getEulaManager().isEulaValid()) {
                    player.sendMessage(I18n.t("clim.eula.system.error"));
                    return true;
                }
                pendingAgreementPlayers.remove(uuid);
                saveAgreedPlayer(uuid);
                enterCLI(player);
            } else {
                player.sendMessage(I18n.t("clim.agree.prompt"));
            }
            return true;
        }

        // 如果玩家在等待 YOLO 协议同意
        if (pendingYoloAgreementPlayers.contains(uuid)) {
            if (message.equalsIgnoreCase("agree")) {
                pendingYoloAgreementPlayers.remove(uuid);
                saveYoloAgreedPlayer(uuid);
                // 如果 YOLO 同意来自 Plan Mode 的 #start 流程，继续 plan 执行
                if (pendingPlanStartMode.contains(uuid)) {
                    handlePlanStartMode(player, "yolo");
                } else {
                    switchMode(player, DialogueSession.Mode.YOLO);
                }
            } else if (message.equalsIgnoreCase("stop")) {
                pendingYoloAgreementPlayers.remove(uuid);
                if (pendingPlanStartMode.contains(uuid)) {
                    // Plan 启动中的 YOLO 取消：重新显示模式选择 UI
                    handlePlanStart(player);
                } else {
                    player.sendMessage(I18n.t("clim.yolo.cancelled"));
                }
            } else {
                player.sendMessage(I18n.t("clim.yolo.prompt"));
            }
            return true;
        }

        // 如果玩家在等待 Plan Mode 上下文清空确认
        if (pendingPlanContextClear.contains(uuid)) {
            if (message.equalsIgnoreCase("y")) {
                handlePlanClearY(player);
            } else if (message.equalsIgnoreCase("n")) {
                handlePlanClearN(player);
            } else {
                player.sendMessage(I18n.t("clim.plan.clear.prompt"));
            }
            return true;
        }

        // 如果玩家处于 CLI 模式
        if (activeCLIPayers.contains(uuid)) {
            if (message.startsWith("！") || message.startsWith("!")) {
                return false;
            }
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[CLI] 拦截到来自 " + player.getName() + " 的消息: " + message);
            }
            if (message.equalsIgnoreCase("exit")) {
                exitCLI(player);
                return true;
            }
            if (message.equalsIgnoreCase("stop")) {
                boolean interrupted = false;
                interruptedToolCalls.remove(uuid);
                
                StreamingHandler activeHandler = activeStreamingHandlers.get(uuid);
                if (activeHandler != null) {
                    activeHandler.cancel();
                    activeStreamingHandlers.remove(uuid);
                }
                
                if (isGenerating.getOrDefault(uuid, false)) {
                    isGenerating.put(uuid, false);
                    recordThinkingTime(uuid);
                    generationStates.put(uuid, GenerationStatus.CANCELLED);
                    generationStartTimes.put(uuid, System.currentTimeMillis());
                    player.sendMessage(I18n.t("clim.stop.interrupted"));
                    interrupted = true;
                }
                if (pendingCommands.containsKey(uuid)) {
                    pendingCommands.remove(uuid);
                    player.sendMessage(I18n.t("clim.stop.cancelled"));
                    isGenerating.put(uuid, false);
                    generationStates.put(uuid, GenerationStatus.CANCELLED);
                    generationStartTimes.put(uuid, System.currentTimeMillis());
                    interrupted = true;
                }
                // 清理批次状态：玩家主动 stop 时批次不再有效，避免后续 feedbackToAI
                // 命中已死批次的 isBatchInProgress() 拦截器，导致工具反馈被吞入幽灵队列。
                DialogueSession stopSession = sessions.get(uuid);
                if (stopSession != null && stopSession.isBatchInProgress()) {
                    stopSession.clearBatchState();
                }
                if (!interrupted) {
                    player.sendMessage(I18n.t("clim.stop.nothing"));
                }
                return true;
            }

            if (message.equalsIgnoreCase("/cli exempt_anti_loop")) {
                DialogueSession session = sessions.get(uuid);
                if (session != null) {
                    session.setAntiLoopExempted(true);
                    player.sendMessage(I18n.t("clim.exempt.enabled"));

                    // 恢复执行之前被打断的工具
                    String interruptedCall = interruptedToolCalls.get(uuid);
                    if (interruptedCall != null) {
                        player.sendMessage(I18n.t("clim.exempt.restoring"));
                        isGenerating.put(uuid, true);
                        generationStates.put(uuid, GenerationStatus.EXECUTING_TOOL);
                        generationStartTimes.put(uuid, System.currentTimeMillis());
                        executeTool(player, interruptedCall);
                    }
                }
                return true;
            }

            if (message.equalsIgnoreCase("/cli retry")) {
                handleRetry(player);
                return true;
            }

            // 处理待确认的命令或选择
            if (pendingCommands.containsKey(uuid)) {
                String pending = pendingCommands.get(uuid);
                if (pending.equals("CHOOSING")) {
                    pendingCommands.remove(uuid);
                    player.sendMessage(I18n.t("clim.ask.choice", message));
                    feedbackToAI(player, "#ask_result: " + message);
                    return true;
                }
                
                if (message.equalsIgnoreCase("y") || message.equalsIgnoreCase("/fancyhelper confirm")) {
                    handleConfirm(player);
                } else if (message.equalsIgnoreCase("n") || message.equalsIgnoreCase("/fancyhelper cancel")) {
                    handleCancel(player);
                } else {
                    player.sendMessage(I18n.t("clim.confirm.prompt"));
                }
                return true;
            }
            
            if (isGenerating.getOrDefault(uuid, false)) {
                TextComponent warnMsg = new TextComponent(TextComponent.fromLegacyText(I18n.t("clim.warn.no.send")));
                TextComponent interruptBtn = new TextComponent(I18n.t("clim.warn.interrupt"));
                interruptBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli stop"));
                interruptBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.warn.interrupt.hover"))));
                warnMsg.addExtra(interruptBtn);
                player.spigot().sendMessage(warnMsg);
                return true;
            }

            DialogueSession session = sessions.get(uuid);
            // 用户发送了消息，重置工具链计数
            if (session != null) {
                session.resetToolChain();
            }

            processAIMessage(player, message);
            return true;
        }

        return false;
    }

    /**
     * 处理重试操作
     */
    public void handleRetry(Player player) {
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);
        if (session != null) {
            session.appendLog("USER_ACTION", "Retrying AI call");
        }
        
        RetryInfo retryInfo = retryInfoMap.get(uuid);
        if (retryInfo == null) {
            player.sendMessage(I18n.t("clim.retry.nothing"));
            return;
        }

        player.sendMessage(I18n.t("clim.retry.retrying"));
        isGenerating.put(uuid, true);
        generationStates.put(uuid, GenerationStatus.THINKING);
        generationStartTimes.put(uuid, System.currentTimeMillis());
        retryInfoMap.remove(uuid);

        // 使用异步任务重试
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // 设置重试回调，向玩家显示重试提示
            ai.setRetryCallback((statusCode, retryMessage) -> {
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        TextComponent retryMsg;
                        if (statusCode == 429) {
                            // 429 错误使用黄色⁕ 白色
                            retryMsg = new TextComponent(ChatColor.YELLOW + "⁕ " + ChatColor.WHITE + retryMessage);
                        } else {
                            // 其他错误使用灰色⁕
                            retryMsg = new TextComponent(ChatColor.GRAY + "⁕ " + retryMessage);
                        }
                        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.CHAT, retryMsg);
                    }
                });
            });
            
            try {
                // 如果有最后一条消息，重新加入会话（因为失败时会被移除）
                if (retryInfo.lastMessage != null) {
                    retryInfo.session.addMessage("user", retryInfo.lastMessage);
                }

                // 重试时使用存储的 matchedSkills 重新构建系统提示
                List<String> retrySystemPrompts = promptManager.getBaseSystemPrompt(player, retryInfo.matchedSkills);
                AIResponse response = ai.chat(player, retryInfo.session, retrySystemPrompts);
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    handleAIResponse(player, response);
                });
            } catch (IOException e) {
                activeStreamingHandlers.remove(uuid);
                plugin.getCloudErrorReport().report(e);
                plugin.getLogger().warning("[CLI] AI 请求失败 (重试) - " + player.getName() + ": " + e);
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // 再次失败，重新移除最后一条消息并保存重试信息
                    if (retryInfo.lastMessage != null) {
                        retryInfo.session.removeLastMessage();
                    }
                    retryInfoMap.put(uuid, new RetryInfo(retryInfo.session, retryInfo.lastMessage, retryInfo.isUserMessage, retryInfo.matchedSkills));

                    TextComponent fullMsg = buildErrorText(e.getMessage(), "AI请求出错");
                    fullMsg.addExtra(buildRetryButton(e.getMessage()));
                    player.spigot().sendMessage(fullMsg);

                    isGenerating.put(uuid, false);
                    recordThinkingTime(uuid);
                    generationStates.put(uuid, GenerationStatus.ERROR);
                    generationStartTimes.remove(uuid);
                    player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new TextComponent(""));
                });
            } catch (Throwable t) {
                activeStreamingHandlers.remove(uuid);
                plugin.getCloudErrorReport().report(t);
                plugin.getLogger().warning("[CLI] AI 请求异常 (重试) - " + player.getName() + ": " + t);
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // 再次失败，重新移除最后一条消息并保存重试信息
                    if (retryInfo.lastMessage != null) {
                        retryInfo.session.removeLastMessage();
                    }
                    retryInfoMap.put(uuid, new RetryInfo(retryInfo.session, retryInfo.lastMessage, retryInfo.isUserMessage, retryInfo.matchedSkills));

                    TextComponent fullMsg = buildErrorText(t.getMessage(), "系统内部错误");
                    fullMsg.addExtra(buildRetryButton(t.getMessage()));
                    player.spigot().sendMessage(fullMsg);

                    isGenerating.put(uuid, false);
                    recordThinkingTime(uuid);
                    generationStates.put(uuid, GenerationStatus.ERROR);
                    generationStartTimes.remove(uuid);
                    player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new TextComponent(""));
                });
            } finally {
                // 清除重试回调
                ai.clearRetryCallback();
            }
        });
    }

    private void processStreamingMessage(Player player, String message, List<org.YanPl.model.Skill> matchedSkills) throws IOException {
        runStreamingRound(player, message, matchedSkills, 1);
    }

    /**
     * 单轮流式对话请求 + 思考循环自动重试链。
     * @param attempt 1=正常轮（保留模型思考）；2=思考循环原样重试（仍保留思考，循环有随机性）；
     *                3=仍循环则降级重试（附加 enable_thinking=false 关思考，牺牲一点能力换可用性）
     */
    private void runStreamingRound(Player player, String message, List<org.YanPl.model.Skill> matchedSkills, int attempt) throws IOException {
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);
        
        StreamingHandler streamingHandler = new StreamingHandler(plugin, player);
        activeStreamingHandlers.put(uuid, streamingHandler);
        final long reservedMessageId = session.getNextMessageId();

        final StringBuilder fullResponseText = new StringBuilder();
        final StringBuilder accumulatedText = new StringBuilder();
        final String[] lastFormatted = {""};
        final boolean[] responseHandled = {false};
        final boolean[] isFirstLine = {true};

        // 思考结束回调：reasoning_content 切换到 content 时立即触发，在正文前展示按钮
        streamingHandler.setOnReasoningCompleteCallback((thinkingTimeMs) -> {
            if (!plugin.isEnabled() || !player.isOnline()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;
                String currentThought = streamingHandler.getThoughtContent();
                if (currentThought == null || currentThought.trim().isEmpty()) return;

                // 提前设置思考内容，让 /cli thought 可立即访问
                session.setLastThought(currentThought);
                // 记录正确的思考耗时到 session，供成书使用
                session.addThinkingTime(thinkingTimeMs);

                TextComponent thoughtBtn = new TextComponent(ChatColor.GRAY + " ○ Thought");
                thoughtBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli thought t:" + reservedMessageId));
                thoughtBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.thought.hover"))));
                double sec = thinkingTimeMs / 1000.0;
                TextComponent timeTag = new TextComponent(ChatColor.DARK_GRAY + " (" + String.format("%.1f", sec) + "s)");
                thoughtBtn.addExtra(timeTag);
                player.spigot().sendMessage(thoughtBtn);
            });
        });

        streamingHandler.setOnReasoningCallback((reasoningChunk) -> {
            // 异步线程直接累计 reasoning tokens
            if (reasoningChunk == null || reasoningChunk.isEmpty()) return;
            streamedOutputTokens.put(uuid, streamedOutputTokens.getOrDefault(uuid, 0L)
                + DialogueSession.calculateTokens(reasoningChunk));
        });

        // 如果 API 返回了真实 token 用量，替换本地估算值
        streamingHandler.setOnUsageTokens((inputTokens, outputTokens) -> {
            streamedOutputTokens.put(uuid, outputTokens);
            if (session != null) {
                session.addInputTokens(inputTokens);
                session.addOutputTokens(outputTokens);
                if (plugin.getConfigManager().isDebug()) {
                    plugin.getLogger().info("[CLI] API Token Usage - Input: " + inputTokens + ", Output: " + outputTokens);
                }
            }
        });

        // 上下文缓存命中统计：写入会话对话日志（不刷服务器控制台）
        streamingHandler.setOnCacheStats((cacheHit, cacheMiss) -> {
            if (session == null) return;
            long total = cacheHit + cacheMiss;
            long pct = total > 0 ? cacheHit * 100 / total : 0;
            session.appendLog("CACHE", "本次请求 prompt=" + session.getEstimatedTokens()
                + " 缓存命中=" + cacheHit + " (" + pct + "%) 未命中=" + cacheMiss);
        });

        streamingHandler.setOnChunkCallback((chunk) -> {
            if (!plugin.isEnabled() || !player.isOnline()) return;
            
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline() || !isGenerating.getOrDefault(uuid, false)) return;

                // 首个 chunk 到达时间 = TTFT（相对本轮生成开始）
                if (accumulatedText.length() == 0) {
                    Long start = generationStartTimes.get(uuid);
                    if (start != null) {
                        plugin.getStatsManager().addTtft(System.currentTimeMillis() - start);
                    }
                }

                accumulatedText.append(chunk);

                // 实时累计流式输出 Token
                streamedOutputTokens.put(uuid, streamedOutputTokens.getOrDefault(uuid, 0L)
                    + DialogueSession.calculateTokens(chunk));

                String safeText = stripIncompleteFormatting(accumulatedText.toString());
                String formatted = convertMarkdownBoldToMinecraft(safeText);
                formatted = ColorUtil.translateCustomColors(formatted);

                int commonPrefix = 0;
                int minLen = Math.min(lastFormatted[0].length(), formatted.length());
                while (commonPrefix < minLen && lastFormatted[0].charAt(commonPrefix) == formatted.charAt(commonPrefix)) {
                    commonPrefix++;
                }

                String newContent = formatted.substring(commonPrefix);
                lastFormatted[0] = formatted;

                if (newContent.isEmpty()) return;

                String[] lines = newContent.split("\n", -1);
                for (int i = 0; i < lines.length; i++) {
                    String line = lines[i];
                    boolean isLastLine = (i == lines.length - 1);

                    if (isFirstLine[0]) {
                        if (!line.isEmpty() || !isLastLine) {
                            player.sendMessage(ChatColor.WHITE + "◆ " + line);
                            isFirstLine[0] = false;
                        }
                    } else {
                        if (!line.isEmpty() || !isLastLine) {
                            player.sendMessage(ChatColor.WHITE + "  " + line);
                        }
                    }
                }
            });
        });
        
        streamingHandler.setOnCompleteCallback((completeText) -> {
            if (responseHandled[0]) return;
            responseHandled[0] = true;
            
            fullResponseText.append(completeText);
            activeStreamingHandlers.remove(uuid);

            if (!plugin.isEnabled()) return;

            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!player.isOnline()) return;

                // 本轮总耗时（生成开始 → 完成）
                Long start = generationStartTimes.get(uuid);
                if (start != null) {
                    plugin.getStatsManager().addResponseTime(System.currentTimeMillis() - start);
                }

                String response = completeText;
                String thoughtContent = "";

                // 优先使用流式处理器中捕获的 reasoning_content（思考模型的 API 级字段）
                String handlerThought = streamingHandler.getThoughtContent();
                if (handlerThought != null && !handlerThought.isEmpty()) {
                    thoughtContent = handlerThought;
                }

                // 同时尝试从文本中提取标签形式的思考内容（作为 fallback）
                java.util.regex.Matcher thoughtMatcher = java.util.regex.Pattern.compile("(?s)<(thought|thinking)>(.*?)</\\1>").matcher(response);
                if (thoughtMatcher.find()) {
                    if (thoughtContent.isEmpty()) {
                        thoughtContent = thoughtMatcher.group(2);
                    }
                    response = response.replaceAll("(?s)<(thought|thinking)>.*?</\\1>", "");
                } else {
                    java.util.regex.Matcher thinkTagMatcher = java.util.regex.Pattern.compile("(?s)<think>(.*?)</think>").matcher(response);
                    if (thinkTagMatcher.find()) {
                        if (thoughtContent.isEmpty()) {
                            thoughtContent = thinkTagMatcher.group(1);
                        }
                        response = response.replaceAll("(?s)<think>.*?</think>", "");
                    } else {
                        java.util.regex.Matcher mdThoughtMatcher = java.util.regex.Pattern.compile("(?s)```thought\n?(.*?)\n?```").matcher(response);
                        if (mdThoughtMatcher.find()) {
                            if (thoughtContent.isEmpty()) {
                                thoughtContent = mdThoughtMatcher.group(1);
                            }
                            response = response.replaceAll("(?s)```thought\n?.*?\n?```", "");
                        }
                    }
                }
                
                response = response.replaceAll("(?i)^Thought:.*?\n", "");
                response = response.replaceAll("(?i)^思考过程:.*?\n", "");
                response = response.trim();

                // 防止误判：如果思考内容与正文相同，说明不是真正的思考过程
                if (!thoughtContent.isEmpty() && thoughtContent.trim().equals(response.trim())) {
                    thoughtContent = "";
                }

                String finalThought = thoughtContent.isEmpty() ? null : thoughtContent;
                session.setLastThought(finalThought);

                // 思考循环检测：模型反复输出同一段思考内容未出正文（流被 StreamingHandler 主动中断）。
                // 走自动重试链：原样重试 → 仍循环则降级关思考重试 → 仍失败明确提示，不再静默无输出。
                // 中断轮的空响应不写入会话历史，避免污染上下文。
                if (streamingHandler.isReasoningLoopDetected() && response.trim().isEmpty()) {
                    handleThinkingLoopRetry(player, session, message, matchedSkills, attempt);
                    return;
                }

                String formatted = convertMarkdownBoldToMinecraft(accumulatedText.toString());
                formatted = ColorUtil.translateCustomColors(formatted);
                
                int commonPrefix = 0;
                int minLen = Math.min(lastFormatted[0].length(), formatted.length());
                while (commonPrefix < minLen && lastFormatted[0].charAt(commonPrefix) == formatted.charAt(commonPrefix)) {
                    commonPrefix++;
                }
                
                // trim 尾部空白，避免 chunk 与 onComplete 之间 stripIncompleteFormatting
                // 的差异导致差分出孤立的 \n 被当成空行发送给玩家
                String remaining = formatted.substring(commonPrefix).trim();
                lastFormatted[0] = formatted;

                if (!remaining.isEmpty()) {
                    String[] lines = remaining.split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i];
                        boolean isLastLine = (i == lines.length - 1);

                        if (isFirstLine[0]) {
                            if (!line.isEmpty() || !isLastLine) {
                                player.sendMessage(ChatColor.WHITE + "◆ " + line);
                                isFirstLine[0] = false;
                            }
                        } else {
                            if (!line.isEmpty() || !isLastLine) {
                                player.sendMessage(ChatColor.WHITE + "  " + line);
                            }
                        }
                    }
                }

                // 原生函数调用：流式响应中解析出的结构化 tool_calls（在 assistant 消息中渲染回文本）
                List<NativeToolCall> nativeCalls = streamingHandler.getNativeToolCalls();
                if (nativeCalls != null && !nativeCalls.isEmpty()) {
                    session.addMessage("assistant", ToolRegistry.renderForHistory(response, nativeCalls), finalThought);
                } else {
                    session.addMessage("assistant", response, finalThought);
                }
                session.logAIResponse(response + "\n\n[Streaming] Finish Reason: stop\n");

                if (!thoughtContent.isEmpty()) {
                    String modelName = plugin.getConfigManager().getCloudflareModel();
                    int thoughtTokens = DialogueSession.calculateTokens(thoughtContent, modelName);
                    session.addThoughtTokens(thoughtTokens);
                }

                // 流式模式也显示思考按钮（仅当 reasoning-complete 未触发时作为 fallback，如标签提取的思考）
                if (finalThought != null && !finalThought.trim().isEmpty() && !streamingHandler.hasReasoningCompleteFired()) {
                    long thoughtMessageId = -1;
                    long thoughtThinkingTimeMs = session.getLastThinkingTimeMs();
                    List<DialogueSession.Message> history = session.getHistory();
                    if (!history.isEmpty()) {
                        DialogueSession.Message last = history.get(history.size() - 1);
                        thoughtMessageId = last.getId();
                        thoughtThinkingTimeMs = last.getThinkingTimeMs();
                    }
                    TextComponent thoughtBtn = new TextComponent(ChatColor.GRAY + " ○ Thought");
                    String cmd = "/cli thought" + (thoughtMessageId != -1 ? " t:" + thoughtMessageId : "");
                    thoughtBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
                    thoughtBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.thought.hover"))));
                    double lastSec = thoughtThinkingTimeMs / 1000.0;
                    TextComponent timeTag = new TextComponent(ChatColor.DARK_GRAY + " (" + String.format("%.1f", lastSec) + "s)");
                    thoughtBtn.addExtra(timeTag);
                    player.spigot().sendMessage(thoughtBtn);
                }

                isGenerating.put(uuid, false);
                generationStates.put(uuid, GenerationStatus.COMPLETED);
                generationStartTimes.remove(uuid);

                boolean hasNative = nativeCalls != null && !nativeCalls.isEmpty();
                if (hasNative) {
                    // cycle 结束但有下一轮 → 当前 tokens 落 session 并清空计数器
                    Long streamedThisCycle = streamedOutputTokens.get(uuid);
                    if (streamedThisCycle != null && streamedThisCycle > 0) {
                        session.addOutputTokens(streamedThisCycle);
                        roundOutputTokens.merge(uuid, streamedThisCycle, (a, b) -> a + b);
                    }
                    streamedOutputTokens.remove(uuid);
                    dispatchNativeCalls(player, session, nativeCalls);
                } else {
                    List<String> textTools = extractToolCalls(response);
                    // 模型可能把 #tool 放在 reasoning_content 而非 content 里（仅文本协议时兜底）
                    if (textTools.isEmpty() && thoughtContent != null && !thoughtContent.isEmpty()) {
                        textTools = extractToolCalls(thoughtContent);
                    }
                    if (!textTools.isEmpty()) {
                        // cycle 结束但有下一轮 → 当前 tokens 落 session 并清空计数器
                        Long streamedThisCycle = streamedOutputTokens.get(uuid);
                        if (streamedThisCycle != null && streamedThisCycle > 0) {
                            session.addOutputTokens(streamedThisCycle);
                            roundOutputTokens.merge(uuid, streamedThisCycle, (a, b) -> a + b);
                        }
                        streamedOutputTokens.remove(uuid);
                        dispatchTextTools(player, session, textTools);
                    } else {
                        // 本轮输出完全结束 → 累积 token 写回 session
                        Long streamedTotal = streamedOutputTokens.get(uuid);
                        if (streamedTotal != null && streamedTotal > 0) {
                            session.addOutputTokens(streamedTotal);
                            roundOutputTokens.merge(uuid, streamedTotal, (a, b) -> a + b);
                        }
                        streamedOutputTokens.remove(uuid);
                        checkTokenWarning(player, session);
                        autoCompressContext(player, session);
                        playFeedbackSound(player, "ai_complete");
                    }
                }

                // 每次 AI 回复后保存会话到磁盘
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    saveSessionToHistory(uuid, session);
                });
            });
        });

        streamingHandler.setOnErrorCallback((error) -> {
            if (responseHandled[0]) return;
            responseHandled[0] = true;

            activeStreamingHandlers.remove(uuid);
            long streamedOutErr = streamedOutputTokens.getOrDefault(uuid, 0L);
            streamedOutputTokens.remove(uuid);
            if (streamedOutErr > 0) {
                roundOutputTokens.merge(uuid, streamedOutErr, (a, b) -> a + b);
            }
            plugin.getCloudErrorReport().report(error);
            plugin.getLogger().warning("[CLI] 流式输出错误 - " + player.getName() + ": " + error);
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (streamedOutErr > 0) {
                    DialogueSession s = sessions.get(uuid);
                    if (s != null) s.addOutputTokens(streamedOutErr);
                }
                retryInfoMap.put(uuid, new RetryInfo(session, message, true, matchedSkills));
                player.spigot().sendMessage(buildErrorText(error.getMessage(), I18n.t("clim.error.streaming")));
                isGenerating.put(uuid, false);
                generationStates.put(uuid, GenerationStatus.ERROR);
                generationStartTimes.remove(uuid);
                playFeedbackSound(player, "ai_error");
            });
        });

        // 估算本轮输入的 prompt tokens 并记入 session
        List<String> systemPrompts = promptManager.getSystemPromptForSession(player, matchedSkills, session.getMode(), message,
                isNativeActiveForPrompt(player, session));
        String modelName = plugin.getConfigManager().getCloudflareModel();
        int promptTokens = systemPrompts.stream().mapToInt(p -> DialogueSession.calculateTokens(p, modelName)).sum();
        int estimatedInput = promptTokens
            + session.getEstimatedTokens(modelName) + 3;
        session.addInputTokens(estimatedInput);

        String completeText = ai.chatStreaming(player, session, systemPrompts, streamingHandler, attempt >= 3);
        
        if (!streamingHandler.isCancelled() && !responseHandled[0] && fullResponseText.length() == 0) {
            responseHandled[0] = true;

            // 思考循环兜底（onComplete 未触发路径）：同样走自动重试链，避免玩家干等
            if (streamingHandler.isReasoningLoopDetected() && (completeText == null || completeText.trim().isEmpty())) {
                handleThinkingLoopRetry(player, session, message, matchedSkills, attempt);
                return;
            }
            
            String response = completeText;
            String thoughtContent = "";
            
            java.util.regex.Matcher thoughtMatcher = java.util.regex.Pattern.compile("(?s)<(thought|thinking)>(.*?)</\\1>").matcher(response);
            if (thoughtMatcher.find()) {
                thoughtContent = thoughtMatcher.group(2);
                response = response.replaceAll("(?s)<(thought|thinking)>.*?</\\1>", "");
            } else {
                java.util.regex.Matcher thinkTagMatcher = java.util.regex.Pattern.compile("(?s)<think>(.*?)</think>").matcher(response);
                if (thinkTagMatcher.find()) {
                    thoughtContent = thinkTagMatcher.group(1);
                    response = response.replaceAll("(?s)<think>.*?</think>", "");
                } else {
                    java.util.regex.Matcher mdThoughtMatcher = java.util.regex.Pattern.compile("(?s)```thought\n?(.*?)\n?```").matcher(response);
                    if (mdThoughtMatcher.find()) {
                        thoughtContent = mdThoughtMatcher.group(1);
                        response = response.replaceAll("(?s)```thought\n?.*?\n?```", "");
                    }
                }
            }
            
            response = response.replaceAll("(?i)^Thought:.*?\n", "");
            response = response.replaceAll("(?i)^思考过程:.*?\n", "");
            response = response.trim();

            // 防止误判：如果思考内容与正文相同，说明不是真正的思考过程
            if (!thoughtContent.isEmpty() && thoughtContent.trim().equals(response.trim())) {
                thoughtContent = "";
            }

            final String finalResponse = response;
            String finalThought = thoughtContent.isEmpty() ? null : thoughtContent;
            session.setLastThought(finalThought);
            session.addMessage("assistant", finalResponse, finalThought);
            session.logAIResponse(finalResponse + "\n\n[Streaming] Finish Reason: stop\n");

            if (!thoughtContent.isEmpty()) {
                int thoughtTokens = DialogueSession.calculateTokens(thoughtContent, modelName);
                session.addThoughtTokens(thoughtTokens);
            }
            
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                isGenerating.put(uuid, false);
                generationStates.put(uuid, GenerationStatus.COMPLETED);
                generationStartTimes.remove(uuid);
                
                // 原生函数调用优先；否则走文本协议提取
                List<NativeToolCall> nativeCalls = streamingHandler.getNativeToolCalls();
                if (nativeCalls != null && !nativeCalls.isEmpty()) {
                    dispatchNativeCalls(player, session, nativeCalls);
                } else {
                    List<String> textTools = extractToolCalls(finalResponse);
                    if (!textTools.isEmpty()) {
                        dispatchTextTools(player, session, textTools);
                    } else {
                        checkTokenWarning(player, session);
                        autoCompressContext(player, session);
                    }
                }
                playFeedbackSound(player, "ai_complete");

                // 每次 AI 回复后保存会话到磁盘
                Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                    saveSessionToHistory(uuid, session);
                });
            });
        }
    }

    /**
     * 思考循环自动重试链。
     * 流式检测到"模型反复输出同一段思考内容（检测到重复）未出正文"（StreamingHandler 已中断流）时调用：
     * attempt 1 → 静默原样重试（保留思考）；attempt 2 → 静默降级重试（关思考）；
     * attempt 3 → 明确告知玩家失败，保存 retryInfo 供 /cli retry 使用。
     * 前两轮重试对玩家静默（自动收敛，不打扰），仅最终失败才提示。
     * @return true 表示已处理（调用方应直接返回，不再走正常完成逻辑）
     */
    private boolean handleThinkingLoopRetry(Player player, DialogueSession session, String message,
                                            List<org.YanPl.model.Skill> matchedSkills, int attempt) {
        if (attempt <= 2) {
            // 重试轮必须放异步线程执行：runStreamingRound 内部有阻塞的 HTTP 流式读取，
            // 调用方（onComplete 回调的 runTask）在主线程，直接同步重试会卡服（Watchdog 报 read 挂起）
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    runStreamingRound(player, message, matchedSkills, attempt + 1);
                } catch (IOException e) {
                    plugin.getLogger().warning("[CLI] 思考循环自动重试失败 - " + player.getName() + ": " + e.getMessage());
                    Bukkit.getScheduler().runTask(plugin, () -> showThinkingLoopFailed(player, session, message, matchedSkills));
                }
            });
            return true;
        }
        showThinkingLoopFailed(player, session, message, matchedSkills);
        return true;
    }

    /**
     * 思考循环重试链彻底失败：明确提示玩家，保存重试信息供 /cli retry 使用。
     */
    private void showThinkingLoopFailed(Player player, DialogueSession session, String message,
                                        List<org.YanPl.model.Skill> matchedSkills) {
        UUID uuid = player.getUniqueId();
        player.sendMessage(I18n.t("clim.streaming.retry_failed"));
        retryInfoMap.put(uuid, new RetryInfo(session, message, true, matchedSkills));
        isGenerating.put(uuid, false);
        generationStates.put(uuid, GenerationStatus.ERROR);
        generationStartTimes.remove(uuid);
        playFeedbackSound(player, "ai_error");
    }

    private void processNonStreamingMessage(Player player, String message, List<org.YanPl.model.Skill> matchedSkills) throws IOException {
        DialogueSession nsSession = sessions.get(player.getUniqueId());
        List<String> systemPrompts = promptManager.getSystemPromptForSession(player, matchedSkills,
                nsSession != null ? nsSession.getMode() : DialogueSession.Mode.NORMAL, message,
                isNativeActiveForPrompt(player, nsSession));
        AIResponse response = ai.chat(player, sessions.get(player.getUniqueId()), systemPrompts);

        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            handleAIResponse(player, response);
            playFeedbackSound(player, "ai_complete");
        });
    }

    private void processAIMessage(Player player, String message) {
        UUID uuid = player.getUniqueId();
        interruptedToolCalls.remove(uuid);
        DialogueSession session = sessions.get(uuid);
        if (session == null) return;

        // 记录用户消息
        session.appendLog("USER_INPUT", message);

        session.addMessage("user", message);

        // 用户发送第一条消息时才创建会话文件（进入CLI不说话不存文件）
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            saveSessionToHistory(uuid, session);
        });

        // 触发标题生成（UUID已在 enterCLI 中分配）
        if (session.getSessionUUID() != null) {
            // 统计用户消息数（只计 role=user）
            int userMsgCount = 0;
            for (DialogueSession.Message msg : session.getHistory()) {
                if ("user".equals(msg.getRole())) userMsgCount++;
            }
            if (userMsgCount == 1) {
                // 第一条消息：初始生成
                generateSessionTitle(uuid, session);
            } else if (userMsgCount > 1 && userMsgCount % 6 == 0) {
                // 每 6 条用户消息重新生成标题（使用所有用户消息）
                generateSessionTitle(uuid, session, true, true);
            }
        }

        isGenerating.put(uuid, true);
        generationStates.put(uuid, GenerationStatus.THINKING);
        generationStartTimes.put(uuid, System.currentTimeMillis());
        messageReceiveTimes.put(uuid, System.currentTimeMillis());
        wordStartTimes.put(uuid, System.currentTimeMillis());
        currentThinkingWords.put(uuid, THINKING_WORDS[new Random().nextInt(THINKING_WORDS.length)]);
        streamedOutputTokens.remove(uuid);
        roundOutputTokens.put(uuid, 0L);

        TextComponent playerMsg = new TextComponent(I18n.t("clim.ask.choice", message));
        player.spigot().sendMessage(playerMsg);
        playFeedbackSound(player, "user_input");

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[CLI] 会话 " + player.getName() + " - 历史记录大小: " + session.getHistory().size() + ", 预计 Token: " + calculateTotalEstimatedTokens(player, session));
        }

        if (!plugin.isEnabled()) return;
        
        // 【Skill 自动注入】根据用户输入匹配相关 Skills
        // 最多匹配 3 个 Skills，最小匹配分数 30
        List<org.YanPl.model.Skill> matchedSkills = plugin.getSkillManager()
                .findMatchingSkills(message, 3, 30);

        if (plugin.getConfigManager().isDebug() && !matchedSkills.isEmpty()) {
            String skillIds = matchedSkills.stream()
                    .map(s -> s.getId())
                    .collect(Collectors.joining(", "));
            plugin.getLogger().info("[Skill] 匹配到 " + matchedSkills.size() + " 个 Skill: " + skillIds);
        }
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            plugin.getStatsManager().incrementConversation();
            ai.setRetryCallback((statusCode, retryMessage) -> {
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        TextComponent retryMsg;
                        if (statusCode == 429) {
                            retryMsg = new TextComponent(ChatColor.YELLOW + "⁕ " + ChatColor.WHITE + retryMessage);
                        } else {
                            retryMsg = new TextComponent(ChatColor.GRAY + "⁕ " + retryMessage);
                        }
                        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.CHAT, retryMsg);
                    }
                });
            });
            
            try {
                if (plugin.getConfigManager().isPlayerStreamingEnabled(player)) {
                    processStreamingMessage(player, message, matchedSkills);
                } else {
                    processNonStreamingMessage(player, message, matchedSkills);
                }
            } catch (IOException e) {
                activeStreamingHandlers.remove(uuid);
                plugin.getCloudErrorReport().report(e);
                plugin.getLogger().warning("[CLI] AI 请求失败 - " + player.getName() + ": " + e);
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // 保存重试信息（存储 matchedSkills，重试时重新构建系统提示）
                    retryInfoMap.put(uuid, new RetryInfo(session, message, true, matchedSkills));

                    TextComponent fullMsg = buildErrorText(e.getMessage(), "AI请求出错");
                    fullMsg.addExtra(buildRetryButton(e.getMessage()));
                    player.spigot().sendMessage(fullMsg);

                    isGenerating.put(uuid, false);
                    recordThinkingTime(uuid);
                    generationStates.put(uuid, GenerationStatus.ERROR);
                    generationStartTimes.remove(uuid);
                    playFeedbackSound(player, "ai_error");
                    // 立即清除动作栏
                    player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new TextComponent(""));
                    // 移除导致失败的消息，防止污染后续对话
                    session.removeLastMessage();
                });
            } catch (Throwable t) {
                activeStreamingHandlers.remove(uuid);
                plugin.getCloudErrorReport().report(t);
                plugin.getLogger().warning("[CLI] AI 请求异常 - " + player.getName() + ": " + t);
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // 保存重试信息
                    retryInfoMap.put(uuid, new RetryInfo(session, message, true, matchedSkills));

                    TextComponent fullMsg = buildErrorText(t.getMessage(), "系统内部错误");
                    fullMsg.addExtra(buildRetryButton(t.getMessage()));
                    player.spigot().sendMessage(fullMsg);
                    plugin.getLogger().warning("系统内部错误: " + t.getMessage());

                    isGenerating.put(uuid, false);
                    recordThinkingTime(uuid);
                    generationStates.put(uuid, GenerationStatus.ERROR);
                    generationStartTimes.remove(uuid);
                    playFeedbackSound(player, "ai_error");
                    // 立即清除动作栏
                    player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new TextComponent(""));
                    // 移除导致失败的消息，防止污染后续对话
                    session.removeLastMessage();
                });
            } finally {
                // 清除重试回调
                ai.clearRetryCallback();
            }
        });
    }

    private void handleAIResponse(Player player, AIResponse aiResponse) {
        handleAIResponse(player, aiResponse, false);
    }

    private void handleAIResponse(Player player, AIResponse aiResponse, boolean skipDisplay) {
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);
        if (session == null) return;

        // 更新 Token 统计
        if (aiResponse.getPromptTokens() > 0 || aiResponse.getCompletionTokens() > 0) {
            session.addInputTokens(aiResponse.getPromptTokens());
            session.addOutputTokens(aiResponse.getCompletionTokens());
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[CLI] Token Usage - Input: " + aiResponse.getPromptTokens() + 
                    ", Output: " + aiResponse.getCompletionTokens() + 
                    ", Total Input: " + session.getTotalInputTokens() + 
                    ", Total Output: " + session.getTotalOutputTokens());
            }
        }

        // 如果生成已被打断，则丢弃响应
        if (!isGenerating.getOrDefault(uuid, false)) {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[CLI] 由于被中断，丢弃了 " + player.getName() + " 的 AI 响应。");
            }
            return;
        }

        // 收到 AI 回复，立即停止计时
        recordThinkingTime(uuid);
        generationStates.put(uuid, GenerationStatus.COMPLETED);
        generationStartTimes.remove(uuid);

        String response = aiResponse.getContent();
        String thoughtContent = aiResponse.getThought() != null ? aiResponse.getThought() : "";

        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[CLI] 已收到 " + player.getName() + " 的 AI 响应 (长度: " + response.length() + ")");
        }

        // 如果 response 里面还有 <thought>、<thinking> 或 <think> 标签（API 可能没拆分出来），则继续尝试提取
        java.util.regex.Matcher thoughtMatcher = java.util.regex.Pattern.compile("(?s)<(thought|thinking)>(.*?)</\\1>").matcher(response);
        if (thoughtMatcher.find()) {
            if (thoughtContent.isEmpty()) {
                thoughtContent = thoughtMatcher.group(2);
            }
            response = response.replaceAll("(?s)<(thought|thinking)>.*?</\\1>", "");
        } else {
            // 针对某些模型可能使用 <think></think> 标签
            java.util.regex.Matcher thinkTagMatcher = java.util.regex.Pattern.compile("(?s)<think>(.*?)</think>").matcher(response);
            if (thinkTagMatcher.find()) {
                if (thoughtContent.isEmpty()) {
                    thoughtContent = thinkTagMatcher.group(1);
                }
                response = response.replaceAll("(?s)<think>.*?</think>", "");
            } else {
                // 针对某些模型可能直接在正文中用 Markdown 块或特定标记显示思考过程
                // 尝试匹配 ```thought ... ``` 块
                java.util.regex.Matcher mdThoughtMatcher = java.util.regex.Pattern.compile("(?s)```thought\n?(.*?)\n?```").matcher(response);
                if (mdThoughtMatcher.find()) {
                    if (thoughtContent.isEmpty()) {
                        thoughtContent = mdThoughtMatcher.group(1);
                    }
                    response = response.replaceAll("(?s)```thought\n?.*?\n?```", "");
                }
            }
        }
        
        // 移除 Markdown 风格的 Thought: 块或类似文本
        String cleanResponse = response.replaceAll("(?i)^Thought:.*?\n", "");
        cleanResponse = cleanResponse.replaceAll("(?i)^思考过程:.*?\n", "");
        cleanResponse = cleanResponse.trim();
        
        // 防止误判：如果思考内容与正文相同，说明不是真正的思考过程
        if (!thoughtContent.isEmpty() && thoughtContent.trim().equals(cleanResponse)) {
            thoughtContent = "";
        }

        // 更新 session 中的最后一次思考内容
        String finalThought = thoughtContent.isEmpty() ? null : thoughtContent;
        session.setLastThought(finalThought);

        // 将 AI 的回复加入历史记录，并关联当前的思考内容
        // 原生函数调用：把结构化 tool_calls 渲染回 #tool 文本，保证历史始终是 {role, content}
        List<NativeToolCall> nativeCalls = aiResponse.getToolCalls();
        if (nativeCalls != null && !nativeCalls.isEmpty()) {
            session.addMessage("assistant", ToolRegistry.renderForHistory(cleanResponse, nativeCalls), finalThought);
        } else {
            session.addMessage("assistant", cleanResponse, finalThought);
        }
        
        // 计算思考内容的 Token
        if (!thoughtContent.isEmpty()) {
            String modelName = plugin.getConfigManager().getCloudflareModel();
            int thoughtTokens = DialogueSession.calculateTokens(thoughtContent, modelName);
            session.addThoughtTokens(thoughtTokens);
        }
        
        // 增强的工具调用提取逻辑：寻找第一个处于行首的工具调用
        // 这样可以避免 AI 在回复末尾多加一个 #over 导致前面的主要工具（如 #edit）被忽略
        String content = cleanResponse;
        String toolCall = "";

        // 定义已知工具列表（顺序敏感：startsWith 前缀匹配，长名在前，否则 #edit_global 会被 #edit 劫持）
        List<String> knownTools = KNOWN_TOOLS;

        int currentPos = 0;
        boolean foundTool = false;
        while (currentPos < cleanResponse.length()) {
            int hashIndex = cleanResponse.indexOf("#", currentPos);
            if (hashIndex == -1) break;

            // 只认行首 #，防止 AI 在对话中提到 #tool_name 时误触发
            boolean isValidStart = hashIndex == 0;
            if (!isValidStart) {
                char prev = cleanResponse.charAt(hashIndex - 1);
                isValidStart = prev == '\n' || prev == '\r';
            }

            if (isValidStart) {
                String potentialToolPart = cleanResponse.substring(hashIndex).trim();
                for (String tool : knownTools) {
                    if (potentialToolPart.toLowerCase().startsWith(tool)) {
                        // 提取完整的工具调用，直到遇到换行符或下一个工具
                        String remainingAfterTool = potentialToolPart.substring(tool.length()).trim();

                        // 如果有冒号或空格，提取参数部分
                        if (remainingAfterTool.startsWith(":") || remainingAfterTool.startsWith(" ")) {
                            int splitIndex = remainingAfterTool.startsWith(":") ? 1 : 0;
                            remainingAfterTool = remainingAfterTool.substring(splitIndex).trim();

                            // 对于 JSON 参数（如 #todo: [...] 或 #ask: {...}），需要找到匹配的闭合括号
                            if (remainingAfterTool.startsWith("[") || remainingAfterTool.startsWith("{")) {
                                char openChar = remainingAfterTool.charAt(0);
                                char closeChar = openChar == '[' ? ']' : '}';
                                int bracketDepth = 0;
                                int endIndex = -1;
                                for (int i = 0; i < remainingAfterTool.length(); i++) {
                                    char c = remainingAfterTool.charAt(i);
                                    if (c == openChar) bracketDepth++;
                                    else if (c == closeChar) bracketDepth--;

                                    if (bracketDepth == 0) {
                                        endIndex = i + 1;
                                        break;
                                    }
                                }
                                if (endIndex != -1) {
                                    toolCall = tool + ":" + remainingAfterTool.substring(0, endIndex);
                                } else {
                                    // 没有找到闭合括号，提取到行尾
                                    int lineEnd = remainingAfterTool.indexOf('\n');
                                    if (lineEnd != -1) {
                                        toolCall = tool + ":" + remainingAfterTool.substring(0, lineEnd);
                                    } else {
                                        toolCall = potentialToolPart;
                                    }
                                }
                            } else {
                                // 对于普通参数，提取到行尾或遇到下一个工具
                                int lineEnd = remainingAfterTool.indexOf('\n');
                                int nextToolPos = -1;
                                for (String nextTool : knownTools) {
                                    int pos = remainingAfterTool.toLowerCase().indexOf(nextTool);
                                    if (pos != -1 && (nextToolPos == -1 || pos < nextToolPos)) {
                                        nextToolPos = pos;
                                    }
                                }

                                int paramEnd = lineEnd;
                                if (nextToolPos != -1 && (paramEnd == -1 || nextToolPos < paramEnd)) {
                                    paramEnd = nextToolPos;
                                }

                                if (paramEnd != -1) {
                                    toolCall = tool + ":" + remainingAfterTool.substring(0, paramEnd).trim();
                                } else {
                                    toolCall = potentialToolPart;
                                }
                            }
                        } else {
                            toolCall = tool;
                        }
                        content = cleanResponse.substring(0, hashIndex).trim();
                        foundTool = true;
                        break;
                    }
                }
            }
            if (foundTool) break;
            currentPos = hashIndex + 1;
        }

        // 模型可能把 #run 放在 reasoning_content 而非 content 里
        if (toolCall.isEmpty() && cleanResponse.isEmpty() && !thoughtContent.isEmpty()) {
            // 从 thoughtContent 中重新尝试提取工具调用
            String thoughtToolCall = "";
            int thoughtPos = 0;
            while (thoughtPos < thoughtContent.length()) {
                int hashIndex = thoughtContent.indexOf("#", thoughtPos);
                if (hashIndex == -1) break;

                boolean isValidStart = hashIndex == 0;
                if (!isValidStart) {
                    char prev = thoughtContent.charAt(hashIndex - 1);
                    isValidStart = prev == '\n' || prev == '\r';
                }

                if (isValidStart) {
                    String potentialToolPart = thoughtContent.substring(hashIndex).trim();
                    for (String tool : knownTools) {
                        if (potentialToolPart.toLowerCase().startsWith(tool)) {
                            String remainingAfterTool = potentialToolPart.substring(tool.length()).trim();
                            if (remainingAfterTool.startsWith(":") || remainingAfterTool.startsWith(" ")) {
                                int splitIndex = remainingAfterTool.startsWith(":") ? 1 : 0;
                                remainingAfterTool = remainingAfterTool.substring(splitIndex).trim();
                                if (remainingAfterTool.startsWith("[") || remainingAfterTool.startsWith("{")) {
                                    char openChar = remainingAfterTool.charAt(0);
                                    char closeChar = openChar == '[' ? ']' : '}';
                                    int bracketDepth = 0;
                                    int endIndex = -1;
                                    for (int i = 0; i < remainingAfterTool.length(); i++) {
                                        char c = remainingAfterTool.charAt(i);
                                        if (c == openChar) bracketDepth++;
                                        else if (c == closeChar) bracketDepth--;
                                        if (bracketDepth == 0) {
                                            endIndex = i + 1;
                                            break;
                                        }
                                    }
                                    if (endIndex != -1) {
                                        thoughtToolCall = tool + ":" + remainingAfterTool.substring(0, endIndex);
                                    } else {
                                        int lineEnd = remainingAfterTool.indexOf('\n');
                                        thoughtToolCall = lineEnd != -1
                                            ? tool + ":" + remainingAfterTool.substring(0, lineEnd)
                                            : potentialToolPart;
                                    }
                                } else {
                                    int lineEnd = remainingAfterTool.indexOf('\n');
                                    thoughtToolCall = lineEnd != -1
                                        ? tool + ":" + remainingAfterTool.substring(0, lineEnd)
                                        : potentialToolPart;
                                }
                            } else {
                                thoughtToolCall = tool;
                            }
                            break;
                        }
                    }
                }
                if (!thoughtToolCall.isEmpty()) break;
                thoughtPos = hashIndex + 1;
            }
            if (!thoughtToolCall.isEmpty()) {
                toolCall = thoughtToolCall;
            }
        }

        // 展示 Fancy 内容（流式输出已提前显示时跳过）
        if (!skipDisplay) {
            if (!content.isEmpty()) {
                displayFancyContent(player, content, finalThought);
            } else if (finalThought != null) {
                // 如果只有思考过程而没有正文内容（例如纯工具调用前的思考），也显示思考按钮
                displayFancyContent(player, "", finalThought);
            }
        }

        // 处理工具调用（原生函数调用优先；否则走文本协议提取）
        boolean hasNative = nativeCalls != null && !nativeCalls.isEmpty();
        if (hasNative) {
            // cycle 结束但有下一轮 → 当前 tokens 落 session 并清空计数器
            Long streamedThisCycle = streamedOutputTokens.get(uuid);
            if (streamedThisCycle != null && streamedThisCycle > 0) {
                session.addOutputTokens(streamedThisCycle);
                roundOutputTokens.merge(uuid, streamedThisCycle, (a, b) -> a + b);
            }
            streamedOutputTokens.remove(uuid);
            dispatchNativeCalls(player, session, nativeCalls);
            return;
        }

        if (!toolCall.isEmpty()) {
            // cycle 结束但有下一轮 → 当前 tokens 落 session 并清空计数器
            Long streamedThisCycle = streamedOutputTokens.get(uuid);
            if (streamedThisCycle != null && streamedThisCycle > 0) {
                session.addOutputTokens(streamedThisCycle);
                roundOutputTokens.merge(uuid, streamedThisCycle, (a, b) -> a + b);
            }
            streamedOutputTokens.remove(uuid);
            executeTool(player, toolCall);
        } else {
            // 检查响应是否被截断
            if (aiResponse.isTruncated()) {
                // 显示截断提示
                player.sendMessage(I18n.t("clim.truncated"));
                // 自动继续生成（streamedOutputTokens 不清除，延续到下一轮）
                continueGeneration(player, session);
            } else {
                // 本轮输出完全结束：累积的流式 token 写回 session
                Long streamedTotal = streamedOutputTokens.get(uuid);
                if (streamedTotal != null && streamedTotal > 0) {
                    session.addOutputTokens(streamedTotal);
                    roundOutputTokens.merge(uuid, streamedTotal, (a, b) -> a + b);
                }
                streamedOutputTokens.remove(uuid);

                isGenerating.put(uuid, false);
                generationStates.put(uuid, GenerationStatus.COMPLETED);
                checkTokenWarning(player, session);
                autoCompressContext(player, session);
            }

            // 每次 AI 回复后保存会话到磁盘
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                saveSessionToHistory(uuid, session);
            });
        }
    }

    /**
     * 剥离 AI 响应中的思考块（<thought>/<think>/```thought``` / Thought: 前缀），返回纯正文。
     */
    private static String stripThoughtContent(String response) {
        if (response == null || response.isEmpty()) {
            return response;
        }
        String clean = response;
        java.util.regex.Matcher thoughtMatcher = java.util.regex.Pattern.compile("(?s)<(thought|thinking)>(.*?)</\\1>").matcher(clean);
        if (thoughtMatcher.find()) {
            clean = clean.replaceAll("(?s)<(thought|thinking)>.*?</\\1>", "");
        } else {
            java.util.regex.Matcher thinkTagMatcher = java.util.regex.Pattern.compile("(?s)<think>(.*?)</think>").matcher(clean);
            if (thinkTagMatcher.find()) {
                clean = clean.replaceAll("(?s)<think>.*?</think>", "");
            } else {
                java.util.regex.Matcher mdThoughtMatcher = java.util.regex.Pattern.compile("(?s)```thought\n?(.*?)\n?```").matcher(clean);
                if (mdThoughtMatcher.find()) {
                    clean = clean.replaceAll("(?s)```thought\n?.*?\n?```", "");
                }
            }
        }
        clean = clean.replaceAll("(?i)^Thought:.*?\n", "");
        clean = clean.replaceAll("(?i)^思考过程:.*?\n", "");
        return clean.trim();
    }

    /**
     * 从AI响应中提取全部工具调用（文本协议多工具支持）。
     * 与 extractToolCall 的解析规则一致，但收集所有合法 #tool 而非遇到第一个就返回。
     * @return 工具调用列表，可能为空
     */
    private static List<String> extractToolCalls(String response) {
        String cleanResponse = stripThoughtContent(response);
        List<String> calls = new java.util.ArrayList<>();
        if (cleanResponse == null || cleanResponse.isEmpty()) {
            return calls;
        }
        List<String> knownTools = KNOWN_TOOLS;

        int currentPos = 0;
        while (currentPos < cleanResponse.length()) {
            int hashIndex = cleanResponse.indexOf("#", currentPos);
            if (hashIndex == -1) break;

            // 只认行首 #，防止 AI 在对话中提到 #tool_name 时误触发
            boolean isValidStart = hashIndex == 0;
            if (!isValidStart) {
                char prev = cleanResponse.charAt(hashIndex - 1);
                isValidStart = prev == '\n' || prev == '\r';
            }

            if (isValidStart) {
                String potentialToolPart = cleanResponse.substring(hashIndex).trim();
                boolean matched = false;
                for (String tool : knownTools) {
                    if (potentialToolPart.toLowerCase().startsWith(tool)) {
                        String remainingAfterTool = potentialToolPart.substring(tool.length()).trim();

                        if (remainingAfterTool.startsWith(":") || remainingAfterTool.startsWith(" ")) {
                            int splitIndex = remainingAfterTool.startsWith(":") ? 1 : 0;
                            remainingAfterTool = remainingAfterTool.substring(splitIndex).trim();

                            if (remainingAfterTool.startsWith("[") || remainingAfterTool.startsWith("{")) {
                                char openChar = remainingAfterTool.charAt(0);
                                char closeChar = openChar == '[' ? ']' : '}';
                                int bracketDepth = 0;
                                int endIndex = -1;
                                for (int i = 0; i < remainingAfterTool.length(); i++) {
                                    char c = remainingAfterTool.charAt(i);
                                    if (c == openChar) bracketDepth++;
                                    else if (c == closeChar) bracketDepth--;

                                    if (bracketDepth == 0) {
                                        endIndex = i + 1;
                                        break;
                                    }
                                }
                                if (endIndex != -1) {
                                    calls.add(tool + ":" + remainingAfterTool.substring(0, endIndex));
                                    currentPos = hashIndex + tool.length() + endIndex;
                                    matched = true;
                                    break;
                                } else {
                                    int lineEnd = remainingAfterTool.indexOf('\n');
                                    if (lineEnd != -1) {
                                        calls.add(tool + ":" + remainingAfterTool.substring(0, lineEnd));
                                        currentPos = hashIndex + tool.length() + lineEnd;
                                        matched = true;
                                        break;
                                    } else {
                                        calls.add(tool + ":" + remainingAfterTool.trim());
                                        currentPos = hashIndex + tool.length() + remainingAfterTool.length();
                                        matched = true;
                                        break;
                                    }
                                }
                            } else {
                                int lineEnd = remainingAfterTool.indexOf('\n');
                                int nextToolPos = -1;
                                for (String nextTool : knownTools) {
                                    int pos = remainingAfterTool.toLowerCase().indexOf(nextTool);
                                    if (pos != -1 && (nextToolPos == -1 || pos < nextToolPos)) {
                                        nextToolPos = pos;
                                    }
                                }

                                int paramEnd = lineEnd;
                                if (nextToolPos != -1 && (paramEnd == -1 || nextToolPos < paramEnd)) {
                                    paramEnd = nextToolPos;
                                }

                                if (paramEnd != -1) {
                                    calls.add(tool + ":" + remainingAfterTool.substring(0, paramEnd).trim());
                                    currentPos = hashIndex + tool.length() + paramEnd;
                                } else {
                                    // 最后一行（无换行、无后续工具）：规范化去掉冒号后空格，与其它分支一致
                                    calls.add(tool + ":" + remainingAfterTool.trim());
                                    currentPos = hashIndex + tool.length() + remainingAfterTool.length();
                                }
                                matched = true;
                                break;
                            }
                        } else {
                            calls.add(tool);
                            currentPos = hashIndex + tool.length();
                            matched = true;
                            break;
                        }
                    }
                }
                if (!matched) {
                    currentPos = hashIndex + 1;
                }
            } else {
                currentPos = hashIndex + 1;
            }
        }
        return calls;
    }

    /**
     * 文本协议多工具分发：多个 #tool 走串行批次（复用原生 FC 批次屏障），单个保持原路径。
     * 批次中的每个工具反馈由 feedbackToAI 拦截推进；批次终结时合并结果一次重入模型。
     * 不可批工具（end/exit/start/未知工具/YOLO 风险 run）回灌模型告知，绝不静默丢弃。
     */
    private void dispatchTextTools(Player player, DialogueSession session, List<String> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        if (toolCalls.size() == 1) {
            if (session != null) {
                session.setPendingBatchDropNote(null);
            }
            executeTool(player, toolCalls.get(0));
            return;
        }
        if (session == null) {
            executeTool(player, toolCalls.get(0));
            return;
        }

        // 划分可批 / 不可批（YOLO 风险 run 渲染确认按钮等待玩家，不入批）
        List<String> batchable = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        for (String tc : toolCalls) {
            if (isTextToolBatchable(tc, session)) {
                batchable.add(tc);
            } else {
                excluded.add(tc);
            }
        }
        String droppedNote = buildTextDroppedNote(excluded);

        if (batchable.isEmpty()) {
            // 全部不可批 → 不执行任何工具，直接回灌模型告知未执行
            plugin.getLogger().warning("[CLI] 文本批次全部不可批，回灌模型未执行项: " + player.getName());
            session.setPendingBatchDropNote(null);
            invokeModelAfterFeedback(player, session, droppedNote);
            return;
        }

        // 重置批次状态后写入本次未执行项（clearBatchState 会清掉历史遗留 note）
        session.clearBatchState();
        noteDroppedCalls(session, droppedNote);

        if (batchable.size() == 1) {
            executeTool(player, batchable.get(0));
            return;
        }

        session.setBatchInProgress(true);
        for (String tc : batchable) {
            session.pushPendingNativeTool(tc);
        }
        executeNativeBatch(player, session);
    }

    /**
     * 文本工具是否可进入串行批次：批次安全集内，且非 YOLO 风险 run。
     */
    private boolean isTextToolBatchable(String tc, DialogueSession session) {
        if (tc == null || session == null) {
            return false;
        }
        String tool = extractTextToolName(tc);
        if (tool == null || !isBatchSafeTool(tool, session.getMode())) {
            return false;
        }
        if (session.getMode() == DialogueSession.Mode.YOLO && "run".equals(tool)) {
            int colon = tc.indexOf(':');
            String cmd = (colon != -1 ? tc.substring(colon + 1) : "").trim();
            if (ToolExecutor.isRiskyCommandPublic(cmd, plugin.getConfigManager().getYoloRiskCommands())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 从 #tool: 文本中提取工具名（无 # 前缀）；非法格式返回 null。
     */
    static String extractTextToolName(String tc) {
        if (tc == null) {
            return null;
        }
        String trimmed = tc.trim();
        if (!trimmed.startsWith("#")) {
            return null;
        }
        int colon = trimmed.indexOf(':');
        String name = (colon > 0 ? trimmed.substring(0, colon) : trimmed);
        if (name.length() <= 1) {
            return null;
        }
        return name.substring(1).toLowerCase();
    }

    /**
     * 构建文本协议批次中未执行调用的回灌说明（无未执行项返回 null）。
     */
    private static String buildTextDroppedNote(List<String> excluded) {
        if (excluded == null || excluded.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("#error: 混合批次无法串行执行，以下工具调用未执行，请重新发起：");
        for (int i = 0; i < excluded.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            String name = extractTextToolName(excluded.get(i));
            sb.append(name != null ? name : excluded.get(i));
        }
        return sb.toString();
    }

    /**
     * 继续生成被截断的响应
     */
    private void continueGeneration(Player player, DialogueSession session) {
        UUID uuid = player.getUniqueId();
        
        // 设置生成状态
        isGenerating.put(uuid, true);
        generationStates.put(uuid, GenerationStatus.THINKING);
        generationStartTimes.putIfAbsent(uuid, System.currentTimeMillis());

        // 添加一个提示消息，让AI知道继续生成
        session.addMessage("user", "请继续生成剩余的内容");
        
        // 异步调用 AI 继续生成
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // 设置重试回调，向玩家显示重试提示
            ai.setRetryCallback((statusCode, retryMessage) -> {
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        TextComponent retryMsg;
                        if (statusCode == 429) {
                            // 429 错误使用黄色⁕ 白色
                            retryMsg = new TextComponent(ChatColor.YELLOW + "⁕ " + ChatColor.WHITE + retryMessage);
                        } else {
                            // 其他错误使用灰色⁕
                            retryMsg = new TextComponent(ChatColor.GRAY + "⁕ " + retryMessage);
                        }
                        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.CHAT, retryMsg);
                    }
                });
            });
            
            try {
                // continueGeneration 不需要匹配新 Skills，使用空列表
                AIResponse response = ai.chat(player, session, promptManager.getSystemPromptForSession(player, Collections.emptyList(), session.getMode(),
                        "", isNativeActiveForPrompt(player, session)));
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    handleAIResponse(player, response);
                });
            } catch (IOException e) {
                plugin.getCloudErrorReport().report(e);
                plugin.getLogger().warning("[CLI] 继续生成失败 - " + player.getName() + ": " + e);
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    TextComponent fullMsg = buildErrorText(e.getMessage(), "继续生成失败");
                    fullMsg.addExtra(buildRetryButton(e.getMessage()));
                    player.spigot().sendMessage(fullMsg);

                    isGenerating.put(uuid, false);
                    generationStates.put(uuid, GenerationStatus.ERROR);
                    generationStartTimes.remove(uuid);
                });
            } finally {
                // 清除重试回调
                ai.clearRetryCallback();
            }
        });
    }

    private void checkTokenWarning(Player player, DialogueSession session) {
        int estimatedTokens = calculateTotalEstimatedTokens(player, session);
        int maxTokens = plugin.getConfigManager().getContextWindowLimit();
        int remaining = maxTokens - estimatedTokens;

        if (remaining < plugin.getConfigManager().getContextWindowWarningThreshold()) {
            player.sendMessage(I18n.t("clim.context.short"));
        }
    }

    /**
     * 手动触发上下文压缩（/cli compact）
     */
    public void compactContext(Player player) {
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);
        if (session == null) {
            player.sendMessage(I18n.t("clim.no.active.session"));
            return;
        }
        if (session.getHistory().size() <= 12) {
            player.sendMessage(I18n.t("clim.compact.not.needed"));
            return;
        }
        player.sendMessage(I18n.t("clim.compacting"));
        autoCompressContext(player, session, true);
    }

    /**
     * CC 风格自动上下文压缩：接近上下文窗口上限时，用 AI 总结旧消息，保留最近对话
     */
    private void autoCompressContext(Player player, DialogueSession session) {
        autoCompressContext(player, session, false);
    }

    private void autoCompressContext(Player player, DialogueSession session, boolean force) {
        int estimatedTokens = calculateTotalEstimatedTokens(player, session);
        int maxTokens = plugin.getConfigManager().getContextWindowLimit();
        double threshold = 0.8;
        int keepRecent = 15;

        if (!force && estimatedTokens <= maxTokens * threshold) return;
        if (session.getHistory().size() <= keepRecent + 2) return; // 太少无法压缩

        int oldCount = session.getHistory().size() - keepRecent;
        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[CLI] 触发自动压缩 - Token: " + estimatedTokens + "/" + maxTokens + ", 压缩 " + oldCount + " 条旧消息");
        }

        // 异步执行压缩，不阻塞主线程
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                // 检查是否有之前的压缩摘要
                boolean hasOldSummary = false;
                String oldSummaryText = null;
                if (oldCount > 0) {
                    DialogueSession.Message first = session.getHistory().get(0);
                    if ("system".equals(first.getRole()) && first.getContent() != null
                            && !first.getContent().isEmpty()
                            && !first.getContent().startsWith("[Skill Reference:")) {
                        hasOldSummary = true;
                        oldSummaryText = first.getContent().length() > 300 ? first.getContent().substring(0, 300) + "..." : first.getContent();
                    }
                }

                // 序列化旧消息，只保留 role + content，过滤掉思考内容
                StringBuilder sb = new StringBuilder();
                int serializeStart = hasOldSummary ? 1 : 0; // 跳过旧摘要，单独处理
                for (int i = serializeStart; i < oldCount && i < session.getHistory().size(); i++) {
                    DialogueSession.Message msg = session.getHistory().get(i);
                    String role = msg.getRole();
                    String content = msg.getContent();
                    if (content == null || content.trim().isEmpty()) continue;
                    // 不截断：上下文完整传入压缩模型（压缩就是为长上下文设计的；
                    // 截断会让工具调用/长回复的信息丢失，压缩结果残缺）。
                    // 若压缩模型上下文窗口不够会由 API 报错，届时再按需设上限。
                    sb.append(role).append(": ").append(content).append("\n\n");
                }

                if (plugin.getConfigManager().isDebug()) {
                    String inputPreview = sb.length() > 800 ? sb.substring(0, 800) + "..." : sb.toString();
                    plugin.getLogger().info("[CLI] 压缩输入 (" + oldCount + " 条消息, " + sb.length() + " 字符):\n" + inputPreview);
                    // 逐条列出 role+长度：直接看到工具调用消息（assistant 含 #tool / user 含 #run_result）
                    // 有没有算进压缩、被截断多少（定位"工具调用上下文缺失"用）
                    StringBuilder roles = new StringBuilder();
                    int listed = 0;
                    for (int i = serializeStart; i < oldCount && i < session.getHistory().size(); i++) {
                        DialogueSession.Message m = session.getHistory().get(i);
                        if (m.getContent() == null || m.getContent().trim().isEmpty()) continue;
                        if (listed > 0) roles.append(", ");
                        roles.append(m.getRole()).append("(").append(m.getContent().length()).append(")");
                        listed++;
                    }
                    plugin.getLogger().info("[CLI] 压缩输入逐条 [" + listed + " 条]: " + roles);
                }

                StringBuilder fullPrompt = new StringBuilder();
                // 如果有旧摘要，要求模型必须保留其内容，只在新消息基础上补充
                if (hasOldSummary && oldSummaryText != null) {
                    fullPrompt.append("以下是上一次压缩的摘要，你必须保留这些信息，并在此基础上添加新的内容：\n")
                        .append(oldSummaryText).append("\n\n");
                }
                fullPrompt.append("需要压缩的对话：\n\n").append(sb);

                String systemPrompt = "你的任务：阅读对话历史，逐条列出所有用户提出的需求、问题和未完成的工作。"
                        + "不要遗漏任何信息，包括已执行的工具调用、用户反馈、搜索查询等。"
                        + "输出 JSON: {\"summary\":\"对话概要\",\"needs\":[\"用户提出的每个需求/问题，逐条列出\",\"不要遗漏\"],\"done\":[\"已完成的事项\"],\"pending\":[\"待办事项\"]}";

                String compactPrompt = fullPrompt.toString();

                String summary = ai.chatWithCompressionModel(systemPrompt, compactPrompt);

                if (plugin.getConfigManager().isDebug()) {
                    String outputPreview = summary != null && summary.length() > 500 ? summary.substring(0, 500) + "..." : (summary != null ? summary : "null");
                    plugin.getLogger().info("[CLI] 压缩模型原始返回:\n" + outputPreview);
                }

                if (summary == null || summary.trim().isEmpty()) {
                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().warning("[CLI] 压缩模型返回空结果，跳过压缩");
                    }
                    return;
                }

                // 在主线程中替换历史
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled() || !player.isOnline()) return;
                    DialogueSession currentSession = sessions.get(player.getUniqueId());
                    if (currentSession == null || currentSession != session) return;

                    // 尝试解析 JSON，失败则用原始文本
                    String compactText;
                    boolean jsonParseSuccess = false;
                    try {
                        Gson gson = new Gson();
                        JsonObject json = gson.fromJson(summary.trim(), JsonObject.class);
                        StringBuilder pretty = new StringBuilder();
                        if (json.has("summary")) pretty.append(json.get("summary").getAsString()).append("\n");
                        if (json.has("needs")) {
                            JsonArray arr = json.getAsJsonArray("needs");
                            if (arr.size() > 0) {
                                pretty.append("Needs:\n");
                                for (int i = 0; i < arr.size(); i++) pretty.append("- ").append(arr.get(i).getAsString()).append("\n");
                            }
                        }
                        if (json.has("done")) {
                            JsonArray arr = json.getAsJsonArray("done");
                            if (arr.size() > 0) {
                                pretty.append("Done:\n");
                                for (int i = 0; i < arr.size(); i++) pretty.append("- ").append(arr.get(i).getAsString()).append("\n");
                            }
                        }
                        if (json.has("pending")) {
                            JsonArray arr = json.getAsJsonArray("pending");
                            if (arr.size() > 0) {
                                pretty.append("Pending:\n");
                                for (int i = 0; i < arr.size(); i++) pretty.append("- ").append(arr.get(i).getAsString()).append("\n");
                            }
                        }
                        compactText = pretty.toString().trim();
                        jsonParseSuccess = true;
                    } catch (Exception e) {
                        compactText = summary.trim();
                    }

                    if (plugin.getConfigManager().isDebug()) {
                        plugin.getLogger().info("[CLI] JSON 解析" + (jsonParseSuccess ? "成功" : "失败，使用原始文本") + ", 最终摘要长度: " + compactText.length());
                    }

                    List<DialogueSession.Message> newHistory = new ArrayList<>();
                    newHistory.add(new DialogueSession.Message("system", compactText));
                    int startIdx = Math.max(0, session.getHistory().size() - keepRecent);
                    for (int i = startIdx; i < session.getHistory().size(); i++) {
                        newHistory.add(session.getHistory().get(i));
                    }

                    session.replaceHistory(newHistory);

                    // 异步保存到磁存
                    UUID uuid = player.getUniqueId();
                    Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                        saveSessionToHistory(uuid, session);
                    });

                    player.sendMessage(ColorUtil.translateCustomColors(
                            "§zFancyHelper§b§r §7> §f上下文已压缩，保留了最近 " + keepRecent + " 条对话"));

                    if (plugin.getConfigManager().isDebug()) {
                        String historyPreview = session.getHistory().stream()
                            .limit(1)
                            .map(m -> m.getContent())
                            .filter(c -> c != null)
                            .findFirst().orElse("");
                        if (historyPreview.length() > 300) historyPreview = historyPreview.substring(0, 300) + "...";
                        plugin.getLogger().info("[CLI] 自动压缩完成 - 新历史大小: " + session.getHistory().size() + " 条, 首条摘要:\n" + historyPreview);
                    }
                });
            } catch (Exception e) {
                plugin.getLogger().warning("[CLI] 自动压缩失败: " + e.getMessage());
                if (plugin.getConfigManager().isDebug()) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * 计算当前会话的预计总 Token 数（包括 System Prompt 和历史记录）
     */
    private int calculateTotalEstimatedTokens(Player player, DialogueSession session) {
        String modelName = plugin.getConfigManager().getCloudflareModel();

        // 1. 计算 System Prompt Token（使用空列表，估算最大 Token 数）
        // 多条独立 system 消息，每条都有 role + per-message 开销
        List<String> systemPromptParts = promptManager.getBaseSystemPrompt(player, Collections.emptyList());
        int systemPromptTokens = 0;
        for (String part : systemPromptParts) {
            systemPromptTokens += DialogueSession.calculateTokens(part, modelName);
            systemPromptTokens += DialogueSession.calculateTokens("system", modelName);
            systemPromptTokens += 3; // per-message overhead
        }

        // 2. 获取历史记录 Token
        int historyTokens = session.getEstimatedTokens(modelName);
        
        // 3. 回复引导 (Reply Primer): <|im_start|>assistant\n
        int replyPrimerTokens = 3;

        return systemPromptTokens + historyTokens + replyPrimerTokens;
    }

    /**
     * 获取当前生效的主模型名（镜像 LLMClient.chat 的分发逻辑）。
     * 用于判断该模型是否启用原生函数调用（决定提示词是否用精简工具列表）。
     */
    private String getEffectiveModel() {
        if (plugin.getConfigManager().isFancyConsoleAi()) {
            return plugin.getConfigManager().getFancyModel();
        }
        if ("openai".equalsIgnoreCase(plugin.getConfigManager().getProvider())) {
            return plugin.getConfigManager().getOpenAiModel();
        }
        return plugin.getConfigManager().getCloudflareModel();
    }

    /** 当前会话是否处于原生函数调用模式（开关开启 && 模型支持 && 未降级）。 */
    private boolean isNativeActiveForPrompt(Player player, DialogueSession session) {
        if (session == null || session.isNativeToolsDegraded()) {
            return false;
        }
        return ToolRegistry.isNativeActiveForModel(
                plugin.getConfigManager().isNativeToolCallingEnabled(), getEffectiveModel());
    }

    private void executeTool(Player player, String toolCall) {
        executeTool(player, toolCall, false);
    }

    private void executeTool(Player player, String toolCall, boolean force) {
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);
        if (session == null) return;

        // --- 防死循环检测逻辑 ---
        if (session.isAntiLoopExempted()) {
            // 已豁免，仅记录
            session.addToolCall(toolCall);
        } else {
            List<String> toolHistory = session.getToolCallHistory();
            int thresholdCount = plugin.getConfigManager().getAntiLoopThresholdCount();
            double similarityThreshold = plugin.getConfigManager().getAntiLoopSimilarityThreshold();
            int maxChainCount = plugin.getConfigManager().getAntiLoopMaxChainCount();

            // 1. 连续相似调用检测
            if (toolHistory.size() >= thresholdCount - 1) {
                int similarCount = 1; // 当前这次调用算作第 1 个
                for (int i = toolHistory.size() - 1; i >= 0 && similarCount < thresholdCount; i--) {
                    double similarity = calculateSimilarity(toolCall, toolHistory.get(i));
                    if (similarity >= similarityThreshold) {
                        similarCount++;
                    } else {
                        break; // 必须是连续的
                    }
                }

                if (similarCount >= thresholdCount) {
                    plugin.getLogger().warning("[CLI] 检测到 " + player.getName() + " 的潜在死循环: 连续 " + thresholdCount + " 次相似的工具调用。");
                    
                    // 仍然显示本次工具调用
                    String toolName = toolCall.split(":", 2)[0];
                    String args = toolCall.contains(":") ? toolCall.split(":", 2)[1] : "";
                    if ("#webfetch".equals(toolName)) {
                        player.sendMessage(I18n.t("clim.tool.call", args.trim()));
                    } else {
                        player.sendMessage(I18n.t("clim.tool.call", toolName + (args.isEmpty() ? "" : " " + args)));
                    }

                    player.sendMessage(I18n.t("clim.loop.detected"));
                    
                    // 显示“不再打断”按钮
                    TextComponent exemptMsg = new TextComponent(I18n.t("clim.loop.interrupted"));
                    TextComponent btn = new TextComponent(I18n.t("clim.loop.btn"));
                    btn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli exempt_anti_loop"));
                    btn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.loop.hover"))));
                    exemptMsg.addExtra(btn);
                    player.spigot().sendMessage(exemptMsg);
                    
                    interruptedToolCalls.put(uuid, toolCall);
                    isGenerating.put(uuid, false);
                    generationStates.put(uuid, GenerationStatus.CANCELLED);
                    generationStartTimes.remove(uuid);
                    player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new TextComponent(""));
                    return;
                }
            }

            // 2. 连续调用次数上限检测
            if (session.getCurrentChainToolCount() >= maxChainCount) {
                plugin.getLogger().warning("[CLI] 检测到 " + player.getName() + " 的工具链过长: 连续 " + session.getCurrentChainToolCount() + " 次工具调用。");
                
                // 仍然显示本次工具调用
                String toolName = toolCall.split(":", 2)[0];
                String args = toolCall.contains(":") ? toolCall.split(":", 2)[1] : "";
                if ("#webfetch".equals(toolName)) {
                    player.sendMessage(ChatColor.GOLD + "⇒ Fancy 尝试调用: " + ChatColor.WHITE + args.trim());
                } else {
                    player.sendMessage(ChatColor.GOLD + "⇒ Fancy 尝试调用: " + ChatColor.WHITE + toolName + (args.isEmpty() ? "" : " " + args));
                }

                player.sendMessage(I18n.t("clim.chain.long", maxChainCount));
                
                // 显示“不再打断”按钮
                TextComponent exemptMsg = new TextComponent(I18n.t("clim.chain.continue"));
                TextComponent btn = new TextComponent(I18n.t("clim.chain.btn"));
                btn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli exempt_anti_loop"));
                btn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.loop.hover"))));
                exemptMsg.addExtra(btn);
                player.spigot().sendMessage(exemptMsg);
                
                interruptedToolCalls.put(uuid, toolCall);
                isGenerating.put(uuid, false);
                generationStates.put(uuid, GenerationStatus.COMPLETED);
                generationStartTimes.remove(uuid);
                player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new TextComponent(""));
                return;
            }
            
            // 记录本次工具调用
            session.addToolCall(toolCall);
        }
        // --- 检测逻辑结束 ---

        // 如果该工具之前被中断过且现在继续执行，清除记录
        interruptedToolCalls.remove(uuid);

        // 委托给 ToolExecutor 执行。同步异常在此兜底（executeNativeBatch 的 catch 是双保险）：
        // 避免单工具路径（processAIMessage 等）工具崩溃后无反馈，对话卡在 EXECUTING_TOOL。
        boolean toolSuccess;
        Throwable failure = null;
        try {
            toolSuccess = toolExecutor.executeTool(player, toolCall, session, force);
        } catch (Throwable t) {
            plugin.getCloudErrorReport().report(t);
            toolSuccess = false;
            failure = t;
        }

        if (session != null) {
            if (toolSuccess) {
                session.incrementToolSuccess();
            } else {
                session.incrementToolFailure();
            }
        }

        // 异常兜底反馈：告知 AI 执行失败（批次路径被批次屏障拦截合并，单路径直接触发模型重入）
        if (failure != null) {
            if (session != null) {
                session.setLastError("工具执行异常: " + failure.getMessage());
            }
            feedbackToAI(player, "#error: 工具执行异常 - " + failure.getMessage());
        }
    }

    public void feedbackToAI(Player player, String feedback) {
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);
        if (session == null) return;

        // === 串行批量拦截：不重入模型，累计结果并推进下一工具 ===
        if (session.isBatchInProgress()) {
            session.addPendingToolResult(feedback);
            executeNativeBatch(player, session);
            return;
        }

        invokeModelAfterFeedback(player, session, feedback);
    }

    /**
     * 工具反馈后触发一次真实的模型重入（单工具路径与批次终结共用）。
     * 顺序与原 feedbackToAI 单路径完全一致：addMessage → token 估算日志 → 状态翻转 → 异步重入。
     */
    private void invokeModelAfterFeedback(Player player, DialogueSession session, String feedback) {
        UUID uuid = player.getUniqueId();

        // 混合批次中被剔除的调用：随本次反馈一并回灌模型，绝不静默丢弃
        String dropNote = session != null ? session.getPendingBatchDropNote() : null;
        final String effectiveFeedback;
        if (dropNote != null && !dropNote.isEmpty()) {
            effectiveFeedback = (feedback == null || feedback.isEmpty())
                    ? dropNote : feedback + "\n" + dropNote;
            session.setPendingBatchDropNote(null);
        } else {
            effectiveFeedback = feedback;
        }

        session.addMessage("user", effectiveFeedback);

        // 记录反馈后的 Token 估算
        int estimatedTokens = calculateTotalEstimatedTokens(player, session);
        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[CLI] Feedback added. Session size: " + session.getHistory().size() + ", Estimated Tokens for next request: " + estimatedTokens);
        }

        isGenerating.put(uuid, true);
        generationStates.put(uuid, GenerationStatus.THINKING);
        generationStartTimes.putIfAbsent(uuid, System.currentTimeMillis());

        // 工具返回信息不显示给玩家，仅在日志记录并触发 AI 思考
        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[CLI] Feedback sent to AI for " + player.getName() + ": " + effectiveFeedback);
        }

        // 异步调用 AI，不显示 "Thought..." 提示，因为这是后台自动反馈
        if (!plugin.isEnabled()) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            // feedbackToAI 不需要匹配新 Skills，使用空列表
            final List<String> systemPrompt = promptManager.getSystemPromptForSession(player, Collections.emptyList(), session.getMode(),
                    "", isNativeActiveForPrompt(player, session));
            
            // 设置重试回调，向玩家显示重试提示
            ai.setRetryCallback((statusCode, retryMessage) -> {
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (player.isOnline()) {
                        TextComponent retryMsg;
                        if (statusCode == 429) {
                            // 429 错误使用黄色⁕ 白色
                            retryMsg = new TextComponent(ChatColor.YELLOW + "⁕ " + ChatColor.WHITE + retryMessage);
                        } else {
                            // 其他错误使用灰色⁕
                            retryMsg = new TextComponent(ChatColor.GRAY + "⁕ " + retryMessage);
                        }
                        player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.CHAT, retryMsg);
                    }
                });
            });
            
            try {
                if (plugin.getConfigManager().isPlayerStreamingEnabled(player)) {
                    // === 流式输出：工具反馈触发的 AI 回复 ===
                    StreamingHandler streamingHandler = new StreamingHandler(plugin, player);
                    activeStreamingHandlers.put(uuid, streamingHandler);
                    final long reservedMessageId = session.getNextMessageId();

                    final StringBuilder fullResponseText = new StringBuilder();
                    final StringBuilder accumulatedText = new StringBuilder();
                    final String[] lastFormatted = {""};
                    final boolean[] isFirstLine = {true};
                    final boolean[] responseHandled = {false};

                    streamingHandler.setOnReasoningCallback((reasoningChunk) -> {
                        if (reasoningChunk == null || reasoningChunk.isEmpty()) return;
                        streamedOutputTokens.put(uuid, streamedOutputTokens.getOrDefault(uuid, 0L)
                            + DialogueSession.calculateTokens(reasoningChunk));
                    });

                    // 如果 API 返回了真实 token 用量，替换本地估算值
                    streamingHandler.setOnUsageTokens((inputTokens, outputTokens) -> {
                        streamedOutputTokens.put(uuid, outputTokens);
                        if (session != null) {
                            session.addInputTokens(inputTokens);
                            session.addOutputTokens(outputTokens);
                        }
                    });

                    // 上下文缓存命中统计：写入会话对话日志（不刷服务器控制台）
                    streamingHandler.setOnCacheStats((cacheHit, cacheMiss) -> {
                        if (session == null) return;
                        long total = cacheHit + cacheMiss;
                        long pct = total > 0 ? cacheHit * 100 / total : 0;
                        session.appendLog("CACHE", "本次请求 prompt=" + session.getEstimatedTokens()
                            + " 缓存命中=" + cacheHit + " (" + pct + "%) 未命中=" + cacheMiss);
                    });

                    // 思考结束回调：在工具反馈流中也显示思考按钮
                    streamingHandler.setOnReasoningCompleteCallback((thinkingTimeMs) -> {
                        if (!plugin.isEnabled() || !player.isOnline()) return;
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) return;
                            String currentThought = streamingHandler.getThoughtContent();
                            if (currentThought == null || currentThought.trim().isEmpty()) return;

                            session.setLastThought(currentThought);
                            session.addThinkingTime(thinkingTimeMs);

                            TextComponent thoughtBtn = new TextComponent(ChatColor.GRAY + " ○ Thought");
                            thoughtBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli thought t:" + reservedMessageId));
                            thoughtBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.thought.hover"))));
                            double sec = thinkingTimeMs / 1000.0;
                            TextComponent timeTag = new TextComponent(ChatColor.DARK_GRAY + " (" + String.format("%.1f", sec) + "s)");
                            thoughtBtn.addExtra(timeTag);
                            player.spigot().sendMessage(thoughtBtn);
                        });
                    });

                    streamingHandler.setOnChunkCallback((chunk) -> {
                        if (!plugin.isEnabled() || !player.isOnline()) return;
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline() || !isGenerating.getOrDefault(uuid, false)) return;
                            accumulatedText.append(chunk);

                            // 实时累计流式输出 Token
                            streamedOutputTokens.put(uuid, streamedOutputTokens.getOrDefault(uuid, 0L)
                                + DialogueSession.calculateTokens(chunk));

                            String safeText = stripIncompleteFormatting(accumulatedText.toString());
                            String formatted = convertMarkdownBoldToMinecraft(safeText);
                            formatted = ColorUtil.translateCustomColors(formatted);
                            int commonPrefix = 0;
                            int minLen = Math.min(lastFormatted[0].length(), formatted.length());
                            while (commonPrefix < minLen && lastFormatted[0].charAt(commonPrefix) == formatted.charAt(commonPrefix)) {
                                commonPrefix++;
                            }
                            String newContent = formatted.substring(commonPrefix);
                            lastFormatted[0] = formatted;
                            if (newContent.isEmpty()) return;
                            String[] lines = newContent.split("\n", -1);
                            for (int i = 0; i < lines.length; i++) {
                                String line = lines[i];
                                boolean isLastLine = (i == lines.length - 1);
                                if (isFirstLine[0]) {
                                    if (!line.isEmpty() || !isLastLine) {
                                        player.sendMessage(ChatColor.WHITE + "◆ " + line);
                                        isFirstLine[0] = false;
                                    }
                                } else {
                                    if (!line.isEmpty() || !isLastLine) {
                                        player.sendMessage(ChatColor.WHITE + "  " + line);
                                    }
                                }
                            }
                        });
                    });

                    streamingHandler.setOnCompleteCallback((completeText) -> {
                        if (responseHandled[0]) return;
                        responseHandled[0] = true;
                        fullResponseText.append(completeText);
                        activeStreamingHandlers.remove(uuid);

                        if (!plugin.isEnabled()) return;
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!player.isOnline()) return;
                            // flush remaining undisplayed text
                            String formatted = convertMarkdownBoldToMinecraft(accumulatedText.toString());
                            formatted = ColorUtil.translateCustomColors(formatted);
                            int commonPrefix = 0;
                            int minLen = Math.min(lastFormatted[0].length(), formatted.length());
                            while (commonPrefix < minLen && lastFormatted[0].charAt(commonPrefix) == formatted.charAt(commonPrefix)) {
                                commonPrefix++;
                            }
                            String remaining = formatted.substring(commonPrefix).trim();
                            if (!remaining.isEmpty()) {
                                String[] lines = remaining.split("\n", -1);
                                for (int i = 0; i < lines.length; i++) {
                                    String line = lines[i];
                                    boolean isLastLine = (i == lines.length - 1);
                                    if (isFirstLine[0]) {
                                        if (!line.isEmpty() || !isLastLine) {
                                            player.sendMessage(ChatColor.WHITE + "◆ " + line);
                                            isFirstLine[0] = false;
                                        }
                                    } else {
                                        if (!line.isEmpty() || !isLastLine) {
                                            player.sendMessage(ChatColor.WHITE + "  " + line);
                                        }
                                    }
                                }
                            }
                            String thought = streamingHandler.getThoughtContent();

                            // 流式模式也显示思考按钮（仅当 reasoning-complete 未触发时作为 fallback，如标签提取的思考）
                            if (thought != null && !thought.trim().isEmpty() && !streamingHandler.hasReasoningCompleteFired()) {
                                long fbThoughtMessageId = -1;
                                long fbThoughtThinkingTimeMs = session.getLastThinkingTimeMs();
                                List<DialogueSession.Message> history = session.getHistory();
                                if (!history.isEmpty()) {
                                    DialogueSession.Message last = history.get(history.size() - 1);
                                    fbThoughtMessageId = last.getId();
                                    fbThoughtThinkingTimeMs = last.getThinkingTimeMs();
                                }
                                TextComponent thoughtBtn = new TextComponent(ChatColor.GRAY + " ○ Thought");
                                String cmd = "/cli thought" + (fbThoughtMessageId != -1 ? " t:" + fbThoughtMessageId : "");
                                thoughtBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
                                thoughtBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.thought.hover"))));
                                double lastSec = fbThoughtThinkingTimeMs / 1000.0;
                                TextComponent timeTag = new TextComponent(ChatColor.DARK_GRAY + " (" + String.format("%.1f", lastSec) + "s)");
                                thoughtBtn.addExtra(timeTag);
                                player.spigot().sendMessage(thoughtBtn);
                            }

                            session.logAIResponse(completeText + "\n\n[Streaming] Finish Reason: stop\n");
                            AIResponse response = new AIResponse(completeText,
                                (thought != null && !thought.isEmpty()) ? thought : null,
                                0, 0, false, streamingHandler.getNativeToolCalls());
                            handleAIResponse(player, response, true);
                            playFeedbackSound(player, "ai_complete");
                        });
                    });

                    streamingHandler.setOnErrorCallback((error) -> {
                        if (responseHandled[0]) return;
                        responseHandled[0] = true;
                        activeStreamingHandlers.remove(uuid);
                        long streamedOutErr2 = streamedOutputTokens.getOrDefault(uuid, 0L);
                        streamedOutputTokens.remove(uuid);
                        if (streamedOutErr2 > 0) {
                            roundOutputTokens.merge(uuid, streamedOutErr2, (a, b) -> a + b);
                        }
                        plugin.getCloudErrorReport().report(error);
                        plugin.getLogger().warning("[CLI] 反馈流式输出错误 - " + player.getName() + ": " + error);
                        if (!plugin.isEnabled()) return;
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (streamedOutErr2 > 0) {
                                DialogueSession s2 = sessions.get(uuid);
                                if (s2 != null) s2.addOutputTokens(streamedOutErr2);
                            }
                            retryInfoMap.put(uuid, new RetryInfo(session, effectiveFeedback, false, Collections.emptyList()));
                            player.spigot().sendMessage(buildErrorText(error.getMessage(), I18n.t("clim.error.streaming")));
                            isGenerating.put(uuid, false);
                            generationStates.put(uuid, GenerationStatus.ERROR);
                            generationStartTimes.remove(uuid);
                            playFeedbackSound(player, "ai_error");
                            player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new TextComponent(""));
                        });
                    });

                    // 估算本轮输入的 prompt tokens 并记入 session
                    String modelName = plugin.getConfigManager().getCloudflareModel();
                    int promptTokens = systemPrompt.stream().mapToInt(p -> DialogueSession.calculateTokens(p, modelName)).sum();
                    int estimatedInput2 = promptTokens
                        + session.getEstimatedTokens(modelName) + 3;
                    session.addInputTokens(estimatedInput2);

                    String completeText = ai.chatStreaming(player, session, systemPrompt, streamingHandler);

                    // 回退：流式未产生任何 chunk（如文本一次到达）
                    if (!streamingHandler.isCancelled() && !responseHandled[0] && fullResponseText.length() == 0) {
                        responseHandled[0] = true;
                        activeStreamingHandlers.remove(uuid);
                        long streamedOutFallback = streamedOutputTokens.getOrDefault(uuid, 0L);
                        streamedOutputTokens.remove(uuid);
                        if (streamedOutFallback > 0 && session != null) {
                            session.addOutputTokens(streamedOutFallback);
                            roundOutputTokens.merge(uuid, streamedOutFallback, (a, b) -> a + b);
                        }
                        String thought = streamingHandler.getThoughtContent();
                        session.logAIResponse(completeText + "\n\n[Streaming] Finish Reason: stop\n");
                        AIResponse response = new AIResponse(completeText,
                            (thought != null && !thought.isEmpty()) ? thought : null);
                        if (!plugin.isEnabled()) return;
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            // 回退路径也显示思考按钮（reasoning-complete 未触发时的 fallback）
                            if (thought != null && !thought.trim().isEmpty() && !streamingHandler.hasReasoningCompleteFired()) {
                                long fbThoughtMessageId = -1;
                                long fbThoughtThinkingTimeMs = session.getLastThinkingTimeMs();
                                List<DialogueSession.Message> history = session.getHistory();
                                if (!history.isEmpty()) {
                                    DialogueSession.Message last = history.get(history.size() - 1);
                                    fbThoughtMessageId = last.getId();
                                    fbThoughtThinkingTimeMs = last.getThinkingTimeMs();
                                }
                                TextComponent thoughtBtn = new TextComponent(ChatColor.GRAY + " ○ Thought");
                                String cmd = "/cli thought" + (fbThoughtMessageId != -1 ? " t:" + fbThoughtMessageId : "");
                                thoughtBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
                                thoughtBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.thought.hover"))));
                                double lastSec = fbThoughtThinkingTimeMs / 1000.0;
                                TextComponent timeTag = new TextComponent(ChatColor.DARK_GRAY + " (" + String.format("%.1f", lastSec) + "s)");
                                thoughtBtn.addExtra(timeTag);
                                player.spigot().sendMessage(thoughtBtn);
                            }
                            handleAIResponse(player, response, true);
                            playFeedbackSound(player, "ai_complete");
                        });
                    }
                } else {
                    AIResponse response = ai.chat(player, session, systemPrompt);

                    if (!plugin.isEnabled()) return;
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        handleAIResponse(player, response);
                        playFeedbackSound(player, "ai_complete");
                    });
                }
            } catch (IOException e) {
                plugin.getLogger().warning("[CLI] 工具反馈后 AI 请求失败 - " + player.getName() + ": " + e);
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // 保存重试信息（feedbackToAI 不需要 Skills）
                    retryInfoMap.put(uuid, new RetryInfo(session, effectiveFeedback, false, Collections.emptyList()));

                    TextComponent fullMsg = buildErrorText(e.getMessage(), "AI请求出错");
                    fullMsg.addExtra(buildRetryButton(e.getMessage()));
                    player.spigot().sendMessage(fullMsg);

                    isGenerating.put(uuid, false);
                    recordThinkingTime(uuid);
                    generationStates.put(uuid, GenerationStatus.ERROR);
                    generationStartTimes.remove(uuid);
                    playFeedbackSound(player, "ai_error");
                    // 立即清除动作栏
                    player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new TextComponent(""));
                    // 移除导致失败的消息，防止污染后续对话
                    session.removeLastMessage();
                });
            } catch (Throwable t) {
                plugin.getCloudErrorReport().report(t);
                plugin.getLogger().warning("[CLI] 工具反馈后 AI 请求异常 - " + player.getName() + ": " + t);
                if (!plugin.isEnabled()) return;
                Bukkit.getScheduler().runTask(plugin, () -> {
                    // 保存重试信息
                    retryInfoMap.put(uuid, new RetryInfo(session, effectiveFeedback, false, Collections.emptyList()));

                    TextComponent fullMsg = buildErrorText(t.getMessage(), "系统内部错误");
                    fullMsg.addExtra(buildRetryButton(t.getMessage()));
                    player.spigot().sendMessage(fullMsg);
                    plugin.getLogger().warning("系统内部错误: " + t.getMessage());

                    isGenerating.put(uuid, false);
                    recordThinkingTime(uuid);
                    generationStates.put(uuid, GenerationStatus.ERROR);
                    generationStartTimes.remove(uuid);
                    playFeedbackSound(player, "ai_error");
                    // 立即清除动作栏
                    player.spigot().sendMessage(net.md_5.bungee.api.ChatMessageType.ACTION_BAR, new TextComponent(""));
                    // 移除导致失败的消息，防止污染后续对话
                    session.removeLastMessage();
                });
            } finally {
                // 清除重试回调
                ai.clearRetryCallback();
            }
        });
    }

    /**
     * 该工具是否可安全进入串行批量（保证恰好一次异步 feedbackToAI，不会死锁批次屏障）。
     * 交互/确认类工具（ask/edit/write/记忆类/mcp）的结果均经 feedbackToAI 或
     * 批次感知的确认/取消/拒绝处理回灌，批次屏障可推进，因此均可批。
     * 控制类工具（start/end/exit）与未知工具无反馈回灌，混入批次会卡死屏障，不可批。
     */
    static boolean isBatchSafeTool(String toolName, DialogueSession.Mode mode) {
        String name = toolName == null ? "" : toolName.toLowerCase();
        switch (name) {
            case "search":
            case "webfetch":
            case "skill":
            case "unloadskill":
            case "mcp_tools":
            case "mcp":
            case "todo":
            case "list":
            case "read":
            case "run":
            case "ask":
            case "edit":
            case "edit_memory":
            case "write":
            case "remember":
            case "forget":
            case "remember_global":
            case "forget_global":
            case "edit_global":
                return true;
            default:
                return false;
        }
    }

    /**
     * YOLO 模式下该 run 调用是否含风险命令（会渲染确认按钮等待玩家，不入批）。
     */
    static boolean isRiskyRunCall(NativeToolCall c, List<String> riskyCommands) {
        if (c == null || !"run".equalsIgnoreCase(c.name())) {
            return false;
        }
        ToolExecutor.ToolParseResult p = ToolExecutor.parseToolCall(ToolRegistry.bridgeToText(c));
        return ToolExecutor.isRiskyCommandPublic(p.args, riskyCommands);
    }

    /**
     * 该调用是否可进入串行批次：
     * 1. 工具名属于批次安全集（结果必定经 feedbackToAI 恰好一次回灌，批次屏障可推进）；
     * 2. YOLO 模式下 #run 含风险命令（会渲染确认按钮等待玩家）除外，与既有语义一致。
     */
    private boolean isBatchableCall(NativeToolCall c, DialogueSession session) {
        if (c == null || c.name() == null) {
            return false;
        }
        String name = c.name().toLowerCase();
        if (!isBatchSafeTool(name, session != null ? session.getMode() : DialogueSession.Mode.NORMAL)) {
            return false;
        }
        if (session != null && session.getMode() == DialogueSession.Mode.YOLO
                && isRiskyRunCall(c, plugin.getConfigManager().getYoloRiskCommands())) {
            return false;
        }
        return true;
    }

    /**
     * 构建混合批次中未执行调用的回灌说明（无未执行项返回 null）。
     */
    static String buildNativeDroppedNote(List<NativeToolCall> excluded) {
        if (excluded == null || excluded.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("#error: 混合批次无法串行执行，以下工具调用未执行，请重新发起：");
        for (int i = 0; i < excluded.size(); i++) {
            if (i > 0) {
                sb.append("、");
            }
            NativeToolCall c = excluded.get(i);
            sb.append(c.name() != null ? c.name() : "unknown");
        }
        return sb.toString();
    }

    /**
     * 把未执行项说明暂存到会话，随下一次工具反馈一并回灌模型。
     */
    private void noteDroppedCalls(DialogueSession session, String note) {
        if (session == null || note == null || note.isEmpty()) {
            return;
        }
        String existing = session.getPendingBatchDropNote();
        session.setPendingBatchDropNote(existing == null || existing.isEmpty() ? note : existing + "\n" + note);
    }

    /**
     * 统一分发原生函数调用：
     * 1. 全部可批且 >1 → 串行批量（结果合并一次回灌模型）。
     * 2. 混合批次（含 start/end/exit/未知工具或 YOLO 风险 run）→ 可批者走批量/单工具，
     *    不可批者回灌模型告知未执行，绝不静默丢弃。
     * 3. 全部不可批 → 不执行任何工具，直接回灌模型告知。
     */
    private void dispatchNativeCalls(Player player, DialogueSession session, List<NativeToolCall> calls) {
        if (calls == null || calls.isEmpty()) {
            return;
        }
        StringBuilder diag = new StringBuilder();
        for (NativeToolCall c : calls) {
            if (diag.length() > 0) diag.append(", ");
            diag.append(c.name()).append(":").append(c.argumentsJson());
        }
        if (plugin.getConfigManager().isDebug()) {
            plugin.getLogger().info("[CLI] 原生 tool_calls 分发 " + player.getName() + " (" + calls.size() + " 个): " + diag);
        }

        // 单个调用直接执行（与旧版一致）；不可批/回灌逻辑只作用于多调用批次，
        // 避免 end/exit/start 等控制类工具单独出现时被回灌成"未执行"而陷入重发循环。
        if (calls.size() == 1) {
            if (session != null) {
                session.setPendingBatchDropNote(null);
            }
            String toolCall = ToolRegistry.bridgeToText(calls.get(0));
            if (!toolCall.isEmpty()) {
                executeTool(player, toolCall, ToolRegistry.isForceCall(calls.get(0)));
            }
            return;
        }

        List<NativeToolCall> batchable = new ArrayList<>();
        List<NativeToolCall> excluded = new ArrayList<>();
        for (NativeToolCall c : calls) {
            if (isBatchableCall(c, session)) {
                batchable.add(c);
            } else {
                excluded.add(c);
            }
        }
        String droppedNote = buildNativeDroppedNote(excluded);

        if (batchable.isEmpty()) {
            // 全部不可批（如 end/exit 混批）→ 不执行任何工具，直接回灌模型告知未执行
            plugin.getLogger().warning("[CLI] 批次全部不可批，回灌模型未执行项: " + player.getName());
            if (session != null) {
                session.setPendingBatchDropNote(null);
            }
            invokeModelAfterFeedback(player, session, droppedNote);
            return;
        }

        if (session == null) {
            // 无会话兜底：退化为单工具路径
            String toolCall = ToolRegistry.bridgeToText(batchable.get(0));
            if (!toolCall.isEmpty()) {
                executeTool(player, toolCall, ToolRegistry.isForceCall(batchable.get(0)));
            }
            return;
        }

        // 重置批次状态后写入本次未执行项（clearBatchState 会清掉历史遗留 note）
        session.clearBatchState();
        noteDroppedCalls(session, droppedNote);

        if (batchable.size() > 1) {
            session.setBatchInProgress(true);
            for (NativeToolCall call : batchable) {
                session.pushPendingNativeTool(ToolRegistry.bridgeToText(call), ToolRegistry.isForceCall(call));
            }
            executeNativeBatch(player, session);
            return;
        }

        // 单个可批工具走单工具路径
        String toolCall = ToolRegistry.bridgeToText(batchable.get(0));
        if (!toolCall.isEmpty()) {
            executeTool(player, toolCall, ToolRegistry.isForceCall(batchable.get(0)));
        }
    }

    /**
     * 串行批量执行器（批次屏障）：
     * 1. 队列非空 → 串行执行下一个工具（不重入模型）。
     * 2. 队列耗尽 → 合并全部结果，一次真实模型重入。
     */
    private void executeNativeBatch(Player player, DialogueSession session) {
        UUID uuid = player.getUniqueId();
        if (!Bukkit.isPrimaryThread() || !plugin.isEnabled()) {
            return;
        }

        // 1) 队列还有工具 → 串行执行下一个
        String next = session.pollPendingNativeTool();
        boolean force = session.pollPendingNativeToolForce();
        if (next != null) {
            if (plugin.getConfigManager().isDebug()) {
                plugin.getLogger().info("[CLI] 批次执行 " + player.getName() + " 下一工具: " + next);
            }
            setGenerating(uuid, true, GenerationStatus.EXECUTING_TOOL);
            try {
                executeTool(player, next, force);
            } catch (Throwable t) {
                // 同步抛出的兜底：记录错误并继续批次
                plugin.getCloudErrorReport().report(t);
                session.addPendingToolResult("#error: 工具执行异常 - " + t.getMessage());
                executeNativeBatch(player, session);
            }
            return;
        }

        // 2) 队列耗尽 → 合并全部结果，一次真实模型重入
        session.setBatchInProgress(false);
        session.clearPendingNativeTools();
        List<String> results = session.drainPendingToolResults();
        String joined = results.isEmpty()
                ? "#error: 批量工具执行未返回任何结果。"
                : String.join("\n", results);
        // 成功/失败计数已由 executeTool 按执行结果布尔完成（每个工具执行时都经过），
        // 此处不再按结果文本启发式重复计数——文本含 "_error" 字样（如搜索结果）会被误判失败。
        invokeModelAfterFeedback(player, session, joined);
    }

    /**
     * 批次安全超时兜底：批次某工具异步异常导致长时间无反馈时，强制终结批次并把部分结果回灌模型。
     */
    private void forceFinalizeBatch(Player player, DialogueSession session) {
        if (session == null) {
            return;
        }
        session.setBatchInProgress(false);
        session.clearPendingNativeTools();
        List<String> results = session.drainPendingToolResults();
        String joined = (results.isEmpty()
                ? java.util.List.of("#error: 批量工具执行超时，未能获取全部结果。")
                : results).stream()
                .collect(java.util.stream.Collectors.joining("\n"));
        invokeModelAfterFeedback(player, session, joined);
    }

    /**
     * 计算两个字符串的相似度 (基于 Levenshtein 距离)
     * @param s1 字符串1
     * @param s2 字符串2
     * @return 相似度 (0.0 - 1.0)
     */
    private double calculateSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        if (s1.equals(s2)) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;

        int len1 = s1.length();
        int len2 = s2.length();
        int[][] dp = new int[len1 + 1][len2 + 1];

        for (int i = 0; i <= len1; i++) dp[i][0] = i;
        for (int j = 0; j <= len2; j++) dp[0][j] = j;

        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = (s1.charAt(i - 1) == s2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }

        int distance = dp[len1][len2];
        return 1.0 - ((double) distance / Math.max(len1, len2));
    }

    private void displayFancyContent(Player player, String content, String currentThought) {
        // 获取当前 session
        DialogueSession session = sessions.get(player.getUniqueId());
        
        // 如果本次回复包含思考过程，或者历史最后一条消息有思考过程，显示按钮
        if (session != null) {
            String thoughtToShow = currentThought;
            long thoughtMessageId = -1;
            long thoughtThinkingTimeMs = session.getLastThinkingTimeMs();

            List<DialogueSession.Message> history = session.getHistory();
            if (thoughtToShow == null && !history.isEmpty()) {
                // 如果当前没有提取到思考过程，尝试查找历史最后一条 assistant 消息
                for (int i = history.size() - 1; i >= 0; i--) {
                    if ("assistant".equalsIgnoreCase(history.get(i).getRole())) {
                        if (history.get(i).hasThought()) {
                            thoughtToShow = history.get(i).getThought();
                            thoughtMessageId = history.get(i).getId();
                            thoughtThinkingTimeMs = history.get(i).getThinkingTimeMs();
                        }
                        break;
                    }
                }
            } else if (thoughtToShow != null) {
                // 如果当前提取到了思考过程，它已经被 addMessage 加入了历史
                if (!history.isEmpty()) {
                    DialogueSession.Message last = history.get(history.size() - 1);
                    thoughtMessageId = last.getId();
                    thoughtThinkingTimeMs = last.getThinkingTimeMs();
                }
            }
            
            if (thoughtToShow != null && !thoughtToShow.trim().isEmpty()) {
                TextComponent thoughtBtn = new TextComponent(ChatColor.GRAY + " ○ Thought");
                // 传递 messageId 以便稳定地回放对应 Thought（避免历史裁剪导致索引漂移）
                String cmd = "/cli thought" + (thoughtMessageId != -1 ? " t:" + thoughtMessageId : "");
                thoughtBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, cmd));
                thoughtBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.thought.hover"))));
                
                // 在 Thought 按钮右侧显示本次思考的时间
                double lastSec = thoughtThinkingTimeMs / 1000.0;
                TextComponent timeTag = new TextComponent(ChatColor.DARK_GRAY + " (" + String.format("%.1f", lastSec) + "s)");
                thoughtBtn.addExtra(timeTag);
                
                player.spigot().sendMessage(thoughtBtn);
            }
        }

        // 处理正文内容
        if (content != null && !content.trim().isEmpty()) {
            // 先处理自定义颜色代码 §x 和 §z
            content = ColorUtil.translateCustomColors(content);
            
            // 处理代码块 ```...```
            String[] codeParts = content.split("```");
            TextComponent finalMessage = new TextComponent(ChatColor.WHITE + "◆ ");
            
            for (int i = 0; i < codeParts.length; i++) {
                if (i % 2 == 1) {
                    // 代码块部分，亮蓝色显示
                    finalMessage.addExtra(ChatColor.DARK_AQUA + codeParts[i]);
                } else {
                    // 普通文本部分，进一步处理 **...** 高亮
                    String text = codeParts[i];
                    String[] highlightParts = text.split("\\*\\*");
                    
                    for (int j = 0; j < highlightParts.length; j++) {
                        if (j % 2 == 1) {
                            // 高亮部分，使用自定义亮蓝色 #30AEE5
                            // 移除内部颜色代码以确保高亮颜色生效
                            String cleanText = ChatColor.stripColor(highlightParts[j]);
                            TextComponent highlightComp = new TextComponent(cleanText);
                            highlightComp.setColor(net.md_5.bungee.api.ChatColor.of(ColorUtil.getColorZ()));
                            finalMessage.addExtra(highlightComp);
                        } else {
                            // 普通部分，白色显示
                            finalMessage.addExtra(ChatColor.WHITE + highlightParts[j]);
                        }
                    }
                }
            }
            player.spigot().sendMessage(finalMessage);
        }
    }

    /**
     * 展示特定索引或最新的思考过程
     */
    public void handleThought(Player player, String[] args) {
        DialogueSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            player.sendMessage(I18n.t("clim.no.active.dialogue"));
            return;
        }

        String thought = null;
        long thinkingTimeMs = session.getLastThinkingTimeMs();
        boolean hasExplicitTarget = args.length > 0;
        if (hasExplicitTarget) {
            String target = args[0];

            if (target.startsWith("t:")) {
                try {
                    long messageId = Long.parseLong(target.substring(2));
                    DialogueSession.ThoughtSnapshot snapshot = session.getThoughtSnapshot(messageId);
                    if (snapshot != null) {
                        thought = snapshot.getThought();
                        thinkingTimeMs = snapshot.getThinkingTimeMs();
                    } else {
                        DialogueSession.Message message = session.findMessageById(messageId);
                        if (message != null && message.hasThought()) {
                            thought = message.getThought();
                            thinkingTimeMs = message.getThinkingTimeMs();
                        }
                    }
                } catch (NumberFormatException ignored) {}
            } else {
                try {
                    int index = Integer.parseInt(target);
                    List<DialogueSession.Message> history = session.getHistory();
                    if (index >= 0 && index < history.size()) {
                        DialogueSession.Message message = history.get(index);
                        if (message.hasThought()) {
                            thought = message.getThought();
                            thinkingTimeMs = message.getThinkingTimeMs();
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // 仅当没有指定目标时，才回退到最后一次思考
        if (!hasExplicitTarget && thought == null) {
            thought = session.getLastThought();
        }

        if (thought == null || thought.trim().isEmpty()) {
            player.sendMessage(ColorUtil.translateCustomColors("§zFancyHelper§b§r §7> §f找不到对应的思考过程。"));
            return;
        }

        // 创建书本
        org.bukkit.inventory.ItemStack book = new org.bukkit.inventory.ItemStack(org.bukkit.Material.WRITTEN_BOOK);
        org.bukkit.inventory.meta.BookMeta meta = (org.bukkit.inventory.meta.BookMeta) book.getItemMeta();
        
        if (meta != null) {
            meta.setTitle("Fancy Thought");
            meta.setAuthor("Fancy");

            // 获取本次思考的时长
            double lastThinkingSec = thinkingTimeMs / 1000.0;
            String timePrefix = ChatColor.DARK_GRAY + "Thought (" + String.format("%.1f", lastThinkingSec) + "s)\n\n" + ChatColor.RESET;
            
            // 将 **文本** 转换为 Minecraft 粗体格式 §l文本§r
            String formattedThought = convertMarkdownBoldToMinecraft(thought);
            String fullThought = timePrefix + formattedThought;
            
            // 分页处理（书本每页约 256 字符，但实际受行数限制，使用 128 作为安全边距）
            List<String> pages = new ArrayList<>();
            int pageSize = 128;
            for (int i = 0; i < fullThought.length(); i += pageSize) {
                pages.add(fullThought.substring(i, Math.min(i + pageSize, fullThought.length())));
            }
            
            if (pages.isEmpty()) pages.add("");
            meta.setPages(pages);
            book.setItemMeta(meta);
            
            // 打开书本
            player.openBook(book);
        }
    }

    /**
     * 将 Markdown 粗体语法 **文本** 转换为 Minecraft 颜色代码格式 §z文本§r
     * @param text 原始文本
     * @return 转换后的文本
     */
    /**
     * 流式输出时，裁掉末尾未闭合的 Markdown 格式标记，
     * 防止 ** 等标记在闭合前就泄露给玩家。
     */
    private void playFeedbackSound(Player player, String soundKey) {
        if (!plugin.getConfigManager().isSoundEnabled()) return;
        if (plugin.getConfigManager().isPlayerSoundDisabled(player.getUniqueId())) return;
        String sound;
        switch (soundKey) {
            case "ai_complete": sound = plugin.getConfigManager().getSoundAiComplete(); break;
            case "ai_error": sound = plugin.getConfigManager().getSoundAiError(); break;
            case "cli_enter": sound = plugin.getConfigManager().getSoundCliEnter(); break;
            case "cli_exit": sound = plugin.getConfigManager().getSoundCliExit(); break;
            case "user_input": sound = plugin.getConfigManager().getSoundUserInput(); break;
            default: return;
        }
        if (sound != null && !sound.isEmpty() && !sound.equalsIgnoreCase("none")) {
            player.playSound(player.getLocation(), sound, 0.5f, 1.0f);
        }
    }

    private String stripIncompleteFormatting(String text) {
        if (text == null || text.isEmpty()) return text;
        // 统计 ** 出现次数，奇数表示最后一个 ** 未闭合
        int count = 0;
        int lastPos = -1;
        int idx = 0;
        while ((idx = text.indexOf("**", idx)) != -1) {
            count++;
            lastPos = idx;
            idx += 2;
        }
        if (count % 2 == 1 && lastPos >= 0) {
            return text.substring(0, lastPos);
        }
        return text;
    }

    private String convertMarkdownBoldToMinecraft(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        // 使用正则表达式匹配 **文本** 并替换为 §z文本§r
        // 使用非贪婪匹配避免跨多个粗体块的问题
        return text.replaceAll("\\*\\*(.+?)\\*\\*", "§z$1§r");
    }

    private void sendAgreement(Player player) {
        player.sendMessage(ChatColor.GRAY + "=================");
        player.sendMessage(I18n.t("clim.agree.title"));
        player.sendMessage(I18n.t("clim.agree.intro"));
        // player.sendMessage("");
        TextComponent message = new TextComponent(I18n.t("clim.agree.click"));
        TextComponent bookBtn = new TextComponent(I18n.t("clim.agree.read"));
        bookBtn.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://blog.baicaizhale.top/post/fancyhelper-eula"));
        bookBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
            new Text(I18n.t("clim.agree.read.hover"))));
        
        message.addExtra(bookBtn);
        message.addExtra(new TextComponent(I18n.t("clim.agree.after")));
        
        TextComponent agreeBtn = new TextComponent(ChatColor.GREEN + "" + ChatColor.BOLD + "agree");
        agreeBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli agree"));
        agreeBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, 
            new Text(I18n.t("clim.agree.agree.hover"))));
        
        message.addExtra(agreeBtn);
        message.addExtra(new TextComponent(I18n.t("clim.agree.suffix")));
        
        player.spigot().sendMessage(message);
        player.sendMessage(I18n.t("clim.agree.footer"));
        player.sendMessage(ChatColor.GRAY + "==============================================");
    }

    /**
     * 为玩家打开 EULA 网页链接。
     */
    public void openEulaUrl(Player player) {
        TextComponent message = new TextComponent(I18n.t("clim.eula.open"));
        message.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, "https://blog.baicaizhale.top/post/fancyhelper-eula"));
        message.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
            new Text(I18n.t("clim.eula.open.hover"))));
        player.spigot().sendMessage(message);
    }

    /**
     * 打开 待办列表 书本
     * @param player 玩家
     */
    public void openTodoBook(Player player) {
        player.openBook(plugin.getTodoManager().getTodoBook(player));
    }

    private String getRandomTip() {
        return ENTER_TIPS[new Random().nextInt(ENTER_TIPS.length)];
    }

    private void sendEnterMessage(Player player) {
        // 顶部分隔线
        player.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "------------------------------------");
        player.sendMessage("");

        // ▌FancyHelper ── Chatting  [Normal] [⚙]
        sendModeLine(player);

        // ▌💡 Tips整行悬停显示 "沉默的设计师"
        TextComponent tipLine = new TextComponent("§8▌ §e💡 §f" + getRandomTip());
        tipLine.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.enter.tips.hover"))));
        player.spigot().sendMessage(tipLine);
        player.sendMessage("");
        player.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "------------------------------------");
    }

    /**
     * 仅重绘模式标题行 ▌FancyHelper ── Chatting [Mode] [🔧] [⌚]。
     * 模式切换（switchMode / Plan 激活）时使用，避免整段头部（含随机提示语）重复刷屏。
     */
    private void sendModeLine(Player player) {
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);
        DialogueSession.Mode mode = session != null ? session.getMode() : DialogueSession.Mode.NORMAL;

        TextComponent line1 = new TextComponent();

        TextComponent bar = new TextComponent("▌ ");
        bar.setColor(net.md_5.bungee.api.ChatColor.DARK_GRAY);
        line1.addExtra(bar);

        TextComponent brand = new TextComponent("FancyHelper ");
        brand.setColor(net.md_5.bungee.api.ChatColor.of("#30AEE5"));
        line1.addExtra(brand);

        TextComponent divider = new TextComponent("──");
        divider.setColor(net.md_5.bungee.api.ChatColor.DARK_GRAY);
        divider.setStrikethrough(true);
        line1.addExtra(divider);

        TextComponent chatting = new TextComponent(" Chatting  ");
        chatting.setColor(net.md_5.bungee.api.ChatColor.WHITE);
        line1.addExtra(chatting);

        // 模式标签（可点击切换）
        net.md_5.bungee.api.ChatColor modeColor;
        String modeName;
        if (mode == DialogueSession.Mode.NORMAL) {
            modeColor = net.md_5.bungee.api.ChatColor.GREEN;
            modeName = "Normal";
        } else if (mode == DialogueSession.Mode.SMART) {
            modeColor = net.md_5.bungee.api.ChatColor.of("#5555FF");
            modeName = "SMART";
        } else if (mode == DialogueSession.Mode.PLAN) {
            modeColor = net.md_5.bungee.api.ChatColor.AQUA;
            modeName = "Plan";
        } else {
            modeColor = net.md_5.bungee.api.ChatColor.RED;
            modeName = "YOLO";
        }

        TextComponent modeTag = new TextComponent("[" + modeName + "]");
        modeTag.setColor(modeColor);
        modeTag.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.enter.mode.hover"))));
        modeTag.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli gui mode"));
        line1.addExtra(modeTag);

        TextComponent space = new TextComponent(" ");
        line1.addExtra(space);

        TextComponent settingsBtn = new TextComponent("[🔧]");
        settingsBtn.setColor(net.md_5.bungee.api.ChatColor.GRAY);
        settingsBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.enter.settings.hover"))));
        settingsBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli settings"));
        line1.addExtra(settingsBtn);

        TextComponent space2 = new TextComponent(" ");
        line1.addExtra(space2);

        TextComponent resumeBtn = new TextComponent("[⌚]");
        resumeBtn.setColor(net.md_5.bungee.api.ChatColor.GRAY);
        resumeBtn.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.t("clim.enter.resume.hover"))));
        resumeBtn.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/cli resume"));
        line1.addExtra(resumeBtn);

        player.spigot().sendMessage(line1);
    }

    private void sendExitMessage(Player player) {
        UUID uuid = player.getUniqueId();
        DialogueSession session = sessions.get(uuid);

        long totalTokens = 0;
        if (session != null) {
            totalTokens = session.getTotalInputTokens() + session.getTotalOutputTokens();
        }

        long durationMs = session != null ? System.currentTimeMillis() - session.getStartTime() : 0;
        double durationSec = durationMs / 1000.0;
        double thinkingSec = session != null ? session.getTotalThinkingTimeMs() / 1000.0 : 0.0;

        // 顶部分隔线
        player.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "------------------------------------");
        player.sendMessage("");

        // ▌FancyHelper ── 已退出
        TextComponent line1 = new TextComponent();

        TextComponent bar = new TextComponent("▌ ");
        bar.setColor(net.md_5.bungee.api.ChatColor.DARK_GRAY);
        line1.addExtra(bar);

        TextComponent brand = new TextComponent("FancyHelper ");
        brand.setColor(net.md_5.bungee.api.ChatColor.of("#30AEE5"));
        line1.addExtra(brand);

        TextComponent divider = new TextComponent("──");
        divider.setColor(net.md_5.bungee.api.ChatColor.DARK_GRAY);
        divider.setStrikethrough(true);
        line1.addExtra(divider);

        TextComponent exited = new TextComponent(I18n.t("clim.exit.exited"));
        exited.setColor(net.md_5.bungee.api.ChatColor.WHITE);
        line1.addExtra(exited);

        player.spigot().sendMessage(line1);

        // ▌⟐ tokens · time (思考 thinkTime)
        String stats = "⟐ " + totalTokens + " tokens · " + String.format("%.1f", durationSec) + "s";
        if (thinkingSec > 0) {
            stats += I18n.t("clim.exit.thinking", String.format("%.1f", thinkingSec));
        }
        player.sendMessage(ColorUtil.translateCustomColors("§8▌ §7" + stats));

        // 底部分隔线
        player.sendMessage("");
        player.sendMessage(ChatColor.DARK_GRAY + "" + ChatColor.STRIKETHROUGH + "------------------------------------");
    }

    /**
     * 获取当前处于 CLI 模式的玩家数量
     * 
     * @return 活跃玩家数量
     */
    public int getActivePlayersCount() {
        return activeCLIPayers.size();
    }

    public boolean isInCLI(Player player) {
        return activeCLIPayers.contains(player.getUniqueId());
    }

    // ==================== 公共访问方法（供 ToolExecutor 使用）====================

    /**
     * 设置生成状态
     */
    public void setGenerating(UUID uuid, boolean generating, GenerationStatus status) {
        isGenerating.put(uuid, generating);
        generationStates.put(uuid, status);
        if (status == GenerationStatus.THINKING || status == GenerationStatus.EXECUTING_TOOL) {
            generationStartTimes.put(uuid, System.currentTimeMillis());
        }
    }

    /**
     * 设置待处理命令
     */
    public void setPendingCommand(UUID uuid, String command) {
        pendingCommands.put(uuid, command);
    }

    /**
     * 获取待处理命令
     */
    public String getPendingCommand(UUID uuid) {
        return pendingCommands.get(uuid);
    }

    /**
     * 移除待处理命令
     */
    public void removePendingCommand(UUID uuid) {
        pendingCommands.remove(uuid);
    }

    /**
     * 获取生成状态
     */
    public GenerationStatus getGenerationState(UUID uuid) {
        return generationStates.getOrDefault(uuid, GenerationStatus.IDLE);
    }

    /**
     * 检查是否正在生成
     */
    public boolean isGenerating(UUID uuid) {
        return isGenerating.getOrDefault(uuid, false);
    }

    /**
     * 获取对话会话
     */
    public DialogueSession getSession(UUID uuid) {
        return sessions.get(uuid);
    }

    public String getLastError(UUID uuid) {
        DialogueSession session = sessions.get(uuid);
        return session != null ? session.getLastError() : null;
    }

    /**
     * 记录思考时间
     */
    public void recordThinkingTimePublic(UUID uuid) {
        recordThinkingTime(uuid);
    }

    /**
     * 清除生成开始时间
     */
    public void clearGenerationStartTime(UUID uuid) {
        generationStartTimes.remove(uuid);
    }

    /**
     * 获取待删除确认的会话 UUID
     */
    public String getPendingDeleteSession(UUID playerUUID) {
        return pendingDeleteSessions.get(playerUUID);
    }

    /**
     * 设置待删除确认的会话 UUID
     */
    public void setPendingDeleteSession(UUID playerUUID, String sessionUUID) {
        pendingDeleteSessions.put(playerUUID, sessionUUID);
    }

    /**
     * 清除待删除确认的会话 UUID
     */
    public void clearPendingDeleteSession(UUID playerUUID) {
        pendingDeleteSessions.remove(playerUUID);
    }
}
