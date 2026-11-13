package com.objcraft.plugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

final class ResourcePackListener implements Listener {
    private final MeshResourcePackManager resourcePacks;
    private final Messages messages;

    ResourcePackListener(MeshResourcePackManager resourcePacks, Messages messages) {
        this.resourcePacks = resourcePacks;
        this.messages = messages;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        resourcePacks.deliver(event.getPlayer());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        resourcePacks.forgetPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onResourcePackStatus(PlayerResourcePackStatusEvent event) {
        if (!resourcePacks.matchesPackStatus(
                event.getPlayer().getUniqueId(), event.getID(), event.getStatus().name(),
                event.getPlayer().getName())) {
            return;
        }
        switch (event.getStatus().name()) {
            case "DECLINED" -> event.getPlayer().sendMessage(
                    net.kyori.adventure.text.Component.text(messages.get("pack.declined")));
            case "FAILED_DOWNLOAD" -> event.getPlayer().sendMessage(
                    net.kyori.adventure.text.Component.text(messages.get("pack.download_failed")));
            case "INVALID_URL" -> event.getPlayer().sendMessage(
                    net.kyori.adventure.text.Component.text(messages.get("pack.invalid_url")));
            case "FAILED_RELOAD" -> event.getPlayer().sendMessage(
                    net.kyori.adventure.text.Component.text(messages.get("pack.reload_failed")));
            case "SUCCESSFULLY_LOADED" -> event.getPlayer().sendMessage(
                    net.kyori.adventure.text.Component.text(messages.get("pack.loaded")));
            default -> {
            }
        }
    }
}
