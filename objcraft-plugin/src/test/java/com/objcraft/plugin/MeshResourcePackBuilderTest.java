package com.objcraft.plugin;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.objcraft.api.ModelTransform;
import com.objcraft.geometry.Mesh;
import com.objcraft.geometry.Triangle;
import com.objcraft.geometry.Vertex;
import com.objcraft.importer.ObjTriangle;
import com.objcraft.importer.TextureCoordinate;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class MeshResourcePackBuilderTest {
    private static final Vertex A = new Vertex(0, 0, 0);
    private static final Vertex B = new Vertex(1, 0, 0);
    private static final Vertex C = new Vertex(0, 1, 1);
    private static final Triangle TRIANGLE = new Triangle(A, B, C);
    private static final List<ObjTriangle> TRIANGLES = List.of(new ObjTriangle(
            TRIANGLE,
            new TextureCoordinate(0, 0),
            new TextureCoordinate(1, 0),
            new TextureCoordinate(0, 1),
            "fur"));

    @Test
    void encodesShaderHeaderCarrierUvAndNormalizedMeshData() throws Exception {
        final BufferedImage texture = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        texture.setRGB(0, 0, 0xFFFF0000);
        texture.setRGB(0, 1, 0xFF0000FF);
        final MeshResourcePackBuilder.EncodedModel encoded = MeshResourcePackBuilder.encodeModel(
                "Hamster", TRIANGLES, texture);
        final BufferedImage png = ImageIO.read(new ByteArrayInputStream(encoded.texturePng()));

        assertEquals(16, png.getWidth());
        assertEquals(32, png.getHeight());
        assertArrayEquals(new int[] {12, 34, 56, 255}, rgba(png, 0, 0));
        assertEquals(4, ((rgba(png, 2, 0)[0] << 24) | (rgba(png, 2, 0)[1] << 16)
                | (rgba(png, 2, 0)[2] << 8) | rgba(png, 7, 0)[1]));
        assertArrayEquals(new int[] {0, 0, 0, 2}, rgba(png, 0, 2));
        assertEquals(8_257_536, u24(png, 0, 19));
        assertEquals(8_355_840, u24(png, 1, 19));
        assertEquals(0, u24(png, 0, 20));
        assertEquals(0, u24(png, 0, 21));
        assertArrayEquals(new int[] {0, 0, 255, 255}, rgba(png, 0, 3));
        assertTrue(encoded.modelJson().contains("\"from\":[8,8,8],\"to\":[24,24,8]"));
        assertTrue(encoded.modelJson().contains("\"texture\":\"#0\""));
        assertTrue(encoded.modelJson().contains("\"fixed\""));
    }

    @Test
    void encodesAnimationFramesAndPbrTextureLayers() throws Exception {
        final ObjTriangle secondFrame = new ObjTriangle(
                new Triangle(new Vertex(2, 0, 0), new Vertex(3, 0, 0), new Vertex(2, 1, 1)),
                TRIANGLES.getFirst().uvA(),
                TRIANGLES.getFirst().uvB(),
                TRIANGLES.getFirst().uvC(),
                "fur");
        final BufferedImage diffuse = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        final BufferedImage normal = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        final BufferedImage metallicRoughness = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        final BufferedImage occlusion = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        normal.setRGB(1, 15, 0xFF123456);
        metallicRoughness.setRGB(1, 15, 0xFF654321);
        occlusion.setRGB(1, 15, 0xFFABCDEF);

        final MeshResourcePackBuilder.EncodedModel encoded = MeshResourcePackBuilder.encodeModel(
                "Animated", List.of(TRIANGLES, List.of(secondFrame)), 20,
                diffuse, normal, metallicRoughness, 0.5f, 0.25f, occlusion);
        final BufferedImage png = ImageIO.read(new ByteArrayInputStream(encoded.texturePng()));

        assertEquals(128, png.getHeight());
        assertArrayEquals(new int[] {0, 0, 2, 1}, rgba(png, 3, 0));
        assertArrayEquals(new int[] {0, 0, 20, 196}, rgba(png, 4, 0));
        assertArrayEquals(new int[] {0x12, 0x34, 0x56, 255}, rgba(png, 1, 23));
        assertArrayEquals(new int[] {0x65, 0x11, 0x11, 255}, rgba(png, 1, 39));
        assertArrayEquals(new int[] {0xAB, 0xCD, 0xEF, 255}, rgba(png, 1, 55));
    }

    @Test
    void splitsLargeModelsIntoShaderCompatibleParts() throws Exception {
        assertEquals(2, MeshResourcePackBuilder.partCount(20_001));
        assertEquals("large_part_2", MeshResourcePackBuilder.partName("large", 1, 2));
        final Mesh mesh = new Mesh(List.of(A, B, C), List.of(TRIANGLE));
        final SimpleModel model = new SimpleModel("large", "large", List.of(mesh), ModelTransform.identity());
        final ImportedModelAsset asset = new ImportedModelAsset(
                model,
                java.util.Collections.nCopies(20_001, TRIANGLES.getFirst()),
                List.of(),
                1,
                1.0f,
                1.0f,
                List.of(),
                null,
                null,
                null,
                null);
        final byte[] pack = new MeshResourcePackBuilder().build(Map.of(model.id(), asset));
        final Set<String> entries = new java.util.HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(pack))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
            }
        }

        assertTrue(entries.contains("assets/objcraft/items/large_part_1.json"));
        assertTrue(entries.contains("assets/objcraft/items/large_part_2.json"));
        assertTrue(entries.contains("assets/objcraft/models/item/large_part_1.json"));
        assertTrue(entries.contains("assets/objcraft/models/item/large_part_2.json"));
        assertTrue(entries.contains("assets/objcraft/textures/item/large_part_1.png"));
        assertTrue(entries.contains("assets/objcraft/textures/item/large_part_2.png"));
    }

    @Test
    void reducesPartSizeForLargeAnimatedPbrAtlases() {
        final BufferedImage texture = new BufferedImage(1_024, 1_024, BufferedImage.TYPE_INT_ARGB);

        final int facesPerPart = MeshResourcePackBuilder.maxFacesPerPart(80, texture, true);

        assertTrue(facesPerPart > 0);
        assertTrue(facesPerPart < MeshResourcePackBuilder.MAX_FACES_PER_PART);
    }

    @Test
    void keepsGeneratedItemSpritesWithinMinecraftAtlasDimensions() throws Exception {
        final BufferedImage largeTexture = new BufferedImage(1_024, 2_048, BufferedImage.TYPE_INT_ARGB);
        final MeshResourcePackBuilder.EncodedModel regular = MeshResourcePackBuilder.encodeModel(
                "regular", TRIANGLES, largeTexture);
        final MeshResourcePackBuilder.EncodedModel pbr = MeshResourcePackBuilder.encodeModel(
                "pbr", List.of(TRIANGLES), 1, largeTexture,
                largeTexture, largeTexture, largeTexture);

        for (MeshResourcePackBuilder.EncodedModel encoded : List.of(regular, pbr)) {
            final BufferedImage sprite = ImageIO.read(new ByteArrayInputStream(encoded.texturePng()));
            assertTrue(sprite.getWidth() <= 1_024);
            assertTrue(sprite.getHeight() <= 1_024);
        }
    }

    @Test
    void tilesTextureCoordinatesOutsideTheShaderRange() throws Exception {
        final ObjTriangle tiledUv = new ObjTriangle(
                TRIANGLE,
                new TextureCoordinate(-0.25, 0),
                new TextureCoordinate(1.5, 0),
                new TextureCoordinate(0.5, 1),
                "fur");
        final MeshResourcePackBuilder.EncodedModel encoded =
                MeshResourcePackBuilder.encodeModel("tiled", List.of(tiledUv), new BufferedImage(
                        16, 16, BufferedImage.TYPE_INT_ARGB));

        final BufferedImage atlas = ImageIO.read(new ByteArrayInputStream(encoded.texturePng()));
        assertTrue(atlas != null);
        assertEquals(48, atlas.getWidth());
    }

    @Test
    void buildsSinglePackWithShaderLicenseAndGeneratedItemDefinition() throws Exception {
        final Mesh mesh = new Mesh(List.of(A, B, C), List.of(TRIANGLE));
        final SimpleModel model = new SimpleModel("triangle", "triangle", List.of(mesh), ModelTransform.identity());
        final ImportedModelAsset asset = new ImportedModelAsset(model, TRIANGLES, List.of(), 1, 1.0f, 1.0f, List.of(),
                new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB), null, null, null);
        final byte[] pack = new MeshResourcePackBuilder().build(Map.of(model.id(), asset));
        final Set<String> entries = new java.util.HashSet<>();
        final Map<String, byte[]> contents = new java.util.HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(pack))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.add(entry.getName());
                contents.put(entry.getName(), zip.readAllBytes());
            }
        }

        assertTrue(entries.contains("LICENSE"));
        assertTrue(entries.contains("assets/minecraft/shaders/core/item.vsh"));
        final String vertexShader = new String(
                contents.get("assets/minecraft/shaders/core/item.vsh"), java.nio.charset.StandardCharsets.UTF_8);
        final String meshDecoder = new String(
                contents.get("assets/minecraft/shaders/include/objmc_main.glsl"),
                java.nio.charset.StandardCharsets.UTF_8);
        final int markerScope = meshDecoder.indexOf("if (marker == ivec4(12,34,56,255)) {");
        final int sizeDeclaration = meshDecoder.indexOf("ivec2 size = ivec2(", markerScope);
        final int pbrBlock = meshDecoder.indexOf("int pbrStart = headerheight", sizeDeclaration);
        final int markerScopeEnd = meshDecoder.indexOf("\n}\n#ifdef ENTITY\nif (isCustom == 0)", pbrBlock);
        assertTrue(markerScope >= 0);
        assertTrue(sizeDeclaration > markerScope);
        assertTrue(pbrBlock > sizeDeclaration);
        assertTrue(markerScopeEnd > pbrBlock);
        assertTrue(!vertexShader.contains("int pbrStart = headerheight"));
        assertTrue(new String(contents.get("assets/minecraft/shaders/core/item.fsh"), java.nio.charset.StandardCharsets.UTF_8)
                .contains("objPbrMetallic = metalRoughness.b"));
        assertTrue(entries.contains("assets/objcraft/items/triangle.json"));
        assertTrue(entries.contains("assets/objcraft/models/item/triangle.json"));
        assertTrue(entries.contains("assets/objcraft/textures/item/triangle.png"));
        assertEquals("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"objcraft:item/triangle\"}}",
                new String(contents.get("assets/objcraft/items/triangle.json"),
                        java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(new String(contents.get("assets/objcraft/models/item/triangle.json"),
                java.nio.charset.StandardCharsets.UTF_8).contains("\"0\":\"objcraft:item/triangle\""));
        assertTrue(ImageIO.read(new ByteArrayInputStream(
                contents.get("assets/objcraft/textures/item/triangle.png"))) != null);
    }

    private static int[] rgba(BufferedImage image, int x, int y) {
        final int argb = image.getRGB(x, y);
        return new int[] {(argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, argb >>> 24};
    }

    private static int u24(BufferedImage image, int x, int y) {
        final int[] pixel = rgba(image, x, y);
        return (pixel[0] << 16) | (pixel[1] << 8) | pixel[2];
    }
}
