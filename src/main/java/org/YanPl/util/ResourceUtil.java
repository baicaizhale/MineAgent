package org.YanPl.util;

import org.YanPl.MineAgent;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 资源工具类，负责从 JAR 包中提取资源
 */
public class ResourceUtil {

    /**
     * 释放 JAR 包中指定目录下的所有文件
     *
     * @param plugin       插件实例
     * @param resourceDir  资源目录路径 (例如 "preset/")
     * @param replace      是否覆盖现有文件
     * @param extension    过滤扩展名 (例如 ".txt")，为 null 则不过滤
     */
    public static void releaseResources(MineAgent plugin, String resourceDir, boolean replace, String extension) {
        if (!resourceDir.endsWith("/")) {
            resourceDir += "/";
        }

        try {
            URL jarUrl = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
            URI jarUri = jarUrl.toURI();
            File jarFile = new File(jarUri);
            
            try (JarFile jar = new JarFile(jarFile)) {
                Enumeration<JarEntry> entries = jar.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String name = entry.getName();

                    if (name.startsWith(resourceDir) && !entry.isDirectory()) {
                        if (extension == null || name.endsWith(extension)) {
                            saveResource(plugin, name, replace);
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("释放资源目录 " + resourceDir + " 时出错: " + e.getMessage());
        }
    }

    /**
     * 保存资源文件到插件数据目录
     *
     * @param plugin       插件实例
     * @param resourcePath 资源路径
     * @param replace      是否覆盖
     */
    public static void saveResource(MineAgent plugin, String resourcePath, boolean replace) {
        File file = new File(plugin.getDataFolder(), resourcePath);
        if (replace || !file.exists()) {
            try {
                plugin.saveResource(resourcePath, replace);
            } catch (IllegalArgumentException e) {
                // 如果资源在 JAR 中也不存在，忽略
            }
        }
    }
}
