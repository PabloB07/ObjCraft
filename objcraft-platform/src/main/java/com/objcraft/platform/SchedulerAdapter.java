package com.objcraft.platform;

import org.bukkit.Location;
import org.bukkit.entity.Entity;

public interface SchedulerAdapter extends AutoCloseable {
    void runTask(Runnable task);

    void runAtLocation(Location location, Runnable task);

    void runForEntity(Entity entity, Runnable task);

    void runTaskLater(Runnable task, long delayTicks);

    void runAsync(Runnable task);

    default boolean isFolia() {
        return false;
    }

    @Override
    default void close() {
    }
}
