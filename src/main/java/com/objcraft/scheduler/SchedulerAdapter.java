package com.objcraft.scheduler;

public interface SchedulerAdapter extends AutoCloseable {
    void runTask(Runnable task);

    void runTaskLater(Runnable task, long delayTicks);

    void runAsync(Runnable task);

    default boolean isFolia() {
        return false;
    }

    @Override
    default void close() {
        // no-op default; adapters may override if they need cleanup
    }
}
