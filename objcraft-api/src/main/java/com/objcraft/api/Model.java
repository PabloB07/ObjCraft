package com.objcraft.api;

import com.objcraft.geometry.Mesh;
import java.util.List;

public interface Model {
    String id();

    String name();

    List<Mesh> meshes();

    ModelTransform transform();
}
