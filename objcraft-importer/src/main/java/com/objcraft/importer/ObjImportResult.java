package com.objcraft.importer;

import com.objcraft.geometry.Mesh;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ObjImportResult {
    private final Mesh mesh;
    private final List<String> warnings;
    private final List<ObjTriangle> objTriangles;
    private final List<String> materialLibraries;

    public ObjImportResult(Mesh mesh, List<String> warnings) {
        this(mesh, warnings, List.of(), List.of());
    }

    public ObjImportResult(
            Mesh mesh,
            List<String> warnings,
            List<ObjTriangle> objTriangles,
            List<String> materialLibraries
    ) {
        this.mesh = mesh;
        this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
        this.objTriangles = List.copyOf(objTriangles);
        this.materialLibraries = List.copyOf(materialLibraries);
    }

    public Mesh mesh() {
        return mesh;
    }

    public List<String> warnings() {
        return warnings;
    }

    public List<ObjTriangle> objTriangles() {
        return objTriangles;
    }

    public List<String> materialLibraries() {
        return materialLibraries;
    }
}
