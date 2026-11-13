package com.objcraft.compat;

public enum VersionSupport {
    VERIFIED_SUPPORTED("Verified support on the runtime matrix"),
    EXPERIMENTAL("Feature compatibility is likely but not yet verified for production use"),
    UNVERIFIED("Version is not covered by current verification evidence"),
    UNSUPPORTED("Version is explicitly outside the supported matrix");

    private final String description;

    VersionSupport(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }
}
