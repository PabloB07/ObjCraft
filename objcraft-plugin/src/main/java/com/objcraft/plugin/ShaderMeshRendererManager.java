package com.objcraft.plugin;

import com.objcraft.api.Model;
import com.objcraft.api.ModelInstance;
import com.objcraft.api.ModelTransform;
import com.objcraft.api.RenderMode;
import com.objcraft.api.RendererManager;
import com.objcraft.platform.SchedulerAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Vector;

final class ShaderMeshRendererManager implements RendererManager {
    private final Plugin plugin;
    private final SchedulerAdapter scheduler;
    private final FileModelManager models;
    private final MeshResourcePackManager resourcePacks;
    private final Map<UUID, MeshModelInstance> instances = new ConcurrentHashMap<>();

    ShaderMeshRendererManager(
            Plugin plugin,
            SchedulerAdapter scheduler,
            FileModelManager models,
            MeshResourcePackManager resourcePacks
    ) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.models = models;
        this.resourcePacks = resourcePacks;
    }

    @Override
    public CompletableFuture<ModelInstance> spawn(String modelId, Location location, RenderMode mode) {
        return models.load(modelId).thenCompose(model -> spawnModel(model, location, mode));
    }

    CompletableFuture<ModelInstance> spawnModel(Model model, Location location, RenderMode mode) {
        if (mode != RenderMode.VERTEX && mode != RenderMode.HYBRID) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("The shader mesh renderer supports VERTEX and HYBRID modes."));
        }
        if (location == null || location.getWorld() == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("A world location is required."));
        }
        final ImportedModelAsset asset = models.asset(model.id());
        if (asset == null || asset.triangles().isEmpty()) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("The model has no imported OBJ triangle data."));
        }
        final Location spawnLocation = location.clone();
        return resourcePacks.ensureModel(asset).thenCompose(ignored -> {
            if (!resourcePacks.isDeliveryConfigured()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Set resource-pack.public-url to a client-accessible HTTP(S) URL before spawning 3D models."));
            }
            return spawnDisplay(
                    model.id(),
                    MeshResourcePackBuilder.partCount(asset),
                    spawnLocation,
                    mode);
        });
    }

    @Override
    public CompletableFuture<Void> remove(ModelInstance instance) {
        if (!(instance instanceof MeshModelInstance meshInstance)) {
            return instance.remove();
        }
        instances.values().remove(meshInstance);
        return meshInstance.remove();
    }

    CompletableFuture<Void> removeAll() {
        final CompletableFuture<?>[] removals = instances.values().stream()
                .map(MeshModelInstance::remove)
                .toArray(CompletableFuture[]::new);
        instances.clear();
        return CompletableFuture.allOf(removals);
    }

    private CompletableFuture<ModelInstance> spawnDisplay(
            String modelId,
            int partCount,
            Location location,
            RenderMode mode
    ) {
        final CompletableFuture<ModelInstance> result = new CompletableFuture<>();
        final Location anchor = location.clone();
        try {
            scheduler.runAtLocation(anchor, () -> {
                final List<ItemDisplay> displays = new ArrayList<>(partCount);
                try {
                    for (int part = 0; part < partCount; part++) {
                        final ItemStack stack = new ItemStack(Material.PAPER);
                        final ItemMeta itemMeta = stack.getItemMeta();
                        itemMeta.setItemModel(new NamespacedKey(
                                plugin, MeshResourcePackBuilder.partName(modelId, part, partCount)));
                        stack.setItemMeta(itemMeta);
                        displays.add(anchor.getWorld().spawn(anchor, ItemDisplay.class, entity -> {
                            entity.setItemStack(stack);
                            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                            entity.setPersistent(false);
                            entity.setGravity(false);
                            entity.setInvulnerable(true);
                            entity.setVelocity(new Vector());
                            entity.setInterpolationDuration(0);
                            entity.setTeleportDuration(0);
                        }));
                    }
                    final MeshModelInstance instance = new MeshModelInstance(
                            modelId, anchor, mode, displays, scheduler);
                    instances.put(UUID.randomUUID(), instance);
                    result.complete(instance);
                } catch (RuntimeException exception) {
                    displays.forEach(ItemDisplay::remove);
                    result.completeExceptionally(exception);
                }
            });
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    private static final class MeshModelInstance implements ModelInstance {
        private final String modelId;
        private final Location anchor;
        private final RenderMode renderMode;
        private final List<ItemDisplay> displays;
        private final SchedulerAdapter scheduler;
        private double scale = 1.0;
        private double moveX;
        private double moveY;
        private double moveZ;
        private float rotationX;
        private float rotationY;
        private float rotationZ;
        private double seatOffsetX;
        private double seatOffsetY = 1.0;
        private double seatOffsetZ;
        private ArmorStand seat;
        private boolean removed;

        private MeshModelInstance(
                String modelId,
                Location anchor,
                RenderMode renderMode,
                List<ItemDisplay> displays,
                SchedulerAdapter scheduler
        ) {
            this.modelId = modelId;
            this.anchor = anchor.clone();
            this.renderMode = renderMode;
            this.displays = List.copyOf(displays);
            this.scheduler = scheduler;
        }

        @Override
        public String modelId() {
            return modelId;
        }

        @Override
        public synchronized Location location() {
            return anchor.clone().add(moveX, moveY, moveZ);
        }

        @Override
        public RenderMode renderMode() {
            return renderMode;
        }

        @Override
        public synchronized ModelTransform transform() {
            return new ModelTransform(scale, moveX, moveY, moveZ, rotationY, rotationX, rotationZ);
        }

        @Override
        public synchronized CompletableFuture<Void> setScale(double scale) {
            if (!(scale > 0) || !Double.isFinite(scale)) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Scale must be a positive finite number."));
            }
            this.scale = scale;
            return update();
        }

        @Override
        public synchronized CompletableFuture<Void> setRotation(float x, float y, float z) {
            if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Rotation must be finite."));
            }
            rotationX = x;
            rotationY = y;
            rotationZ = z;
            return update();
        }

        @Override
        public synchronized CompletableFuture<Void> move(double x, double y, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Move coordinates must be finite."));
            }
            moveX += x;
            moveY += y;
            moveZ += z;
            return update();
        }

        @Override
        public synchronized CompletableFuture<Void> setSeatOffset(double x, double y, double z) {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("Seat coordinates must be finite."));
            }
            seatOffsetX = x;
            seatOffsetY = y;
            seatOffsetZ = z;
            return updateSeat();
        }

        @Override
        public CompletableFuture<Void> sit(Player player) {
            if (player == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("A player is required."));
            }
            final CompletableFuture<Void> result = new CompletableFuture<>();
            final Location target;
            synchronized (this) {
                if (removed) {
                    return CompletableFuture.failedFuture(
                            new IllegalStateException("Model instance has been removed."));
                }
                target = seatLocation();
            }
            if (player.getWorld() != target.getWorld()) {
                return CompletableFuture.failedFuture(
                        new IllegalArgumentException("The player must be in the model's world to sit on it."));
            }
            try {
                scheduler.runAtLocation(target, () -> {
                    try {
                        synchronized (this) {
                            if (removed) {
                                throw new IllegalStateException("Model instance has been removed.");
                            }
                            if (seat == null || !seat.isValid()) {
                                seat = target.getWorld().spawn(target, ArmorStand.class, stand -> {
                                    stand.setInvisible(true);
                                    stand.setGravity(false);
                                    stand.setInvulnerable(true);
                                    stand.setPersistent(false);
                                    stand.setCollidable(false);
                                    stand.setBasePlate(false);
                                    stand.setArms(false);
                                    stand.setCanTick(false);
                                });
                            } else {
                                seat.teleport(target);
                            }
                            if (!seat.addPassenger(player)) {
                                throw new IllegalStateException("Minecraft refused to mount the player on the model.");
                            }
                        }
                        result.complete(null);
                    } catch (RuntimeException exception) {
                        result.completeExceptionally(exception);
                    }
                });
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
            return result;
        }

        @Override
        public synchronized CompletableFuture<Void> remove() {
            if (removed) {
                return CompletableFuture.completedFuture(null);
            }
            removed = true;
            final CompletableFuture<Void> displaysRemoved = forEachDisplay(display -> {
                if (display.isValid()) {
                    display.remove();
                }
            });
            final ArmorStand currentSeat = seat;
            if (currentSeat == null) {
                return displaysRemoved;
            }
            return CompletableFuture.allOf(displaysRemoved, forEntity(currentSeat, Entity::remove));
        }

        private CompletableFuture<Void> update() {
            if (removed) {
                return CompletableFuture.failedFuture(new IllegalStateException("Model instance has been removed."));
            }
            final Location target = anchor.clone().add(moveX, moveY, moveZ);
            final org.joml.Quaternionf rotation = new org.joml.Quaternionf()
                    .rotateZYX(rotationZ, rotationY, rotationX);
            final org.bukkit.util.Transformation transformation = new org.bukkit.util.Transformation(
                    new org.joml.Vector3f(),
                    rotation,
                    new org.joml.Vector3f((float) scale),
                    new org.joml.Quaternionf());
            return forEachDisplay(display -> {
                if (display.isValid()) {
                    display.teleport(target);
                    display.setVelocity(new Vector());
                    display.setTransformation(transformation);
                }
            }).thenCompose(ignored -> updateSeat());
        }

        private synchronized CompletableFuture<Void> updateSeat() {
            final ArmorStand currentSeat = seat;
            if (currentSeat == null || !currentSeat.isValid()) {
                return CompletableFuture.completedFuture(null);
            }
            final Location target = seatLocation();
            return forEntity(currentSeat, entity -> entity.teleport(target));
        }

        private synchronized Location seatLocation() {
            return anchor.clone().add(
                    moveX + seatOffsetX,
                    moveY + seatOffsetY,
                    moveZ + seatOffsetZ);
        }

        private CompletableFuture<Void> forEachDisplay(java.util.function.Consumer<ItemDisplay> operation) {
            final CompletableFuture<?>[] updates = displays.stream().map(display -> {
                final CompletableFuture<Void> update = new CompletableFuture<>();
                try {
                    scheduler.runForEntity(display, () -> {
                        try {
                            operation.accept(display);
                            update.complete(null);
                        } catch (RuntimeException exception) {
                            update.completeExceptionally(exception);
                        }
                    });
                } catch (RuntimeException exception) {
                    update.completeExceptionally(exception);
                }
                return update;
            }).toArray(CompletableFuture[]::new);
            return CompletableFuture.allOf(updates);
        }

        private CompletableFuture<Void> forEntity(Entity entity, java.util.function.Consumer<Entity> operation) {
            final CompletableFuture<Void> update = new CompletableFuture<>();
            try {
                scheduler.runForEntity(entity, () -> {
                    try {
                        operation.accept(entity);
                        update.complete(null);
                    } catch (RuntimeException exception) {
                        update.completeExceptionally(exception);
                    }
                });
            } catch (RuntimeException exception) {
                update.completeExceptionally(exception);
            }
            return update;
        }
    }
}
