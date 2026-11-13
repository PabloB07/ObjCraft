package com.objcraft.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GltfParserTest {
    @TempDir
    Path tempDirectory;

    @Test
    void importsTexturedGltfWithEmbeddedBufferAndUvCoordinates() throws Exception {
        final Fixture fixture = fixture();
        final String document = document("data:application/octet-stream;base64,"
                + Base64.getEncoder().encodeToString(fixture.binary()), fixture.binary().length, fixture.imageLength());
        final Path model = tempDirectory.resolve("model.gltf");
        Files.writeString(model, document, StandardCharsets.UTF_8);

        final GltfImportResult result = new GltfParser().parse(model, ObjImporterConfig.DEFAULT);

        assertEquals(1, result.triangles().size());
        assertEquals(new TextureCoordinate(0, 0), result.triangles().getFirst().uvA());
        assertEquals(new TextureCoordinate(1, 0), result.triangles().getFirst().uvB());
        assertEquals(new TextureCoordinate(0, 1), result.triangles().getFirst().uvC());
        assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(result.diffuseTexture())));
        assertNotNull(result.normalTexture());
        assertNotNull(result.metallicRoughnessTexture());
        assertNotNull(result.occlusionTexture());
    }

    @Test
    void importsTexturedGlbWithEmbeddedImageAndNodeTransform() throws Exception {
        final Fixture fixture = fixture();
        final byte[] glb = glb(document(null, fixture.binary().length, fixture.imageLength()), fixture.binary());
        final Path model = tempDirectory.resolve("model.glb");
        Files.write(model, glb);

        final GltfImportResult result = new GltfParser().parse(model, ObjImporterConfig.DEFAULT);

        assertEquals(1, result.triangles().size());
        assertEquals(2.0, result.triangles().getFirst().triangle().a().x());
        assertEquals(0.0, result.triangles().getFirst().triangle().a().y());
        assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(result.diffuseTexture())));
    }

    @Test
    void importsGltfWithRelativeBufferAndTextureFiles() throws Exception {
        final Fixture fixture = fixture();
        final byte[] imageBytes = java.util.Arrays.copyOfRange(
                fixture.binary(), 68, 68 + fixture.imageLength());
        final String document = document("mesh.bin", fixture.binary().length, fixture.imageLength())
                .replace("\"images\":[{\"bufferView\":3,\"mimeType\":\"image/png\"}]",
                        "\"images\":[{\"uri\":\"albedo.png\"}]");
        Files.writeString(tempDirectory.resolve("model.gltf"), document, StandardCharsets.UTF_8);
        Files.write(tempDirectory.resolve("mesh.bin"), fixture.binary());
        Files.write(tempDirectory.resolve("albedo.png"), imageBytes);

        final GltfImportResult result = new GltfParser().parse(
                tempDirectory.resolve("model.gltf"), ObjImporterConfig.DEFAULT);

        assertEquals(1, result.triangles().size());
        assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(result.diffuseTexture())));
    }

    @Test
    void rejectsExternalAssetsEscapingTheModelDirectory() throws Exception {
        final Fixture fixture = fixture();
        final String document = document("../mesh.bin", fixture.binary().length, fixture.imageLength());
        final Path model = tempDirectory.resolve("model.gltf");
        Files.writeString(model, document, StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> new GltfParser().parse(model, ObjImporterConfig.DEFAULT));
    }

    @Test
    void importsMetallicAndRoughnessMaterialFactors() throws Exception {
        final Fixture fixture = fixture();
        final String document = document(
                "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(fixture.binary()),
                fixture.binary().length,
                fixture.imageLength()).replace("\"baseColorTexture\"",
                        "\"metallicFactor\":0.5,\"roughnessFactor\":0.25,\"baseColorTexture\"");
        final Path model = tempDirectory.resolve("material-factor.gltf");
        Files.writeString(model, document, StandardCharsets.UTF_8);

        final GltfImportResult result = new GltfParser().parse(model, ObjImporterConfig.DEFAULT);
        assertEquals(0.5f, result.metallicFactor());
        assertEquals(0.25f, result.roughnessFactor());
    }

    @Test
    void bakesSkinnedNodeTranslationAnimationIntoGeometryFrames() throws Exception {
        final byte[] binary = skinnedAnimationBuffer();
        final String document = """
                {
                  "asset":{"version":"2.0"},
                  "scene":0,
                  "scenes":[{"nodes":[0,1]}],
                  "nodes":[{"mesh":0,"skin":0},{"name":"joint"}],
                  "skins":[{"joints":[1]}],
                  "meshes":[{"primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":1,"JOINTS_0":3,"WEIGHTS_0":4},"indices":2}]}],
                  "buffers":[{"uri":"data:application/octet-stream;base64,%s","byteLength":%d}],
                  "bufferViews":[
                    {"buffer":0,"byteOffset":0,"byteLength":36},
                    {"buffer":0,"byteOffset":36,"byteLength":24},
                    {"buffer":0,"byteOffset":60,"byteLength":6},
                    {"buffer":0,"byteOffset":68,"byteLength":12},
                    {"buffer":0,"byteOffset":80,"byteLength":48},
                    {"buffer":0,"byteOffset":128,"byteLength":8},
                    {"buffer":0,"byteOffset":136,"byteLength":24}
                  ],
                  "accessors":[
                    {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                    {"bufferView":1,"componentType":5126,"count":3,"type":"VEC2"},
                    {"bufferView":2,"componentType":5123,"count":3,"type":"SCALAR"},
                    {"bufferView":3,"componentType":5121,"count":3,"type":"VEC4"},
                    {"bufferView":4,"componentType":5126,"count":3,"type":"VEC4"},
                    {"bufferView":5,"componentType":5126,"count":2,"type":"SCALAR"},
                    {"bufferView":6,"componentType":5126,"count":2,"type":"VEC3"}
                  ],
                  "animations":[{"samplers":[{"input":5,"output":6,"interpolation":"LINEAR"}],
                    "channels":[{"sampler":0,"target":{"node":1,"path":"translation"}}]}]
                }
                """.formatted(Base64.getEncoder().encodeToString(binary), binary.length);
        final Path model = tempDirectory.resolve("skinned.gltf");
        Files.writeString(model, document, StandardCharsets.UTF_8);

        final GltfImportResult result = new GltfParser().parse(model, ObjImporterConfig.DEFAULT);

        assertEquals(11, result.animationFrames().size());
        assertEquals(20, result.animationDurationTicks());
        assertEquals(0.0, result.animationFrames().getFirst().getFirst().triangle().a().x());
        assertEquals(1.0, result.animationFrames().getLast().getFirst().triangle().a().x());
    }

    private static String document(String bufferUri, int bufferLength, int imageLength) {
        return """
                {
                  "asset":{"version":"2.0"},
                  "scene":0,
                  "scenes":[{"nodes":[0]}],
                  "nodes":[{"mesh":0,"translation":[2,0,0]}],
                  "meshes":[{"primitives":[{"attributes":{"POSITION":0,"TEXCOORD_0":1},"indices":2,"material":0}]}],
                  "materials":[{"pbrMetallicRoughness":{"baseColorTexture":{"index":0},"metallicRoughnessTexture":{"index":0}},"normalTexture":{"index":0},"occlusionTexture":{"index":0}}],
                  "textures":[{"source":0}],
                  "images":[{"bufferView":3,"mimeType":"image/png"}],
                  "buffers":[{%s"byteLength":%d}],
                  "bufferViews":[
                    {"buffer":0,"byteOffset":0,"byteLength":36},
                    {"buffer":0,"byteOffset":36,"byteLength":24},
                    {"buffer":0,"byteOffset":60,"byteLength":6},
                    {"buffer":0,"byteOffset":68,"byteLength":%d}
                  ],
                  "accessors":[
                    {"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                    {"bufferView":1,"componentType":5126,"count":3,"type":"VEC2"},
                    {"bufferView":2,"componentType":5123,"count":3,"type":"SCALAR"}
                  ]
                }
                """.formatted(bufferUri == null ? "" : "\"uri\":\"" + bufferUri + "\",", bufferLength, imageLength);
    }

    private static byte[] glb(String json, byte[] binary) {
        final byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        final int paddedJsonLength = (jsonBytes.length + 3) & ~3;
        final int paddedBinaryLength = (binary.length + 3) & ~3;
        final int totalLength = 12 + 8 + paddedJsonLength + 8 + paddedBinaryLength;
        final ByteBuffer output = ByteBuffer.allocate(totalLength).order(ByteOrder.LITTLE_ENDIAN);
        output.putInt(0x46546C67);
        output.putInt(2);
        output.putInt(totalLength);
        output.putInt(paddedJsonLength);
        output.putInt(0x4E4F534A);
        output.put(jsonBytes);
        while (output.position() < 20 + paddedJsonLength) {
            output.put((byte) ' ');
        }
        output.putInt(paddedBinaryLength);
        output.putInt(0x004E4942);
        output.put(binary);
        while (output.hasRemaining()) {
            output.put((byte) 0);
        }
        return output.array();
    }

    private static Fixture fixture() throws Exception {
        final BufferedImage image = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xFFFF0000);
        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        final byte[] imageBytes = png.toByteArray();
        final int imageOffset = 68;
        final int paddedLength = (imageOffset + imageBytes.length + 3) & ~3;
        final ByteBuffer binary = ByteBuffer.allocate(paddedLength).order(ByteOrder.LITTLE_ENDIAN);
        binary.putFloat(0).putFloat(0).putFloat(0);
        binary.putFloat(1).putFloat(0).putFloat(0);
        binary.putFloat(0).putFloat(1).putFloat(0);
        binary.putFloat(0).putFloat(0);
        binary.putFloat(1).putFloat(0);
        binary.putFloat(0).putFloat(1);
        binary.putShort((short) 0).putShort((short) 1).putShort((short) 2);
        binary.position(imageOffset);
        binary.put(imageBytes);
        return new Fixture(binary.array(), imageBytes.length);
    }

    private static byte[] skinnedAnimationBuffer() {
        final ByteBuffer buffer = ByteBuffer.allocate(160).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putFloat(0).putFloat(0).putFloat(0);
        buffer.putFloat(1).putFloat(0).putFloat(0);
        buffer.putFloat(0).putFloat(1).putFloat(0);
        buffer.putFloat(0).putFloat(0);
        buffer.putFloat(1).putFloat(0);
        buffer.putFloat(0).putFloat(1);
        buffer.putShort((short) 0).putShort((short) 1).putShort((short) 2);
        buffer.position(68);
        for (int vertex = 0; vertex < 3; vertex++) {
            buffer.put((byte) 0).put((byte) 0).put((byte) 0).put((byte) 0);
        }
        for (int vertex = 0; vertex < 3; vertex++) {
            buffer.putFloat(1).putFloat(0).putFloat(0).putFloat(0);
        }
        buffer.putFloat(0).putFloat(1);
        buffer.putFloat(0).putFloat(0).putFloat(0);
        buffer.putFloat(1).putFloat(0).putFloat(0);
        return buffer.array();
    }

    private record Fixture(byte[] binary, int imageLength) {
    }
}
