package com.objcraft.plugin;

import com.objcraft.geometry.Triangle;
import com.objcraft.geometry.Vertex;
import com.objcraft.importer.ObjTriangle;
import com.objcraft.importer.TextureCoordinate;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

final class MeshResourcePackBuilder {
    private static final int HEADER_ROWS = 2;
    private static final int MIN_TEXTURE_WIDTH = 16;
    private static final long MAX_TEXTURE_PIXELS = 4_194_304L;
    private static final long MAX_ATLAS_PIXELS = 1_048_576L;
    static final int MAX_FACES_PER_PART = 20_000;
    private static final int MAX_ATLAS_DIMENSION = 1_024;

    static int partCount(ImportedModelAsset asset) {
        final boolean hasPbr = hasPbr(asset);
        final TextureLayout layout = prepareTextureLayout(
                asset.frames(),
                asset.diffuseTexture(),
                asset.normalTexture(),
                asset.metallicRoughnessTexture(),
                asset.occlusionTexture(),
                hasPbr);
        final List<List<ObjTriangle>> frames = layout.frames();
        final BufferedImage texture = prepareTextureForAtlas(layout.diffuseTexture(), hasPbr);
        final int facesPerPart = maxFacesPerPart(frames.size(), texture, hasPbr);
        return (frames.getFirst().size() - 1) / facesPerPart + 1;
    }

    static int partCount(int faceCount) {
        return (faceCount - 1) / MAX_FACES_PER_PART + 1;
    }

    static int maxFacesPerPart(int frameCount, BufferedImage sourceTexture, boolean hasPbr) {
        final BufferedImage texture = prepareTextureForAtlas(sourceTexture, hasPbr);
        int low = 1;
        int high = MAX_FACES_PER_PART;
        while (low < high) {
            final int candidate = low + (high - low + 1) / 2;
            if (canFitAtlas(candidate, frameCount, texture, hasPbr)) {
                low = candidate;
            } else {
                high = candidate - 1;
            }
        }
        if (!canFitAtlas(low, frameCount, texture, hasPbr)) {
            throw new IllegalArgumentException("Even one face exceeds Minecraft's supported texture atlas limits.");
        }
        return low;
    }

    static String partName(String modelId, int part, int partCount) {
        final String normalized = modelId.toLowerCase(java.util.Locale.ROOT);
        return partCount == 1 ? normalized : normalized + "_part_" + (part + 1);
    }

    private static boolean hasPbr(ImportedModelAsset asset) {
        return asset.normalTexture() != null || asset.metallicRoughnessTexture() != null
                || asset.occlusionTexture() != null;
    }

    byte[] build(Map<String, ImportedModelAsset> assets) throws IOException {
        final Map<String, byte[]> entries = new TreeMap<>();
        final var basePack = MeshResourcePackBuilder.class.getResourceAsStream("/objcubed-base.zip");
        if (basePack == null) {
            throw new IOException("The bundled Minecraft 26.3 shader pack is missing.");
        }
        try (ZipInputStream input = new ZipInputStream(basePack)) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    byte[] content = input.readAllBytes();
                    content = patchPbrShader(entry.getName(), content);
                    entries.put(entry.getName(), content);
                }
                input.closeEntry();
            }
        }

        final java.util.Set<String> resourceNames = new java.util.HashSet<>();
        for (Map.Entry<String, ImportedModelAsset> model : assets.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER))
                .toList()) {
            final String name = model.getKey().toLowerCase(java.util.Locale.ROOT);
            if (!resourceNames.add(name)) {
                throw new IllegalArgumentException("Model names must be unique without regard to letter case: " + name);
            }
            final ImportedModelAsset asset = model.getValue();
            final boolean hasPbr = hasPbr(asset);
            final TextureLayout layout = prepareTextureLayout(
                    asset.frames(),
                    asset.diffuseTexture(),
                    asset.normalTexture(),
                    asset.metallicRoughnessTexture(),
                    asset.occlusionTexture(),
                    hasPbr);
            final List<List<ObjTriangle>> frames = layout.frames();
            final int faceCount = frames.getFirst().size();
            final BufferedImage atlasTexture = prepareTextureForAtlas(layout.diffuseTexture(), hasPbr);
            final int facesPerPart = maxFacesPerPart(frames.size(), atlasTexture, hasPbr);
            final int parts = (faceCount - 1) / facesPerPart + 1;
            final Bounds modelBounds = bounds(frames.stream().flatMap(List::stream).toList());
            for (int part = 0; part < parts; part++) {
                final int firstFace = part * facesPerPart;
                final int lastFace = Math.min(faceCount, firstFace + facesPerPart);
                final List<List<ObjTriangle>> partFrames = frames.stream()
                        .map(frame -> frame.subList(firstFace, lastFace))
                        .toList();
                final String partName = partName(name, part, parts);
                if (parts > 1 && !resourceNames.add(partName)) {
                    throw new IllegalArgumentException("Generated model part name conflicts with another model: "
                            + partName);
                }
                final EncodedModel encoded = encodeModelPrepared(
                        partName,
                        partFrames,
                        asset.animationDurationTicks(),
                        layout.diffuseTexture(),
                        layout.normalTexture(),
                        layout.metallicRoughnessTexture(),
                        asset.metallicFactor(),
                        asset.roughnessFactor(),
                        layout.occlusionTexture(),
                        modelBounds);
                entries.put("assets/objcraft/items/" + partName + ".json",
                        ("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"objcraft:item/"
                                + partName + "\"}}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                entries.put("assets/objcraft/models/item/" + partName + ".json",
                        encoded.modelJson().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                entries.put("assets/objcraft/textures/item/" + partName + ".png", encoded.texturePng());
            }
        }

        validateEntries(entries);
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream output = new ZipOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                final ZipEntry zipEntry = new ZipEntry(entry.getKey());
                zipEntry.setTime(0);
                output.putNextEntry(zipEntry);
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static void validateEntries(Map<String, byte[]> entries) throws IOException {
        for (String required : List.of(
                "pack.mcmeta",
                "assets/minecraft/shaders/core/item.vsh",
                "assets/minecraft/shaders/core/item.fsh")) {
            if (!entries.containsKey(required)) {
                throw new IOException("The generated resource pack is missing required file " + required + ".");
            }
        }
        for (String path : entries.keySet()) {
            final String itemPrefix = "assets/objcraft/items/";
            if (!path.startsWith(itemPrefix) || !path.endsWith(".json")) {
                continue;
            }
            final String name = path.substring(itemPrefix.length(), path.length() - ".json".length());
            final String modelPath = "assets/objcraft/models/item/" + name + ".json";
            final String texturePath = "assets/objcraft/textures/item/" + name + ".png";
            final String modelDefinition = new String(entries.get(path), java.nio.charset.StandardCharsets.UTF_8);
            if (!entries.containsKey(modelPath) || !entries.containsKey(texturePath)
                    || !modelDefinition.equals("{\"model\":{\"type\":\"minecraft:model\",\"model\":\"objcraft:item/"
                            + name + "\"}}")) {
                throw new IOException("Generated resource pack entries are incomplete for model " + name + ".");
            }
            final String modelJson = new String(entries.get(modelPath), java.nio.charset.StandardCharsets.UTF_8);
            if (!modelJson.contains("\"0\":\"objcraft:item/" + name + "\"")) {
                throw new IOException("Generated item model " + name + " references a missing texture.");
            }
            validatePng(texturePath, entries.get(texturePath));
        }
    }

    private static void validatePng(String path, byte[] png) throws IOException {
        if (png.length < 24
                || png[0] != (byte) 0x89
                || png[1] != 'P'
                || png[2] != 'N'
                || png[3] != 'G'
                || png[12] != 'I'
                || png[13] != 'H'
                || png[14] != 'D'
                || png[15] != 'R') {
            throw new IOException("Generated resource pack texture is not a valid PNG: " + path + ".");
        }
        final int width = readPngDimension(png, 16);
        final int height = readPngDimension(png, 20);
        if (width < 1 || height < 1 || width > MAX_ATLAS_DIMENSION || height > MAX_ATLAS_DIMENSION
                || (long) width * height > MAX_ATLAS_PIXELS) {
            throw new IOException("Generated resource pack texture exceeds atlas limits: " + path + ".");
        }
    }

    private static int readPngDimension(byte[] png, int offset) {
        return ((png[offset] & 255) << 24) | ((png[offset + 1] & 255) << 16)
                | ((png[offset + 2] & 255) << 8) | (png[offset + 3] & 255);
    }

    private static byte[] patchPbrShader(String path, byte[] sourceBytes) throws IOException {
        final String source;
        try {
            source = new String(sourceBytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (RuntimeException exception) {
            throw new IOException("Could not decode bundled shader " + path + ".", exception);
        }
        final String patched = switch (path) {
            case "assets/minecraft/shaders/core/item.vsh" -> patchItemVertex(source);
            case "assets/minecraft/shaders/core/item.fsh" -> patchItemFragment(source);
            case "assets/minecraft/shaders/include/objmc_main.glsl" -> patchMeshMain(source);
            case "assets/minecraft/shaders/include/objmc_light.glsl" -> replaceRequired(
                    source,
                    "if (dot(ocSmoothNormal,ocSmoothNormal) > 1e-10) {",
                    "#ifdef OBJC_PBR_SHADER\n"
                            + "    if (objHasPbr == 1 && dot(objPbrNormal, objPbrNormal) > 1e-10) {\n"
                            + "        normal = normalize(objPbrNormal);\n"
                            + "    } else\n"
                            + "#endif\n"
                            + "    if (dot(ocSmoothNormal,ocSmoothNormal) > 1e-10) {");
            default -> source;
        };
        return patched.equals(source) ? sourceBytes : patched.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String patchItemVertex(String source) throws IOException {
        String patched = replaceRequired(
                source,
                "#include <minecraft:objmc_skin_config.glsl>",
                "#define OBJC_PBR_SHADER\n#include <minecraft:objmc_skin_config.glsl>");
        patched = replaceRequired(
                patched,
                "layout(location = 17) out vec3 ocSmoothNormal;",
                "layout(location = 17) out vec3 ocSmoothNormal;\n"
                        + "layout(location = 18) out vec2 objPbrUv;\n"
                        + "layout(location = 19) out float objPbrLayerOffset;\n"
                        + "layout(location = 20) flat out int objHasPbr;");
        patched = replaceRequired(
                patched,
                "    ocCarrierFailure = 0.0;",
                "    ocCarrierFailure = 0.0;\n"
                        + "    objPbrUv = UV0;\n"
                        + "    objPbrLayerOffset = 0.0;\n"
                        + "    objHasPbr = 0;");
        patched = replaceRequired(
                patched,
                "    #include <minecraft:objmc_main.glsl>",
                "    #include <minecraft:objmc_main.glsl>");
        return patched;
    }

    private static String patchMeshMain(String source) throws IOException {
        return replaceRequired(
                source,
                "    // ===========================================================\n"
                        + "    // DEBUG MODE (ENTITY only",
                """
                    #ifdef OBJC_PBR_SHADER
                    if (isCustom == 1 && (t[4].a & 4) != 0) {
                        int pbrStart = headerheight + size.y * ntextures + vph + vth
                                + (nframes * nvertices * 2 + size.x - 1) / size.x;
                        objPbrUv = (vec2(topleft.x, topleft.y + pbrStart) + texuvpx)
                                / vec2(atlasSize);
                        objPbrLayerOffset = float(size.y) / float(atlasSize.y);
                        objHasPbr = 1;
                    }
                    #endif
                    // ===========================================================
                    // DEBUG MODE (ENTITY only""");
    }

    private static String patchItemFragment(String source) throws IOException {
        String patched = replaceRequired(
                source,
                "#include <minecraft:objmc_skin_config.glsl>",
                "#define OBJC_PBR_SHADER\n#include <minecraft:objmc_skin_config.glsl>");
        patched = replaceRequired(
                patched,
                "layout(location = 17) in vec3 ocSmoothNormal;",
                "layout(location = 17) in vec3 ocSmoothNormal;\n"
                        + "layout(location = 18) in vec2 objPbrUv;\n"
                        + "layout(location = 19) in float objPbrLayerOffset;\n"
                        + "layout(location = 20) flat in int objHasPbr;\n"
                        + "vec3 objPbrNormal = vec3(0.0);\n"
                        + "float objPbrAo = 1.0;\n"
                        + "float objPbrMetallic = 0.0;\n"
                        + "float objPbrRoughness = 1.0;");
        patched = replaceRequired(
                patched,
                "        #define ENTITY\n        #include <minecraft:objmc_light.glsl>",
                """
                        #define ENTITY
                        #ifdef OBJC_PBR_SHADER
                        if (objHasPbr == 1) {
                            vec3 mapNormal = texture(Sampler0, objPbrUv).rgb * 2.0 - 1.0;
                            vec3 dpdx = dFdx(Pos);
                            vec3 dpdy = dFdy(Pos);
                            vec2 duvdx = dFdx(texCoord);
                            vec2 duvdy = dFdy(texCoord);
                            vec3 geometricNormal = normalize(cross(dpdx, dpdy));
                            vec3 tangent = normalize(dpdx * duvdy.y - dpdy * duvdx.y);
                            vec3 bitangent = normalize(-dpdx * duvdy.x + dpdy * duvdx.x);
                            objPbrNormal = normalize(mat3(tangent, bitangent, geometricNormal) * mapNormal);
                            vec3 metalRoughness = texture(Sampler0, objPbrUv + vec2(0.0, objPbrLayerOffset)).rgb;
                            objPbrMetallic = metalRoughness.b;
                            objPbrRoughness = metalRoughness.g;
                            objPbrAo = texture(Sampler0,
                                    objPbrUv + vec2(0.0, 2.0 * objPbrLayerOffset)).r;
                        }
                        #endif
                        #include <minecraft:objmc_light.glsl>
                        #ifdef OBJC_PBR_SHADER
                        if (objHasPbr == 1) {
                            color.rgb *= objPbrAo;
                            vec3 viewDirection = normalize(-Pos);
                            vec3 f0 = mix(vec3(0.04), color.rgb, objPbrMetallic);
                            float exponent = mix(128.0, 4.0, objPbrRoughness * objPbrRoughness);
                            vec3 directSpecular = vec3(0.0);
                            vec3 light0 = normalize(Light0_Direction);
                            vec3 half0 = normalize(light0 + viewDirection);
                            directSpecular += pow(max(dot(objPbrNormal, half0), 0.0), exponent)
                                    * max(dot(objPbrNormal, light0), 0.0);
                            vec3 light1 = normalize(Light1_Direction);
                            vec3 half1 = normalize(light1 + viewDirection);
                            directSpecular += pow(max(dot(objPbrNormal, half1), 0.0), exponent)
                                    * max(dot(objPbrNormal, light1), 0.0);
                            color.rgb += f0 * directSpecular * 0.25 * lightColor.rgb;
                        }
                        #endif
                        """);
        return patched;
    }

    private static String replaceRequired(String source, String target, String replacement) throws IOException {
        if (!source.contains(target)) {
            throw new IOException("The bundled shader no longer contains the expected PBR integration point.");
        }
        return source.replace(target, replacement);
    }

    static EncodedModel encodeModel(String modelId, List<ObjTriangle> triangles, BufferedImage sourceTexture)
            throws IOException {
        return encodeModel(modelId, List.of(triangles), 1, sourceTexture, null, null, null);
    }

    static EncodedModel encodeModel(
            String modelId,
            List<List<ObjTriangle>> frames,
            int durationTicks,
            BufferedImage sourceTexture,
            BufferedImage normalTexture,
            BufferedImage metallicRoughnessTexture,
            BufferedImage occlusionTexture
    ) throws IOException {
        return encodeModel(modelId, frames, durationTicks, sourceTexture, normalTexture, metallicRoughnessTexture,
                1.0f, 1.0f, occlusionTexture);
    }

    static EncodedModel encodeModel(
            String modelId,
            List<List<ObjTriangle>> frames,
            int durationTicks,
            BufferedImage sourceTexture,
            BufferedImage normalTexture,
            BufferedImage metallicRoughnessTexture,
            float metallicFactor,
            float roughnessFactor,
            BufferedImage occlusionTexture
    ) throws IOException {
        if (frames.isEmpty() || frames.getFirst().isEmpty()) {
            throw new IllegalArgumentException("The model has no faces to encode.");
        }
        final boolean hasPbr = normalTexture != null || metallicRoughnessTexture != null || occlusionTexture != null;
        final TextureLayout layout = prepareTextureLayout(
                frames, sourceTexture, normalTexture, metallicRoughnessTexture, occlusionTexture, hasPbr);
        final Bounds sharedBounds = bounds(layout.frames().stream().flatMap(List::stream).toList());
        return encodeModelPrepared(
                modelId,
                layout.frames(),
                durationTicks,
                layout.diffuseTexture(),
                layout.normalTexture(),
                layout.metallicRoughnessTexture(),
                metallicFactor,
                roughnessFactor,
                layout.occlusionTexture(),
                sharedBounds);
    }

    private static EncodedModel encodeModelPrepared(
            String modelId,
            List<List<ObjTriangle>> frames,
            int durationTicks,
            BufferedImage sourceTexture,
            BufferedImage normalTexture,
            BufferedImage metallicRoughnessTexture,
            float metallicFactor,
            float roughnessFactor,
            BufferedImage occlusionTexture,
            Bounds sharedBounds
    ) throws IOException {
        if (frames.isEmpty() || frames.getFirst().isEmpty()) {
            throw new IllegalArgumentException("The model has no faces to encode.");
        }
        final List<ObjTriangle> triangles = frames.getFirst();
        if (triangles.size() > MAX_FACES_PER_PART) {
            throw new IllegalArgumentException("The shader renderer supports at most " + MAX_FACES_PER_PART
                    + " faces per model part; this part has " + triangles.size() + ".");
        }
        if (frames.size() > 255 || durationTicks < 1 || durationTicks > 16_777_215
                || frames.stream().anyMatch(frame -> frame.size() != triangles.size())) {
            throw new IllegalArgumentException("Animation exceeds resource-pack limits or changes mesh topology.");
        }
        final String resourceName = modelId.toLowerCase(java.util.Locale.ROOT);
        final boolean hasPbr = normalTexture != null || metallicRoughnessTexture != null || occlusionTexture != null;
        final BufferedImage preparedTexture = prepareTextureForAtlas(sourceTexture, hasPbr);
        final AtlasPlan atlasPlan = atlasPlan(triangles.size(), frames.size(), preparedTexture, hasPbr);
        if (atlasPlan == null) {
            throw new IllegalArgumentException("The model part exceeds Minecraft's supported texture atlas limits.");
        }
        final BufferedImage texture = resizeTextureWidth(
                preparedTexture,
                atlasPlan.width(),
                textureLayerPixelLimit(hasPbr));
        final int width = texture.getWidth();
        final int textureHeight = texture.getHeight();
        if (width > MAX_ATLAS_DIMENSION || textureHeight > MAX_ATLAS_DIMENSION
                || (long) width * textureHeight > MAX_TEXTURE_PIXELS) {
            throw new IllegalArgumentException("The diffuse texture must fit Minecraft's atlas limits.");
        }

        final Bounds bounds = sharedBounds;
        final int vertexCount = Math.multiplyExact(triangles.size(), 4);
        final int uvHeaderHeight = ceilDiv(triangles.size(), width);
        final int animatedVertexCount = Math.multiplyExact(vertexCount, frames.size());
        final int positionRows = ceilDiv(Math.multiplyExact(animatedVertexCount, 3), width);
        final int uvRows = ceilDiv(Math.multiplyExact(vertexCount, 2), width);
        final int vertexRows = ceilDiv(Math.multiplyExact(animatedVertexCount, 2), width);
        final BufferedImage normal = hasPbr ? normalizePbrTexture(normalTexture, texture, 0xFF8080FF) : null;
        final BufferedImage metallicRoughness = hasPbr
                ? applyMetallicRoughnessFactors(
                        normalizePbrTexture(metallicRoughnessTexture, texture, 0xFFFFFFFF),
                        metallicFactor,
                        roughnessFactor)
                : null;
        final BufferedImage occlusion = hasPbr ? normalizePbrTexture(occlusionTexture, texture, 0xFFFFFFFF) : null;
        if (positionRows > 65_535 || uvRows > 65_535 || vertexRows > 65_535) {
            throw new IllegalArgumentException("The encoded model data exceeds the shader format limits.");
        }

        final int height = atlasPlan.height();
        if (height > MAX_ATLAS_DIMENSION || (long) width * height > MAX_ATLAS_PIXELS) {
            throw new IllegalArgumentException("The model part exceeds Minecraft's supported texture atlas limits.");
        }
        final long byteCount = (long) width * height * 4;
        if (byteCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("The encoded model texture is too large to build safely.");
        }
        final byte[] rgba = new byte[(int) byteCount];

        writePixel(rgba, width, 0, 0, 12, 34, 56, 255);
        writePixel(rgba, width, 1, 0, width >>> 8, width, textureHeight >>> 8, textureHeight);
        writePixel(rgba, width, 2, 0, vertexCount >>> 24, vertexCount >>> 16, vertexCount >>> 8, 255);
        writePixel(rgba, width, 3, 0, (frames.size() >>> 16) & 255,
                (frames.size() >>> 8) & 255, frames.size() & 255, 1);
        writePixel(rgba, width, 4, 0, (durationTicks >>> 16) & 255,
                (durationTicks >>> 8) & 255, durationTicks & 255,
                128 | (frames.size() > 1 ? 64 : 0) | (hasPbr ? 4 : 0));
        writePixel(rgba, width, 5, 0, positionRows >>> 8, positionRows, uvRows >>> 8, 255);
        writePixel(rgba, width, 6, 0, 0x1C, 0, 2, 255);
        writePixel(rgba, width, 7, 0, textureHeight, vertexCount, uvRows, 255);
        writeGuiDefaults(rgba, width);
        for (int x = 0; x < width; x++) {
            writePixel(rgba, width, x, 1, 0, 0, 0, 255);
        }
        writePixel(rgba, width, 5, 1, 0, 0, 2, 255);

        final List<Vertex> positions = new ArrayList<>(animatedVertexCount);
        final List<TextureCoordinate> uvs = new ArrayList<>(vertexCount);
        final int[] vertexPositionIndex = new int[animatedVertexCount];
        final int[] vertexUvIndex = new int[animatedVertexCount];
        for (int face = 0; face < triangles.size(); face++) {
            final int faceX = face % width;
            final int faceY = face / width + HEADER_ROWS;
            writePixel(rgba, width, faceX, faceY, faceX >>> 8, faceX, faceY >>> 8, faceY);

            final ObjTriangle objTriangle = triangles.get(face);
            final TextureCoordinate[] faceUvs = {
                    coordinate(objTriangle.uvA()),
                    coordinate(objTriangle.uvB()),
                    coordinate(objTriangle.uvC()),
                    coordinate(objTriangle.uvC())
            };
            for (int corner = 0; corner < 4; corner++) {
                final int index = face * 4 + corner;
                uvs.add(faceUvs[corner]);
                vertexUvIndex[index] = index % vertexCount;
            }
        }
        for (int frame = 0; frame < frames.size(); frame++) {
            for (int face = 0; face < triangles.size(); face++) {
                final Triangle triangle = frames.get(frame).get(face).triangle();
                final Vertex[] faceVertices = {triangle.a(), triangle.b(), triangle.c(), triangle.c()};
                for (int corner = 0; corner < 4; corner++) {
                    final int index = frame * vertexCount + face * 4 + corner;
                    positions.add(normalize(faceVertices[corner], bounds));
                    vertexPositionIndex[index] = index;
                }
            }
        }

        final int textureStartY = HEADER_ROWS + uvHeaderHeight;
        for (int y = 0; y < textureHeight; y++) {
            for (int x = 0; x < width; x++) {
                final int argb = texture.getRGB(x, textureHeight - y - 1);
                writePixel(rgba, width, x, textureStartY + y,
                        (argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, argb >>> 24);
            }
        }

        int dataY = textureStartY + textureHeight;
        for (int index = 0; index < positions.size(); index++) {
            final Vertex vertex = positions.get(index);
            writePosition(rgba, width, dataY, index * 3, vertex.x());
            writePosition(rgba, width, dataY, index * 3 + 1, vertex.y() - 0.5);
            writePosition(rgba, width, dataY, index * 3 + 2, vertex.z());
        }
        dataY += positionRows;
        for (int index = 0; index < uvs.size(); index++) {
            final TextureCoordinate uv = uvs.get(index);
            writeUv(rgba, width, dataY, index * 2, uv.u());
            writeUv(rgba, width, dataY, index * 2 + 1, uv.v());
        }
        dataY += uvRows;
        for (int index = 0; index < animatedVertexCount; index++) {
            writeU24(rgba, width, dataY, index * 2, vertexPositionIndex[index]);
            writeU24(rgba, width, dataY, index * 2 + 1, vertexUvIndex[index]);
        }
        dataY += vertexRows;
        if (hasPbr) {
            writeImage(rgba, width, dataY, normal);
            writeImage(rgba, width, dataY + textureHeight, metallicRoughness);
            writeImage(rgba, width, dataY + textureHeight * 2, occlusion);
        }

        final BufferedImage encodedTexture = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int offset = (y * width + x) * 4;
                final int argb = ((rgba[offset + 3] & 255) << 24)
                        | ((rgba[offset] & 255) << 16)
                        | ((rgba[offset + 1] & 255) << 8)
                        | (rgba[offset + 2] & 255);
                encodedTexture.setRGB(x, y, argb);
            }
        }
        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        if (!ImageIO.write(encodedTexture, "png", png)) {
            throw new IOException("No PNG writer is available for the encoded model texture.");
        }
        return new EncodedModel(buildModelJson(resourceName, triangles.size(), width, height), png.toByteArray());
    }

    private static String buildModelJson(String name, int faces, int width, int height) {
        final StringBuilder json = new StringBuilder(Math.max(256, faces * 180));
        json.append("{\"textures\":{\"0\":\"objcraft:item/").append(name)
                .append("\",\"particle\":\"objcraft:item/").append(name)
                .append("\"},\"elements\":[");
        for (int face = 0; face < faces; face++) {
            if (face > 0) {
                json.append(',');
            }
            final int x = face % width;
            final int y = face / width + HEADER_ROWS;
            json.append("{\"from\":[8,8,8],\"to\":[24,24,8],\"faces\":{\"north\":{\"uv\":[")
                    .append(number((x + 0.1) * 16.0 / width)).append(',')
                    .append(number((y + 0.1) * 16.0 / height)).append(',')
                    .append(number((x + 0.9) * 16.0 / width)).append(',')
                    .append(number((y + 0.9) * 16.0 / height))
                    .append("],\"texture\":\"#0\",\"tintindex\":0}}}");
        }
        json.append("],\"display\":{\"fixed\":{\"rotation\":[0,0,0],\"translation\":[0,0,0],\"scale\":[1,1,1]}}}");
        return json.toString();
    }

    private static void writeGuiDefaults(byte[] rgba, int width) {
        final int scale = 16_384;
        final int zeroTranslation = 32_768;
        writePixel(rgba, width, 8, 0, scale >>> 8, scale, scale >>> 8, 255);
        writePixel(rgba, width, 9, 0, scale, scale >>> 8, scale, 255);
        writePixel(rgba, width, 10, 0, zeroTranslation >>> 8, zeroTranslation, zeroTranslation >>> 8, 255);
        writePixel(rgba, width, 11, 0, zeroTranslation, zeroTranslation >>> 8, zeroTranslation, 255);
        writePixel(rgba, width, 12, 0, 0, 0, 0, 255);
        writePixel(rgba, width, 13, 0, 0, 0, 0, 255);
        writePixel(rgba, width, 14, 0, zeroTranslation >>> 8, zeroTranslation, zeroTranslation >>> 8, 255);
        writePixel(rgba, width, 15, 0, zeroTranslation, zeroTranslation >>> 8, zeroTranslation, 255);
    }

    private static TextureCoordinate coordinate(TextureCoordinate coordinate) {
        return coordinate == null ? new TextureCoordinate(0, 0) : coordinate;
    }

    private static TextureLayout prepareTextureLayout(
            List<List<ObjTriangle>> frames,
            BufferedImage diffuseTexture,
            BufferedImage normalTexture,
            BufferedImage metallicRoughnessTexture,
            BufferedImage occlusionTexture,
            boolean hasPbr
    ) {
        double minU = Double.POSITIVE_INFINITY;
        double minV = Double.POSITIVE_INFINITY;
        double maxU = Double.NEGATIVE_INFINITY;
        double maxV = Double.NEGATIVE_INFINITY;
        for (ObjTriangle triangle : frames.getFirst()) {
            for (TextureCoordinate uv : List.of(
                    coordinate(triangle.uvA()),
                    coordinate(triangle.uvB()),
                    coordinate(triangle.uvC()))) {
                if (!Double.isFinite(uv.u()) || !Double.isFinite(uv.v())
                        || Math.abs(uv.u()) > 1_000_000_000 || Math.abs(uv.v()) > 1_000_000_000) {
                    throw new IllegalArgumentException("Texture coordinates must be finite and within ±1 billion.");
                }
                minU = Math.min(minU, uv.u());
                minV = Math.min(minV, uv.v());
                maxU = Math.max(maxU, uv.u());
                maxV = Math.max(maxV, uv.v());
            }
        }
        final UvMapping mapping = new UvMapping(tileRange(minU, maxU), tileRange(minV, maxV));
        final List<List<ObjTriangle>> mappedFrames = frames.stream()
                .map(frame -> frame.stream()
                        .map(triangle -> new ObjTriangle(
                                triangle.triangle(),
                                mapping.map(coordinate(triangle.uvA())),
                                mapping.map(coordinate(triangle.uvB())),
                                mapping.map(coordinate(triangle.uvC())),
                                triangle.materialName()))
                        .toList())
                .toList();
        final long maxPixels = textureLayerPixelLimit(hasPbr);
        return new TextureLayout(
                mappedFrames,
                tileTexture(diffuseTexture, mapping, maxPixels),
                tileTexture(normalTexture, mapping, maxPixels),
                tileTexture(metallicRoughnessTexture, mapping, maxPixels),
                tileTexture(occlusionTexture, mapping, maxPixels));
    }

    private static TileRange tileRange(double minimum, double maximum) {
        final double first = Math.floor(minimum);
        final double end = Math.ceil(maximum);
        if (first < -1_000_000_000 || end > 1_000_000_000) {
            throw new IllegalArgumentException("Texture coordinate repeat range is too large.");
        }
        final long startTile = (long) first;
        final long endTile = Math.max(startTile + 1, (long) end);
        final long tileCount = endTile - startTile;
        if (tileCount > MAX_ATLAS_DIMENSION) {
            throw new IllegalArgumentException("Texture coordinates repeat more than "
                    + MAX_ATLAS_DIMENSION + " times on one axis.");
        }
        return new TileRange(startTile, (int) tileCount);
    }

    private static BufferedImage tileTexture(BufferedImage source, UvMapping mapping, long maxPixels) {
        if (source == null) {
            return null;
        }
        final BufferedImage normalized = normalizeTexture(source);
        final long tileCount = (long) mapping.u().tileCount() * mapping.v().tileCount();
        if (tileCount > maxPixels) {
            throw new IllegalArgumentException("UV tiling would exceed the available texture atlas budget.");
        }
        final double scale = Math.min(
                1.0,
                Math.min(
                        Math.sqrt((maxPixels / (double) tileCount)
                                / ((long) normalized.getWidth() * normalized.getHeight())),
                        Math.min(
                                MAX_ATLAS_DIMENSION / ((double) normalized.getWidth() * mapping.u().tileCount()),
                                MAX_ATLAS_DIMENSION / ((double) normalized.getHeight() * mapping.v().tileCount()))));
        final int tileWidth = Math.max(1, (int) Math.floor(normalized.getWidth() * scale));
        final int tileHeight = Math.max(1, (int) Math.floor(normalized.getHeight() * scale));
        final int width = Math.multiplyExact(tileWidth, mapping.u().tileCount());
        final int height = Math.multiplyExact(tileHeight, mapping.v().tileCount());
        if ((long) width * height > maxPixels
                || width > MAX_ATLAS_DIMENSION || height > MAX_ATLAS_DIMENSION) {
            throw new IllegalArgumentException("UV tiling exceeds Minecraft's texture atlas limits.");
        }
        final BufferedImage tiled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = tiled.createGraphics();
        if (scale < 1.0) {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        } else {
            graphics.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        }
        for (int tileY = 0; tileY < mapping.v().tileCount(); tileY++) {
            for (int tileX = 0; tileX < mapping.u().tileCount(); tileX++) {
                graphics.drawImage(
                        normalized,
                        tileX * tileWidth,
                        tileY * tileHeight,
                        (tileX + 1) * tileWidth,
                        (tileY + 1) * tileHeight,
                        0,
                        0,
                        normalized.getWidth(),
                        normalized.getHeight(),
                        null);
            }
        }
        graphics.dispose();
        return tiled;
    }

    private static BufferedImage normalizeTexture(BufferedImage source) {
        if (source == null) {
            final BufferedImage fallback = new BufferedImage(MIN_TEXTURE_WIDTH, MIN_TEXTURE_WIDTH, BufferedImage.TYPE_INT_ARGB);
            final java.awt.Graphics2D graphics = fallback.createGraphics();
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, fallback.getWidth(), fallback.getHeight());
            graphics.dispose();
            return fallback;
        }
        if (source.getWidth() >= MIN_TEXTURE_WIDTH) {
            return source;
        }
        final int width = MIN_TEXTURE_WIDTH;
        final int height = Math.max(1, (int) Math.round(source.getHeight() * (double) width / source.getWidth()));
        final BufferedImage scaled = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return scaled;
    }

    private static BufferedImage prepareTextureForAtlas(BufferedImage source, boolean hasPbr) {
        final BufferedImage normalized = normalizeTexture(source);
        final long maxPixels = textureLayerPixelLimit(hasPbr);
        final int maxTextureHeight = hasPbr ? 128 : 512;
        final double scale = Math.min(
                1.0,
                Math.min(
                        Math.sqrt(maxPixels / ((double) normalized.getWidth() * normalized.getHeight())),
                        Math.min(
                                MAX_ATLAS_DIMENSION / (double) normalized.getWidth(),
                                maxTextureHeight / (double) normalized.getHeight())));
        final int width = Math.max(1, (int) Math.floor(normalized.getWidth() * scale));
        final int height = Math.max(1, (int) Math.floor(normalized.getHeight() * scale));
        if (width == normalized.getWidth() && height == normalized.getHeight()) {
            return normalized;
        }
        return resizeTexture(normalized, width, height);
    }

    private static long textureLayerPixelLimit(boolean hasPbr) {
        return hasPbr ? MAX_TEXTURE_PIXELS / 4 : MAX_TEXTURE_PIXELS;
    }

    private static boolean canFitAtlas(int faces, int frameCount, BufferedImage texture, boolean hasPbr) {
        return atlasPlan(faces, frameCount, texture, hasPbr) != null;
    }

    private static AtlasPlan atlasPlan(int faces, int frameCount, BufferedImage texture, boolean hasPbr) {
        final long maxLayerPixels = textureLayerPixelLimit(hasPbr);
        int width = Math.max(texture.getWidth(), faces > 5_000 ? 64 : MIN_TEXTURE_WIDTH);
        while (width <= MAX_ATLAS_DIMENSION) {
            final int textureHeight = Math.max(
                    1, (int) Math.round(texture.getHeight() * (double) width / texture.getWidth()));
            if ((long) width * textureHeight > maxLayerPixels) {
                return null;
            }
            final long vertices = (long) faces * 4;
            final long animatedVertices = vertices * frameCount;
            final long requiredHeight = HEADER_ROWS + ceilDiv(faces, width) + textureHeight
                    + ceilDiv(animatedVertices * 3, width)
                    + ceilDiv(vertices * 2, width)
                    + ceilDiv(animatedVertices * 2, width)
                    + (hasPbr ? 3L * textureHeight : 0);
            if (requiredHeight <= MAX_ATLAS_DIMENSION) {
                final int atlasHeight = nextPowerOfTwo(requiredHeight);
                if ((long) width * atlasHeight <= MAX_ATLAS_PIXELS) {
                    return new AtlasPlan(width, textureHeight, atlasHeight);
                }
            }
            if (width > MAX_ATLAS_DIMENSION / 2) {
                break;
            }
            width *= 2;
        }
        return null;
    }

    private static BufferedImage resizeTextureWidth(BufferedImage source, int width, long maxPixels) {
        final int height = Math.max(1, (int) Math.round(source.getHeight() * (double) width / source.getWidth()));
        if ((long) width * height > maxPixels) {
            throw new IllegalArgumentException("The resized diffuse texture would exceed its texture atlas budget.");
        }
        return resizeTexture(source, width, height);
    }

    private static BufferedImage resizeTexture(BufferedImage source, int width, int height) {
        final BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, width, height, null);
        graphics.dispose();
        return resized;
    }

    private static BufferedImage normalizePbrTexture(BufferedImage source, BufferedImage base, int fallbackArgb) {
        if (source == null) {
            final BufferedImage fallback = new BufferedImage(base.getWidth(), base.getHeight(),
                    BufferedImage.TYPE_INT_ARGB);
            final Graphics2D graphics = fallback.createGraphics();
            graphics.setColor(new java.awt.Color(fallbackArgb, true));
            graphics.fillRect(0, 0, fallback.getWidth(), fallback.getHeight());
            graphics.dispose();
            return fallback;
        }
        if (source.getWidth() == base.getWidth() && source.getHeight() == base.getHeight()) {
            return source;
        }
        final BufferedImage resized = new BufferedImage(base.getWidth(), base.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = resized.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, resized.getWidth(), resized.getHeight(), null);
        graphics.dispose();
        return resized;
    }

    private static BufferedImage applyMetallicRoughnessFactors(
            BufferedImage source,
            float metallicFactor,
            float roughnessFactor
    ) {
        if (!Float.isFinite(metallicFactor) || metallicFactor < 0 || metallicFactor > 1
                || !Float.isFinite(roughnessFactor) || roughnessFactor < 0 || roughnessFactor > 1) {
            throw new IllegalArgumentException("Metallic and roughness factors must be between 0 and 1.");
        }
        final BufferedImage adjusted = new BufferedImage(
                source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                final int argb = source.getRGB(x, y);
                final int red = (argb >>> 16) & 255;
                final int green = Math.round(((argb >>> 8) & 255) * roughnessFactor);
                final int blue = Math.round((argb & 255) * metallicFactor);
                adjusted.setRGB(x, y, (argb & 0xFF000000) | (red << 16) | (green << 8) | blue);
            }
        }
        return adjusted;
    }

    private static void writeImage(byte[] rgba, int width, int startY, BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                final int argb = image.getRGB(x, image.getHeight() - y - 1);
                writePixel(rgba, width, x, startY + y,
                        (argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, argb >>> 24);
            }
        }
    }

    private static Bounds bounds(List<ObjTriangle> triangles) {
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        for (ObjTriangle source : triangles) {
            for (Vertex vertex : List.of(source.triangle().a(), source.triangle().b(), source.triangle().c())) {
                minX = Math.min(minX, vertex.x());
                minY = Math.min(minY, vertex.y());
                minZ = Math.min(minZ, vertex.z());
                maxX = Math.max(maxX, vertex.x());
                maxY = Math.max(maxY, vertex.y());
                maxZ = Math.max(maxZ, vertex.z());
            }
        }
        final double extent = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        final double scale = extent == 0 ? 1 : 4.0 / extent;
        return new Bounds((minX + maxX) / 2.0, minY, (minZ + maxZ) / 2.0, scale);
    }

    private static Vertex normalize(Vertex vertex, Bounds bounds) {
        return new Vertex(
                (vertex.x() - bounds.centerX()) * bounds.scale(),
                (vertex.y() - bounds.minY()) * bounds.scale(),
                (vertex.z() - bounds.centerZ()) * bounds.scale());
    }

    private static void writePosition(byte[] rgba, int width, int dataY, int valueIndex, double value) {
        if (!Double.isFinite(value) || value < -128 || value >= 128) {
            throw new IllegalArgumentException("A normalized vertex is outside the shader range [-128, 128).");
        }
        final int encoded = (int) (8_388_608 + value * 65_536);
        writeU24(rgba, width, dataY, valueIndex, encoded);
    }

    private static void writeUv(byte[] rgba, int width, int dataY, int valueIndex, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Texture coordinates must be finite.");
        }
        final int encoded = (int) (Math.max(0, Math.min(1, value)) * 65_535);
        final int pixel = pixelOffset(width, dataY, valueIndex);
        rgba[pixel] = 0;
        rgba[pixel + 1] = (byte) (encoded >>> 8);
        rgba[pixel + 2] = (byte) encoded;
        rgba[pixel + 3] = (byte) 255;
    }

    private static void writeU24(byte[] rgba, int width, int dataY, int valueIndex, int value) {
        final int pixel = pixelOffset(width, dataY, valueIndex);
        rgba[pixel] = (byte) (value >>> 16);
        rgba[pixel + 1] = (byte) (value >>> 8);
        rgba[pixel + 2] = (byte) value;
        rgba[pixel + 3] = (byte) 255;
    }

    private static void writePixel(byte[] rgba, int width, int x, int y, int red, int green, int blue, int alpha) {
        final int offset = (y * width + x) * 4;
        rgba[offset] = (byte) red;
        rgba[offset + 1] = (byte) green;
        rgba[offset + 2] = (byte) blue;
        rgba[offset + 3] = (byte) alpha;
    }

    private static int pixelOffset(int width, int startY, int valueIndex) {
        return ((startY + valueIndex / width) * width + valueIndex % width) * 4;
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }

    private static long ceilDiv(long value, int divisor) {
        return (value + divisor - 1L) / divisor;
    }

    private static int nextPowerOfTwo(long value) {
        if (value > 65_535) {
            throw new IllegalArgumentException("The encoded texture height exceeds the shader format limit.");
        }
        int result = 1;
        while (result < value) {
            result <<= 1;
        }
        if (result > 32_768) {
            throw new IllegalArgumentException("The encoded texture height exceeds the shader format limit.");
        }
        return result;
    }

    private static String number(double value) {
        return Double.toString(value);
    }

    record EncodedModel(String modelJson, byte[] texturePng) {
    }

    private record TextureLayout(
            List<List<ObjTriangle>> frames,
            BufferedImage diffuseTexture,
            BufferedImage normalTexture,
            BufferedImage metallicRoughnessTexture,
            BufferedImage occlusionTexture
    ) {
    }

    private record TileRange(long startTile, int tileCount) {
    }

    private record UvMapping(TileRange u, TileRange v) {
        private TextureCoordinate map(TextureCoordinate coordinate) {
            return new TextureCoordinate(
                    (coordinate.u() - u.startTile()) / u.tileCount(),
                    (coordinate.v() - v.startTile()) / v.tileCount());
        }
    }

    private record AtlasPlan(int width, int textureHeight, int height) {
    }

    private record Bounds(double centerX, double minY, double centerZ, double scale) {
    }
}
