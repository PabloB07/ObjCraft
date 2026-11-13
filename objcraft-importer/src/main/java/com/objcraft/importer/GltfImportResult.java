package com.objcraft.importer;

import com.objcraft.geometry.Mesh;
import java.util.List;

public record GltfImportResult(
        Mesh mesh,
        List<ObjTriangle> triangles,
        List<List<ObjTriangle>> animationFrames,
        int animationDurationTicks,
        float metallicFactor,
        float roughnessFactor,
        byte[] diffuseTexture,
        byte[] normalTexture,
        byte[] metallicRoughnessTexture,
        byte[] occlusionTexture
) {
    public GltfImportResult {
        triangles = List.copyOf(triangles);
        animationFrames = animationFrames.stream().map(List::copyOf).toList();
        if (!Float.isFinite(metallicFactor) || metallicFactor < 0 || metallicFactor > 1
                || !Float.isFinite(roughnessFactor) || roughnessFactor < 0 || roughnessFactor > 1) {
            throw new IllegalArgumentException("glTF metallic and roughness factors must be between 0 and 1.");
        }
        diffuseTexture = diffuseTexture == null ? null : diffuseTexture.clone();
        normalTexture = normalTexture == null ? null : normalTexture.clone();
        metallicRoughnessTexture = metallicRoughnessTexture == null ? null : metallicRoughnessTexture.clone();
        occlusionTexture = occlusionTexture == null ? null : occlusionTexture.clone();
    }

    @Override
    public byte[] diffuseTexture() {
        return diffuseTexture == null ? null : diffuseTexture.clone();
    }

    @Override
    public byte[] normalTexture() {
        return normalTexture == null ? null : normalTexture.clone();
    }

    @Override
    public byte[] metallicRoughnessTexture() {
        return metallicRoughnessTexture == null ? null : metallicRoughnessTexture.clone();
    }

    @Override
    public byte[] occlusionTexture() {
        return occlusionTexture == null ? null : occlusionTexture.clone();
    }
}
