package com.objcraft.importer;

import com.objcraft.geometry.Mesh;
import com.objcraft.geometry.Triangle;
import com.objcraft.geometry.Vertex;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ObjParser {
    public ObjImportResult parse(InputStream inputStream) throws IOException {
        return parse(inputStream, ObjImporterConfig.DEFAULT);
    }

    public ObjImportResult parse(InputStream inputStream, ObjImporterConfig config) throws IOException {
        config.validate();

        final List<String> warnings = new ArrayList<>();
        final List<Vertex> vertices = new ArrayList<>();
        final List<Vertex> normals = new ArrayList<>();
        final List<Vertex> uv = new ArrayList<>();
        final List<Triangle> triangles = new ArrayList<>();
        final List<ObjTriangle> objTriangles = new ArrayList<>();
        final List<String> materialLibraries = new ArrayList<>();
        String currentMaterial = "";

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                final String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }

                final String[] tokens = trimmed.split("\\s+");
                switch (tokens[0]) {
                    case "mtllib" -> {
                        for (int index = 1; index < tokens.length; index++) {
                            materialLibraries.add(tokens[index]);
                        }
                    }
                    case "usemtl" -> currentMaterial = trimmed.substring("usemtl".length()).trim();
                    case "v" -> {
                        if (tokens.length < 4) {
                            warnings.add("Skipping malformed vertex: " + trimmed);
                            continue;
                        }
                        if (vertices.size() >= config.maxVertices()) {
                            throw new IOException("OBJ exceeds configured maximum vertices");
                        }
                        vertices.add(new Vertex(parseDouble(tokens[1]), parseDouble(tokens[2]), parseDouble(tokens[3])));
                    }
                    case "vn" -> {
                        if (tokens.length < 4) {
                            warnings.add("Skipping malformed normal: " + trimmed);
                            continue;
                        }
                        normals.add(new Vertex(parseDouble(tokens[1]), parseDouble(tokens[2]), parseDouble(tokens[3])));
                    }
                    case "vt" -> {
                        if (tokens.length < 3) {
                            warnings.add("Skipping malformed uv: " + trimmed);
                            continue;
                        }
                        uv.add(new Vertex(parseDouble(tokens[1]), parseDouble(tokens[2]), 0.0));
                    }
                    case "f" -> {
                        final List<FaceVertex> faceIndices = new ArrayList<>();
                        for (int index = 1; index < tokens.length; index++) {
                            String token = tokens[index];
                            if (token.isBlank()) {
                                continue;
                            }
                            final String[] raw = token.split("/", -1);
                            final int vertexIndex = resolveIndex(raw[0], vertices.size());
                            if (vertexIndex < 0 || vertexIndex >= vertices.size()) {
                                throw new IOException("Face references an invalid vertex index: " + token);
                            }
                            TextureCoordinate textureCoordinate = null;
                            if (raw.length > 1 && !raw[1].isBlank()) {
                                final int uvIndex = resolveIndex(raw[1], uv.size());
                                if (uvIndex < 0 || uvIndex >= uv.size()) {
                                    throw new IOException("Face references an invalid texture coordinate index: " + token);
                                }
                                final Vertex coordinate = uv.get(uvIndex);
                                textureCoordinate = new TextureCoordinate(coordinate.x(), coordinate.y());
                            }
                            faceIndices.add(new FaceVertex(vertices.get(vertexIndex), textureCoordinate));
                        }
                        if (faceIndices.size() < 3) {
                            warnings.add("Skipping degenerate face: " + trimmed);
                            continue;
                        }
                        if (faceIndices.size() > config.maxPolygonSize()) {
                            throw new IOException("Polygon too large for importer config");
                        }
                        final int trianglesForFace = faceIndices.size() - 2;
                        if (triangles.size() + trianglesForFace > config.maxFaces()) {
                            throw new IOException("OBJ exceeds configured maximum faces");
                        }
                        if (faceIndices.size() == 3) {
                            addTriangle(faceIndices.get(0), faceIndices.get(1), faceIndices.get(2),
                                    currentMaterial, triangles, objTriangles);
                        } else {
                            for (int index = 1; index < faceIndices.size() - 1; index++) {
                                addTriangle(faceIndices.get(0), faceIndices.get(index), faceIndices.get(index + 1),
                                        currentMaterial, triangles, objTriangles);
                            }
                        }
                    }
                    default -> {
                        // Ignore unsupported OBJ commands; they are not critical for core parsing.
                    }
                }
            }
        }

        if (vertices.isEmpty()) {
            throw new IOException("OBJ file does not contain any vertices");
        }
        if (triangles.isEmpty()) {
            throw new IOException("OBJ file does not contain any triangulated faces");
        }
        if (vertices.size() > config.maxVertices()) {
            throw new IOException("OBJ exceeds configured maximum vertices");
        }
        if (triangles.size() > config.maxFaces()) {
            throw new IOException("OBJ exceeds configured maximum faces");
        }

        final Mesh mesh = Mesh.fromTriangles(vertices, triangles);
        return new ObjImportResult(mesh, warnings, objTriangles, materialLibraries);
    }

    private static void addTriangle(
            FaceVertex a,
            FaceVertex b,
            FaceVertex c,
            String materialName,
            List<Triangle> triangles,
            List<ObjTriangle> objTriangles
    ) {
        final Triangle triangle = new Triangle(a.vertex(), b.vertex(), c.vertex());
        triangles.add(triangle);
        objTriangles.add(new ObjTriangle(
                triangle,
                a.textureCoordinate(),
                b.textureCoordinate(),
                c.textureCoordinate(),
                materialName));
    }

    private static double parseDouble(String value) {
        return Double.parseDouble(value);
    }

    private static int resolveIndex(String rawIndex, int count) {
        if (rawIndex == null || rawIndex.isBlank()) {
            return -1;
        }
        final int value = Integer.parseInt(rawIndex);
        if (value > 0) {
            return value - 1;
        }
        if (value < 0) {
            return count + value;
        }
        return -1;
    }

    private record FaceVertex(Vertex vertex, TextureCoordinate textureCoordinate) {
    }
}
