package com.objcraft.platform;

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
}
