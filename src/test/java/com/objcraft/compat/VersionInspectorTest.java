package com.objcraft.compat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VersionInspectorTest {
    @Test
    void parsesSemanticVersionFromServerVersion() {
        final VersionInspector.VersionParts parts = VersionInspector.parseVersion("Paper 1.21.8-R0.1-SNAPSHOT");

        assertEquals(1, parts.major());
        assertEquals(21, parts.minor());
        assertEquals(8, parts.patch());
    }

    @Test
    void verifiesSupportedMatrixForPaperAndFolia1_21() {
        assertEquals(VersionSupport.VERIFIED_SUPPORTED, VersionInspector.determineSupport(1, 21, 8));
    }

    @Test
    void marksUnreleasedFutureVersionsAsUnverified() {
        assertEquals(VersionSupport.UNVERIFIED, VersionInspector.determineSupport(1, 26, 0));
    }
}
