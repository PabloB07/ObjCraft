package com.objcraft.plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class ModelAssetPathResolver {
    private ModelAssetPathResolver() {
    }

    static Path resolve(Path modelsDirectory, Path baseDirectory, String relativeName) throws IOException {
        if (relativeName == null || relativeName.isBlank() || relativeName.indexOf('\0') >= 0) {
            throw new IOException("Model asset path is empty or invalid.");
        }
        final String portableName = relativeName.replace('\\', '/');
        if (portableName.startsWith("/") || portableName.matches("^[A-Za-z]:.*")) {
            throw new IOException("Model asset path must be relative to the models folder: " + relativeName);
        }
        final Path realModelsDirectory = modelsDirectory.toRealPath();
        final Path realBaseDirectory = baseDirectory.toRealPath();
        if (!realBaseDirectory.startsWith(realModelsDirectory)) {
            throw new IOException("Model asset base directory resolves outside the models folder.");
        }
        final Path resolved = realBaseDirectory.resolve(portableName).normalize();
        if (!resolved.startsWith(realModelsDirectory)) {
            throw new IOException("Model asset path escapes the models folder: " + relativeName);
        }
        if (Files.exists(resolved)) {
            final Path realResolved = resolved.toRealPath();
            if (!realResolved.startsWith(realModelsDirectory)) {
                throw new IOException("Model asset resolves outside the models folder: " + relativeName);
            }
            return realResolved;
        }
        return resolved;
    }
}
