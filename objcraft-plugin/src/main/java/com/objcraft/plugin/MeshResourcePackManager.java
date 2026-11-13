package com.objcraft.plugin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class MeshResourcePackManager implements AutoCloseable {
    private static final String PACK_PATH = "/objcubed.zip";

    private final Plugin plugin;
    private final com.objcraft.platform.SchedulerAdapter scheduler;
    private final Messages messages;
    private final String host;
    private final int port;
    private final String publicUrl;
    private final boolean required;
    private final MeshResourcePackBuilder builder = new MeshResourcePackBuilder();
    private final Map<String, ImportedModelAsset> assets = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> packRequestPlayers = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> latestPackRequests = new ConcurrentHashMap<>();
    private final ExecutorService buildExecutor = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "ObjCraft-resource-pack-builder");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService httpExecutor = Executors.newFixedThreadPool(2, runnable -> {
        final Thread thread = new Thread(runnable, "ObjCraft-resource-pack-http");
        thread.setDaemon(true);
        return thread;
    });

    private volatile PackSnapshot publishedPack;
    private volatile CompletableFuture<Void> buildChain = CompletableFuture.completedFuture(null);
    private HttpServer server;

    MeshResourcePackManager(
            Plugin plugin,
            com.objcraft.platform.SchedulerAdapter scheduler,
            Messages messages
    ) {
        this.plugin = plugin;
        this.scheduler = scheduler;
        this.messages = messages;
        host = plugin.getConfig().getString("resource-pack.host", "0.0.0.0").trim();
        port = plugin.getConfig().getInt("resource-pack.port", 9100);
        publicUrl = plugin.getConfig().getString("resource-pack.public-url", "").trim();
        required = plugin.getConfig().getBoolean("resource-pack.required", false);
        if (host.isEmpty()) {
            throw new IllegalArgumentException("resource-pack.host cannot be empty.");
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("resource-pack.port must be between 1 and 65535.");
        }
        if (!publicUrl.isEmpty()) {
            validatePublicUrl(publicUrl);
        }
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext(PACK_PATH, this::servePack);
        server.setExecutor(httpExecutor);
        server.start();
        plugin.getLogger().info("Resource pack endpoint listening on " + host + ":" + port + PACK_PATH);
        if (publicUrl.isEmpty()) {
            plugin.getLogger().warning("Set resource-pack.public-url to an HTTP(S) URL reachable by clients before spawning 3D models.");
        } else {
            plugin.getLogger().info("Resource pack client URL: " + publicUrl);
        }
    }

    synchronized CompletableFuture<Void> replaceModels(Map<String, ImportedModelAsset> newAssets) {
        assets.clear();
        assets.putAll(newAssets);
        return scheduleBuild();
    }

    synchronized CompletableFuture<Void> ensureModel(ImportedModelAsset asset) {
        if (asset == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("The imported model asset is unavailable."));
        }
        final String id = asset.model().id();
        final boolean wasPresent = assets.containsKey(id);
        assets.put(id, asset);
        final PackSnapshot current = publishedPack;
        if (wasPresent && ((current != null && current.modelIds().contains(id)) || !buildChain.isDone())) {
            return buildChain;
        }
        return scheduleBuild();
    }

    boolean isDeliveryConfigured() {
        return !publicUrl.isEmpty();
    }

    void deliver(Player player) {
        if (publicUrl.isEmpty()) {
            return;
        }
        final CompletableFuture<Void> build = buildChain;
        build.whenComplete((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().severe("Could not prepare the ObjCraft resource pack: " + message(failure));
                return;
            }
            final PackSnapshot pack = publishedPack;
            if (pack == null) {
                plugin.getLogger().warning("Resource pack delivery was requested before the pack was built.");
                return;
            }
            try {
                scheduler.runForEntity(player, () -> {
                    if (player.isOnline()) {
                        final UUID playerId = player.getUniqueId();
                        final UUID requestId = UUID.randomUUID();
                        final UUID previousRequest = latestPackRequests.put(playerId, requestId);
                        if (previousRequest != null) {
                            packRequestPlayers.remove(previousRequest);
                        }
                        packRequestPlayers.put(requestId, playerId);
                        player.setResourcePack(requestId, publicUrl, pack.sha1(),
                                net.kyori.adventure.text.Component.text(messages.get("pack.prompt")), required);
                    }
                });
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Could not schedule resource pack delivery to " + player.getName()
                        + ": " + exception.getMessage());
            }
        });
    }

    void deliverToOnlinePlayers() {
        if (publicUrl.isEmpty()) {
            return;
        }
        scheduler.runTask(() -> Bukkit.getOnlinePlayers().forEach(this::deliver));
    }

    private synchronized CompletableFuture<Void> scheduleBuild() {
        final Map<String, ImportedModelAsset> modelSnapshot = Map.copyOf(assets);
        buildChain = buildChain.handle((ignored, failure) -> {
            if (failure != null) {
                plugin.getLogger().severe("A previous resource pack build failed: " + message(failure));
            }
            return null;
        }).thenRunAsync(() -> {
            try {
                final byte[] zip = builder.build(modelSnapshot);
                final String hash = sha1(zip);
                persist(zip);
                publishedPack = new PackSnapshot(zip, hash, Set.copyOf(modelSnapshot.keySet()));
                plugin.getLogger().info("Built ObjCraft resource pack (" + zip.length + " bytes, "
                        + modelSnapshot.size() + " model(s), SHA-1 " + hash + ").");
                deliverToOnlinePlayers();
            } catch (IOException | RuntimeException exception) {
                throw new CompletionException(exception);
            }
        }, buildExecutor);
        return buildChain;
    }

    boolean matchesPackStatus(UUID playerId, UUID requestId, String status, String playerName) {
        if (!playerId.equals(packRequestPlayers.get(requestId))
                || !requestId.equals(latestPackRequests.get(playerId))) {
            return false;
        }
        plugin.getLogger().info("Resource pack status for " + playerName + ": " + status);
        if (status.equals("FAILED_RELOAD")) {
            plugin.getLogger().warning("The pack downloaded for " + playerName
                    + " but the client could not load it; check the client's latest.log for the reload error.");
        } else if (status.equals("INVALID_URL") || status.equals("FAILED_DOWNLOAD")) {
            plugin.getLogger().warning("The resource pack could not be downloaded by " + playerName
                    + "; verify resource-pack.public-url is reachable from outside the server.");
        }
        if (Set.of("DECLINED", "FAILED_DOWNLOAD", "INVALID_URL", "FAILED_RELOAD",
                "SUCCESSFULLY_LOADED", "DISCARDED").contains(status)) {
            packRequestPlayers.remove(requestId, playerId);
            latestPackRequests.remove(playerId, requestId);
        }
        return true;
    }

    void forgetPlayer(UUID playerId) {
        final UUID requestId = latestPackRequests.remove(playerId);
        if (requestId != null) {
            packRequestPlayers.remove(requestId, playerId);
        }
    }

    private void servePack(HttpExchange exchange) throws IOException {
        try {
            if (!exchange.getRequestURI().getPath().equals(PACK_PATH)) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            if (!exchange.getRequestMethod().equals("GET") && !exchange.getRequestMethod().equals("HEAD")) {
                exchange.getResponseHeaders().set("Allow", "GET, HEAD");
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            final PackSnapshot pack = publishedPack;
            if (pack == null) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("ETag", "\"" + pack.sha1() + "\"");
            exchange.getResponseHeaders().set("Content-Length", Integer.toString(pack.zip().length));
            if (exchange.getRequestMethod().equals("HEAD")) {
                exchange.sendResponseHeaders(200, -1);
                return;
            }
            exchange.sendResponseHeaders(200, pack.zip().length);
            exchange.getResponseBody().write(pack.zip());
        } finally {
            exchange.close();
        }
    }

    private void persist(byte[] zip) throws IOException {
        final Path directory = plugin.getDataFolder().toPath().resolve("generated");
        Files.createDirectories(directory);
        final Path target = directory.resolve("objcubed.zip");
        final Path temporary = directory.resolve("objcubed.zip.tmp");
        Files.write(temporary, zip);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void validatePublicUrl(String value) {
        final URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("resource-pack.public-url must be a valid HTTP(S) URL.", exception);
        }
        if (uri.getScheme() == null
                || (!uri.getScheme().equalsIgnoreCase("http") && !uri.getScheme().equalsIgnoreCase("https"))
                || uri.getHost() == null) {
            throw new IllegalArgumentException("resource-pack.public-url must include an HTTP(S) scheme and host.");
        }
    }

    private static String sha1(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("The Java runtime does not provide SHA-1.", exception);
        }
    }

    private static String message(Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
        buildExecutor.shutdownNow();
        httpExecutor.shutdownNow();
        packRequestPlayers.clear();
        latestPackRequests.clear();
    }

    private record PackSnapshot(byte[] zip, String sha1, Set<String> modelIds) {
    }
}
