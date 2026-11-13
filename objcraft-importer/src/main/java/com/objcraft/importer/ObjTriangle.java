package com.objcraft.importer;

import com.objcraft.geometry.Triangle;

public record ObjTriangle(
        Triangle triangle,
        TextureCoordinate uvA,
        TextureCoordinate uvB,
        TextureCoordinate uvC,
        String materialName
) {
    public boolean hasTextureCoordinates() {
        return uvA != null && uvB != null && uvC != null;
    }
}
