package com.objcraft.geometry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TriangleSamplerTest {
    @Test
    void samplesWithinTriangleSurfaceAndHonorsLimit() {
        final Triangle triangle = new Triangle(
                new Vertex(0, 0, 0),
                new Vertex(1, 0, 0),
                new Vertex(0, 1, 0));

        final List<Vertex> samples = TriangleSampler.sample(List.of(triangle), 128);

        assertEquals(128, samples.size());
        for (Vertex sample : samples) {
            assertTrue(sample.x() >= 0);
            assertTrue(sample.y() >= 0);
            assertTrue(sample.x() + sample.y() <= 1.0000001);
            assertEquals(0, sample.z(), 0.000001);
        }
    }

    @Test
    void ignoresDegenerateTriangles() {
        final Triangle degenerate = new Triangle(
                new Vertex(0, 0, 0),
                new Vertex(1, 1, 1),
                new Vertex(2, 2, 2));

        assertTrue(TriangleSampler.sample(List.of(degenerate), 32).isEmpty());
    }

    @Test
    void distributesSamplesByTriangleArea() {
        final Triangle small = new Triangle(
                new Vertex(0, 0, 0),
                new Vertex(1, 0, 0),
                new Vertex(0, 1, 0));
        final Triangle large = new Triangle(
                new Vertex(2, 0, 0),
                new Vertex(8, 0, 0),
                new Vertex(2, 1, 0));

        final List<Vertex> samples = TriangleSampler.sample(List.of(small, large), 700);
        final long smallTriangleSamples = samples.stream().filter(vertex -> vertex.x() < 2).count();

        assertEquals(100, smallTriangleSamples);
    }
}
