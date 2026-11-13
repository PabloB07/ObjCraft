package com.objcraft.geometry;

public record Vector3(double x, double y, double z) {
    public static Vector3 zero() {
        return new Vector3(0.0, 0.0, 0.0);
    }

    public Vector3 add(Vector3 other) {
        return new Vector3(x + other.x, y + other.y, z + other.z);
    }

    public Vector3 subtract(Vector3 other) {
        return new Vector3(x - other.x, y - other.y, z - other.z);
    }

    public Vector3 scale(double factor) {
        return new Vector3(x * factor, y * factor, z * factor);
    }
}
