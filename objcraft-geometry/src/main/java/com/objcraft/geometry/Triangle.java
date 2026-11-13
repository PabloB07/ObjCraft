package com.objcraft.geometry;

public record Triangle(Vertex a, Vertex b, Vertex c) {
    public double area() {
        final double ux = b.x() - a.x();
        final double uy = b.y() - a.y();
        final double uz = b.z() - a.z();
        final double vx = c.x() - a.x();
        final double vy = c.y() - a.y();
        final double vz = c.z() - a.z();
        final double crossX = uy * vz - uz * vy;
        final double crossY = uz * vx - ux * vz;
        final double crossZ = ux * vy - uy * vx;
        return 0.5 * Math.sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ);
    }
}
