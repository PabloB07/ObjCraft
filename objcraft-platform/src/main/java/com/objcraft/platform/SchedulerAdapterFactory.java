package com.objcraft.platform;

import org.bukkit.plugin.Plugin;

public final class SchedulerAdapterFactory {
    private SchedulerAdapterFactory() {
    }

    public static SchedulerAdapter create(Plugin plugin) {
        return VersionInspector.detectRuntimePlatform() == RuntimePlatform.FOLIA
                ? new FoliaSchedulerAdapter(plugin)
                : new PaperSchedulerAdapter(plugin);
    }
}
