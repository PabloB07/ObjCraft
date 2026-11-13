package com.objcraft.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class MeshTest {
    @Test
    void boundingBoxIsCalculatedFromVertices() {
        final Mesh mesh = Mesh.fromTriangles(List.of(
                new Vertex(0.0, 0.0, 0.0),
                new Vertex(2.0, 0.0, 0.0),
                new Vertex(0.0, 3.0, 0.0),
                new Vertex(0.0, 0.0, 4.0)
        ), List.of(
                new Triangle(new Vertex(0.0, 0.0, 0.0), new Vertex(2.0, 0.0, 0.0), new Vertex(0.0, 3.0, 0.0)),
                new Triangle(new Vertex(0.0, 0.0, 0.0), new Vertex(0.0, 3.0, 0.0), new Vertex(0.0, 0.0, 4.0))
        ));

        final BoundingBox box = mesh.boundingBox();
        assertNotNull(box);
        assertEquals(0.0, box.min().x());
        assertEquals(4.0, box.max().z());
    }

    @Test
    void scaleTransformsVertices() {
        final Mesh mesh = Mesh.fromTriangles(List.of(new Vertex(1.0, 1.0, 1.0)), List.of(new Triangle(new Vertex(1.0, 1.0, 1.0), new Vertex(1.0, 1.0, 1.0), new Vertex(1.0, 1.0, 1.0))));
        final Mesh scaled = mesh.scaled(2.0);
        assertEquals(2.0, scaled.vertices().getFirst().x());
    }
}
