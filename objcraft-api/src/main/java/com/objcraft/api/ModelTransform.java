package com.objcraft.api;

public record ModelTransform(double scale, double x, double y, double z, float yaw, float pitch, float roll) {
    public static ModelTransform identity() {
        return new ModelTransform(1.0, 0.0, 0.0, 0.0, 0.0f, 0.0f, 0.0f);
    }
}
