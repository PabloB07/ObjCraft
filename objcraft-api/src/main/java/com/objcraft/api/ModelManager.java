package com.objcraft.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;

public interface ModelManager {
    CompletableFuture<Model> load(String modelId);

    CompletableFuture<ModelInstance> spawn(String modelId, Location location, RenderMode mode);

    CompletableFuture<Void> remove(ModelInstance instance);

    CompletableFuture<Model> getModel(String modelId);

    List<String> listModels();
}
