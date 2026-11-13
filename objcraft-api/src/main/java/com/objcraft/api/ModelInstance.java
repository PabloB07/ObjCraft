package com.objcraft.api;

import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.entity.Player;

public interface ModelInstance {
    String modelId();

    Location location();

    RenderMode renderMode();

    ModelTransform transform();

    CompletableFuture<Void> setScale(double scale);

    CompletableFuture<Void> setRotation(float x, float y, float z);

    CompletableFuture<Void> move(double x, double y, double z);

    default CompletableFuture<Void> setSeatOffset(double x, double y, double z) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("This renderer has no seat."));
    }

    default CompletableFuture<Void> sit(Player player) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("This renderer has no seat."));
    }

    CompletableFuture<Void> remove();
}
