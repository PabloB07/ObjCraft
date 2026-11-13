package com.objcraft.importer;

public record ObjImporterConfig(
        long maxFileSizeBytes,
        int maxVertices,
        int maxFaces,
        int maxMaterials,
        int maxPolygonSize,
        boolean strictValidation
) {
    public static final ObjImporterConfig DEFAULT = new ObjImporterConfig(32L * 1024 * 1024, 500_000, 500_000, 256, 32, true);

    public void validate() {
        if (maxFileSizeBytes <= 0) {
            throw new IllegalArgumentException("maxFileSizeBytes must be > 0");
        }
        if (maxVertices <= 0) {
            throw new IllegalArgumentException("maxVertices must be > 0");
        }
        if (maxFaces <= 0) {
            throw new IllegalArgumentException("maxFaces must be > 0");
        }
        if (maxMaterials <= 0) {
            throw new IllegalArgumentException("maxMaterials must be > 0");
        }
        if (maxPolygonSize <= 0) {
            throw new IllegalArgumentException("maxPolygonSize must be > 0");
        }
    }
}
