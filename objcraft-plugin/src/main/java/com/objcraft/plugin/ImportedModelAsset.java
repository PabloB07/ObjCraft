package com.objcraft.plugin;

import com.objcraft.api.Model;
import com.objcraft.importer.ObjTriangle;
import java.awt.image.BufferedImage;
import java.util.List;

record ImportedModelAsset(
        Model model,
        List<ObjTriangle> triangles,
        List<List<ObjTriangle>> animationFrames,
        int animationDurationTicks,
        float metallicFactor,
        float roughnessFactor,
        List<String> materialLibraries,
        BufferedImage diffuseTexture,
        BufferedImage normalTexture,
        BufferedImage metallicRoughnessTexture,
        BufferedImage occlusionTexture
) {
    ImportedModelAsset {
        triangles = List.copyOf(triangles);
        animationFrames = animationFrames.stream().map(List::copyOf).toList();
        materialLibraries = List.copyOf(materialLibraries);
    }

    List<List<ObjTriangle>> frames() {
        return animationFrames.isEmpty() ? List.of(triangles) : animationFrames;
    }
}
