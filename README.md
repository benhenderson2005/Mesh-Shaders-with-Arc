# Mesh Shaders with Arc

Mesh Shaders with Arc is an experimental, clean-room Sodium terrain backend for
Minecraft Java Edition 26.2. It targets Intel Arc first and replaces accepted
Sodium terrain draws with the cross-vendor
[`VK_EXT_mesh_shader`](https://docs.vulkan.org/refpages/latest/refpages/source/VK_EXT_mesh_shader.html)
path in Minecraft's built-in experimental Vulkan renderer.

Version 0.3.1 requires Sodium `0.9.1+mc26.2`. Sodium still builds chunk meshes,
maintains render lists and sorted translucent indices, uploads its normal terrain
arenas, and provides the safe fallback renderer. This mod captures Sodium's
accepted build output, maintains a second Vulkan-oriented geometry representation,
and cancels a Sodium terrain pass only after the custom backend has accepted it.
That is broadly the same renderer-extension relationship Nvidium has with Sodium,
but this is an original implementation and contains no Nvidium code or shaders.

## Renderer architecture

- **Sodium bridge:** accepted `BuilderTaskOutput` meshes are captured immediately
  before Sodium uploads and destroys their temporary buffers. Allocations are
  keyed by immutable section coordinates and terrain layer, and are released or
  retained with Sodium's section lifecycle.
- **Packed geometry:** Sodium's 20-byte `CompactChunkVertex` input is re-encoded
  once per accepted upload into a 16-byte Arc vertex containing fixed-point
  position, color, light, and texture coordinates. If packing is disabled or an
  allocation is unavailable, the mesh shader can read Sodium's 20-byte arena
  directly.
- **Geometry arenas:** packed allocations use a persistent arena. Vulkan sparse
  buffers provide a stable virtual range with lazily committed device-local pages
  when supported; otherwise the manager uses dense arena pages. Sparse binds are
  ordered before uploads with a timeline semaphore.
- **Opaque and cutout terrain:** work is divided into mesh workgroups of at most
  32 quads. The optional task shader tests 32 candidate workgroups against a
  double-buffered hierarchical depth buffer (HZB) from the previous frame and
  emits only the visible work. A direct mesh-shader path is always available.
- **Conservative HZB history:** the depth pyramid uses the exact previous camera
  transform. History is rejected after resize, world change, near-plane ambiguity,
  or a terrain-geometry epoch change so stale depth cannot hide changed terrain.
- **Custom translucency:** a separate mesh pipeline consumes Sodium's current
  CPU-sorted index data and preserves its quad order. It is mesh-driven, but it
  is not GPU-sorted or order-independent transparency.
- **Vulkan shaders:** shader sources are GLSL because shaderc is used as the
  front end; `validateMeshShaders` compiles them to SPIR-V, which is the binary
  consumed by Vulkan.

Minecraft continues to own render targets, depth images, command submission,
texture atlases, lightmaps, and Sodium's normal chunk pipeline. Small,
version-specific mixins expose the native Vulkan command buffer, enable optional
device features, capture Sodium uploads, follow Sodium section lifetime, and
intercept `DefaultChunkRenderer` draws.

## Arc Settings page

Open Sodium's Video Settings and select **Arc Settings**. The page is registered
through Sodium's public Config API. Except for **Disable Arc Renderer**, changes
are saved to `config/mesh-shaders-with-arc.json`.

- **Disable Arc Renderer** (default: off): switches terrain back to Sodium's
  normal renderer. It is deliberately session-only and resets when Minecraft
  restarts. Applying it reloads renderer resources.
- **Temporal HZB Culling** (default: on): enables task-shader occlusion against
  the previous frame's HZB. Disable it to use direct mesh dispatches.
- **Packed Geometry** (default: on): enables the 20-byte-to-16-byte Sodium vertex
  conversion. Disabling it keeps the custom mesh path but reads Sodium's compact
  vertices.
- **Sparse Residency** (default: on): uses sparse virtual-buffer pages for packed
  geometry when the device supports them. Unsupported or failed sparse setup
  falls back to dense pages.
- **Custom Translucency** (default: on): routes Sodium's currently listed
  translucent terrain through the sorted translucent mesh pipeline. Disabling
  it leaves that pass entirely to Sodium.
- **Region Keep Distance** (default: 32): controls retained, already-built
  terrain. At `32`, allocations follow Sodium/render distance and are released
  when Sodium removes the section. Values `33` through `256` retain previously
  visited packed `SOLID`/`CUTOUT` sections and let the Arc renderer draw those
  inside the selected per-axis horizontal section distance (a square centered on
  the camera) when they also pass frustum and face culling. The allocator uses
  four chunks of eviction hysteresis beyond the exact draw distance.
  `257` is displayed as **Keep All** and disables distance eviction; frustum and
  face culling still apply. This setting does not load chunks from the server,
  generate terrain, or retain the sorted index buffers needed to draw detached
  translucent sections. Translucency therefore remains limited to Sodium's
  current render list.
- **Automatic Memory Limit** (default: on): uses one quarter of the largest
  device-local Vulkan memory heap, normalized to the supported slider range, as
  the packed-arena budget. If heap discovery fails, the saved manual value is
  used.
- **Max GPU Memory** (saved default: 2048 MiB): the manual packed-arena budget,
  from 512 MiB to 32768 MiB in 512 MiB steps. It is editable only when automatic
  memory is off. Reaching the budget can prevent new packed allocations; the
  renderer can continue from Sodium's compact geometry. Applying a memory change
  rebuilds renderer resources.

The feature toggles and memory controls that affect Vulkan resources request a
Sodium renderer reload. Keep Distance is a live retention policy.

### Important VRAM caveat

**Max GPU Memory is not a total-VRAM limit.** It limits this mod's committed
packed-geometry arena (and may be further constrained by Vulkan alignment,
virtual range, or `maxStorageBufferRange`). Sodium's ordinary 20-byte terrain
arenas remain uploaded in parallel so the mod can fall back without terrain
holes. HZB images, task/chunk rings, textures, framebuffers, entities, and other
Minecraft resources are also outside this budget. Total VRAM use can therefore
exceed the selected value; with packing active, resident terrain can exist
simultaneously in Sodium's 20-byte form and this mod's 16-byte form.

## Requirements

- Minecraft Java Edition `26.2`
- Fabric Loader `0.19.3` or newer
- Fabric API `0.156.0+26.2` or a compatible 26.2 build
- Sodium `0.9.1+mc26.2` exactly (the bridge targets its internal renderer API)
- Java 25
- Minecraft's experimental Vulkan graphics backend
- a Vulkan driver exposing `VK_EXT_mesh_shader` with `meshShader = true`

`taskShader`, `sparseBinding`, and `sparseResidencyBuffer` are optional. Missing
optional features select direct mesh dispatch or dense arena pages automatically.
The mod checks Vulkan capabilities rather than a PCI vendor ID, so a conforming
non-Intel GPU may work, but Intel Arc is the primary target.

## Build, run, and test

The first Gradle invocation resolves Fabric and Sodium from their configured
Maven repositories.

Compile the client sources quickly:

```powershell
.\gradlew.bat compileClientJava
```

Build the remapped mod JAR and sources JAR under `build/libs`:

```powershell
.\gradlew.bat build
```

Run verification, including compilation of every mesh, task, compute, and
fragment shader variant to Vulkan 1.2 SPIR-V:

```powershell
.\gradlew.bat check
```

The shader validation task can also be run directly:

```powershell
.\gradlew.bat validateMeshShaders
```

Start the development client with Vulkan selected:

```powershell
.\gradlew.bat runClient --args="--graphicsBackend vulkan"
```

Add `--vulkanValidation` when the Vulkan SDK validation layer is installed.

Run the automated Sodium/Vulkan terrain smoke test:

```powershell
$env:JAVA_TOOL_OPTIONS = '-Dfabric.client.gametest=true -Dfabric.client.gametest.modid=mesh-shaders-with-arc'
.\gradlew.bat runClient --args="--graphicsBackend vulkan --renderDebugLabels"
Remove-Item Env:JAVA_TOOL_OPTIONS
```

The test creates a disposable single-player world and nested red/blue
stained-glass fixture. It checks Sodium-driven opaque and translucent passes,
packed work submission, task/HZB history when available, and resident sparse
pages when enabled, then writes `run/screenshots/0000_arc-mesh-terrain-smoke.png`.
With `-DmeshShadersWithArc.enabled=false`, the same test performs a short Sodium
fallback smoke run instead.

To exercise Region Keep Distance itself, add
`-DmeshShadersWithArc.keepDistance=64 -DmeshShadersWithArc.testKeepDistance=true`.
The test then teleports beyond Sodium's five-chunk test view distance and requires
retained opaque workgroups from the original area to remain in the Arc draw plan.

## Advanced JVM overrides

The Arc Settings page is the normal configuration path. These system properties
are useful for debugging, repeatable test runs, and devices that cannot open the
settings page. A supplied property takes priority over the saved value and makes
the corresponding control read-only for that launch.

```text
-DmeshShadersWithArc.enabled=false
-DmeshShadersWithArc.keepDistance=128
-DmeshShadersWithArc.automaticMemory=false
-DmeshShadersWithArc.sparsePhysicalMiB=2048
-DmeshShadersWithArc.packedGeometry=false
-DmeshShadersWithArc.sparseResidency=false
-DmeshShadersWithArc.taskCulling=false
-DmeshShadersWithArc.occlusionCulling=false
-DmeshShadersWithArc.customTranslucency=false
-DmeshShadersWithArc.sparseVirtualMiB=4096
```

- `enabled` forces the entire custom backend on or off. Supplying either value
  overrides and locks the session-only Disable control.
- `keepDistance` is normalized to `32..257`; `257` means Keep All.
- `automaticMemory` selects automatic heap-based budgeting when true.
- `sparsePhysicalMiB` is the legacy property name for the Max GPU Memory budget.
  Supplying it also takes precedence over automatic budgeting. Values normalize
  to the GUI's 512 MiB steps and `512..32768` range.
- `packedGeometry`, `sparseResidency`, and `customTranslucency` override the
  matching page toggles.
- `taskCulling` and `occlusionCulling` are separate diagnostic gates for the
  Temporal HZB path; setting either false selects direct mesh dispatch.
- `sparseVirtualMiB` sets the requested sparse virtual address range. Its default
  is at least 1024 MiB and at least the effective physical budget; the final size
  is page-aligned and clamped to the device's `maxStorageBufferRange`. This is
  advanced address-space sizing, not additional physical memory permission.

## Fallback behavior and limitations

- An incompatible backend/device, a disabled Arc renderer, or a failed custom
  preflight leaves the pass with Sodium. A fatal custom renderer error disables
  the Arc path so later passes use Sodium.
- Missing packed geometry does not create a terrain hole: the mesh shader can
  consume Sodium's compact arena, or Sodium can render the pass if custom setup
  cannot proceed.
- Task/HZB failure keeps direct mesh rendering available. Sparse failure keeps
  dense packed arenas available. Disabling Custom Translucency keeps Sodium's
  translucent renderer available.
- Kept opaque/cutout geometry represents previously received and compiled
  sections only. Keep Distance does not change simulation distance, server view
  distance, chunk generation, or Sodium's compilation schedule. Keep All can
  exhaust the selected packed-arena budget on long sessions.
- HZB culling covers opaque/cutout terrain and previous terrain depth, not later
  entities or block entities. Streaming geometry invalidates history
  conservatively, temporarily reducing culling efficiency.
- Custom translucency retains Sodium's CPU sorting and current-list lifetime.
  Detached translucent geometry is not drawn because its sorted index buffer is
  owned by Sodium and is not retained.
- Shader packs and renderer mods that replace Sodium's terrain pipeline are not
  compatibility targets. Sodium internals and Minecraft Vulkan internals are
  version-specific; updating either dependency requires revalidating the mixins.

## License

This project is available under the GNU Lesser General Public License v3.0 only
(`LGPL-3.0-only`). Nvidium is a separate project; no Nvidium code or shader
source is included here.
