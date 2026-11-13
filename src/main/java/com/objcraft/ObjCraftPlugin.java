package com.objcraft;

import com.objcraft.compat.CompatibilityStatus;
import com.objcraft.compat.RuntimePlatform;
import com.objcraft.compat.VersionInspector;
import com.objcraft.scheduler.SchedulerAdapter;
import com.objcraft.scheduler.SchedulerAdapterFactory;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ObjCraftPlugin extends JavaPlugin {
    private VersionInspector versionInspector;
    private SchedulerAdapter schedulerAdapter;

    @Override
    public void onEnable() {
        this.versionInspector = new VersionInspector(this);
        final CompatibilityStatus status = versionInspector.detect();

        getLogger().info("Detected runtime: " + status.platform() + " / " + status.serverVersion());
        getLogger().info("Compatibility verdict: " + status.verdict().description());

        if (status.platform() == RuntimePlatform.FOLIA) {
            getLogger().info("Folia-specific scheduling is enabled via reflection-safe adapter.");
        }

        this.schedulerAdapter = SchedulerAdapterFactory.create(this);
        getLogger().info("Scheduler adapter active: " + schedulerAdapter.getClass().getSimpleName());

        saveDefaultConfig();
        final FileConfiguration config = getConfig();
        if (config.getBoolean("compatibility.strict-version-check") && !status.isVerifiedSupported()) {
            getLogger().warning("This runtime is outside the verified Paper/Folia matrix; the plugin will operate conservatively.");
        }
    }

    @Override
    public void onDisable() {
        if (schedulerAdapter != null) {
            schedulerAdapter.close();
        }
    }

    public VersionInspector getVersionInspector() {
        return versionInspector;
    }

    public SchedulerAdapter getSchedulerAdapter() {
        return schedulerAdapter;
    }
}
