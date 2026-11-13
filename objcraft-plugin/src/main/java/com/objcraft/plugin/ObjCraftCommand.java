package com.objcraft.plugin;

import com.objcraft.api.ModelInstance;
import com.objcraft.api.RenderMode;
import com.objcraft.platform.SchedulerAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class ObjCraftCommand implements CommandExecutor, TabCompleter {
    private final FileModelManager models;
    private final SchedulerAdapter scheduler;
    private final MeshResourcePackManager resourcePacks;
    private final JavaPlugin plugin;
    private final Messages messages;
    private final Map<String, ModelInstance> spawned = new ConcurrentHashMap<>();
    private final AtomicInteger nextSpawnId = new AtomicInteger(1);

    public ObjCraftCommand(
            FileModelManager models,
            SchedulerAdapter scheduler,
            MeshResourcePackManager resourcePacks,
            JavaPlugin plugin,
            Messages messages
    ) {
        this.models = models;
        this.scheduler = scheduler;
        this.resourcePacks = resourcePacks;
        this.plugin = plugin;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(java.util.Locale.ROOT)) {
            case "import" -> importModel(sender, args);
            case "list" -> listModels(sender);
            case "spawn" -> spawnModel(sender, args);
            case "remove" -> removeModel(sender, args);
            case "reload" -> reload(sender, args);
            case "edit" -> editModel(sender, args);
            case "sit" -> sitOnModel(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return matching(List.of("help", "import", "list", "spawn", "remove", "reload", "edit", "sit"), args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            return matching(models.listModels(), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            return matching(new ArrayList<>(spawned.keySet()), args[1]);
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("edit") || args[0].equalsIgnoreCase("sit"))) {
            return matching(new ArrayList<>(spawned.keySet()), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("edit")) {
            return matching(List.of("move", "rotate", "scale", "seat"), args[2]);
        }
        return List.of();
    }

    private void importModel(CommandSender sender, String[] args) {
        if (!sender.hasPermission("objcraft.import")) {
            sender.sendMessage(color(messages.get("command.permission.import")));
            return;
        }
        if (args.length != 2) {
            sender.sendMessage(color(messages.get("command.usage.import")));
            return;
        }
        final String modelId = modelId(args[1]);
        models.load(modelId).thenCompose(model ->
                resourcePacks.ensureModel(models.asset(model.id())).thenApply(ignored -> model)
        ).whenComplete((model, failure) -> {
            if (failure != null) {
                reply(sender, messages.get("command.import.failure", message(failure)));
                return;
            }
            reply(sender, messages.get("command.import.success", model.id()));
            if (!resourcePacks.isDeliveryConfigured()) {
                reply(sender, messages.get("command.import.url_missing"));
            }
        });
    }

    private void listModels(CommandSender sender) {
        final List<String> available = models.listModels();
        if (available.isEmpty()) {
            sender.sendMessage(color(messages.get("command.models.empty")));
            return;
        }
        sender.sendMessage(color(messages.get("command.models.list", available.size(), String.join(", ", available))));
    }

    private void spawnModel(CommandSender sender, String[] args) {
        if (!sender.hasPermission("objcraft.spawn")) {
            sender.sendMessage(color(messages.get("command.spawn.permission")));
            return;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color(messages.get("command.spawn.player_only")));
            return;
        }
        if (args.length != 2) {
            sender.sendMessage(color(messages.get("command.spawn.usage")));
            return;
        }

        final Location spawnLocation = player.getLocation();
        final org.bukkit.util.Vector direction = spawnLocation.getDirection().setY(0).normalize();
        spawnLocation.add(direction.multiply(3));
        spawn(modelId(args[1]), spawnLocation).whenComplete((instance, failure) -> {
            if (failure != null) {
                sendAt(player, messages.get("command.spawn.failure", message(failure)));
                return;
            }
            final String spawnId = "m" + nextSpawnId.getAndIncrement();
            spawned.put(spawnId, instance);
            sendAt(player, messages.get("command.spawn.success", instance.modelId(), spawnId));
        });
    }

    private java.util.concurrent.CompletableFuture<ModelInstance> spawn(String modelId, Location location) {
        return models.spawn(modelId, location, RenderMode.VERTEX);
    }

    private void removeModel(CommandSender sender, String[] args) {
        if (!sender.hasPermission("objcraft.remove")) {
            sender.sendMessage(color(messages.get("command.remove.permission")));
            return;
        }
        if (args.length != 2) {
            sender.sendMessage(color(messages.get("command.remove.usage")));
            return;
        }
        final ModelInstance instance = spawned.get(args[1]);
        if (instance == null) {
            sender.sendMessage(color(messages.get("command.instance.not_found")));
            return;
        }
        models.remove(instance).whenComplete((ignored, failure) -> {
            if (failure != null) {
                reply(sender, messages.get("command.remove.failure", message(failure)));
            } else {
                spawned.remove(args[1], instance);
                reply(sender, messages.get("command.remove.success", args[1]));
            }
        });
    }

    private void reload(CommandSender sender, String[] args) {
        if (!sender.hasPermission("objcraft.reload")) {
            sender.sendMessage(color(messages.get("command.reload.permission")));
            return;
        }
        if (args.length != 1) {
            sender.sendMessage(color(messages.get("command.reload.usage")));
            return;
        }
        plugin.reloadConfig();
        messages.reload();
        reply(sender, messages.get("command.reload.start"));
        java.util.concurrent.CompletableFuture
                .supplyAsync(models::reloadAssets)
                .thenCompose(assets -> resourcePacks.replaceModels(assets).thenApply(ignored -> assets.size()))
                .whenComplete((modelCount, failure) -> {
                    if (failure != null) {
                        reply(sender, messages.get("command.reload.failure", message(failure)));
                        return;
                    }
                    reply(sender, messages.get("command.reload.success", modelCount));
                });
    }

    private void editModel(CommandSender sender, String[] args) {
        if (!sender.hasPermission("objcraft.edit")) {
            sender.sendMessage(color(messages.get("command.edit.permission")));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(color(messages.get("command.edit.usage")));
            return;
        }
        final ModelInstance instance = spawned.get(args[1]);
        if (instance == null) {
            sender.sendMessage(color(messages.get("command.instance.not_found")));
            return;
        }
        try {
            final String operation = args[2].toLowerCase(java.util.Locale.ROOT);
            final java.util.concurrent.CompletableFuture<Void> change;
            switch (operation) {
                case "move" -> {
                    requireArgumentCount(args, 6);
                    change = instance.move(number(args[3]), number(args[4]), number(args[5]));
                }
                case "rotate" -> {
                    requireArgumentCount(args, 6);
                    change = instance.setRotation(
                            (float) number(args[3]), (float) number(args[4]), (float) number(args[5]));
                }
                case "scale" -> {
                    requireArgumentCount(args, 4);
                    change = instance.setScale(number(args[3]));
                }
                case "seat" -> {
                    requireArgumentCount(args, 6);
                    change = instance.setSeatOffset(number(args[3]), number(args[4]), number(args[5]));
                }
                default -> {
                    sender.sendMessage(color(messages.get("command.edit.operation_unknown")));
                    return;
                }
            }
            change.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    reply(sender, messages.get("command.edit.failure", message(failure)));
                } else {
                    reply(sender, messages.get("command.edit.success", args[1]));
                }
            });
        } catch (IllegalArgumentException exception) {
            reply(sender, messages.get("command.edit." + exception.getMessage()));
        }
    }

    private void sitOnModel(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(color(messages.get("command.sit.player_only")));
            return;
        }
        if (!player.hasPermission("objcraft.sit")) {
            player.sendMessage(color(messages.get("command.sit.permission")));
            return;
        }
        if (args.length != 2) {
            player.sendMessage(color(messages.get("command.sit.usage")));
            return;
        }
        final ModelInstance instance = spawned.get(args[1]);
        if (instance == null) {
            player.sendMessage(color(messages.get("command.instance.not_found")));
            return;
        }
        instance.sit(player).whenComplete((ignored, failure) -> {
            if (failure != null) {
                sendAt(player, messages.get("command.sit.failure", message(failure)));
            } else {
                sendAt(player, messages.get("command.sit.success", instance.modelId()));
            }
        });
    }

    private static void requireArgumentCount(String[] args, int expected) {
        if (args.length != expected) {
            throw new IllegalArgumentException("argument_count");
        }
    }

    private static double number(String value) {
        try {
            final double parsed = Double.parseDouble(value);
            if (!Double.isFinite(parsed)) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("number");
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(color(messages.get("help.title")));
        sender.sendMessage(color(messages.get("help.reload")));
        sender.sendMessage(color(messages.get("help.edit")));
        sender.sendMessage(color(messages.get("help.sit")));
        sender.sendMessage(color(messages.get("help.list")));
        sender.sendMessage(color(messages.get("help.import")));
        sender.sendMessage(color(messages.get("help.spawn")));
        sender.sendMessage(color(messages.get("help.remove")));
    }

    private void sendAt(Player player, String message) {
        scheduler.runForEntity(player, () -> player.sendMessage(color(message)));
    }

    private void reply(CommandSender sender, String message) {
        if (sender instanceof Player player) {
            sendAt(player, message);
        } else {
            scheduler.runTask(() -> sender.sendMessage(color(message)));
        }
    }

    private static String modelId(String input) {
        final String lower = input.toLowerCase(java.util.Locale.ROOT);
        for (String extension : List.of(".obj", ".gltf", ".glb")) {
            if (lower.endsWith(extension)) {
                return input.substring(0, input.length() - extension.length());
            }
        }
        return input;
    }

    private static List<String> matching(List<String> values, String prefix) {
        final String normalized = prefix.toLowerCase(java.util.Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(java.util.Locale.ROOT).startsWith(normalized)).toList();
    }

    private static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    private static Component color(String message) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize("&3[ObjCraft] " + message);
    }
}
