package com.objcraft.plugin;

import com.objcraft.api.Model;
import com.objcraft.api.ModelTransform;
import com.objcraft.geometry.Mesh;
import java.util.List;

public record SimpleModel(String id, String name, List<Mesh> meshes, ModelTransform transform) implements Model {
}
