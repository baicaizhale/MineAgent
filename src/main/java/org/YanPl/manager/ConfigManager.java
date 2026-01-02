package org.YanPl.manager;

import org.YanPl.MineAgent;
import org.YanPl.util.ResourceUtil;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 配置管理器，负责加载和保存插件配置
 */
public class ConfigManager {
    private final MineAgent plugin;
    private FileConfiguration config;

    public ConfigManager(MineAgent plugin) {
        this.plugin = plugin;
        checkAndUpdateConfig();
        loadConfig();
    }

    /**
     * 检测并更新配置文件
     */
    private void checkAndUpdateConfig() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            return;
        }

        FileConfiguration currentConfig = YamlConfiguration.loadConfiguration(configFile);
        String configVersion = currentConfig.getString("version", "");
        String pluginVersion = plugin.getDescription().getVersion();

        if (!configVersion.equals(pluginVersion)) {
            plugin.getLogger().info("检测到版本更新 (" + configVersion + " -> " + pluginVersion + ")，正在更新配置...");

            // 1. 读取旧配置到内存 (除了 version)
            Map<String, Object> oldValues = new HashMap<>();
            for (String key : currentConfig.getKeys(true)) {
                if (!key.equals("version")) {
                    oldValues.put(key, currentConfig.get(key));
                }
            }

            // 2. 删除旧配置和 preset 目录
            configFile.delete();
            File presetDir = new File(plugin.getDataFolder(), "preset");
            if (presetDir.exists()) {
                deleteDirectory(presetDir);
            }

            // 3. 释放新文件
            plugin.saveDefaultConfig();
            ResourceUtil.releaseResources(plugin, "preset/", true, ".txt");

            // 4. 将内存中的配置写入新 config.yml
            FileConfiguration newConfig = YamlConfiguration.loadConfiguration(configFile);
            for (Map.Entry<String, Object> entry : oldValues.entrySet()) {
                // 只有当新配置中存在该键时才覆盖，或者根据需求决定是否保留新配置的默认值
                // 这里选择覆盖新配置中的同名键，保留旧用户的设置
                if (newConfig.contains(entry.getKey())) {
                    newConfig.set(entry.getKey(), entry.getValue());
                }
            }
            
            try {
                newConfig.save(configFile);
                plugin.getLogger().info("配置文件更新完成！");
            } catch (IOException e) {
                plugin.getLogger().severe("保存新配置文件时出错: " + e.getMessage());
            }
        }
    }

    private void deleteDirectory(File directory) {
        File[] files = directory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        directory.delete();
    }

    /**
     * 加载配置文件
     */
    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        this.config = plugin.getConfig();
    }

    /**
     * 获取 CloudFlare API Token (cf_key)
     */
    public String getCloudflareCfKey() {
        return config.getString("cloudflare.cf_key", "");
    }

    /**
     * 获取 AI 模型名称
     */
    public String getCloudflareModel() {
        return config.getString("cloudflare.model", "@cf/openai/gpt-oss-120b");
    }

    /**
     * 获取超时分钟数
     */
    public int getTimeoutMinutes() {
        return config.getInt("settings.timeout_minutes", 10);
    }

    /**
     * 获取 Token 警告阈值
     */
    public int getTokenWarningThreshold() {
        return config.getInt("settings.token_warning_threshold", 500);
    }
}
