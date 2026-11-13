package com.objcraft.platform;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VersionInspectorTest {
    @Test
    void supportsPaper1_21() {
        assertEquals(VersionSupport.VERIFIED_SUPPORTED, VersionInspector.determineSupport(1, 21, 8));
    }

    @Test
    void marksPaper26AsUnverified() {
        assertEquals(VersionSupport.UNVERIFIED, VersionInspector.determineSupport(26, 3, 0));
    }

    @Test
    void marksFutureMinecraftVersionsAsUnverified() {
        assertEquals(VersionSupport.UNVERIFIED, VersionInspector.determineSupport(1, 26, 0));
    }
}
