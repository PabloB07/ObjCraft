package com.objcraft.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModelAssetPathResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsAssetBaseResolvedThroughModelsDirectorySymlink() throws Exception {
        final Path actualModels = Files.createDirectory(temporaryDirectory.resolve("actual-models"));
        final Path modelsAlias = temporaryDirectory.resolve("models");
        Files.createSymbolicLink(modelsAlias, actualModels);
        final Path materialFile = Files.writeString(actualModels.resolve("Hamster-bl.mtl"), "newmtl fur\n");

        assertEquals(materialFile.toRealPath(),
                ModelAssetPathResolver.resolve(modelsAlias, actualModels, "Hamster-bl.mtl"));
    }

    @Test
    void normalizesWindowsAssetSeparatorsWithoutAllowingTraversal() throws Exception {
        final Path models = Files.createDirectory(temporaryDirectory.resolve("models"));
        final Path textures = Files.createDirectory(models.resolve("textures"));
        final Path texture = Files.writeString(textures.resolve("albedo.png"), "image");

        assertEquals(texture, ModelAssetPathResolver.resolve(models, models, "textures\\albedo.png"));
        assertThrows(IOException.class,
                () -> ModelAssetPathResolver.resolve(models, models, "..\\outside.mtl"));
    }

    @Test
    void rejectsAssetSymlinksThatResolveOutsideModelsDirectory() throws Exception {
        final Path models = Files.createDirectory(temporaryDirectory.resolve("models"));
        final Path outside = Files.writeString(temporaryDirectory.resolve("outside.mtl"), "newmtl fur\n");
        Files.createSymbolicLink(models.resolve("linked.mtl"), outside);

        assertThrows(IOException.class,
                () -> ModelAssetPathResolver.resolve(models, models, "linked.mtl"));
    }
}
