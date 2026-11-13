package com.objcraft.platform;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

public final class FoliaSchedulerAdapter implements SchedulerAdapter {
    private final Plugin plugin;

    public FoliaSchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runTask(Runnable task) {
        try {
            final Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            scheduler.getClass().getMethod("run", Plugin.class, Runnable.class).invoke(scheduler, plugin, task);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Folia global scheduler unavailable", e);
        }
    }

    @Override
    public void runAtLocation(Location location, Runnable task) {
        try {
            final Object scheduler = Bukkit.class.getMethod("getRegionScheduler").invoke(null);
            final Method execute = scheduler.getClass().getMethod(
                    "execute", Plugin.class, org.bukkit.World.class, int.class, int.class, Runnable.class);
            execute.invoke(scheduler, plugin, location.getWorld(),
                    location.getBlockX() >> 4, location.getBlockZ() >> 4, task);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Folia region scheduler unavailable", e);
        }
    }

    @Override
    public void runForEntity(Entity entity, Runnable task) {
        try {
            final Object scheduler = entity.getClass().getMethod("getScheduler").invoke(entity);
            scheduler.getClass().getMethod(
                    "execute", Plugin.class, Runnable.class, Runnable.class, long.class)
                    .invoke(scheduler, plugin, task, (Runnable) () -> { }, 1L);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Folia entity scheduler unavailable", e);
        }
    }

    @Override
    public void runTaskLater(Runnable task, long delayTicks) {
        try {
            final Object scheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
            scheduler.getClass().getMethod("runDelayed", Plugin.class, Runnable.class, long.class).invoke(scheduler, plugin, task, delayTicks);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Folia delayed scheduler unavailable", e);
        }
    }

    @Override
    public void runAsync(Runnable task) {
        try {
            final Object scheduler = Bukkit.class.getMethod("getAsyncScheduler").invoke(null);
            scheduler.getClass().getMethod("runNow", Plugin.class, Runnable.class).invoke(scheduler, plugin, task);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Folia async scheduler unavailable", e);
        }
    }

    @Override
    public boolean isFolia() {
        return true;
    }
}
