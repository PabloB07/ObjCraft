package com.objcraft.geometry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Mesh {
    private final List<Vertex> vertices;
    private final List<Triangle> triangles;

    public Mesh(List<Vertex> vertices, List<Triangle> triangles) {
        this.vertices = List.copyOf(vertices);
        this.triangles = List.copyOf(triangles);
    }

    public static Mesh fromTriangles(List<Vertex> vertices, List<Triangle> triangles) {
        return new Mesh(vertices, triangles);
    }

    public List<Vertex> vertices() {
        return Collections.unmodifiableList(vertices);
    }

    public List<Triangle> triangles() {
        return Collections.unmodifiableList(triangles);
    }

    public BoundingBox boundingBox() {
        return BoundingBox.fromVertices(vertices);
    }

    public Mesh translated(Vector3 translation) {
        final List<Vertex> transformedVertices = new ArrayList<>();
        for (Vertex vertex : vertices) {
            transformedVertices.add(new Vertex(vertex.x() + translation.x(), vertex.y() + translation.y(), vertex.z() + translation.z()));
        }
        return new Mesh(transformedVertices, triangles);
    }

    public Mesh scaled(double factor) {
        final List<Vertex> transformedVertices = new ArrayList<>();
        for (Vertex vertex : vertices) {
            transformedVertices.add(new Vertex(vertex.x() * factor, vertex.y() * factor, vertex.z() * factor));
        }
        return new Mesh(transformedVertices, triangles);
    }

    public Mesh rotatedX(double angleRadians) {
        final double c = Math.cos(angleRadians);
        final double s = Math.sin(angleRadians);
        final List<Vertex> transformedVertices = new ArrayList<>();
        for (Vertex vertex : vertices) {
            final double y = vertex.y() * c - vertex.z() * s;
            final double z = vertex.y() * s + vertex.z() * c;
            transformedVertices.add(new Vertex(vertex.x(), y, z));
        }
        return new Mesh(transformedVertices, triangles);
    }

    public Mesh rotatedY(double angleRadians) {
        final double c = Math.cos(angleRadians);
        final double s = Math.sin(angleRadians);
        final List<Vertex> transformedVertices = new ArrayList<>();
        for (Vertex vertex : vertices) {
            final double x = vertex.x() * c + vertex.z() * s;
            final double z = -vertex.x() * s + vertex.z() * c;
            transformedVertices.add(new Vertex(x, vertex.y(), z));
        }
        return new Mesh(transformedVertices, triangles);
    }

    public Mesh rotatedZ(double angleRadians) {
        final double c = Math.cos(angleRadians);
        final double s = Math.sin(angleRadians);
        final List<Vertex> transformedVertices = new ArrayList<>();
        for (Vertex vertex : vertices) {
            final double x = vertex.x() * c - vertex.y() * s;
            final double y = vertex.x() * s + vertex.y() * c;
            transformedVertices.add(new Vertex(x, y, vertex.z()));
        }
        return new Mesh(transformedVertices, triangles);
    }
}
