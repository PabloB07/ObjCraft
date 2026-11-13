package com.objcraft.importer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.objcraft.geometry.Mesh;
import com.objcraft.geometry.Triangle;
import com.objcraft.geometry.Vertex;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

public final class GltfParser {
    private static final int GLB_MAGIC = 0x46546C67;
    private static final int JSON_CHUNK = 0x4E4F534A;
    private static final int BIN_CHUNK = 0x004E4942;
    private static final int MAX_ANIMATION_FRAMES = 60;

    public GltfImportResult parse(Path source, ObjImporterConfig config) throws IOException {
        config.validate();
        final Path normalizedSource = source.toAbsolutePath().normalize().toRealPath();
        final Path root = normalizedSource.getParent().toRealPath();
        if (root == null || !Files.isRegularFile(normalizedSource)) {
            throw new IOException("glTF model file does not exist.");
        }
        final long sourceSize = Files.size(normalizedSource);
        if (sourceSize > config.maxFileSizeBytes()) {
            throw new IOException("glTF file exceeds the configured import size limit.");
        }

        final byte[] sourceBytes = Files.readAllBytes(normalizedSource);
        final Document document = normalizedSource.getFileName().toString().toLowerCase().endsWith(".glb")
                ? readGlb(sourceBytes)
                : readGltf(sourceBytes);
        final JsonObject json;
        try {
            json = JsonParser.parseString(new String(document.json(), StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("Invalid glTF JSON document.", exception);
        }

        final List<byte[]> buffers = loadBuffers(json, document.binaryChunk(), root, config.maxFileSizeBytes());
        final List<JsonObject> meshes = objects(json, "meshes");
        final List<JsonObject> nodes = objects(json, "nodes");
        final Animation animation = parseFirstAnimation(json, buffers, config.maxVertices());
        if (animation != null) {
            animation.validateNodeCount(nodes.size());
        }
        final List<List<ObjTriangle>> animationFrames = new ArrayList<>();
        final int animationDurationTicks;
        final SceneFrame baseFrame;
        if (animation == null) {
            baseFrame = importScene(nodes, meshes, json, buffers, root, config, Map.of());
            animationFrames.add(baseFrame.triangles());
            animationDurationTicks = 1;
        } else {
            animationDurationTicks = Math.max(1, (int) Math.ceil(animation.duration() * 20.0));
            final int frameCount = Math.max(2, Math.min(MAX_ANIMATION_FRAMES,
                    (int) Math.ceil(animation.duration() * 10.0) + 1));
            SceneFrame first = null;
            for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
                final double time = animation.duration() * frameIndex / (frameCount - 1);
                final SceneFrame frame = importScene(
                        nodes, meshes, json, buffers, root, config, animation.sample(time));
                if (first == null) {
                    first = frame;
                }
                animationFrames.add(frame.triangles());
            }
            baseFrame = first;
        }
        final List<ObjTriangle> triangles = animationFrames.getFirst();
        final List<Vertex> vertices = baseFrame.vertices();
        final TextureSelection texture = baseFrame.textures();

        if (triangles.isEmpty()) {
            throw new IOException("glTF file does not contain any triangle primitives.");
        }
        if (texture.hasAny() && triangles.stream().anyMatch(triangle -> !triangle.hasTextureCoordinates())) {
            throw new IOException("Textured glTF primitives must provide TEXCOORD_0 UV coordinates.");
        }
        final List<Triangle> meshTriangles = triangles.stream().map(ObjTriangle::triangle).toList();
        return new GltfImportResult(
                Mesh.fromTriangles(vertices, meshTriangles),
                triangles,
                animationFrames.size() > 1 ? animationFrames : List.of(),
                animationDurationTicks,
                texture.metallicFactor(),
                texture.roughnessFactor(),
                texture.baseColor,
                texture.normal,
                texture.metallicRoughness,
                texture.occlusion);
    }

    private static SceneFrame importScene(
            List<JsonObject> nodes,
            List<JsonObject> meshes,
            JsonObject document,
            List<byte[]> buffers,
            Path root,
            ObjImporterConfig config,
            Map<Integer, NodePose> overrides
    ) throws IOException {
        final List<ObjTriangle> triangles = new ArrayList<>();
        final List<Vertex> vertices = new ArrayList<>();
        final TextureSelection texture = new TextureSelection();
        final double[][] worldTransforms = computeWorldTransforms(nodes, document, overrides);
        if (nodes.isEmpty()) {
            for (JsonObject mesh : meshes) {
                importMesh(mesh, identity(), -1, worldTransforms, document, buffers, root, config,
                        triangles, vertices, texture);
            }
        } else {
            final JsonArray scenes = array(document, "scenes");
            if (scenes == null || scenes.isEmpty()) {
                for (JsonObject mesh : meshes) {
                    importMesh(mesh, identity(), -1, worldTransforms, document, buffers, root, config,
                            triangles, vertices, texture);
                }
            } else {
                final int sceneIndex = integer(document, "scene", 0);
                if (sceneIndex < 0 || sceneIndex >= scenes.size()) {
                    throw new IOException("glTF references an invalid default scene.");
                }
                final JsonObject scene = objectAt(scenes, sceneIndex, "scene");
                final JsonArray roots = array(scene, "nodes");
                if (roots == null) {
                    throw new IOException("glTF default scene does not contain any root nodes.");
                }
                final boolean[] visiting = new boolean[nodes.size()];
                for (JsonElement rootNode : roots) {
                    importNode(rootNode.getAsInt(), nodes, meshes, document, buffers, root, config,
                            triangles, vertices, texture, visiting, 0, worldTransforms);
                }
            }
        }
        return new SceneFrame(List.copyOf(triangles), List.copyOf(vertices), texture);
    }

    private static void importNode(
            int nodeIndex,
            List<JsonObject> nodes,
            List<JsonObject> meshes,
            JsonObject document,
            List<byte[]> buffers,
            Path root,
            ObjImporterConfig config,
            List<ObjTriangle> triangles,
            List<Vertex> vertices,
            TextureSelection texture,
            boolean[] visiting,
            int depth,
            double[][] worldTransforms
    ) throws IOException {
        if (nodeIndex < 0 || nodeIndex >= nodes.size() || depth > 128 || visiting[nodeIndex]) {
            throw new IOException("glTF node graph contains an invalid reference or cycle.");
        }
        visiting[nodeIndex] = true;
        final JsonObject node = nodes.get(nodeIndex);
        final double[] transform = worldTransforms[nodeIndex];
        if (node.has("mesh")) {
            final int meshIndex = node.get("mesh").getAsInt();
            if (meshIndex < 0 || meshIndex >= meshes.size()) {
                throw new IOException("glTF node references an invalid mesh.");
            }
            importMesh(meshes.get(meshIndex), transform, integer(node, "skin", -1), worldTransforms,
                    document, buffers, root, config,
                    triangles, vertices, texture);
        }
        final JsonArray children = array(node, "children");
        if (children != null) {
            for (JsonElement child : children) {
                importNode(child.getAsInt(), nodes, meshes, document, buffers, root, config,
                        triangles, vertices, texture, visiting, depth + 1, worldTransforms);
            }
        }
        visiting[nodeIndex] = false;
    }

    private static void importMesh(
            JsonObject mesh,
            double[] transform,
            int skinIndex,
            double[][] worldTransforms,
            JsonObject document,
            List<byte[]> buffers,
            Path root,
            ObjImporterConfig config,
            List<ObjTriangle> triangles,
            List<Vertex> vertices,
            TextureSelection texture
    ) throws IOException {
        final JsonArray primitives = array(mesh, "primitives");
        if (primitives == null || primitives.isEmpty()) {
            throw new IOException("glTF mesh has no primitives.");
        }
        if (mesh.has("weights")) {
            throw new IOException("glTF morph targets are not supported.");
        }
        for (JsonElement primitiveElement : primitives) {
            final JsonObject primitive = primitiveElement.getAsJsonObject();
            if (primitive.has("targets")) {
                throw new IOException("glTF morph targets are not supported.");
            }
            final int mode = integer(primitive, "mode", 4);
            if (mode != 4) {
                throw new IOException("Only glTF TRIANGLES primitives are supported.");
            }
            final JsonObject attributes = object(primitive, "attributes");
            if (attributes == null || !attributes.has("POSITION")) {
                throw new IOException("glTF primitive is missing POSITION data.");
            }
            final float[][] positions = readAccessor(document, buffers,
                    attributes.get("POSITION").getAsInt(), 3, config.maxVertices(), false, false);
            final float[][] uvs = attributes.has("TEXCOORD_0")
                    ? readAccessor(document, buffers, attributes.get("TEXCOORD_0").getAsInt(),
                            2, config.maxVertices(), true, false)
                    : null;
            if (uvs != null && uvs.length != positions.length) {
                throw new IOException("glTF POSITION and TEXCOORD_0 accessor counts do not match.");
            }
            final int materialIndex = integer(primitive, "material", -1);
            final MaterialTextures materialTextures = materialTextures(
                    document, buffers, materialIndex, root, config.maxFileSizeBytes());
            texture.set(materialTextures);
            if (materialTextures.hasAny()) {
                if (uvs == null) {
                    throw new IOException("Textured glTF primitives must provide TEXCOORD_0 UV coordinates.");
                }
            }
            final float[][] joints;
            final float[][] weights;
            final double[][] jointMatrices;
            if (skinIndex >= 0) {
                if (!attributes.has("JOINTS_0") || !attributes.has("WEIGHTS_0")) {
                    throw new IOException("Skinned glTF primitives must provide JOINTS_0 and WEIGHTS_0.");
                }
                joints = readAccessor(document, buffers, attributes.get("JOINTS_0").getAsInt(),
                        4, config.maxVertices(), true, true);
                weights = readAccessor(document, buffers, attributes.get("WEIGHTS_0").getAsInt(),
                        4, config.maxVertices(), true, false);
                if (joints.length != positions.length || weights.length != positions.length) {
                    throw new IOException("Skin joint and weight accessors must match POSITION count.");
                }
                jointMatrices = skinMatrices(document, buffers, skinIndex, worldTransforms);
            } else {
                if (attributes.has("JOINTS_0") || attributes.has("WEIGHTS_0")) {
                    throw new IOException("glTF joint attributes require a node with a skin.");
                }
                joints = null;
                weights = null;
                jointMatrices = null;
            }

            final int[] indices;
            if (primitive.has("indices")) {
                indices = readIndices(document, buffers, primitive.get("indices").getAsInt(),
                        Math.multiplyExact(config.maxFaces(), 3));
            } else {
                indices = new int[positions.length];
                for (int index = 0; index < indices.length; index++) {
                    indices[index] = index;
                }
            }
            if (indices.length == 0 || indices.length % 3 != 0) {
                throw new IOException("glTF triangle primitive index count must be a non-zero multiple of three.");
            }
            if (triangles.size() + indices.length / 3 > config.maxFaces()) {
                throw new IOException("glTF exceeds configured maximum faces.");
            }
            for (int index = 0; index < indices.length; index += 3) {
                final int a = indices[index];
                final int b = indices[index + 1];
                final int c = indices[index + 2];
                validateVertexIndex(a, positions.length);
                validateVertexIndex(b, positions.length);
                validateVertexIndex(c, positions.length);
                final Vertex vertexA = skinIndex >= 0
                        ? skinVertex(positions[a], joints[a], weights[a], jointMatrices)
                        : transform(positions[a], transform);
                final Vertex vertexB = skinIndex >= 0
                        ? skinVertex(positions[b], joints[b], weights[b], jointMatrices)
                        : transform(positions[b], transform);
                final Vertex vertexC = skinIndex >= 0
                        ? skinVertex(positions[c], joints[c], weights[c], jointMatrices)
                        : transform(positions[c], transform);
                final Triangle triangle = new Triangle(vertexA, vertexB, vertexC);
                triangles.add(new ObjTriangle(
                        triangle,
                        coordinate(uvs, a),
                        coordinate(uvs, b),
                        coordinate(uvs, c),
                        ""));
                vertices.add(vertexA);
                vertices.add(vertexB);
                vertices.add(vertexC);
            }
        }
    }

    private static MaterialTextures materialTextures(
            JsonObject document,
            List<byte[]> buffers,
            int materialIndex,
            Path root,
            long maxFileSize
    )
            throws IOException {
        if (materialIndex < 0) {
            return MaterialTextures.EMPTY;
        }
        final JsonObject material = objectAt(array(document, "materials"), materialIndex, "material");
        final JsonObject pbr = object(material, "pbrMetallicRoughness");
        final JsonArray baseColorFactor = pbr == null ? null : array(pbr, "baseColorFactor");
        if (baseColorFactor != null) {
            if (baseColorFactor.size() != 4) {
                throw new IOException("glTF baseColorFactor must contain four values.");
            }
            for (JsonElement component : baseColorFactor) {
                if (component.getAsFloat() != 1.0f) {
                    throw new IOException("Non-white glTF baseColorFactor values are not supported.");
                }
            }
        }
        final float metallicFactor = pbr == null ? 1.0f : materialFactor(pbr, "metallicFactor", 1.0f);
        final float roughnessFactor = pbr == null ? 1.0f : materialFactor(pbr, "roughnessFactor", 1.0f);
        final JsonObject baseTexture = pbr == null ? null : object(pbr, "baseColorTexture");
        final JsonObject normalTexture = object(material, "normalTexture");
        final JsonObject metallicRoughness = pbr == null ? null : object(pbr, "metallicRoughnessTexture");
        final JsonObject occlusionTexture = object(material, "occlusionTexture");
        if (normalTexture != null) {
            requireDefaultFactor(normalTexture, "scale", 1.0);
        }
        if (occlusionTexture != null) {
            requireDefaultFactor(occlusionTexture, "strength", 1.0);
        }
        return new MaterialTextures(
                readTexture(document, buffers, baseTexture, root, maxFileSize),
                readTexture(document, buffers, normalTexture, root, maxFileSize),
                readTexture(document, buffers, metallicRoughness, root, maxFileSize),
                readTexture(document, buffers, occlusionTexture, root, maxFileSize),
                metallicFactor,
                roughnessFactor);
    }

    private static float materialFactor(JsonObject object, String key, float defaultValue) throws IOException {
        if (!object.has(key)) {
            return defaultValue;
        }
        final float value;
        try {
            value = object.get(key).getAsFloat();
        } catch (RuntimeException exception) {
            throw new IOException("glTF material factor " + key + " must be numeric.", exception);
        }
        if (!Float.isFinite(value) || value < 0 || value > 1) {
            throw new IOException("glTF material factor " + key + " must be between 0 and 1.");
        }
        return value;
    }

    private static void requireDefaultFactor(JsonObject object, String key, double defaultValue) throws IOException {
        if (!object.has(key)) {
            return;
        }
        final double value;
        try {
            value = object.get(key).getAsDouble();
        } catch (RuntimeException exception) {
            throw new IOException("glTF material factor " + key + " must be numeric.", exception);
        }
        if (!Double.isFinite(value) || value != defaultValue) {
            throw new IOException("Non-default glTF material factor " + key + " is not supported.");
        }
    }

    private static double[][] skinMatrices(
            JsonObject document,
            List<byte[]> buffers,
            int skinIndex,
            double[][] worldTransforms
    ) throws IOException {
        final JsonObject skin = objectAt(array(document, "skins"), skinIndex, "skin");
        final JsonArray joints = array(skin, "joints");
        if (joints == null || joints.isEmpty() || joints.size() > 256) {
            throw new IOException("glTF skin must reference between 1 and 256 joints.");
        }
        final double[][] inverseBind = new double[joints.size()][];
        if (skin.has("inverseBindMatrices")) {
            final float[][] values = readAccessor(document, buffers, skin.get("inverseBindMatrices").getAsInt(),
                    16, joints.size(), false, false);
            if (values.length != joints.size()) {
                throw new IOException("glTF inverseBindMatrices count must match the skin joint count.");
            }
            for (int index = 0; index < values.length; index++) {
                inverseBind[index] = new double[16];
                for (int component = 0; component < 16; component++) {
                    inverseBind[index][component] = values[index][component];
                }
            }
        } else {
            for (int index = 0; index < joints.size(); index++) {
                inverseBind[index] = identity();
            }
        }
        final double[][] matrices = new double[joints.size()][];
        for (int index = 0; index < joints.size(); index++) {
            final int node = joints.get(index).getAsInt();
            if (node < 0 || node >= worldTransforms.length || worldTransforms[node] == null) {
                throw new IOException("glTF skin references a joint outside the scene graph.");
            }
            matrices[index] = multiply(worldTransforms[node], inverseBind[index]);
        }
        return matrices;
    }

    private static Vertex skinVertex(
            float[] position,
            float[] joints,
            float[] weights,
            double[][] jointMatrices
    ) throws IOException {
        double x = 0;
        double y = 0;
        double z = 0;
        double weightSum = 0;
        for (int influence = 0; influence < 4; influence++) {
            final double weight = weights[influence];
            if (!Double.isFinite(weight) || weight < 0) {
                throw new IOException("glTF skin weights must be finite and non-negative.");
            }
            if (weight == 0) {
                continue;
            }
            final double jointValue = joints[influence];
            final int joint = (int) jointValue;
            if (jointValue != joint || joint < 0 || joint >= jointMatrices.length) {
                throw new IOException("glTF vertex references an invalid skin joint.");
            }
            final Vertex transformed = transform(position, jointMatrices[joint]);
            x += transformed.x() * weight;
            y += transformed.y() * weight;
            z += transformed.z() * weight;
            weightSum += weight;
        }
        if (weightSum <= 1e-8) {
            throw new IOException("glTF skinned vertex has no positive joint weights.");
        }
        final double inverseWeight = 1.0 / weightSum;
        return new Vertex(x * inverseWeight, y * inverseWeight, z * inverseWeight);
    }

    private static byte[] readTexture(
            JsonObject document,
            List<byte[]> buffers,
            JsonObject textureInfo,
            Path root,
            long maxFileSize
    ) throws IOException {
        if (textureInfo == null) {
            return null;
        }
        final JsonObject extensions = object(textureInfo, "extensions");
        if (extensions != null && extensions.has("KHR_texture_transform")) {
            throw new IOException("glTF KHR_texture_transform is not supported.");
        }
        if (integer(textureInfo, "texCoord", 0) != 0) {
            throw new IOException("Only glTF material textures using TEXCOORD_0 are supported.");
        }
        final int textureIndex = integer(textureInfo, "index", -1);
        final JsonObject texture = objectAt(array(document, "textures"), textureIndex, "texture");
        final int imageIndex = integer(texture, "source", -1);
        return imageBytes(document, buffers, imageIndex, root, maxFileSize);
    }

    private static Animation parseFirstAnimation(
            JsonObject document,
            List<byte[]> buffers,
            int maxAccessorCount
    ) throws IOException {
        final JsonArray animations = array(document, "animations");
        if (animations == null || animations.isEmpty()) {
            return null;
        }
        final JsonObject animation = objectAt(animations, 0, "animation");
        final JsonArray samplers = array(animation, "samplers");
        final JsonArray channels = array(animation, "channels");
        if (samplers == null || channels == null || channels.isEmpty()) {
            throw new IOException("glTF animation must define samplers and channels.");
        }
        final List<AnimationTrack> tracks = new ArrayList<>();
        double duration = 0;
        for (JsonElement channelElement : channels) {
            final JsonObject channel = channelElement.getAsJsonObject();
            final int samplerIndex = integer(channel, "sampler", -1);
            final JsonObject sampler = objectAt(samplers, samplerIndex, "animation sampler");
            final JsonObject target = object(channel, "target");
            if (target == null) {
                throw new IOException("glTF animation channel is missing its target.");
            }
            final int node = integer(target, "node", -1);
            final String path = string(target, "path", "");
            if (node < 0 || !List.of("translation", "rotation", "scale").contains(path)) {
                throw new IOException("Only glTF node translation, rotation, and scale animation is supported.");
            }
            final int components = path.equals("rotation") ? 4 : 3;
            final float[][] input = readAccessor(document, buffers,
                    integer(sampler, "input", -1), 1, maxAccessorCount, false, false);
            final float[][] output = readAccessor(document, buffers,
                    integer(sampler, "output", -1), components, maxAccessorCount * 3, false, false);
            final String interpolation = string(sampler, "interpolation", "LINEAR");
            if (!List.of("LINEAR", "STEP", "CUBICSPLINE").contains(interpolation)) {
                throw new IOException("Unsupported glTF animation interpolation: " + interpolation);
            }
            final int multiplier = interpolation.equals("CUBICSPLINE") ? 3 : 1;
            if (input.length < 1 || output.length != input.length * multiplier) {
                throw new IOException("glTF animation input/output key counts do not match.");
            }
            for (int index = 0; index < input.length; index++) {
                if (input[index][0] < 0 || (index > 0 && input[index][0] <= input[index - 1][0])) {
                    throw new IOException("glTF animation key times must be increasing and non-negative.");
                }
            }
            duration = Math.max(duration, input[input.length - 1][0]);
            tracks.add(new AnimationTrack(node, path, input, output, interpolation));
        }
        if (!Double.isFinite(duration) || duration <= 0) {
            throw new IOException("glTF animation duration must be positive and finite.");
        }
        return new Animation(tracks, duration);
    }

    private static Map<Integer, NodePose> sampleAnimation(List<AnimationTrack> tracks, double time) {
        final Map<Integer, NodePose> poses = new java.util.HashMap<>();
        for (AnimationTrack track : tracks) {
            final float[] value = track.sample(time);
            final NodePose pose = poses.computeIfAbsent(track.node(), ignored -> new NodePose());
            switch (track.path()) {
                case "translation" -> pose.translation = value;
                case "rotation" -> pose.rotation = value;
                case "scale" -> pose.scale = value;
                default -> throw new IllegalStateException("Unsupported animation path.");
            }
        }
        return poses;
    }

    private static float[][] readAccessor(
            JsonObject document,
            List<byte[]> buffers,
            int accessorIndex,
            int components,
            int maxCount,
            boolean allowNormalizedInteger,
            boolean allowUnnormalizedInteger
    ) throws IOException {
        final JsonObject accessor = objectAt(array(document, "accessors"), accessorIndex, "accessor");
        if (accessor.has("sparse")) {
            throw new IOException("Sparse glTF accessors are not supported.");
        }
        final int viewIndex = integer(accessor, "bufferView", -1);
        final JsonObject view = objectAt(array(document, "bufferViews"), viewIndex, "bufferView");
        final int bufferIndex = integer(view, "buffer", -1);
        if (bufferIndex < 0 || bufferIndex >= buffers.size()) {
            throw new IOException("glTF bufferView references an invalid buffer.");
        }
        final int count = integer(accessor, "count", -1);
        if (count < 1) {
            throw new IOException("glTF accessor count must be positive.");
        }
        if (count > maxCount) {
            throw new IOException("glTF accessor count " + count + " exceeds the configured limit "
                    + maxCount + "; increase importer.max-vertices or reduce the model.");
        }
        final String expectedType = switch (components) {
            case 1 -> "SCALAR";
            case 2 -> "VEC2";
            case 3 -> "VEC3";
            case 4 -> "VEC4";
            case 16 -> "MAT4";
            default -> throw new IOException("Unsupported glTF accessor component count.");
        };
        if (!expectedType.equals(string(accessor, "type", ""))) {
            throw new IOException("glTF accessor must use " + expectedType + " data.");
        }
        final int componentType = integer(accessor, "componentType", -1);
        if (components == 3 && componentType != 5126) {
            throw new IOException("glTF POSITION accessors must use FLOAT components.");
        }
        final int componentSize = componentSize(componentType, allowNormalizedInteger);
        final boolean normalized = accessor.has("normalized") && accessor.get("normalized").getAsBoolean();
        if (componentType != 5126 && !normalized && !allowUnnormalizedInteger) {
            throw new IOException("Integer glTF texture coordinates must be normalized.");
        }
        final int packedSize = componentSize * components;
        final int stride = integer(view, "byteStride", packedSize);
        final int viewLength = integer(view, "byteLength", -1);
        final int viewOffset = integer(view, "byteOffset", 0);
        final int accessorOffset = integer(accessor, "byteOffset", 0);
        final long lastByte = (long) accessorOffset + (long) (count - 1) * stride + packedSize;
        final byte[] buffer = buffers.get(bufferIndex);
        if (stride < packedSize || stride > 252 || viewOffset < 0 || viewLength < 0
                || accessorOffset < 0 || lastByte > viewLength
                || (long) viewOffset + viewLength > buffer.length) {
            throw new IOException("glTF accessor exceeds its bufferView bounds.");
        }
        final ByteBuffer data = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
        final float[][] values = new float[count][components];
        for (int item = 0; item < count; item++) {
            final int offset = viewOffset + accessorOffset + item * stride;
            for (int component = 0; component < components; component++) {
                final int componentOffset = offset + component * componentSize;
                values[item][component] = readComponent(data, componentOffset, componentType, normalized);
                if (!Float.isFinite(values[item][component])) {
                    throw new IOException("glTF accessor contains a non-finite value.");
                }
            }
        }
        return values;
    }

    private static int[] readIndices(
            JsonObject document,
            List<byte[]> buffers,
            int accessorIndex,
            int maxCount
    ) throws IOException {
        final JsonObject accessor = objectAt(array(document, "accessors"), accessorIndex, "index accessor");
        if (accessor.has("sparse") || !"SCALAR".equals(string(accessor, "type", ""))) {
            throw new IOException("glTF indices must use a non-sparse SCALAR accessor.");
        }
        final int componentType = integer(accessor, "componentType", -1);
        if (componentType != 5121 && componentType != 5123 && componentType != 5125) {
            throw new IOException("glTF indices must use unsigned byte, short, or int components.");
        }
        final float[][] values = readAccessorRaw(document, buffers, accessorIndex, componentType, maxCount);
        final int[] result = new int[values.length];
        for (int index = 0; index < result.length; index++) {
            final float value = values[index][0];
            if (value < 0 || value > Integer.MAX_VALUE) {
                throw new IOException("glTF index exceeds supported range.");
            }
            result[index] = (int) value;
        }
        return result;
    }

    private static float[][] readAccessorRaw(
            JsonObject document,
            List<byte[]> buffers,
            int accessorIndex,
            int componentType,
            int maxCount
    ) throws IOException {
        final JsonObject accessor = objectAt(array(document, "accessors"), accessorIndex, "index accessor");
        final JsonObject view = objectAt(array(document, "bufferViews"),
                integer(accessor, "bufferView", -1), "bufferView");
        final int bufferIndex = integer(view, "buffer", -1);
        if (bufferIndex < 0 || bufferIndex >= buffers.size()) {
            throw new IOException("glTF index bufferView references an invalid buffer.");
        }
        final byte[] buffer = buffers.get(bufferIndex);
        final int count = integer(accessor, "count", -1);
        final int size = componentSize(componentType, false);
        final int stride = integer(view, "byteStride", size);
        final int viewOffset = integer(view, "byteOffset", 0);
        final int viewLength = integer(view, "byteLength", -1);
        final int accessorOffset = integer(accessor, "byteOffset", 0);
        if (count < 1 || count > maxCount || stride < size || stride > 252
                || viewOffset < 0 || viewLength < 0 || accessorOffset < 0
                || (long) accessorOffset + (long) (count - 1) * stride + size > viewLength
                || (long) viewOffset + viewLength > buffer.length) {
            throw new IOException("glTF index accessor exceeds its bufferView bounds.");
        }
        final ByteBuffer data = ByteBuffer.wrap(buffer).order(ByteOrder.LITTLE_ENDIAN);
        final float[][] result = new float[count][1];
        for (int index = 0; index < count; index++) {
            result[index][0] = readComponent(data, viewOffset + accessorOffset + index * stride,
                    componentType, false);
        }
        return result;
    }

    private static int componentSize(int componentType, boolean allowNormalizedInteger) throws IOException {
        return switch (componentType) {
            case 5126 -> 4;
            case 5123 -> 2;
            case 5121 -> 1;
            case 5125 -> {
                if (allowNormalizedInteger) {
                    throw new IOException("Unsigned int texture coordinates are not supported.");
                }
                yield 4;
            }
            default -> throw new IOException("Unsupported glTF accessor component type: " + componentType);
        };
    }

    private static float readComponent(ByteBuffer data, int offset, int componentType, boolean normalized) {
        return switch (componentType) {
            case 5126 -> data.getFloat(offset);
            case 5123 -> normalized ? (data.getShort(offset) & 0xFFFF) / 65535.0f : data.getShort(offset) & 0xFFFF;
            case 5121 -> normalized ? (data.get(offset) & 0xFF) / 255.0f : data.get(offset) & 0xFF;
            case 5125 -> (float) Integer.toUnsignedLong(data.getInt(offset));
            default -> throw new IllegalArgumentException("Unsupported glTF component type.");
        };
    }

    private static List<byte[]> loadBuffers(
            JsonObject document,
            byte[] binaryChunk,
            Path root,
            long maxFileSize
    ) throws IOException {
        final JsonArray bufferDefinitions = array(document, "buffers");
        if (bufferDefinitions == null || bufferDefinitions.isEmpty()) {
            throw new IOException("glTF document does not define any buffers.");
        }
        final List<byte[]> buffers = new ArrayList<>(bufferDefinitions.size());
        long totalBytes = 0;
        for (int index = 0; index < bufferDefinitions.size(); index++) {
            final JsonObject definition = bufferDefinitions.get(index).getAsJsonObject();
            final int expectedLength = integer(definition, "byteLength", -1);
            if (expectedLength < 0 || expectedLength > maxFileSize) {
                throw new IOException("glTF buffer length is invalid or exceeds the import size limit.");
            }
            final byte[] bytes;
            if (definition.has("uri")) {
                bytes = readUri(definition.get("uri").getAsString(), root, maxFileSize);
            } else if (index == 0 && binaryChunk != null) {
                bytes = binaryChunk;
            } else {
                throw new IOException("glTF buffer has no URI or GLB binary chunk.");
            }
            if (bytes.length < expectedLength) {
                throw new IOException("glTF buffer is shorter than its declared byteLength.");
            }
            totalBytes += bytes.length;
            if (totalBytes > maxFileSize) {
                throw new IOException("Total glTF buffer data exceeds the configured import size limit.");
            }
            buffers.add(bytes);
        }
        return buffers;
    }

    private static byte[] imageBytes(JsonObject document, List<byte[]> buffers, int imageIndex, Path root,
            long maxFileSize) throws IOException {
        final JsonObject image = objectAt(array(document, "images"), imageIndex, "image");
        final byte[] bytes;
        if (image.has("uri")) {
            bytes = readUri(image.get("uri").getAsString(), root, maxFileSize);
        } else if (image.has("bufferView")) {
            final JsonObject view = objectAt(array(document, "bufferViews"),
                    image.get("bufferView").getAsInt(), "image bufferView");
            final int bufferIndex = integer(view, "buffer", -1);
            if (bufferIndex < 0 || bufferIndex >= buffers.size()) {
                throw new IOException("glTF image references an invalid buffer.");
            }
            final byte[] buffer = buffers.get(bufferIndex);
            final int offset = integer(view, "byteOffset", 0);
            final int length = integer(view, "byteLength", -1);
            if (offset < 0 || length < 0 || length > maxFileSize || (long) offset + length > buffer.length) {
                throw new IOException("glTF embedded image exceeds its bufferView bounds.");
            }
            bytes = Arrays.copyOfRange(buffer, offset, offset + length);
        } else {
            throw new IOException("glTF image has neither a URI nor a bufferView.");
        }
        if (bytes.length == 0 || bytes.length > maxFileSize) {
            throw new IOException("glTF image is empty or exceeds the configured import size limit.");
        }
        return bytes;
    }

    private static byte[] readUri(String uriText, Path root, long maxFileSize) throws IOException {
        if (uriText.startsWith("data:")) {
            final int separator = uriText.indexOf(',');
            if (separator < 0 || !uriText.substring(0, separator).endsWith(";base64")) {
                throw new IOException("Only base64 data URIs are supported in glTF.");
            }
            try {
                final byte[] data = Base64.getDecoder().decode(uriText.substring(separator + 1));
                if (data.length > maxFileSize) {
                    throw new IOException("Embedded glTF data exceeds the configured import size limit.");
                }
                return data;
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid base64 data URI in glTF.", exception);
            }
        }
        try {
            final URI uri = new URI(uriText);
            if (uri.isAbsolute() || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IOException("External glTF assets must be local relative paths.");
            }
            final Path asset = root.resolve(uri.getPath()).normalize();
            if (!asset.startsWith(root) || !Files.isRegularFile(asset)) {
                throw new IOException("Referenced glTF asset is missing or escapes the models folder: " + uriText);
            }
            final Path realAsset = asset.toRealPath();
            if (!realAsset.startsWith(root)) {
                throw new IOException("Referenced glTF asset resolves outside the models folder: " + uriText);
            }
            final long size = Files.size(realAsset);
            if (size > maxFileSize) {
                throw new IOException("Referenced glTF asset exceeds the configured import size limit.");
            }
            return Files.readAllBytes(realAsset);
        } catch (URISyntaxException exception) {
            throw new IOException("Invalid glTF asset URI: " + uriText, exception);
        }
    }

    private static Document readGltf(byte[] bytes) throws IOException {
        return new Document(bytes, null);
    }

    private static Document readGlb(byte[] bytes) throws IOException {
        if (bytes.length < 20) {
            throw new IOException("GLB file is truncated.");
        }
        final ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (data.getInt(0) != GLB_MAGIC || data.getInt(4) != 2 || data.getInt(8) != bytes.length) {
            throw new IOException("Invalid GLB header; only GLB version 2 is supported.");
        }
        int offset = 12;
        byte[] json = null;
        byte[] binary = null;
        while (offset + 8 <= bytes.length) {
            final int length = data.getInt(offset);
            final int type = data.getInt(offset + 4);
            offset += 8;
            if (length < 0 || (long) offset + length > bytes.length) {
                throw new IOException("GLB chunk exceeds the file bounds.");
            }
            if (type == JSON_CHUNK && json == null) {
                json = Arrays.copyOfRange(bytes, offset, offset + length);
            } else if (type == BIN_CHUNK && binary == null) {
                binary = Arrays.copyOfRange(bytes, offset, offset + length);
            }
            offset += length;
        }
        if (json == null || offset != bytes.length) {
            throw new IOException("GLB file is missing a valid JSON chunk.");
        }
        return new Document(json, binary);
    }

    private static double[] nodeTransform(JsonObject node, NodePose pose) throws IOException {
        if (node.has("matrix")) {
            if (pose != null) {
                throw new IOException("Animated glTF nodes must use TRS transforms, not a matrix.");
            }
            final JsonArray matrix = node.getAsJsonArray("matrix");
            if (matrix.size() != 16) {
                throw new IOException("glTF node matrix must contain 16 values.");
            }
            final double[] result = new double[16];
            for (int index = 0; index < 16; index++) {
                result[index] = matrix.get(index).getAsDouble();
            }
            return result;
        }

        final double[] translation = pose != null && pose.translation != null
                ? doubles(pose.translation) : vector(node, "translation", new double[]{0, 0, 0}, 3);
        final double[] scale = pose != null && pose.scale != null
                ? doubles(pose.scale) : vector(node, "scale", new double[]{1, 1, 1}, 3);
        final double[] rotation = pose != null && pose.rotation != null
                ? doubles(pose.rotation) : vector(node, "rotation", new double[]{0, 0, 0, 1}, 4);
        final double x = rotation[0], y = rotation[1], z = rotation[2], w = rotation[3];
        final double[] result = identity();
        result[0] = (1 - 2 * (y * y + z * z)) * scale[0];
        result[1] = (2 * (x * y + z * w)) * scale[0];
        result[2] = (2 * (x * z - y * w)) * scale[0];
        result[4] = (2 * (x * y - z * w)) * scale[1];
        result[5] = (1 - 2 * (x * x + z * z)) * scale[1];
        result[6] = (2 * (y * z + x * w)) * scale[1];
        result[8] = (2 * (x * z + y * w)) * scale[2];
        result[9] = (2 * (y * z - x * w)) * scale[2];
        result[10] = (1 - 2 * (x * x + y * y)) * scale[2];
        result[12] = translation[0];
        result[13] = translation[1];
        result[14] = translation[2];
        return result;
    }

    private static double[][] computeWorldTransforms(
            List<JsonObject> nodes,
            JsonObject document,
            Map<Integer, NodePose> overrides
    ) throws IOException {
        final double[][] world = new double[nodes.size()][];
        final int[] parents = new int[nodes.size()];
        Arrays.fill(parents, -1);
        for (int parent = 0; parent < nodes.size(); parent++) {
            final JsonArray children = array(nodes.get(parent), "children");
            if (children == null) {
                continue;
            }
            for (JsonElement childElement : children) {
                final int child = childElement.getAsInt();
                if (child < 0 || child >= nodes.size() || parents[child] >= 0) {
                    throw new IOException("glTF node graph contains an invalid child or multiple parents.");
                }
                parents[child] = parent;
            }
        }
        final byte[] state = new byte[nodes.size()];
        for (int node = 0; node < nodes.size(); node++) {
            resolveWorldTransform(node, parents, state, world, nodes, overrides, 0);
        }
        final JsonArray skins = array(document, "skins");
        if (skins != null) {
            for (JsonElement skinElement : skins) {
                final JsonArray joints = array(skinElement.getAsJsonObject(), "joints");
                if (joints == null) {
                    throw new IOException("glTF skin is missing its joints.");
                }
                for (JsonElement joint : joints) {
                    final int node = joint.getAsInt();
                    if (node < 0 || node >= world.length) {
                        throw new IOException("glTF skin references an invalid joint node.");
                    }
                }
            }
        }
        return world;
    }

    private static double[] resolveWorldTransform(
            int nodeIndex,
            int[] parents,
            byte[] state,
            double[][] world,
            List<JsonObject> nodes,
            Map<Integer, NodePose> overrides,
            int depth
    ) throws IOException {
        if (depth > 128 || state[nodeIndex] == 1) {
            throw new IOException("glTF node graph contains a cycle.");
        }
        if (state[nodeIndex] == 2) {
            return world[nodeIndex];
        }
        state[nodeIndex] = 1;
        final double[] local = nodeTransform(nodes.get(nodeIndex), overrides.get(nodeIndex));
        final int parent = parents[nodeIndex];
        world[nodeIndex] = parent < 0 ? local
                : multiply(resolveWorldTransform(parent, parents, state, world, nodes, overrides, depth + 1), local);
        state[nodeIndex] = 2;
        return world[nodeIndex];
    }

    private static double[] doubles(float[] values) {
        final double[] result = new double[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = values[index];
        }
        return result;
    }

    private static double[] vector(JsonObject object, String key, double[] fallback, int expectedSize)
            throws IOException {
        if (!object.has(key)) {
            return fallback;
        }
        final JsonArray values = object.getAsJsonArray(key);
        if (values.size() != expectedSize) {
            throw new IOException("glTF node " + key + " has an invalid number of values.");
        }
        final double[] result = new double[expectedSize];
        for (int index = 0; index < expectedSize; index++) {
            result[index] = values.get(index).getAsDouble();
        }
        return result;
    }

    private static double[] multiply(double[] left, double[] right) {
        final double[] result = new double[16];
        for (int column = 0; column < 4; column++) {
            for (int row = 0; row < 4; row++) {
                for (int index = 0; index < 4; index++) {
                    result[column * 4 + row] += left[index * 4 + row] * right[column * 4 + index];
                }
            }
        }
        return result;
    }

    private static Vertex transform(float[] point, double[] matrix) throws IOException {
        final double x = point[0], y = point[1], z = point[2];
        final double w = matrix[3] * x + matrix[7] * y + matrix[11] * z + matrix[15];
        if (!Double.isFinite(w) || w == 0) {
            throw new IOException("glTF node transform produced an invalid position.");
        }
        final double transformedX = (matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12]) / w;
        final double transformedY = (matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13]) / w;
        final double transformedZ = (matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]) / w;
        if (!Double.isFinite(transformedX) || !Double.isFinite(transformedY) || !Double.isFinite(transformedZ)) {
            throw new IOException("glTF node transform produced a non-finite position.");
        }
        return new Vertex(transformedX, transformedY, transformedZ);
    }

    private static TextureCoordinate coordinate(float[][] uvs, int index) {
        return uvs == null ? null : new TextureCoordinate(uvs[index][0], uvs[index][1]);
    }

    private static void validateVertexIndex(int index, int vertexCount) throws IOException {
        if (index < 0 || index >= vertexCount) {
            throw new IOException("glTF primitive references an invalid vertex index.");
        }
    }

    private static double[] identity() {
        return new double[]{1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
    }

    private static JsonArray array(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonArray() ? object.getAsJsonArray(key) : null;
    }

    private static List<JsonObject> objects(JsonObject object, String key) throws IOException {
        final JsonArray array = array(object, key);
        if (array == null) {
            return List.of();
        }
        final List<JsonObject> result = new ArrayList<>(array.size());
        for (JsonElement element : array) {
            if (!element.isJsonObject()) {
                throw new IOException("glTF " + key + " must contain JSON objects.");
            }
            result.add(element.getAsJsonObject());
        }
        return result;
    }

    private static JsonObject object(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonObject() ? object.getAsJsonObject(key) : null;
    }

    private static JsonObject objectAt(JsonArray array, int index, String description) throws IOException {
        if (array == null || index < 0 || index >= array.size() || !array.get(index).isJsonObject()) {
            throw new IOException("glTF references an invalid " + description + ".");
        }
        return array.get(index).getAsJsonObject();
    }

    private static int integer(JsonObject object, String key, int fallback) throws IOException {
        if (!object.has(key)) {
            return fallback;
        }
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException exception) {
            throw new IOException("glTF property " + key + " must be an integer.", exception);
        }
    }

    private static String string(JsonObject object, String key, String fallback) {
        return object.has(key) ? object.get(key).getAsString() : fallback;
    }

    private static final class TextureSelection {
        private byte[] baseColor;
        private byte[] normal;
        private byte[] metallicRoughness;
        private byte[] occlusion;
        private Float metallicFactor;
        private Float roughnessFactor;

        private boolean hasAny() {
            return baseColor != null || normal != null || metallicRoughness != null || occlusion != null;
        }

        private void set(MaterialTextures candidate) throws IOException {
            baseColor = select(baseColor, candidate.baseColor);
            normal = select(normal, candidate.normal);
            metallicRoughness = select(metallicRoughness, candidate.metallicRoughness);
            occlusion = select(occlusion, candidate.occlusion);
            metallicFactor = selectFactor(metallicFactor, candidate.metallicFactor, "metallicFactor");
            roughnessFactor = selectFactor(roughnessFactor, candidate.roughnessFactor, "roughnessFactor");
        }

        private float metallicFactor() {
            return metallicFactor == null ? 1.0f : metallicFactor;
        }

        private float roughnessFactor() {
            return roughnessFactor == null ? 1.0f : roughnessFactor;
        }

        private static Float selectFactor(Float current, float candidate, String name) throws IOException {
            if (current != null && Float.compare(current, candidate) != 0) {
                throw new IOException("ObjCraft supports one " + name + " value across the model.");
            }
            return current == null ? candidate : current;
        }

        private static byte[] select(byte[] current, byte[] candidate) throws IOException {
            if (current != null && candidate != null && !Arrays.equals(current, candidate)) {
                throw new IOException("ObjCraft supports one texture image per PBR map across the model.");
            }
            return current == null ? candidate : current;
        }
    }

    private record MaterialTextures(
            byte[] baseColor,
            byte[] normal,
            byte[] metallicRoughness,
            byte[] occlusion,
            float metallicFactor,
            float roughnessFactor
    ) {
        private static final MaterialTextures EMPTY = new MaterialTextures(null, null, null, null, 1.0f, 1.0f);

        boolean hasAny() {
            return baseColor != null || normal != null || metallicRoughness != null || occlusion != null;
        }
    }

    private static final class NodePose {
        private float[] translation;
        private float[] rotation;
        private float[] scale;
    }

    private record SceneFrame(List<ObjTriangle> triangles, List<Vertex> vertices, TextureSelection textures) {
    }

    private record Animation(List<AnimationTrack> tracks, double duration) {
        Map<Integer, NodePose> sample(double time) {
            return sampleAnimation(tracks, time);
        }

        void validateNodeCount(int nodeCount) throws IOException {
            for (AnimationTrack track : tracks) {
                if (track.node() >= nodeCount) {
                    throw new IOException("glTF animation targets an invalid node.");
                }
            }
        }
    }

    private record AnimationTrack(
            int node,
            String path,
            float[][] times,
            float[][] values,
            String interpolation
    ) {
        private float[] sample(double time) {
            int next = 0;
            while (next < times.length && times[next][0] <= time) {
                next++;
            }
            if (next == 0) {
                return keyValue(0);
            }
            if (next == times.length) {
                return keyValue(times.length - 1);
            }
            final int previous = next - 1;
            final double start = times[previous][0];
            final double end = times[next][0];
            final double amount = (time - start) / (end - start);
            if (interpolation.equals("STEP")) {
                return keyValue(previous);
            }
            if (interpolation.equals("CUBICSPLINE")) {
                final double t2 = amount * amount;
                final double t3 = t2 * amount;
                final double delta = end - start;
                final float[] result = new float[values[previous * 3 + 1].length];
                for (int component = 0; component < result.length; component++) {
                    result[component] = (float) (
                            values[previous * 3 + 1][component] * (2 * t3 - 3 * t2 + 1)
                                    + values[previous * 3 + 2][component] * delta * (t3 - 2 * t2 + amount)
                                    + values[next * 3 + 1][component] * (-2 * t3 + 3 * t2)
                                    + values[next * 3][component] * delta * (t3 - t2));
                }
                return path.equals("rotation") ? normalizeQuaternion(result) : result;
            }
            final float[] left = keyValue(previous);
            final float[] right = keyValue(next);
            if (path.equals("rotation")) {
                return slerp(left, right, amount);
            }
            final float[] result = new float[left.length];
            for (int component = 0; component < result.length; component++) {
                result[component] = (float) (left[component] * (1 - amount) + right[component] * amount);
            }
            return result;
        }

        private float[] keyValue(int index) {
            return values[interpolation.equals("CUBICSPLINE") ? index * 3 + 1 : index].clone();
        }

        private static float[] slerp(float[] left, float[] right, double amount) {
            double dot = 0;
            for (int component = 0; component < 4; component++) {
                dot += left[component] * right[component];
            }
            final float[] target = right.clone();
            if (dot < 0) {
                dot = -dot;
                for (int component = 0; component < 4; component++) {
                    target[component] = -target[component];
                }
            }
            if (dot > 0.9995) {
                final float[] result = new float[4];
                for (int component = 0; component < 4; component++) {
                    result[component] = (float) (left[component] * (1 - amount) + target[component] * amount);
                }
                return normalizeQuaternion(result);
            }
            final double angle = Math.acos(Math.max(-1, Math.min(1, dot)));
            final double sine = Math.sin(angle);
            final double leftWeight = Math.sin((1 - amount) * angle) / sine;
            final double rightWeight = Math.sin(amount * angle) / sine;
            final float[] result = new float[4];
            for (int component = 0; component < 4; component++) {
                result[component] = (float) (left[component] * leftWeight + target[component] * rightWeight);
            }
            return normalizeQuaternion(result);
        }

        private static float[] normalizeQuaternion(float[] value) {
            double lengthSquared = 0;
            for (float component : value) {
                lengthSquared += component * component;
            }
            if (lengthSquared <= 1e-20) {
                return new float[]{0, 0, 0, 1};
            }
            final float inverseLength = (float) (1.0 / Math.sqrt(lengthSquared));
            final float[] result = value.clone();
            for (int index = 0; index < result.length; index++) {
                result[index] *= inverseLength;
            }
            return result;
        }
    }

    private record Document(byte[] json, byte[] binaryChunk) {
    }
}
