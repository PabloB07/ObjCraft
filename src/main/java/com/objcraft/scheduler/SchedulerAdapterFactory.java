package com.objcraft.scheduler;

import com.objcraft.compat.RuntimePlatform;
import com.objcraft.compat.VersionInspector;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class SchedulerAdapterFactory {
    private SchedulerAdapterFactory() {
    }

    public static SchedulerAdapter create(Plugin plugin) {
        final RuntimePlatform platform = VersionInspector.detectRuntimePlatform();
        if (platform == RuntimePlatform.FOLIA) {
            return new FoliaSchedulerAdapter(plugin);
        }
        return new PaperSchedulerAdapter(plugin);
    }

    public static boolean isFoliaRuntime() {
        return VersionInspector.detectRuntimePlatform() == RuntimePlatform.FOLIA;
    }

    public static boolean isPaperRuntime() {
        return Bukkit.getServer().getVersion().toLowerCase().contains("paper") || VersionInspector.detectRuntimePlatform() == RuntimePlatform.PAPER;
    }
}
