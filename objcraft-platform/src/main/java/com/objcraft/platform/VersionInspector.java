package com.objcraft.platform;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;

public final class VersionInspector {
    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    public CompatibilityStatus detect() {
        final String rawVersion = Bukkit.getServer() == null ? "unknown" : Bukkit.getServer().getVersion();
        final String normalized = normalize(rawVersion);
        final VersionParts version = parseVersion(normalized);
        final RuntimePlatform runtimePlatform = detectRuntimePlatform();
        final VersionSupport verdict = determineSupport(version.major(), version.minor(), version.patch());
        final boolean verified = verdict == VersionSupport.VERIFIED_SUPPORTED;
        final String note = switch (verdict) {
            case VERIFIED_SUPPORTED -> "Paper/Folia 1.21.x is within the verified compatibility matrix.";
            case UNVERIFIED -> "This runtime is outside the verified matrix and should be treated as unverified.";
            case UNSUPPORTED -> "This runtime is outside the current supported matrix.";
        };
        return new CompatibilityStatus(rawVersion, normalized, version.major(), version.minor(), version.patch(), runtimePlatform, verdict, verified, note);
    }

    public static VersionParts parseVersion(String raw) {
        final Matcher matcher = VERSION_PATTERN.matcher(normalize(raw));
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
        return raw.trim().replace('_', '.').toLowerCase(Locale.ROOT);
    }

    public static RuntimePlatform detectRuntimePlatform() {
        if (Bukkit.getServer() == null) {
            return RuntimePlatform.UNKNOWN;
        }
        final String className = Bukkit.getServer().getClass().getName().toLowerCase(Locale.ROOT);
        final String version = Bukkit.getServer().getVersion().toLowerCase(Locale.ROOT);
        if (className.contains("folia") || version.contains("folia")) {
            return RuntimePlatform.FOLIA;
        }
        if (className.contains("paper") || version.contains("paper") || version.contains("(mc:") || version.contains("mc:")) {
            return RuntimePlatform.PAPER;
        }
        return RuntimePlatform.UNKNOWN;
    }

    public static VersionSupport determineSupport(int major, int minor, int patch) {
        if (major == 1 && minor == 21) {
            return VersionSupport.VERIFIED_SUPPORTED;
        }
        if (major >= 1 && (major > 1 || minor >= 22)) {
            return VersionSupport.UNVERIFIED;
        }
        return VersionSupport.UNSUPPORTED;
    }

    public record VersionParts(int major, int minor, int patch) {
    }
}
