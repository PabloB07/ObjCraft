package com.objcraft.api;

import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;

public interface RendererManager {
    CompletableFuture<ModelInstance> spawn(String modelId, Location location, RenderMode mode);

    CompletableFuture<Void> remove(ModelInstance instance);
}
