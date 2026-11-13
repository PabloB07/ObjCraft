package com.objcraft.plugin;

import com.objcraft.api.Model;
import com.objcraft.api.ModelInstance;
import com.objcraft.api.ModelManager;
import com.objcraft.api.ModelTransform;
import com.objcraft.api.RenderMode;
import com.objcraft.importer.ObjImportResult;
import com.objcraft.importer.ObjImporterConfig;
import com.objcraft.importer.ObjParser;
import com.objcraft.importer.GltfImportResult;
import com.objcraft.importer.GltfParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Iterator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageReadParam;
import javax.imageio.stream.ImageInputStream;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

public final class FileModelManager implements ModelManager {
    private static final long DEFAULT_MAX_FILE_SIZE = 64L * 1024 * 1024;
    private static final int DEFAULT_MAX_VERTICES = 1_000_000;
    private static final int DEFAULT_MAX_FACES = 500_000;
    private static final int DEFAULT_MAX_POLYGON_SIZE = 64;
    private static final long MAX_SOURCE_TEXTURE_PIXELS = 268_435_456L;
    private static final long MAX_DECODED_TEXTURE_PIXELS = 4_194_304L;
    private static final Pattern MODEL_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");

    private final Plugin plugin;
    private final Path modelsDirectory;
    private final long maxFileSizeBytes;
    private final ObjImporterConfig importConfig;
    private final Map<String, Model> models = new ConcurrentHashMap<>();
    private final Map<String, ImportedModelAsset> assets = new ConcurrentHashMap<>();
    private ShaderMeshRendererManager renderer;

    public FileModelManager(Plugin plugin) {
        this.plugin = plugin;
        this.modelsDirectory = plugin.getDataFolder().toPath().resolve("models").normalize();
        this.maxFileSizeBytes = plugin.getConfig().getLong("importer.max-file-size-bytes", DEFAULT_MAX_FILE_SIZE);
        this.importConfig = new ObjImporterConfig(
                maxFileSizeBytes,
                plugin.getConfig().getInt("importer.max-vertices", DEFAULT_MAX_VERTICES),
                plugin.getConfig().getInt("importer.max-faces", DEFAULT_MAX_FACES),
                32,
                plugin.getConfig().getInt("importer.max-polygon-size", DEFAULT_MAX_POLYGON_SIZE),
                true);
        importConfig.validate();
    }

    void setRenderer(ShaderMeshRendererManager renderer) {
        this.renderer = renderer;
    }

    @Override
    public CompletableFuture<Model> load(String modelId) {
        if (modelId != null) {
            final Model cached = models.get(modelId);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }
        }
        try {
            return CompletableFuture.completedFuture(loadNow(modelId));
        } catch (IOException | IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private Model loadNow(String modelId) throws IOException {
        if (modelId == null || !MODEL_ID.matcher(modelId).matches()) {
            throw new IllegalArgumentException("Model names may contain only letters, numbers, _ and -.");
        }
        final Path modelFile = findModelFile(modelId);
        if (modelFile == null) {
            throw new IOException("No OBJ, glTF, or GLB file named " + modelId + " exists in the models folder.");
        }
        final long fileSize = Files.size(modelFile);
        if (fileSize > maxFileSizeBytes) {
            throw new IOException("Model file exceeds the configured import size limit.");
        }
        final String extension = extension(modelFile);
        final Model model;
        final List<com.objcraft.importer.ObjTriangle> triangles;
        final List<List<com.objcraft.importer.ObjTriangle>> animationFrames;
        final int animationDurationTicks;
        final float metallicFactor;
        final float roughnessFactor;
        final List<String> materialLibraries;
        final BufferedImage diffuseTexture;
        final BufferedImage normalTexture;
        final BufferedImage metallicRoughnessTexture;
        final BufferedImage occlusionTexture;
        if (extension.equals(".obj")) {
            final ObjImportResult result;
            try (InputStream input = Files.newInputStream(modelFile)) {
                result = new ObjParser().parse(input, importConfig);
            }
            model = new SimpleModel(modelId, modelId, List.of(result.mesh()), ModelTransform.identity());
            triangles = result.objTriangles();
            animationFrames = List.of();
            animationDurationTicks = 1;
            metallicFactor = 1.0f;
            roughnessFactor = 1.0f;
            materialLibraries = result.materialLibraries();
            diffuseTexture = loadDiffuseTexture(modelFile, materialLibraries);
            normalTexture = null;
            metallicRoughnessTexture = null;
            occlusionTexture = null;
        } else {
            final GltfImportResult result = new GltfParser().parse(modelFile, importConfig);
            model = new SimpleModel(modelId, modelId, List.of(result.mesh()), ModelTransform.identity());
            triangles = result.triangles();
            animationFrames = result.animationFrames();
            animationDurationTicks = result.animationDurationTicks();
            metallicFactor = result.metallicFactor();
            roughnessFactor = result.roughnessFactor();
            materialLibraries = List.of();
            diffuseTexture = decodeDiffuseTexture(result.diffuseTexture(), modelFile.getFileName().toString());
            normalTexture = decodeDiffuseTexture(result.normalTexture(), modelFile.getFileName().toString());
            metallicRoughnessTexture = decodeDiffuseTexture(
                    result.metallicRoughnessTexture(), modelFile.getFileName().toString());
            occlusionTexture = decodeDiffuseTexture(result.occlusionTexture(), modelFile.getFileName().toString());
        }
        final ImportedModelAsset asset = new ImportedModelAsset(
                model, triangles, animationFrames, animationDurationTicks, metallicFactor, roughnessFactor,
                materialLibraries, diffuseTexture,
                normalTexture, metallicRoughnessTexture, occlusionTexture);
        assets.put(modelId, asset);
        models.put(modelId, model);
        return model;
    }

    void preloadExisting() {
        for (String modelId : listModels()) {
            try {
                loadNow(modelId);
            } catch (IOException | IllegalArgumentException exception) {
                plugin.getLogger().warning("Could not preload model " + modelId + ": " + exception.getMessage());
            }
        }
    }

    synchronized Map<String, ImportedModelAsset> reloadAssets() {
        models.clear();
        assets.clear();
        preloadExisting();
        return assets();
    }

    Map<String, ImportedModelAsset> assets() {
        return Map.copyOf(assets);
    }

    ImportedModelAsset asset(String modelId) {
        return assets.get(modelId);
    }

    private BufferedImage loadDiffuseTexture(Path modelFile, List<String> materialLibraries) throws IOException {
        final java.util.Set<Path> textureFiles = new java.util.LinkedHashSet<>();
        for (String materialLibrary : materialLibraries) {
            final Path materialFile = resolveAsset(modelFile.getParent(), materialLibrary);
            if (!Files.isRegularFile(materialFile)) {
                throw new IOException("Referenced material library is missing: " + materialLibrary);
            }
            if (Files.size(materialFile) > maxFileSizeBytes) {
                throw new IOException("Referenced material library exceeds the configured import size limit: "
                        + materialLibrary);
            }
            final List<String> lines = Files.readAllLines(materialFile);
            for (String line : lines) {
                final String trimmed = line.trim();
                if (!trimmed.startsWith("map_Kd ")) {
                    continue;
                }
                final String[] tokens = trimmed.split("\\s+");
                final String textureName = tokens[tokens.length - 1];
                textureFiles.add(resolveAsset(materialFile.getParent(), textureName));
            }
        }
        if (textureFiles.size() > 1) {
            throw new IOException("ObjCraft currently supports one diffuse texture per model; the MTL files reference "
                    + textureFiles.size() + ".");
        }
        if (!textureFiles.isEmpty()) {
            final Path textureFile = textureFiles.iterator().next();
            if (!Files.isRegularFile(textureFile)) {
                throw new IOException("Referenced diffuse texture is missing: " + textureFile.getFileName());
            }
            if (Files.size(textureFile) > maxFileSizeBytes) {
                throw new IOException("Diffuse texture exceeds the configured import size limit: "
                        + textureFile.getFileName());
            }
            try (ImageInputStream input = ImageIO.createImageInputStream(textureFile.toFile())) {
                if (input == null) {
                    throw new IOException("Could not read diffuse texture image: " + textureFile.getFileName());
                }
                final Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) {
                    throw new IOException("Unsupported diffuse texture image: " + textureFile.getFileName());
                }
                final ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    final BufferedImage texture = readTexture(reader, textureFile.getFileName().toString());
                    if (texture == null) {
                        throw new IOException("Could not decode diffuse texture image: " + textureFile.getFileName());
                    }
                    return texture;
                } finally {
                    reader.dispose();
                }
            }
        }
        if (!materialLibraries.isEmpty()) {
            plugin.getLogger().warning("No map_Kd diffuse texture found for " + modelFile.getFileName());
        }
        return null;
    }

    private BufferedImage decodeDiffuseTexture(byte[] bytes, String modelName) throws IOException {
        if (bytes == null) {
            return null;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (input == null) {
                throw new IOException("Could not read base color texture from " + modelName + ".");
            }
            final Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported base color texture in " + modelName + ".");
            }
            final ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                final BufferedImage texture = readTexture(reader, modelName);
                if (texture == null) {
                    throw new IOException("Could not decode base color texture in " + modelName + ".");
                }
                return texture;
            } finally {
                reader.dispose();
            }
        }
    }

    static BufferedImage readTexture(ImageReader reader, String textureName) throws IOException {
        final int width = reader.getWidth(0);
        final int height = reader.getHeight(0);
        final long sourcePixels = (long) width * height;
        if (width <= 0 || height <= 0 || sourcePixels > MAX_SOURCE_TEXTURE_PIXELS) {
            throw new IOException("Texture " + textureName + " exceeds the 256-megapixel source limit.");
        }
        final int sampleSize = Math.max(1,
                (int) Math.ceil(Math.sqrt(sourcePixels / (double) MAX_DECODED_TEXTURE_PIXELS)));
        final ImageReadParam parameters = reader.getDefaultReadParam();
        if (sampleSize > 1) {
            parameters.setSourceSubsampling(sampleSize, sampleSize, 0, 0);
        }
        final BufferedImage texture = reader.read(0, parameters);
        if (texture != null && (long) texture.getWidth() * texture.getHeight() > MAX_DECODED_TEXTURE_PIXELS) {
            throw new IOException("Texture " + textureName + " could not be reduced to the safe 4-megapixel limit.");
        }
        return texture;
    }

    private Path findModelFile(String modelId) throws IOException {
        final Path realModelsDirectory = modelsDirectory.toRealPath();
        try (Stream<Path> files = Files.list(modelsDirectory)) {
            final List<Path> matches = files.filter(Files::isRegularFile)
                    .filter(path -> {
                        final String name = path.getFileName().toString();
                        final int dot = name.lastIndexOf('.');
                        return dot > 0 && name.substring(0, dot).equals(modelId)
                                && List.of(".obj", ".gltf", ".glb")
                                        .contains(name.substring(dot).toLowerCase(java.util.Locale.ROOT));
                    })
                    .sorted((left, right) -> Integer.compare(
                            extensionPriority(extension(left)), extensionPriority(extension(right))))
                    .toList();
            if (matches.isEmpty()) {
                return null;
            }
            final Path realModelFile = matches.getFirst().toRealPath();
            if (!realModelFile.startsWith(realModelsDirectory)) {
                throw new IOException("Model file resolves outside the models folder.");
            }
            return realModelFile;
        }
    }

    private static int extensionPriority(String extension) {
        return switch (extension) {
            case ".obj" -> 0;
            case ".gltf" -> 1;
            case ".glb" -> 2;
            default -> Integer.MAX_VALUE;
        };
    }

    private static String extension(Path path) {
        final String name = path.getFileName().toString();
        return name.substring(name.lastIndexOf('.')).toLowerCase(java.util.Locale.ROOT);
    }

    private Path resolveAsset(Path baseDirectory, String relativeName) throws IOException {
        return ModelAssetPathResolver.resolve(modelsDirectory, baseDirectory, relativeName);
    }

    @Override
    public CompletableFuture<ModelInstance> spawn(String modelId, Location location, RenderMode mode) {
        return load(modelId).thenCompose(model -> renderer.spawnModel(model, location, mode));
    }

    @Override
    public CompletableFuture<Void> remove(ModelInstance instance) {
        return renderer.remove(instance);
    }

    @Override
    public CompletableFuture<Model> getModel(String modelId) {
        return CompletableFuture.completedFuture(models.get(modelId));
    }

    @Override
    public List<String> listModels() {
        try (Stream<Path> files = Files.list(modelsDirectory)) {
            return files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .filter(name -> {
                        final String lower = name.toLowerCase(java.util.Locale.ROOT);
                        return lower.endsWith(".obj") || lower.endsWith(".gltf") || lower.endsWith(".glb");
                    })
                    .map(name -> name.substring(0, name.lastIndexOf('.')))
                    .filter(name -> MODEL_ID.matcher(name).matches())
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not list model files: " + exception.getMessage());
            return List.of();
        }
    }
}
