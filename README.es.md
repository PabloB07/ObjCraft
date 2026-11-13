# ObjCraft

ObjCraft importa modelos Wavefront OBJ, glTF (`.gltf`) y GLB (`.glb`) y los muestra como mallas 3D en clientes vanilla de Minecraft 26.3. glTF/GLB puede traer geometría, UV y textura base en el mismo archivo. El servidor solo necesita este plugin; ObjCraft genera y distribuye el resource pack con el shader incluido. No requiere instalar otro plugin ni un mod en el cliente.

## Uso

1. Inicia el servidor para crear `plugins/ObjCraft/models/`.
2. Copia el modelo a esa carpeta. En OBJ, incluye el `.mtl` y la textura `map_Kd`; en glTF/GLB se admiten buffers e imágenes embebidos o archivos externos con rutas relativas. El modelo debe incluir UV (`vt` en OBJ o `TEXCOORD_0` en glTF).
   Para subirlo desde Blender, expórtalo como **glTF 2.0** en formato **GLB** e incluye la textura Base Color; así normalmente basta copiar un solo `.glb`.
3. Configura `resource-pack.public-url` como una URL HTTP(S) que los jugadores puedan abrir y permite el puerto configurado en `resource-pack.port` (por defecto `9100`) desde Internet hasta el servidor.
4. Reinicia el servidor. Al entrar, los jugadores recibirán el pack; deben aceptarlo.
5. Usa `/objcraft list`, `/objcraft import <modelo>` y `/objcraft spawn <modelo>`. El nombre es el archivo `.obj`, `.gltf` o `.glb` sin extensión.
6. `/objcraft reload` vuelve a leer los modelos y reconstruye/reenvía el resource pack; requiere permiso `objcraft.reload` (por defecto, operadores).
7. `/objcraft edit <id> move <x> <y> <z>` desplaza una instancia; `rotate <x> <y> <z>` aplica rotación en grados; `scale <valor>` cambia su tamaño. `seat <x> <y> <z>` ajusta el punto para sentarse. Requiere `objcraft.edit`.
8. `/objcraft sit <id>` monta al jugador en una instancia; usa Shift para bajarte.
9. `/objcraft remove <id-instancia>` elimina un modelo mostrado.

Las animaciones importadas se hornean desde glTF/GLB. Una malla renderizada como `ItemDisplay` no ejecuta la IA ni el esqueleto de un mob vanilla, así que las animaciones de caminar/atacar de la IA de Minecraft no se pueden aplicar automáticamente a una malla arbitraria.

Ejemplo mínimo en `plugins/ObjCraft/config.yml`:

```yaml
general:
  language: es_ES # es_ES o en_EN

resource-pack:
  host: 0.0.0.0
  port: 9100
  public-url: "http://IP_PUBLICA_DEL_SERVIDOR:9100/objcubed.zip"
  required: false
```

`/objcraft reload` vuelve a cargar `general.language` y cambia los mensajes del juego sin reiniciar. Los valores antiguos `en_US` y `en_GB` siguen seleccionando inglés. Los demás ajustes que se leen al iniciar requieren reiniciar el servidor para aplicarse.

Reemplaza `IP_PUBLICA_DEL_SERVIDOR` por el nombre/IP público real. Si usas HTTPS, configura un proxy TLS que reenvíe `/objcubed.zip` al puerto HTTP del plugin. El endpoint solo sirve el ZIP generado; no expone archivos del servidor. El pack se guarda también en `plugins/ObjCraft/generated/objcubed.zip`.

El renderer remapea las UV al atlas y admite coordenadas repetidas fuera de `[0, 1]` al repetir la textura, normaliza el modelo a un máximo de 4 bloques y divide automáticamente modelos grandes en partes de hasta 20.000 caras. También limita cada sprite generado a 1024×1024 píxeles y reduce las texturas fuente cuando hace falta para que quepan en el atlas de ítems de Minecraft. Por defecto, `importer.max-faces` acepta hasta 500.000 caras por archivo; también puedes configurarlo en `plugins/ObjCraft/config.yml`, aunque límites mayores consumen más memoria y generan más entidades `ItemDisplay`. La configuración inicial permite archivos de hasta 64 MiB y un millón de vértices/accesores. Si ya existe un `config.yml` de una versión anterior, edita allí `importer.max-vertices` y `importer.max-faces`: el plugin conserva la configuración existente al actualizarse. Las dimensiones de atlas y la memoria disponible siguen siendo límites prácticos. glTF/GLB admite primitivas triangulares, transforms de nodos, `baseColorTexture`, y mapas `normalTexture`, `metallicRoughnessTexture` y `occlusionTexture`, todos con `TEXCOORD_0`. También hornea la primera animación del archivo (translation, rotation y scale; interpolación LINEAR, STEP o CUBICSPLINE) en hasta 60 cuadros a 10 cuadros/s, y admite skinning con `JOINTS_0`, `WEIGHTS_0` e inverse-bind matrices. Los factores `metallicFactor` y `roughnessFactor` se aplican si son iguales para todos los materiales del modelo; el pack combinado no admite materiales con factores diferentes entre sí. Morph targets, más de cuatro influencias por vértice, varias imágenes distintas por mapa PBR, `baseColorFactor` no blanco, `normalTexture.scale` y `occlusionTexture.strength` no están soportados. El shader aplica mapas de normales, oclusión ambiental y un reflejo metálico aproximado; no es un pipeline PBR físicamente completo. Las imágenes de hasta 256 megapíxeles se aceptan y se reducen durante la importación a un máximo de 4 megapíxeles para controlar la memoria y ajustarse a los atlas. Los archivos siguen sujetos a `importer.max-file-size-bytes`. Los shaders reemplazan los shaders core de Minecraft y pueden entrar en conflicto con Sodium y otros packs que también los reemplacen.

El pack se valida antes de publicarse; el servidor registra el estado de carga de cada cliente. Si ves la textura magenta/negra, revisa la consola del servidor: `FAILED_RELOAD` significa que el cliente descargó el pack pero no pudo cargarlo. En ese caso revisa `latest.log` del cliente y confirma que usa Minecraft 26.3 y que no hay otro pack/shader que reemplace los shaders de Minecraft. `INVALID_URL` o `FAILED_DOWNLOAD` indican un problema de acceso a `resource-pack.public-url`.

## Compatibilidad y licencia

El pack de shaders incluido está dirigido a Minecraft 26.3; tanto servidor como clientes deben usar esa versión compatible. La API de compilación de Paper no demuestra por sí sola compatibilidad con otras versiones. La integración usa el resource pack MIT de [obj-cubed](https://github.com/JagerMeistars/obj-cubed), basado en [objmc](https://github.com/Godlander/objmc); el aviso de licencia se incluye dentro del pack.

## Compilar y verificar

```bash
cd /home/blancocl/ObjCraft
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew test :objcraft-plugin:shadowJar
```

El plugin distribuible queda en `objcraft-plugin/build/libs/objcraft-plugin.jar`.
