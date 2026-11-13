package com.objcraft.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObjParserTest {
    @Test
    void parsesVerticesAndTriangulatesFaces() throws Exception {
        final String obj = """
                v 0 0 0
                v 1 0 0
                v 0 1 0
                v 0 0 1
                f 1 2 3 4
                """;

        final ObjImportResult result = new ObjParser().parse(new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(result);
        assertNotNull(result.mesh());
        assertEquals(2, result.mesh().triangles().size());
    }

    @Test
    void supportsNegativeIndices() throws Exception {
        final String obj = """
                v 0 0 0
                v 1 0 0
                v 0 1 0
                f -3 -2 -1
                """;

        final ObjImportResult result = new ObjParser().parse(new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8)));
        assertEquals(1, result.mesh().triangles().size());
    }

    @Test
    void enforcesVertexLimitWhileParsing() {
        final String obj = """
                v 0 0 0
                v 1 0 0
                v 0 1 0
                v 0 0 1
                """;
        final ObjImporterConfig config = new ObjImporterConfig(1024, 3, 10, 4, 32, true);

        assertThrows(IOException.class, () -> new ObjParser().parse(
                new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8)), config));
    }

    @Test
    void enforcesTriangulatedFaceLimitWhileParsing() {
        final String obj = """
                v 0 0 0
                v 1 0 0
                v 1 1 0
                v 0 1 0
                f 1 2 3 4
                """;
        final ObjImporterConfig config = new ObjImporterConfig(1024, 10, 1, 4, 32, true);

        assertThrows(IOException.class, () -> new ObjParser().parse(
                new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8)), config));
    }

    @Test
    void acceptsModelsLargerThanTheFormerMvpLimit() throws Exception {
        final int vertexCount = 12_019;
        final int faceCount = 24_086;
        final StringBuilder obj = new StringBuilder(vertexCount * 20 + faceCount * 10);
        for (int index = 0; index < vertexCount; index++) {
            obj.append("v ").append(index).append(" 0 0\n");
        }
        for (int index = 0; index < faceCount; index++) {
            obj.append("f 1 2 3\n");
        }
        final ObjImporterConfig config = new ObjImporterConfig(16L * 1024 * 1024, 50_000, 100_000, 32, 64, true);

        final ObjImportResult result = new ObjParser().parse(
                new ByteArrayInputStream(obj.toString().getBytes(StandardCharsets.UTF_8)), config);

        assertEquals(vertexCount, result.mesh().vertices().size());
        assertEquals(faceCount, result.mesh().triangles().size());
    }

    @Test
    void preservesTextureCoordinatesAndMaterialReferences() throws Exception {
        final String obj = """
                mtllib hamster.mtl
                v 0 0 0
                v 1 0 0
                v 0 1 0
                vt 0.1 0.2
                vt 0.8 0.2
                vt 0.1 0.9
                usemtl Fur
                f 1/1 2/2 3/3
                """;

        final ObjImportResult result = new ObjParser().parse(
                new ByteArrayInputStream(obj.getBytes(StandardCharsets.UTF_8)));

        assertEquals(List.of("hamster.mtl"), result.materialLibraries());
        assertEquals(1, result.objTriangles().size());
        final ObjTriangle triangle = result.objTriangles().getFirst();
        assertEquals("Fur", triangle.materialName());
        assertEquals(new TextureCoordinate(0.1, 0.2), triangle.uvA());
        assertEquals(new TextureCoordinate(0.8, 0.2), triangle.uvB());
        assertEquals(new TextureCoordinate(0.1, 0.9), triangle.uvC());
        assertTrue(triangle.hasTextureCoordinates());
    }
}
