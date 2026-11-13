package com.objcraft.geometry;

import java.util.ArrayList;
import java.util.List;

public final class TriangleSampler {
    private TriangleSampler() {
    }

    public static List<Vertex> sample(List<Triangle> triangles, int maximumSamples) {
        return sampleDetailed(triangles, maximumSamples).stream()
                .map(SurfaceSample::position)
                .toList();
    }

    public static List<SurfaceSample> sampleDetailed(List<Triangle> triangles, int maximumSamples) {
        if (maximumSamples <= 0) {
            throw new IllegalArgumentException("maximumSamples must be > 0");
        }

        final List<Integer> validTriangleIndices = new ArrayList<>();
        for (int index = 0; index < triangles.size(); index++) {
            if (triangles.get(index).area() > 0) {
                validTriangleIndices.add(index);
            }
        }
        if (validTriangleIndices.isEmpty()) {
            return List.of();
        }

        final double[] cumulativeAreas = new double[validTriangleIndices.size()];
        double totalArea = 0;
        for (int index = 0; index < validTriangleIndices.size(); index++) {
            totalArea += triangles.get(validTriangleIndices.get(index)).area();
            cumulativeAreas[index] = totalArea;
        }

        final int sampleCount = maximumSamples;
        final List<SurfaceSample> samples = new ArrayList<>(sampleCount);
        int areaTriangleIndex = 0;
        for (int index = 0; index < sampleCount; index++) {
            final double areaPosition = (index + 0.5) * totalArea / sampleCount;
            while (areaTriangleIndex < cumulativeAreas.length - 1
                    && areaPosition >= cumulativeAreas[areaTriangleIndex]) {
                areaTriangleIndex++;
            }
            final int triangleIndex = validTriangleIndices.get(areaTriangleIndex);
            samples.add(sample(
                    triangleIndex,
                    triangles.get(triangleIndex),
                    radicalInverse(index + 1, 2),
                    radicalInverse(index + 1, 3)));
        }
        return List.copyOf(samples);
    }

    private static SurfaceSample sample(int triangleIndex, Triangle triangle, double u, double v) {
        final double root = Math.sqrt(u);
        final double weightA = 1 - root;
        final double weightB = root * (1 - v);
        final double weightC = root * v;
        final Vertex position = new Vertex(
                weightA * triangle.a().x() + weightB * triangle.b().x() + weightC * triangle.c().x(),
                weightA * triangle.a().y() + weightB * triangle.b().y() + weightC * triangle.c().y(),
                weightA * triangle.a().z() + weightB * triangle.b().z() + weightC * triangle.c().z());
        return new SurfaceSample(triangleIndex, position, weightA, weightB, weightC);
    }

    private static double radicalInverse(int value, int base) {
        double result = 0;
        double factor = 1.0 / base;
        while (value > 0) {
            result += (value % base) * factor;
            value /= base;
            factor /= base;
        }
        return result;
    }
}
