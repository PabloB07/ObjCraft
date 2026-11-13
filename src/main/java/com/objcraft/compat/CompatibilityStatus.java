package com.objcraft.compat;

public record CompatibilityStatus(
        String serverVersion,
        String normalizedVersion,
        int major,
        int minor,
        int patch,
        RuntimePlatform platform,
        VersionSupport verdict,
        boolean isVerifiedSupported,
        String note
) {
    public static CompatibilityStatus of(
            String serverVersion,
            String normalizedVersion,
            int major,
            int minor,
            int patch,
            RuntimePlatform platform,
            VersionSupport verdict,
            boolean isVerifiedSupported,
            String note
    ) {
        return new CompatibilityStatus(serverVersion, normalizedVersion, major, minor, patch, platform, verdict,
                isVerifiedSupported, note);
    }
}
