package org.YanPl;

import org.YanPl.command.CLICommand;
import org.YanPl.listener.ChatListener;
import org.YanPl.manager.CLIManager;
import org.YanPl.manager.ConfigManager;
import org.YanPl.manager.WorkspaceIndexer;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;

public final class MineAgent extends JavaPlugin {
    private ConfigManager configManager;
    private WorkspaceIndexer workspaceIndexer;
    private CLIManager cliManager;

    /**
     * 插件启用时的初始化逻辑
     */
    @Override
    public void onEnable() {
        // 初始化配置管理器
        configManager = new ConfigManager(this);
        
        // 初始化工作区索引器并执行索引
        workspaceIndexer = new WorkspaceIndexer(this);
        workspaceIndexer.indexAll();

        // 初始化 CLI 管理器
        cliManager = new CLIManager(this);

        // 注册命令
        CLICommand cliCommand = new CLICommand(this);
        getCommand("mineagent").setExecutor(cliCommand);
        getCommand("mineagent").setTabCompleter(cliCommand);

        // 注册监听器
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);

        // bStats 统计
        int pluginId = 28567;
        new Metrics(this, pluginId);
        
        checkSecureProfile();
        
        getLogger().info("MineAgent 已启用！");
    }

    private void checkSecureProfile() {
        try {
            // 使用反射检查，因为旧版本可能没有这个方法
            java.lang.reflect.Method method = getServer().getClass().getMethod("shouldEnforceSecureProfile");
            boolean enforce = (boolean) method.invoke(getServer());
            if (enforce) {
                getLogger().warning("====================================================");
                getLogger().warning("检测到服务器启用了 'enforce-secure-profile'。");
                getLogger().warning("这可能会导致 CLI 模式下的聊天拦截出现警告。");
                getLogger().warning("建议在 server.properties 中将其设置为 false。");
                getLogger().warning("====================================================");
            }
        } catch (Exception e) {
            // 如果方法不存在或调用失败，忽略
        }
    }

    /**
     * 插件停用时的逻辑
     */
    @Override
    public void onDisable() {
        getLogger().info("MineAgent 已禁用！");
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public WorkspaceIndexer getWorkspaceIndexer() {
        return workspaceIndexer;
    }

    public CLIManager getCliManager() {
        return cliManager;
    }
}
