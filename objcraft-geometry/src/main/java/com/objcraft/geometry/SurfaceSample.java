package com.objcraft.geometry;

public record SurfaceSample(
        int triangleIndex,
        Vertex position,
        double weightA,
        double weightB,
        double weightC
) {
}
