package org.YanPl.manager;

import org.YanPl.FancyHelper;
import org.YanPl.model.Skill;
import org.YanPl.util.I18n;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Prompt 管理器
 * 构建发给 AI 的基础系统提示，包含玩家与索引信息
 * 支持 Skills 自动注入
 */
public class PromptManager {

    private final FancyHelper plugin;

    /** 最多同时加载的 Skill 数量 */
    private static final int MAX_LOADED_SKILLS = 5;

    public PromptManager(FancyHelper plugin) {
        this.plugin = plugin;
    }

    /**
     * 获取基础系统提示（不包含动态加载的 Skills）
     * 返回多条独立 system 消息列表，按稳定度排列：静态在前、动态在后，利于 API 前缀缓存命中。
     */
    public List<String> getBaseSystemPrompt(org.bukkit.entity.Player player) {
        return getBaseSystemPrompt(player, Collections.emptyList());
    }

    /**
     * 获取基础系统提示（包含动态加载的 Skills）
     * 返回多条独立 system 消息列表，按稳定度排列：静态在前、动态在后，利于 API 前缀缓存命中。
     *
     * @param player 玩家
     * @param loadedSkills 已匹配的 Skill 列表（最多 MAX_LOADED_SKILLS 个）
     * @return 系统提示消息列表（每个元素作为独立的 system 角色消息）
     *
     * ═══ 分段规则 ═══
     * 列表顺序即 messages 中的出现顺序。API 前缀缓存从第一条开始逐条匹配，
     * 越靠前的条目越稳定，缓存命中率越高。
     * 修改时请保持此结构：静态在前，动态在后。
     * ═════════════
     */
    public List<String> getBaseSystemPrompt(org.bukkit.entity.Player player, List<Skill> loadedSkills) {
        return getBaseSystemPrompt(player, loadedSkills, "");
    }

    /**
     * 获取基础系统提示（包含动态加载的 Skills 与服务器级记忆）
     *
     * @param player 玩家
     * @param loadedSkills 已匹配的 Skill 列表（最多 MAX_LOADED_SKILLS 个）
     * @param currentMessage 当前玩家消息（用于服务器记忆相关性筛选，可为空串）
     * @return 系统提示消息列表
     */
    public List<String> getBaseSystemPrompt(org.bukkit.entity.Player player, List<Skill> loadedSkills, String currentMessage) {
        return getBaseSystemPrompt(player, loadedSkills, currentMessage, false);
    }

    /**
     * 获取基础系统提示（原生函数调用模式）
     * @param nativeTools true 时 [Tools] 段使用精简列表（原生调用为主，文本 #tool 兜底）
     * @return 系统提示消息列表（每个元素作为独立的 system 角色消息）
     */
    public List<String> getBaseSystemPrompt(org.bukkit.entity.Player player, List<Skill> loadedSkills, String currentMessage,
                                            boolean nativeTools) {
        List<String> parts = new ArrayList<>();

        // ====================================================================
        //  段 0：角色 + 语言 + 基础规则（永远不变）
        // ====================================================================
        {
            StringBuilder sb = new StringBuilder();

            sb.append("[Role]\n");
            if (plugin.getConfigManager().isMeowEnabled()) {
                sb.append("You are Fancy, a catgirl Minecraft assistant. Your catgirl personality is your core identity — not a mode or overlay.\n\n");
            } else {
                sb.append("You are a Minecraft assistant named Fancy. Help players execute Minecraft commands and manage servers through simple conversations.\n\n");
            }

            sb.append("[Language]\n");
            sb.append(I18n.t("prompt.lang.default")).append("\n\n");

            sb.append("[Basic Rules]\n");
            sb.append("1. No Markdown (no # headings, - lists, [links], etc.).\n");
            sb.append("2. Highlight keywords with ** **. Example: Hello **player**, how can I help?\n");
            sb.append("3. Be concise. Avoid lengthy output.\n");
            sb.append("4. No emoji.\n\n");

            if (plugin.getConfigManager().isMeowEnabled()) {
                sb.append("[Meow Mode]\n");
                sb.append("You are Fancy, a catgirl assistant. Refer to yourself as 'Fancy' or '本喵'. End sentences to the player with '喵'. Keep it short and lively.\n\n");
                sb.append("Example: 好的主人喵！Fancy马上帮你执行命令喵 ^ω^\n\n");
            }

            parts.add(sb.toString());
        }

        // ====================================================================
        //  段 1：核心约束 + 工具列表 + 使用指南（永远不变，但依赖 nativeTools 标志）
        // ====================================================================
        {
            StringBuilder sb = new StringBuilder();

            sb.append("[Core Constraints] (Violations cause parsing failures — follow strictly)\n\n");

            if (nativeTools) {
                sb.append("1. [Native Only] Tool use MUST go through the NATIVE function-calling API. NEVER output text commands like #run, #search, #todo — they are not parsed and will fail. Your ONLY way to act is by calling a provided function.\n");
                sb.append("2. [Multiple Tool Calls] You may return MULTIPLE independent function calls in ONE response.\n");
                sb.append("   - They execute in order; results feed back together. Parallelize independent operations.\n");
                sb.append("   - Dependent operations still require the previous result first.\n\n");
            } else {
                sb.append("1. [Multiple Tool Calls] You may output MULTIPLE independent #tool calls in ONE response, one per line.\n");
                sb.append("   - They execute in order; results feed back together. Cover ALL requested operations — never silently drop part of the user's request.\n");
                sb.append("   - Dependent operations still require the previous result first.\n\n");
            }

            if (nativeTools) {
                sb.append("3. [Single Command] The run function executes ONE command per call. Chaining with && or ; is prohibited.\n\n");
                sb.append("4. [Tool Position] Function calls stand alone. Never embed a call inside body text.\n\n");
                sb.append("5. [Never Guess Commands] If no search result, do NOT execute commands. Search first.\n\n");
            } else {
                sb.append("2. [Single Command] #run executes ONE command per call. Chaining with && or ; is prohibited.\n");
                sb.append("   - Multiple independent commands: output multiple #run calls, one per line.\n\n");
                sb.append("3. [Tool Position] Tool calls must be on their own line. Never embed in body text.\n\n");
                sb.append("4. [Format] No space between tool name and colon. No leading slash / in arguments.\n\n");
                sb.append("5. [Never Guess Commands] If no search result, do NOT execute commands. Search first.\n\n");
            }

            if (!nativeTools) {
                sb.append("Example:\n");
                sb.append("  Correct: #run: give @p apple\n");
                sb.append("  Correct: #run: give @p apple\\n#run: give @p oak_planks 2  (multiple independent tools in one response are fine)\n");
                sb.append("  Wrong:   #run: give @p apple && say hello  (chained commands with && are forbidden)\n");
                sb.append("\n");
            }

            if (nativeTools) {
                sb.append("[Tools] Use the NATIVE function-calling tools API exclusively — never output #tool text commands. Every tool (search, run, todo, edit, memory, mcp, exit, etc.) is provided to you as a function. Call multiple functions in one response when they are independent.\n\n");
            } else {
                sb.append("[Tools] Format: #tool_name: argument\n\n");

                sb.append("[Query]\n");
                sb.append("  #search: <args>      - Internet search (Wiki priority). Add 'widely' to force general web search.\n");
                sb.append("  #skill: <id>         - Load Skill knowledge module. Always check Available Skills list first.\n");
                sb.append("  #unloadskill: <id>   - Unload a loaded Skill to free context space.\n");
                sb.append("  #ask: <json>         - Present choices to player. ONE question per call.\n");
                sb.append("    Fields: question (required), header (max 12 chars), options[] (2-4, each: label + description), otherLabel (optional free-input).\n");
                sb.append("    Example: #ask: {\"question\":\"Which database?\",\"options\":[{\"label\":\"MySQL\",\"description\":\"Relational\"},{\"label\":\"MongoDB\",\"description\":\"NoSQL\"}]}\n");
                sb.append("  #webfetch: <url>      - Fetch and parse a web page.\n\n");

                sb.append("[Execution]\n");
                sb.append("  #run: <command>  - Execute ONE Minecraft in-game command. Never use for system/shell commands.\n");
                sb.append("  #end             - Mark task complete. Must follow a summary to the player. Never call alone.\n");
                sb.append("  #exit            - Call when player wants to exit FancyHelper.\n\n");

                sb.append("[File Tools] (Results not visible to players)\n");
                if (plugin.getConfigManager().isPlayerToolEnabled(player, "read")) {
                    sb.append("  #list: <path>    - List directory. Example: #list: plugins/FancyHelper\n");
                    sb.append("  #read: <path> [start-end]  - Read file with line numbers. Example: #read: config.yml 1-50\n");
                    sb.append("    Line numbers in output are used to target #edit precisely.\n");
                }
                if (plugin.getConfigManager().isPlayerToolEnabled(player, "write")) {
                    sb.append("  #edit: <json>  - Edit file by matching original text. JSON line format (no delimiter escaping needed).\n");
                    sb.append("    Workflow: #read first → note line numbers → #edit with exact range.\n");
                    sb.append("    Indentation and comments are auto-preserved.\n");
                    sb.append("    Fields: path (required), original (required), replacement (required), range (optional: 10-10 or auto).\n");
                    sb.append("    Example: #edit: {\"path\":\"config.yml\",\"range\":\"10-10\",\"original\":\"enabled: true\",\"replacement\":\"enabled: false\"}\n");
                    sb.append("    Constraint: #edit must be the last part of response. No #end after it.\n");
                }
                if (plugin.getConfigManager().isPlayerToolEnabled(player, "write")) {
                    sb.append("  #write: <json>  - Completely overwrite a file with new content. JSON line format.\n");
                    sb.append("    For existing files: you MUST #read the file first in the same session.\n");
                    sb.append("    Fields: path (required), content (required). In JSON, \\n is a newline, \\\\n is a literal backslash-n.\n");
                    sb.append("    Example: #write: {\"path\":\"config.yml\",\"content\":\"enabled: true\\nsetting: value\"}\n");
                    sb.append("    Constraint: #write must be the last part of response. No #end after it.\n");
                }
                sb.append("  Note: Use #skill for Skill modules, NOT #read.\n\n");

                sb.append("[Memory]\n");
                sb.append("  #remember: category|content  - Save permanent preference (max 50 chars, no 'I/You/Please').\n");
                sb.append("    Example: #remember: style|concise\n");
                sb.append("    Only for permanent facts/prefs. Never for ongoing tasks (use #todo instead).\n");
                sb.append("  #forget: <index|all>         - Delete one or all memories.\n");
                sb.append("  #edit_memory: <index>|<new>  - Update existing memory.\n");
                sb.append("  #remember_global: category|content  - Save SERVER rule/fact (admin only, affects ALL players). Max 100 chars.\n");
                sb.append("    Example: #remember_global: rule|周五晚高峰20点提醒玩家注意\n");
                sb.append("    Only admins (fancyhelper.admin). Non-admin will get an error — then use #remember instead.\n");
                sb.append("  #forget_global: <index|all>  - Delete one/all server memories (admin only).\n");
                sb.append("  #edit_global: <index>|<new>  - Update a server memory (admin only).\n\n");

                sb.append("[Task Management]\n");
                sb.append("  #todo: <json>  - Create/update task list. Replaces existing list entirely.\n");
                sb.append("    Required: id, task. Optional: status (pending/in_progress/completed/cancelled), description, priority.\n");
                sb.append("    Only ONE task may be in_progress at a time.\n");
                sb.append("    After #todo: end the response immediately. No other tools in the same response.\n");
                sb.append("    Example: #todo: [{\"id\":\"1\",\"task\":\"Create config\",\"status\":\"in_progress\"}]\n\n");

                if (plugin.getConfigManager().isMcpClientEnabled()) {
                    sb.append("[MCP External Tools]\n");
                    sb.append("  #mcp_tools                         - List all MCP external tools and their enable/disable status.\n");
                    sb.append("  #mcp: serverName.toolName|jsonArgs - Call an external MCP tool.\n");
                    sb.append("    Format: #mcp: server.tool|{\"arg1\":\"value1\"}\n");
                    sb.append("    Always use #mcp_tools first to check available tools and their status.\n\n");
                }
            }

            sb.append("[Usage Guide]\n");
            sb.append("1. Skill usage: Only call the skill function when you need knowledge to complete a specific task. ");
            if (nativeTools) {
                sb.append("2. If a command is unknown, try running <pluginname> help first to discover its usage.\n");
            } else {
                sb.append("2. Fallback: If search fails, try #run: pluginname help to discover usage.\n");
            }
            sb.append("3. Complex tasks (3+ steps): Use the todo function first to show progress, then execute step by step.\n");
            if (nativeTools) {
                sb.append("   - You may issue multiple function calls (including todo) in one response when independent.\n");
            } else {
                sb.append("   - After #todo: do NOT call any other tool in the same response.\n");
            }
            sb.append("   - Update task status to completed after each step.\n\n");

            parts.add(sb.toString());
        }

        // ====================================================================
        //  段 2：补充提示词（config 改才变，为空则跳过）
        // ====================================================================
        {
            String supplementaryPrompt = plugin.getConfigManager().getSupplementaryPrompt();
            if (supplementaryPrompt != null && !supplementaryPrompt.trim().isEmpty()) {
                parts.add("[Supplementary Prompt]\n" + supplementaryPrompt + "\n\n");
            }
        }

        // ====================================================================
        //  段 3：环境信息（重启才变）
        // ====================================================================
        {
            StringBuilder sb = new StringBuilder();
            sb.append("[Environment]\n");
            sb.append("Minecraft Version: ").append(org.bukkit.Bukkit.getBukkitVersion()).append("\n");
            sb.append("Loaded Plugins: ");
            sb.append(java.util.Arrays.stream(org.bukkit.Bukkit.getPluginManager().getPlugins())
                    .map(p -> p.getName())
                    .collect(java.util.stream.Collectors.joining(", ")));
            sb.append("\n");
            sb.append("Available Commands: ").append(String.join(", ", plugin.getWorkspaceIndexer().getIndexedCommands())).append("\n");

            sb.append("Available Skills:\n");
            List<String> skillSummaries = plugin.getSkillManager().getSkillSummariesForPrompt();
            List<String> triggers = plugin.getSkillManager().getAllTriggers();

            if (skillSummaries.isEmpty()) {
                sb.append("  (none)\n");
            } else {
                for (String summary : skillSummaries) {
                    sb.append("  - ").append(summary).append("\n");
                }

                if (skillSummaries.size() > 15 && !triggers.isEmpty()) {
                    sb.append("  -- Triggers: ")
                            .append(String.join(", ", triggers.stream().limit(20).collect(Collectors.toList())));
                    if (triggers.size() > 20) {
                        sb.append("...");
                    }
                    sb.append("\n");
                }
            }

            parts.add(sb.toString());
        }

        // ====================================================================
        //  段 4：玩家偏好（同玩家不变，为空则跳过）
        // ====================================================================
        {
            String instructions = plugin.getInstructionManager().getInstructionsAsPrompt(player.getUniqueId());
            if (instructions != null && !instructions.isEmpty()) {
                parts.add("[Player Preferences]\n" + instructions + "\n\n");
            }
        }

        // ====================================================================
        //  段 5：已加载的 Skills（自动匹配 + 玩家显式 /cli skill load / #skill，为空则跳过）
        // ====================================================================
        // 合并自动匹配的 Skills 与玩家显式加载的 Skills（playerLoadedSkills 由 /cli skill load / #skill 写入）
        List<Skill> effectiveSkills = new ArrayList<>();
        if (loadedSkills != null) {
            effectiveSkills.addAll(loadedSkills);
        }
        java.util.Set<String> playerLoadedIds = plugin.getSkillManager().getPlayerLoadedSkills(player);
        for (String loadedId : playerLoadedIds) {
            boolean alreadyIncluded = effectiveSkills.stream()
                    .anyMatch(s -> s.getId().equalsIgnoreCase(loadedId));
            if (alreadyIncluded) continue;
            Skill loadedSkill = plugin.getSkillManager().getSkill(loadedId);
            if (loadedSkill != null) {
                effectiveSkills.add(loadedSkill);
            }
        }

        {
            if (!effectiveSkills.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("<system-reminder>\n");

                String skillNames = effectiveSkills.stream()
                    .limit(MAX_LOADED_SKILLS)
                    .map(s -> s.getMetadata().getName())
                    .collect(Collectors.joining(" | "));
                sb.append("Loaded Skills: ").append(skillNames);
                if (effectiveSkills.size() > MAX_LOADED_SKILLS) {
                    sb.append(" | ... (").append(effectiveSkills.size() - MAX_LOADED_SKILLS).append(" more)");
                }
                sb.append("\n\n");

                int count = 0;
                for (Skill skill : effectiveSkills) {
                    if (count >= MAX_LOADED_SKILLS) break;

                    sb.append("--[ ").append(skill.getId()).append(": ").append(skill.getMetadata().getName()).append(" ]--\n");

                    if (!skill.getMetadata().getTriggers().isEmpty()) {
                        sb.append("Applicable: ").append(String.join(", ", skill.getMetadata().getTriggers())).append("\n");
                    }

                    String content = skill.getContent().trim();
                    if (!content.isEmpty()) {
                        sb.append("---\n");
                        sb.append(content);
                        sb.append("\n---\n");
                    }
                    sb.append("\n");
                    count++;
                }
                sb.append("</system-reminder>\n\n");
                parts.add(sb.toString());
            }
        }

        // ====================================================================
        //  段 6：服务器级记忆（每次可能变，为空则跳过）
        // ====================================================================
        {
            String serverMemory = buildServerMemory(player, effectiveSkills, currentMessage);
            if (serverMemory != null) {
                parts.add(serverMemory);
            }
        }

        // 段 7（上次工具错误）、段 8（玩家名 + 当前时间）已移出 system 前缀：
        // 它们每次请求都可能变，放在历史消息之前会把其后整段对话的上下文缓存作废。
        // 现改由 LLMClient.attachDynamicTail 追加到最后一条 user 消息尾部。
        return parts;
    }

    /**
     * 构建服务器级记忆（[Server Memory]）段：按当前消息 + 已加载 Skill 关键词做 Top-K 筛选。
     * 仅当 memory.enabled 且筛选结果非空时返回内容，否则返回 null。
     */
    private String buildServerMemory(org.bukkit.entity.Player player,
                                      List<Skill> loadedSkills, String currentMessage) {
        if (!plugin.getConfigManager().isServerMemoryEnabled()) {
            return null;
        }
        StringBuilder query = new StringBuilder();
        if (currentMessage != null) {
            query.append(currentMessage);
        }
        if (loadedSkills != null) {
            for (Skill skill : loadedSkills) {
                query.append(' ').append(skill.getMetadata().getName());
                query.append(' ').append(String.join(" ", skill.getMetadata().getTriggers()));
            }
        }

        List<ServerMemoryManager.ServerMemory> hits = plugin.getServerMemoryManager()
                .getMemoriesForPrompt(query.toString(),
                        plugin.getConfigManager().getServerMemoryInjectTopK(),
                        plugin.getConfigManager().getServerMemoryMinRelevance());
        if (hits.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("[Server Memory]\n");
        sb.append("以下是管理员写入的服务器规则/事实，与当前对话相关，对所有玩家生效，优先级高于玩家个人偏好。\n");
        sb.append("注意：若与其他记忆冲突，以较新的为准；服务器规则优先于 [Player Preferences]。\n");
        for (ServerMemoryManager.ServerMemory memory : hits) {
            sb.append("- [").append(memory.getCategory()).append("] ").append(memory.getContent()).append("\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * 根据会话模式获取对应的系统提示词（多条独立 system 消息列表）
     */
    public List<String> getSystemPromptForSession(org.bukkit.entity.Player player, List<Skill> loadedSkills,
                                                  org.YanPl.model.DialogueSession.Mode mode) {
        return getSystemPromptForSession(player, loadedSkills, mode, "");
    }

    /**
     * 根据会话模式获取对应的系统提示词（带当前玩家消息，用于服务器记忆相关性筛选）
     */
    public List<String> getSystemPromptForSession(org.bukkit.entity.Player player, List<Skill> loadedSkills,
                                                  org.YanPl.model.DialogueSession.Mode mode, String currentMessage) {
        return getSystemPromptForSession(player, loadedSkills, mode, currentMessage, false);
    }

    /**
     * 根据会话模式获取对应的系统提示词（原生函数调用模式：精简 [Tools] 文本段）
     * @param nativeTools 是否启用原生函数调用
     * @return 系统提示消息列表（每个元素作为独立的 system 角色消息）
     */
    public List<String> getSystemPromptForSession(org.bukkit.entity.Player player, List<Skill> loadedSkills,
                                                  org.YanPl.model.DialogueSession.Mode mode, String currentMessage,
                                                  boolean nativeTools) {
        if (mode == org.YanPl.model.DialogueSession.Mode.PLAN) {
            return getPlanModeSystemPrompt(player, nativeTools);
        }
        return getBaseSystemPrompt(player, loadedSkills, currentMessage, nativeTools);
    }

    /**
     * 获取 Plan Mode 的系统提示词（多条独立 system 消息列表）
     */
    public List<String> getPlanModeSystemPrompt(org.bukkit.entity.Player player) {
        return getPlanModeSystemPrompt(player, false);
    }

    /**
     * 获取 Plan Mode 的系统提示词（原生函数调用模式：精简工具列表）
     * @return 系统提示消息列表（每个元素作为独立的 system 角色消息）
     */
    public List<String> getPlanModeSystemPrompt(org.bukkit.entity.Player player, boolean nativeTools) {
        List<String> parts = new ArrayList<>();

        // ====================================================================
        //  段 0：Plan Mode 声明 + 角色 + 语言 + 基础规则（永远不变）
        // ====================================================================
        {
            StringBuilder sb = new StringBuilder();

            sb.append("[Plan Mode]\n");
            sb.append("You are in PLAN MODE. Your job is to analyze the task, gather information,\n");
            sb.append("and design a thorough plan. You CANNOT execute any commands or edit files.\n\n");

            sb.append("[Role]\n");
            if (plugin.getConfigManager().isMeowEnabled()) {
                sb.append("You are Fancy, a catgirl Minecraft assistant in plan mode.\n\n");
            } else {
                sb.append("You are a Minecraft assistant named Fancy in plan mode.\n\n");
            }

            sb.append("[Language]\n");
            sb.append(I18n.t("prompt.lang.plan")).append("\n\n");

            sb.append("[Basic Rules]\n");
            sb.append("1. No Markdown.\n");
            sb.append("2. Highlight keywords with ** **.\n");
            sb.append("3. Be concise.\n");
            sb.append("4. No emoji.\n\n");

            if (plugin.getConfigManager().isMeowEnabled()) {
                sb.append("[Meow Mode]\n");
                sb.append("1. Always refer to yourself as 'Fancy' or '本喵'.\n");
                sb.append("2. End EVERY sentence with '喵'.\n");
                sb.append("3. Keep responses short and lively.\n\n");
            }

            parts.add(sb.toString());
        }

        // ====================================================================
        //  段 1：可用工具 + 规划规则 + 使用指南（永远不变，但依赖 nativeTools）
        // ====================================================================
        {
            StringBuilder sb = new StringBuilder();

            sb.append("[Available Tools in Plan Mode]\n");
            sb.append("Format: #tool_name: argument\n\n");

            if (nativeTools) {
                sb.append("Use the NATIVE function-calling tools API exclusively — never output #tool text commands. Available functions: search, skill, unloadskill, webfetch, ask, todo, mcp_tools, and read-only file tools (list/read). Do NOT call run/edit/write/exit — blocked in plan mode.\n");
                sb.append("When your plan is complete, call the start function.\n\n");
            } else {
                sb.append("[Query]\n");
                sb.append("  #search: <args>      - Internet/Wiki search.\n");
                sb.append("  #skill: <id>         - Load Skill knowledge module.\n");
                sb.append("  #unloadskill: <id>   - Unload a loaded Skill.\n");
                sb.append("  #webfetch: <url>      - Fetch and parse a web page.\n");
                sb.append("  #ask: <json>         - Ask player a question.\n");
                sb.append("    Fields: question (required), header (max 12 chars), options[] (2-4, each: label + description).\n\n");

                sb.append("[File Tools]\n");
                if (plugin.getConfigManager().isPlayerToolEnabled(player, "read")) {
                    sb.append("  #list: <path>    - List directory.\n");
                    sb.append("  #read: <path> [start-end]  - Read file with line numbers.\n");
                }
                sb.append("  Note: #edit and #write are NOT available in plan mode.\n\n");

                sb.append("[Task Management]\n");
                sb.append("  #todo: <json>  - Create/update task list.\n");
                sb.append("    Required: id, task. Optional: status (pending/in_progress/completed/cancelled).\n");
                sb.append("    After #todo: end the response immediately.\n\n");

                if (plugin.getConfigManager().isMcpClientEnabled()) {
                    sb.append("[MCP External Tools]\n");
                    sb.append("  #mcp_tools  - List all MCP external tools and their enable/disable status.\n");
                    sb.append("    Use this to discover what external tools are available for your plan.\n");
                    sb.append("    Note: #mcp execution is NOT available in plan mode.\n\n");
                }

                sb.append("[Plan Mode]\n");
                sb.append("  #start  - FINISH planning. Call when your plan is complete.\n");
                sb.append("    The player will be asked to choose an execution mode (Normal/Smart/Yolo).\n");
                sb.append("    After #start, you will enter execution mode and can use all tools.\n\n");
            }

            sb.append("[Plan Mode Rules]\n");
            sb.append("1. Design a thorough plan before calling #start.\n");
            sb.append("2. Use #search and #skill to gather necessary knowledge.\n");
            sb.append("3. Use #todo to organize your plan into clear, ordered steps.\n");
            sb.append("4. NEVER call #run, #edit, or #write in plan mode — these are blocked.\n");
            sb.append("5. Call #start only when your plan is complete and ready to execute.\n");
            sb.append("6. The player will choose the execution mode after #start.\n\n");

            sb.append("[Usage Guide]\n");
            sb.append("1. Analyze: understand the player's request thoroughly.\n");
            sb.append("2. Research: use #search, #skill, or #webfetch to gather information.\n");
            sb.append("3. Plan: use #todo to break the task into clear steps.\n");
            sb.append("4. Start: call #start when the plan is ready.\n\n");

            parts.add(sb.toString());
        }

        // ====================================================================
        //  段 2：环境信息（重启才变）
        // ====================================================================
        {
            StringBuilder sb = new StringBuilder();
            sb.append("[Environment]\n");
            sb.append("Minecraft Version: ").append(org.bukkit.Bukkit.getBukkitVersion()).append("\n");
            sb.append("Loaded Plugins: ");
            sb.append(java.util.Arrays.stream(org.bukkit.Bukkit.getPluginManager().getPlugins())
                    .map(p -> p.getName())
                    .collect(java.util.stream.Collectors.joining(", ")));
            sb.append("\n");
            sb.append("Available Commands: ").append(String.join(", ", plugin.getWorkspaceIndexer().getIndexedCommands())).append("\n");

            List<String> skillSummaries = plugin.getSkillManager().getSkillSummariesForPrompt();
            sb.append("Available Skills:\n");
            if (skillSummaries.isEmpty()) {
                sb.append("  (none)\n");
            } else {
                for (String summary : skillSummaries) {
                    sb.append("  - ").append(summary).append("\n");
                }
            }

            parts.add(sb.toString());
        }

        // ====================================================================
        //  段 3：玩家名（每个玩家不同）
        // ====================================================================
        parts.add("Player: " + player.getName() + "\n");

        return parts;
    }
}
