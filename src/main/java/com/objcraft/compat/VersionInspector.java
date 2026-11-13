package com.objcraft.compat;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

public final class VersionInspector {
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    private final Plugin plugin;

    public VersionInspector(Plugin plugin) {
        this.plugin = plugin;
    }

    public CompatibilityStatus detect() {
        final String rawVersion = Bukkit.getServer() == null ? "unknown" : Bukkit.getServer().getVersion();
        final String normalized = normalize(rawVersion);
        final VersionParts parts = parseVersion(normalized);
        final RuntimePlatform platform = detectRuntimePlatform();
        final VersionSupport support = determineSupport(parts.major(), parts.minor(), parts.patch());

        final boolean verified = support == VersionSupport.VERIFIED_SUPPORTED;
        final String note;
        if (support == VersionSupport.VERIFIED_SUPPORTED) {
            note = "Paper/Folia 1.21.x is within the currently verified runtime matrix.";
        } else if (support == VersionSupport.EXPERIMENTAL) {
            note = "This runtime exists outside the current verification set and should be treated as experimental.";
        } else if (support == VersionSupport.UNVERIFIED) {
            note = "The runtime may be compatible, but evidence is not available in this workspace for production claims.";
        } else {
            note = "The runtime is outside the supported matrix and should not be claimed as compatible without explicit validation.";
        }

        return CompatibilityStatus.of(
                rawVersion,
                normalized,
                parts.major(),
                parts.minor(),
                parts.patch(),
                platform,
                support,
                verified,
                note
        );
    }

    public static VersionParts parseVersion(String raw) {
        final String value = normalize(raw);
        final Matcher matcher = VERSION_PATTERN.matcher(value);
        if (!matcher.find()) {
            return new VersionParts(0, 0, 0);
        }

        final int major = Integer.parseInt(matcher.group(1));
        final int minor = Integer.parseInt(matcher.group(2));
        final int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        return new VersionParts(major, minor, patch);
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return "0.0.0";
        }
        final String cleaned = raw.trim();
        return cleaned.replace('_', '.').toLowerCase(Locale.ROOT);
    }

    public static RuntimePlatform detectRuntimePlatform() {
        if (Bukkit.getServer() == null) {
            return RuntimePlatform.UNKNOWN;
        }

        final String serverClassName = Bukkit.getServer().getClass().getName();
        final String serverVersion = Bukkit.getServer().getVersion();
        if (serverClassName.toLowerCase(Locale.ROOT).contains("folia")) {
            return RuntimePlatform.FOLIA;
        }
        if (serverClassName.toLowerCase(Locale.ROOT).contains("paper") || serverVersion.toLowerCase(Locale.ROOT).contains("paper")) {
            return RuntimePlatform.PAPER;
        }
        return RuntimePlatform.UNKNOWN;
    }

    public static VersionSupport determineSupport(int major, int minor, int patch) {
        if (major == 1 && minor == 21) {
            return VersionSupport.VERIFIED_SUPPORTED;
        }
        if (major == 1 && minor >= 22 && minor <= 26) {
            return VersionSupport.UNVERIFIED;
        }
        return VersionSupport.UNSUPPORTED;
    }

    public String getPluginName() {
        return plugin.getName();
    }

    public record VersionParts(int major, int minor, int patch) {
    }
}
