package com.objcraft.plugin;

import com.objcraft.api.ObjCraftAPI;
import com.objcraft.platform.CompatibilityStatus;
import com.objcraft.platform.SchedulerAdapter;
import com.objcraft.platform.SchedulerAdapterFactory;
import com.objcraft.platform.VersionInspector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

public final class ObjCraftPlugin extends JavaPlugin {
    private SchedulerAdapter schedulerAdapter;
    private MeshResourcePackManager resourcePacks;
    private ObjCraftRuntimeAPI runtimeAPI;
    private Messages messages;
    private final VersionInspector versionInspector = new VersionInspector();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        messages.reload();
        final Path modelsDirectory = getDataFolder().toPath().resolve("models");
        try {
            Files.createDirectories(modelsDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not create the ObjCraft models folder", exception);
        }
        final CompatibilityStatus status = versionInspector.detect();
        getLogger().info("Detected runtime: " + status.platform() + " / " + status.serverVersion());
        getLogger().info("Compatibility verdict: " + status.verdict() + " - " + status.note());
        schedulerAdapter = SchedulerAdapterFactory.create(this);
        getLogger().info("Using scheduler adapter: " + schedulerAdapter.getClass().getSimpleName());

        resourcePacks = new MeshResourcePackManager(this, schedulerAdapter, messages);
        try {
            resourcePacks.start();
        } catch (IOException exception) {
            throw new IllegalStateException("Could not start the ObjCraft resource pack HTTP server", exception);
        }
        runtimeAPI = new ObjCraftRuntimeAPI(this, schedulerAdapter, resourcePacks);
        runtimeAPI.fileModels().preloadExisting();
        resourcePacks.replaceModels(runtimeAPI.fileModels().assets()).whenComplete((ignored, failure) -> {
            if (failure != null) {
                getLogger().severe("Could not build the initial ObjCraft resource pack: " + failure.getMessage());
            }
        });
        getServer().getServicesManager().register(
                ObjCraftAPI.class, runtimeAPI, this, org.bukkit.plugin.ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(new ResourcePackListener(resourcePacks, messages), this);
        final ObjCraftCommand command = new ObjCraftCommand(
                runtimeAPI.fileModels(),
                schedulerAdapter,
                resourcePacks,
                this,
                messages);
        final org.bukkit.command.PluginCommand pluginCommand =
                Objects.requireNonNull(getCommand("objcraft"), "objcraft command is missing from plugin.yml");
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
        getLogger().info("ObjCraft mesh renderer ready. Place .obj, .gltf, or .glb files in " + modelsDirectory);
    }

    @Override
    public void onDisable() {
        if (runtimeAPI != null) {
            runtimeAPI.vertexRenderer().removeAll();
        }
        if (resourcePacks != null) {
            resourcePacks.close();
        }
        if (schedulerAdapter != null) {
            schedulerAdapter.close();
        }
    }
}
