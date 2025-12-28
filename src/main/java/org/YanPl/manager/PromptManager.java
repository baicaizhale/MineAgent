package org.YanPl.manager;

import org.YanPl.MineAgent;

/**
 * 提示词管理器，负责生成系统提示词
 */
public class PromptManager {
    private final MineAgent plugin;

    public PromptManager(MineAgent plugin) {
        this.plugin = plugin;
    }

    /**
     * 生成基础系统提示词
     */
    public String getBaseSystemPrompt(org.bukkit.entity.Player player) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个名为 MineAgent 的 Minecraft 助手。你的目标是通过简单的对话生成并执行 Minecraft 命令。\n");
        sb.append("当前与你对话的玩家是：").append(player.getName()).append("\n");
        sb.append("当前可用命令列表（索引）：").append(String.join(", ", plugin.getWorkspaceIndexer().getIndexedCommands())).append("\n");
        sb.append("当前可用插件预设文件：").append(String.join(", ", plugin.getWorkspaceIndexer().getIndexedPresets())).append("\n");
        sb.append("\n规则：\n");
        sb.append("1. **绝对禁止使用任何 Markdown 格式**（如 # 标题、- 列表、[链接]等）。\n");
        sb.append("2. 如果你需要高亮显示某些关键词（如命令、玩家名、物品名），请使用 ** ** 将其括起来。例如：你可以输入 **weather rain** 来更改天气。\n");
        sb.append("3. 你可以使用以下工具。**重要：工具调用必须独立成行，且必须放在整个回复的最末尾。**\n");
        sb.append("   格式：#工具名: 参数\n");
        sb.append("   #search: <args> - 在 Minecraft Wiki 搜索。使用 #search: widely <args> 调用全网搜索。\n");
        sb.append("   #choose: <A>,<B>,<C>... - 展示多个选项供用户选择。\n");
        sb.append("   #get: <file> - 从预设目录获取文件内容。\n");
        sb.append("   #run: <command> - 以玩家身份执行命令。注意：命令参数不要带领先的斜杠 /。例如 #run: give @p apple 1\n");
        sb.append("   #over - 完成任务，停止对话。\n");
        sb.append("   #exit - 当用户想退出 CLI 时调用。\n");
        sb.append("   **注意：每轮回复只能包含一个工具调用。工具名和冒号之间不要有空格。执行命令时绝对不要带斜杠 /。**\n");
        sb.append("3. 执行 #run 前，建议先用 #search 查询命令语法。\n");
        sb.append("4. 你的思考过程（Thought）不应展示给用户，只需输出最终正文和工具调用。\n");
        
        return sb.toString();
    }
}
