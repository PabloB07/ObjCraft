package com.objcraft.scheduler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class FoliaSchedulerAdapter implements SchedulerAdapter {
    private final Plugin plugin;

    public FoliaSchedulerAdapter(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void runTask(Runnable task) {
        invokeGlobalRegionScheduler(task);
    }

    @Override
    public void runTaskLater(Runnable task, long delayTicks) {
        invokeGlobalRegionSchedulerDelayed(task, delayTicks);
    }

    @Override
    public void runAsync(Runnable task) {
        invokeGlobalRegionSchedulerConsumer(task);
    }

    @Override
    public boolean isFolia() {
        return true;
    }

    private void invokeGlobalRegionScheduler(Runnable task) {
        invokeGlobalRegionSchedulerMethod("run", task, null);
    }

    private void invokeGlobalRegionSchedulerDelayed(Runnable task, long delayTicks) {
        invokeGlobalRegionSchedulerMethod("runDelayed", task, delayTicks);
    }

    private void invokeGlobalRegionSchedulerConsumer(Runnable task) {
        invokeGlobalRegionSchedulerConsumerMethod("run", task, 0L);
    }

    private void invokeGlobalRegionSchedulerMethod(String methodName, Runnable task, Long delayTicks) {
        try {
            final Object scheduler = invokeGetter(Bukkit.class, "getGlobalRegionScheduler");
            final Method method = findCompatibleMethod(scheduler, methodName, delayTicks == null);
            if (method == null) {
                throw new IllegalStateException("Folia global region scheduler method '" + methodName + "' is not available on this runtime");
            }

            final Class<?>[] parameterTypes = method.getParameterTypes();
            if (delayTicks == null) {
                if (parameterTypes[1].equals(Runnable.class)) {
                    method.invoke(scheduler, plugin, task);
                } else {
                    method.invoke(scheduler, plugin, adaptRunnable(task));
                }
            } else {
                if (parameterTypes[1].equals(Runnable.class)) {
                    method.invoke(scheduler, plugin, task, delayTicks);
                } else {
                    method.invoke(scheduler, plugin, adaptRunnable(task), delayTicks);
                }
            }
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
            throw new IllegalStateException("Folia scheduling failed on the current runtime", e);
        }
    }

    private void invokeGlobalRegionSchedulerConsumerMethod(String methodName, Runnable task, long delayTicks) {
        try {
            final Object scheduler = invokeGetter(Bukkit.class, "getGlobalRegionScheduler");
            final Class<?> consumerType = Class.forName("java.util.function.Consumer");
            final Method method = findMethodById(scheduler, methodName, plugin.getClass(), consumerType, long.class);
            if (method != null) {
                method.invoke(scheduler, plugin, (Consumer<Object>) ignored -> task.run(), delayTicks);
                return;
            }

            final Method fallback = findMethodById(scheduler, methodName, plugin.getClass(), consumerType);
            if (fallback != null) {
                fallback.invoke(scheduler, plugin, (Consumer<Object>) ignored -> task.run());
                return;
            }

            throw new IllegalStateException("No Folia asynchronous scheduler method matched the runtime signature");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Folia asynchronous scheduling failed on the current runtime", e);
        }
    }

    private static Object invokeGetter(Class<?> owner, String methodName) throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        final Method getter = owner.getMethod(methodName);
        return getter.invoke(null);
    }

    private static Method findCompatibleMethod(Object target, String methodName, boolean withoutDelay) throws NoSuchMethodException {
        final Class<?>[] types = withoutDelay ? new Class<?>[] {Plugin.class, Consumer.class} : new Class<?>[] {Plugin.class, Consumer.class, long.class};
        final Method method = findMethodById(target, methodName, types);
        if (method != null) {
            return method;
        }

        if (withoutDelay) {
            return target.getClass().getMethod(methodName, Plugin.class, Runnable.class);
        }
        return target.getClass().getMethod(methodName, Plugin.class, Runnable.class, long.class);
    }

    private static Method findMethodById(Object target, String methodName, Class<?>... parameterTypes) {
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(methodName)) {
                continue;
            }
            final Class<?>[] declared = method.getParameterTypes();
            if (declared.length != parameterTypes.length) {
                continue;
            }
            boolean matches = true;
            for (int index = 0; index < declared.length; index++) {
                if (!declared[index].equals(parameterTypes[index])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return method;
            }
        }
        return null;
    }

    private static Consumer<Object> adaptRunnable(Runnable task) {
        return ignored -> task.run();
    }
}
