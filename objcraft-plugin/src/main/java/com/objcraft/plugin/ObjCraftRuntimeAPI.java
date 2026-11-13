package com.objcraft.plugin;

import com.objcraft.api.ModelManager;
import com.objcraft.api.ObjCraftAPI;
import com.objcraft.api.RendererManager;
import com.objcraft.platform.SchedulerAdapter;
import org.bukkit.plugin.Plugin;

public final class ObjCraftRuntimeAPI implements ObjCraftAPI {
    private final FileModelManager modelManager;
    private final ShaderMeshRendererManager rendererManager;

    public ObjCraftRuntimeAPI(
            Plugin plugin,
            SchedulerAdapter scheduler,
            MeshResourcePackManager resourcePacks
    ) {
        modelManager = new FileModelManager(plugin);
        rendererManager = new ShaderMeshRendererManager(plugin, scheduler, modelManager, resourcePacks);
        modelManager.setRenderer(rendererManager);
    }

    FileModelManager fileModels() {
        return modelManager;
    }

    ShaderMeshRendererManager vertexRenderer() {
        return rendererManager;
    }

    @Override
    public ModelManager models() {
        return modelManager;
    }

    @Override
    public RendererManager renderer() {
        return rendererManager;
    }
}
