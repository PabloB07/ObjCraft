package com.objcraft.geometry;

public record Vertex(double x, double y, double z) {
    public Vector3 toVector() {
        return new Vector3(x, y, z);
    }
}
