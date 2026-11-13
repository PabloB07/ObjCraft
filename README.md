# ObjCraft

ObjCraft imports Wavefront OBJ, glTF (`.gltf`), and GLB (`.glb`) models and displays them as 3D meshes in vanilla Minecraft 26.3 clients. glTF/GLB can carry geometry, UVs, and a base-color texture together. The server needs only this plugin: ObjCraft builds and distributes its shader resource pack. No second plugin or client mod is required.

## Use

1. Start the server once to create `plugins/ObjCraft/models/`.
2. Put the model in that directory. OBJ needs its `.mtl` and `map_Kd` texture; glTF/GLB supports embedded data or external assets using relative paths. Models must include UVs (`vt` in OBJ or `TEXCOORD_0` in glTF).
   For Blender, export as **glTF 2.0** in **GLB** format and include the Base Color texture; this usually produces a single file to copy.
3. Set `resource-pack.public-url` to an HTTP(S) URL reachable by players, and expose the configured `resource-pack.port` (default `9100`) from the Internet to the server.
4. Restart the server. Players receive the pack on join and must accept it.
5. Run `/objcraft list`, `/objcraft import <model>`, and `/objcraft spawn <model>`. Use the `.obj`, `.gltf`, or `.glb` filename without its extension.
6. `/objcraft reload` rereads model files, rebuilds the resource pack, and sends it to online players; it requires `objcraft.reload` (granted to operators by default).
7. `/objcraft edit <id> move <x> <y> <z>` moves an instance; `rotate <x> <y> <z>` applies rotation in degrees; `scale <value>` changes its size. `seat <x> <y> <z>` adjusts the sitting point. Requires `objcraft.edit`.
8. `/objcraft sit <id>` mounts the player on an instance; press Shift to dismount.
9. `/objcraft remove <instance-id>` removes a spawned model.

Imported animations are baked from glTF/GLB. A mesh rendered as an `ItemDisplay` does not run a vanilla mob's AI or skeleton, so Minecraft AI walk/attack animations cannot be applied automatically to an arbitrary mesh.

Example `plugins/ObjCraft/config.yml`:

```yaml
general:
  language: en_EN # or es_ES

resource-pack:
  host: 0.0.0.0
  port: 9100
  public-url: "http://YOUR_PUBLIC_SERVER_IP:9100/objcubed.zip"
  required: false
```

`/objcraft reload` reloads `general.language` and switches in-game messages without a restart. Existing `en_US` and `en_GB` values continue to select English. Other settings read during startup still require a server restart to take effect.

Replace the example address with the actual public hostname/IP. For HTTPS, use a TLS reverse proxy that forwards `/objcubed.zip` to the plugin's HTTP port. The endpoint serves only the generated ZIP and does not expose server files. ObjCraft also writes it to `plugins/ObjCraft/generated/objcubed.zip`.

The renderer remaps UVs into the atlas and supports repeat coordinates outside `[0, 1]` by tiling the texture, normalizes models to a maximum 4-block extent, and automatically splits large models into parts of up to 20,000 faces. It also caps each generated sprite at 1024×1024 pixels and downsamples source textures when needed to fit Minecraft's item atlas. By default, `importer.max-faces` accepts up to 500,000 faces per file; this can be configured in `plugins/ObjCraft/config.yml`, but higher limits consume more memory and spawn more `ItemDisplay` entities. Atlas dimensions and available memory remain practical limits. The default configuration also allows files up to 64 MiB and one million vertices/accessor entries. If upgrading an existing server, edit `importer.max-vertices` and `importer.max-faces` in its existing config; the plugin preserves existing configuration values. glTF/GLB supports triangle primitives, node transforms, `baseColorTexture`, and `normalTexture`, `metallicRoughnessTexture`, and `occlusionTexture` maps, all using `TEXCOORD_0`. It also bakes the first animation in the file (translation, rotation, and scale; LINEAR, STEP, or CUBICSPLINE interpolation) into up to 60 frames at 10 fps, and supports skinning with `JOINTS_0`, `WEIGHTS_0`, and inverse-bind matrices. `metallicFactor` and `roughnessFactor` are applied when they are uniform across all materials in the model; the combined pack does not support varying factors between materials. Morph targets, more than four influences per vertex, multiple distinct images per PBR map, non-white `baseColorFactor`, `normalTexture.scale`, and `occlusionTexture.strength` are not supported. The shader applies normal and occlusion maps plus an approximate metallic highlight; it is not a full physically based rendering pipeline. Images up to 256 megapixels are accepted and downsampled during import to at most 4 megapixels to control memory use and fit atlas limits. Files remain subject to `importer.max-file-size-bytes`. The shaders replace Minecraft core shaders and may conflict with Sodium or other packs that replace them.

ObjCraft validates the generated pack before publishing it and logs each client's load status. If the model appears with Minecraft's magenta/black missing-texture pattern, check the server console: `FAILED_RELOAD` means the client downloaded the pack but could not reload it. Inspect the client's `latest.log`, confirm it runs Minecraft 26.3, and check for other packs/shaders that replace Minecraft core shaders. `INVALID_URL` or `FAILED_DOWNLOAD` indicates that `resource-pack.public-url` is not reachable by the client.

## Compatibility and license

The included shader pack targets Minecraft 26.3; use a compatible matching server and client version. Building against Paper's API does not independently verify compatibility with other versions. The integration uses the MIT-licensed resource pack from [obj-cubed](https://github.com/JagerMeistars/obj-cubed), based on [objmc](https://github.com/Godlander/objmc); its license notice is included in the pack.

## Build and verify

```bash
cd /home/blancocl/ObjCraft
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew test :objcraft-plugin:shadowJar
```

The distributable plugin is `objcraft-plugin/build/libs/objcraft-plugin.jar`.
