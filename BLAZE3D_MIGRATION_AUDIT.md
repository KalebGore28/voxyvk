# Voxy (voxyvk) × Blaze3D — dependency audit and migration plan

| | |
|---|---|
| Audited | branch `vulkan-audit-fixes` @ `ee85c6f8`, 2026-09-25 |
| Targets | Minecraft 26.2, Sodium `mc26.2-0.9.2`, Fabric Loader 0.19.3, LWJGL 3.4.1, Java 25 |
| Ground truth | Blaze3D decompiled from the 26.2 jar via `./gradlew genSources` (see [§1](#1-how-this-was-checked-and-how-to-re-check)) |
| Supersedes | `BLAZE3D_GAP_ANALYSIS.md` (deleted; written before the VK backend existed, several of its API claims are wrong for 26.2 — see [§2.4](#24-corrections-to-the-old-blaze3d_gap_analysismd)) |

Goal of the plan: make future Minecraft ports cheaper by leaning on Blaze3D wherever it can do the work, and fencing off everything it can't.

---

## 0. TL;DR

1. **The Vulkan path is about 90% self-implemented raw Vulkan.** Blaze3D supplies the device, queue and handles, the frame's command buffer and its submission, MC's render-target views (colour, depth, lightmap, block atlas), and one semaphore-signal hook. Voxy does everything else itself: memory, buffers, images, samplers, GLSL→SPIR-V, pipelines, descriptors, barriers and layouts, uploads, readbacks, frame retirement and deferred destruction. That is 35 files and about 5,050 lines, with about 122 raw Vulkan call sites (63 distinct `vk*` functions), against about 25 Blaze3D touchpoints in 11 files.
2. **"Fully on Blaze3D" isn't reachable on 26.2.** Blaze3D has no compute shaders, no storage buffers or images, no stencil, no indirect-count draws, no push constants and no array textures, and its Vulkan pipelines hardcode a `D32_SFLOAT` depth attachment. Of Voxy's 19 VK pipelines, 13 are compute and 0 of those can move. Of the 6 graphics pipelines, 2 can move once their inputs change: chunk bounds after small changes (step 4.1), composite after 5.1/5.2. A third (depth setup) could move if stencil masking is removed.
3. **The porting risk isn't the raw Vulkan code, which uses a stable API.** It's the coupling to Blaze3D's **Vulkan-backend internals**: one private-field accessor, one inject into a private method, the `com.mojang.blaze3d.vulkan.*` classes, and about 11 unwritten behavioural contracts (for example, MC keeps every image in `GENERAL` layout and ends every operation with a full barrier). MC 26.2 labels this backend *"Prefer Vulkan (Experimental)"*, so these are the likeliest things to break next version.
4. **A second maintenance cost is the GL/VK mirroring.** The VK path mirrors the GL path class for class (about 20 pairs, [§4.2](#42-glvk-duplicate-pairs)). Upstream (`MCRcortex/voxy`) renderer changes merge into the GL classes automatically but have to be re-ported by hand into their VK twins; the recent `fix: update Vulkan code for fog …` commits are exactly this. With GL in maintenance mode ([Decisions](#decisions-2026-09-25)), the fork's own work goes into VK only and the GL twins stay frozen until GL is removed. What Blaze3D adoption buys is a VK path that leans on MC's public API instead of its internals, which is what keeps ports cheap.
5. **Recommended order:**
   - Phase 0: fence the internals into one adapter and write the contracts down (no behaviour change).
   - Phase 1: swap private hooks for the public ones MC itself uses (`execute()`, `createFence()`, `queueForDestroy()`, VMA).
   - Phase 2: move small pieces onto the public Blaze3D API, VK first (async atlas readback, GPU timing, device info).
   - Phase 3: create textures through Blaze3D.
   - Phase 4: pilot a Blaze3D `RenderPass` with the chunk-bounds renderer, then the composite pass.
   - Phase 5: decide on stencil-free masking and fragment SSAO, which gate any further moves.
   - Compute, traversal and terrain raster stay raw Vulkan until Mojang adds the missing features ([§6](#6-watchlist--blaze3d-features-that-would-unlock-more)).

---

## Decisions (2026-09-25)

- **The OpenGL backend is in maintenance mode.** MC will eventually drop OpenGL, and Voxy's GL backend goes with it.
  - Until then, GL only gets bug fixes, upstream merges, and the small seam adapters a VK/Blaze3D step needs.
  - No drastic GL rewrites unless they directly advance the Vulkan path or the Blaze3D migration.
  - Blaze3D-based replacements target the VK path. Switching GL over to one is optional, and only when it's a true drop-in.
  - GL is still MC 26.2's *default* backend, so it has to keep working: changes to shared code still get a GL regression check.
  - Plan ahead for the removal: Voxy's Iris shader-pack integration (`client/iris`, `client/mixin/iris`, ~1,480 lines) is GL-only today.
- **The block-atlas readback becomes asynchronous** (step 2.3, approved). After a world load or resource reload, LODs may appear a frame or two later.

---

## Progress

Work happens on the branch `blaze3d-migration`, off `vulkan-audit-fixes`. Sections 2–4 and Appendix A describe the code as audited at `ee85c6f8`; this table lists what has changed since.

| Step | Status | Commit |
|---|---|---|
| 2.3 Async atlas readback via Blaze3D | Done, tested in-game (see below) | `1ce62302` |
| 0.1 Blaze3D-VK classes only in `MinecraftVkHostAdapter` + `mixin/vk` | Done | `6fc22f19` |
| 0.2 Contracts D1–D11 on `IVkHost`; D5 checked at device creation | Done | `b81829ec` |
| 1.1 Frame spliced in with `execute()`; `AccessorVulkanCommandEncoder` deleted | Done, tested in-game (see below) | `16678071` |
| 1.2 Frame retirement through `GpuFence` | Done, fine in normal play (see below) | `dded36b2` |
| 1.3 MC's destruction queue for deferred destroys (immediate during teardown) | Done, fine in normal play | `7a56ff41` |
| 1.4 Allocations through MC's VMA | Done, fine in normal play | `e14ef866` |
| Fix: LOD sections blanking while turning or leaving a spyglass (not a plan step) | Done, tested in-game (see below) | `110a6555`, `e0c6544d`, `719c29c1` |
| 2.1 GPU timing on Blaze3D queries | Done, tested in-game (see below); F3 layout reworked in `1fb6a3a3` | `6dac2b9b` |
| 2.2 Name and limits from `DeviceInfo` | Done, tested in-game | `c09df914` |
| 2.3 follow-up: drop deferred VK renderer creation | Decided: keep it (see [2.3](#phase-2--small-pieces-onto-the-public-blaze3d-api-vk-first)) | |
| 1.5 (optional) Samplers from Blaze3D | Not started; do it together with 3.1, which touches the atlas sampler anyway | |
| Phase 3 onwards | Not started | |

In-game checks for the finished steps:
- VK (Prefer Vulkan), with `--vulkanValidation` if the validation layer is installed.
- The built jar in the Modrinth App on the M2 Max.
- World join/leave twice, and a resource-pack reload (F3+T). LODs should appear a frame or two after joining or reloading, with correct block textures.
- A GL (Default) regression check.

Tested 2026-09-25 (jar `1667807`, M2 Max, Vulkan/MoltenVK, a server with a large pregenerated LOD set, heavily modded profile):
- Rendering was correct, with no noticeable performance change.
- The log has none of the new code's failure messages, and the startup feature check passed.
- The renderer was built, rebuilt when the server set the view distance (pre-existing behaviour), and shut down cleanly.
- Not reported yet: the F3+T reload and the GL regression check.

Tested 2026-09-25 (jars `e14ef86` and `719c29c`, same setup):
- 1.2–1.4: normal play works, including joining the server.
- The LOD blanking is gone: holes while panning on a mountain, and a blank second after leaving a spyglass. It is gone with and without `-Dvoxy.vk.disableSubgroupHiZ=true`, so the subgroup HiZ was not a cause; the switch stays as a diagnostic.
  - Causes: the flat 2 GB geometry buffer stayed full, so the cleaner evicted whatever had just left the screen (`719c29c1` sizes it like GL, about 4 GiB on the M2 Max); and MoltenVK's draw budget, sized from a count read back 2–3 frames late, cut off draws when the visible count jumped (`e0c6544d` holds the largest count of the last 8 s).
- FPS is somewhat lower than before, as expected: more geometry stays resident and gets drawn. See [Performance watch](#performance-watch).
- Still not reported: the F3+T reload and the GL regression check.

Tested 2026-09-25 (jar `c09df91`, M2 Max, 4112×2580 window, singleplayer and then a server):
- 2.1: the `GpuTime` line works on MoltenVK. No errors across world join, leave, rejoin and settings rebuilds. On Apple GPUs the per-pass split is skewed; see [Performance watch](#performance-watch).
- 2.2: the log reads *"vendor=APPLE, type=INTEGRATED, driver=1.2.334 MoltenVK 1.4.2"*.
- The one-line F3 layout ran under the right column and hid the total. `1fb6a3a3` puts the total first and wraps the passes onto two more lines.

What to watch for with 2.1 and 2.2:
- **2.1:** set F3's `voxy:gpu_debug` entry to "In F3" (F3+F6 opens the debug options). About a second later F3 shows `GpuTime: [setup:…, bounds:…, RO:…, hiz:…, I:…, prep:…, OT:…, CG:…, TS:…, TP:…, ao:…, RT:…, comp:…, dyn:…] = total ms, worst …`. LODs must look the same with the line on and off, because the line splits Voxy's frame into one command buffer per section. If the log shows *"GPU timing marker inside a rendering instance"*, a marker sits inside a pass. On MoltenVK the times are approximate: Metal samples timestamps at encoder boundaries.
- **2.2:** the log line *"Voxy Vulkan context adopted Minecraft device: … (vendor=…, type=…, driver=…"* names the vendor, device type and driver; everything else behaves as before.

What to watch for with 1.2–1.4:
- **1.2:** frame retirement drives readbacks and staging reuse. If it breaks, LODs stop loading or refining, and the log shows *"VK upload stream full"* or *"VK download stream full"* with hitches.
- **1.3:** GPU objects are now destroyed by MC's queue. Test world leave/rejoin, a resource-pack reload, resizing the window (recreates Voxy's targets) and changing Voxy's render distance. Watch for a device-lost crash, or *"VkFrameCtx used after teardown began"* in the log.
- **1.4:** memory now comes from MC's allocator. The log should still say *"Allocating 2048MB VK geometry buffer"* with no *"VK geometry allocation failed, retrying"* line, and GPU memory use should look the same.

### Performance watch

Performance is a co-priority of the migration: a step that makes the VK path slower needs a reason.
- **Measure:**
  - F3 `GpuTime` (2.1, with `voxy:gpu_debug`) gives the frame's GPU time and its split into passes.
  - **On Apple GPUs only the total is reliable.** MoltenVK samples timestamps at Metal encoder boundaries, from an empty blit pass that waits on nothing. A timestamp after a draw pass is therefore taken while the draws still run, and their time lands in the next section that has to wait for them: RO → `hiz`/`I`, OT → `CG`, TP → `ao`, RT → `comp`. For per-pass costs on the Mac, use Xcode Instruments (Metal System Trace), or A/B one setting at a time against the total.
  - F3 `VK draws/budget (10s shortfalls)` (MoltenVK only) gives each terrain pass's real draw count, the budget it was drawn with, and how often the budget fell short.
- **Baseline, 2026-09-25** (jar `c09df91`, M2 Max, 4112×2580 = 10.6 MP, MoltenVK 1.4.2):

  | View | FPS (p50 / p98) | Voxy GPU total | Largest sections | Draws (real / budget) |
  |---|---|---|---|---|
  | Straight up at the sky, SSAO better (12 spp) | 120 / 120 (vsync) | ≈ 2.2 ms | `I` 1.09, `ao` 0.67, `hiz` 0.17 | O 0/1024, X 0/256 |
  | Vista from y = 275 on a server, SSAO best (24 spp); 1,331 of 4,095 MB geometry, 98k nodes | 70 / 39 | ≈ 12.2 ms | `I` 8.02 (mostly the opaque draw), `ao` 2.26, `hiz` 0.93, `comp` 0.39, `CG` 0.35 | O 28,054/43,105, X 3,394/5,347 |

  At 70 fps a frame lasts about 14 ms, so in the vista Voxy's GPU work is most of the frame: the game is GPU-bound there.
- **Next measurements** (same spot, one change at a time, compare the total):
  1. Shrink the window to about half width and height. If the total drops by half or more, per-pixel work (LOD fragments, SSAO, HiZ, composite) dominates; if it barely moves, per-draw and per-vertex work does.
  2. SSAO `best` → `auto` (12 spp at this size) or `basic`.
  3. `-Dvoxy.vk.drawBudgetGrowth=1.0` shows what the ~15k empty opaque draws cost (watch `short` for dropped sections).
- **Known costs on MoltenVK (the M2 Max):**
  - MoltenVK has no indirect-count draws, so every budget slot is one Metal draw, empty ones included, encoded on the render thread inside MC's submit. The budget is the largest count of the last 8 s × 1.5 plus headroom (1024 opaque, 256 temporal/translucent). The × 1.5 predates the 8 s hold (`e0c6544d`) and could come down if `short` stays at 0.
  - Since `719c29c1` the geometry buffer is about 4 GiB instead of 2 GB, so more detail stays resident and is drawn. That costs frames, but it is what GL does too.
- **Rules for the remaining steps:**
  - Every Blaze3D encoder operation ends with a full barrier (D2). Don't route per-item work through Blaze3D calls without batching it: for example 3.1's model-atlas uploads, which would be one `writeToTexture` per region. Compare `GpuTime` before and after.
  - A Blaze3D call inside Voxy's frame needs a segment split (1.1, gotcha a). Splits are cheap, but keep them at pass boundaries.
  - Phase 4 swaps raw passes for Blaze3D ones: compare `bounds` (4.1) and `comp` (4.2) before and after.
- **Bigger levers, outside the migration (need a decision):**
  - Fewer, larger terrain draws on MoltenVK: today every visible section is one Metal draw.
  - A smaller growth factor in the draw budget.

---

## 1. How this was checked (and how to re-check)

- Read all 35 files under `client/core/vk/**` and `client/mixin/vk/**`, the backend-neutral seams (`IDeviceBuffer`, `IModelStore`, `INodeGpuOps`, `IAtlasTextureReader`, `Abstract{Upload,Download}Stream`, …), `VoxyRenderSystem`, `RenderProperties`, and every mixin's target.
- Decompiled MC 26.2 and read `com.mojang.blaze3d.*`: the public `GpuDevice`, `CommandEncoder`, `RenderPass`, `RenderPipeline`, `GpuBuffer`, `GpuTexture` and `BindGroupLayout`, plus the `vulkan` backend (`VulkanDevice`, `VulkanCommandEncoder`, `VulkanBackend`, `VulkanRenderPipeline`, `VulkanRenderPass`, `GlslCompiler`, `IntermediaryShaderModule`, …).

Re-generate the sources in a future session (`genSources` writes only into `.gradle/loom-cache`):

```bash
./gradlew genSources
```

```bash
mkdir -p /tmp/mc-src && unzip -o -q "$(ls .gradle/loom-cache/minecraftMaven/net/minecraft/minecraft-merged-*/26.2/*-sources.jar | head -1)" 'com/mojang/blaze3d/*' 'net/minecraft/client/*' -d /tmp/mc-src
```

---

## 2. What Blaze3D 26.2 actually provides

### 2.1 Public, backend-neutral API (the part worth building on)

| Area | Available in 26.2 | Missing in 26.2 (blockers for Voxy) |
|---|---|---|
| Buffers — `GpuDevice.createBuffer` | usages `MAP_READ, MAP_WRITE, HINT_CLIENT_STORAGE, COPY_DST, COPY_SRC, VERTEX, INDEX, UNIFORM, UNIFORM_TEXEL_BUFFER, INDIRECT_PARAMETERS`; `map()`, slices | **no storage-buffer usage** (`VulkanConst.bufferUsageToVk` never sets `STORAGE_BUFFER_BIT`) |
| Textures — `createTexture` / `createTextureView(tex, baseMip, count)` | 2D, mips, cubemaps; usages `COPY_DST, COPY_SRC, TEXTURE_BINDING, RENDER_ATTACHMENT, CUBEMAP_COMPATIBLE`; formats incl. `D32_FLOAT`, `D32_FLOAT_S8_UINT`, `D24_UNORM_S8_UINT`, `R32_FLOAT`, `RGBA8_UNORM` | **no storage-image usage**; `depthOrLayers > 1` throws *"Array or 3D textures are not yet supported"* |
| Samplers — `createSampler`, `RenderSystem.getSamplerCache()` | address/filter modes, anisotropy, `maxLod` | mip mode is derived (`maxLod > 0.25 ⇒ LINEAR`), so no *nearest-mip over a full chain* (needed by HiZ) |
| Pipelines — `RenderPipeline.builder()` | vertex + fragment; defines; `BindGroupLayout` (named samplers, `UNIFORM_BUFFER`, `TEXEL_BUFFER`); ≤8 `ColorTargetState` (format, blend, write mask); `DepthStencilState(compare, write, biasScale, biasConst)`; cull; polygon mode; topologies incl. `TRIANGLE_STRIP`; `VertexFormat.builder(stepRate)` (instancing) | **`ShaderType` = VERTEX, FRAGMENT only (no compute)**; **no stencil**; **no push constants**; no SSBO/storage-image bindings |
| Render passes — `CommandEncoder.createRenderPass(RenderPassDescriptor)` | N colour + optional depth, clears, render area, `withUnusedColorAttachment()`; `setPipeline`, `bindTexture(name, view, sampler)`, `setUniform(name, buffer)`, vertex/index buffers, `draw/drawIndexed/multiDraw*`, **`drawIndirect` / `drawIndexedIndirect`** (buffer needs `USAGE_INDIRECT_PARAMETERS`), scissor, debug groups, `writeTimestamp` | **no indirect-count**, no dispatch |
| Transfers | `writeToBuffer`, `copyToBuffer`, `writeToTexture` (per mip/region), `copyBufferToTexture`, `copyTextureToBuffer(+callback)`, `copyTextureToTexture`, clears | copies can only target Blaze3D objects |
| Sync | `createFence()` → `GpuFence.awaitCompletion(t)`; `RenderSystem.queueFencedTask(Runnable)` (drained by MC every frame in `RenderSystem.executePendingTasks()`) | no user barriers (the backend inserts its own) |
| Timing | `GpuDevice.createTimestampQueryPool(n)`, `CommandEncoder/RenderPass.writeTimestamp`, `GpuQueryPool.getValue`, `DeviceInfo.timestampPeriod()`, `TimerQuery` | — |
| Transient/staging memory | `CommandEncoder.transientMemory()` (per-submit ring), `vertex.StagingBuffer`, `vertex.UberGpuBuffer` + `TlsfAllocator` | only feeds Blaze3D buffers |
| Device info | `DeviceInfo` (name, vendorName, driverInfo, `backendName`, `isZZeroToOne`, `limits`, `features`, `underlyingExtensions`, `type`) | no subgroup / SSBO-range / memory-budget info |
| Shaders | GLSL `#version 330` + `#moj_import`, resources bound **by name**; `ShaderManager` loads `.vsh/.fsh/.glsl` under `shaders/` from **every namespace** (so `assets/voxy/shaders/core/x.vsh` is `voxy:core/x`); `GpuDevice.precompilePipeline(pipeline, ShaderSource)` also exists, but its cache is cleared on resource reload | on VK, MC's reflection (SPIRV-Cross) only sees uniform buffers, 2D/cube samplers, texel buffers and stage in/out; **SSBOs, images and push constants are invisible to it** |

### 2.2 Vulkan-backend internals (public classes, but not a stable API)

`com.mojang.blaze3d.vulkan.*` — everything here can change without notice while the backend is experimental.

- **`VulkanDevice`**:
  - `instance()`, `vkDevice()`, `graphicsQueue()`, `computeQueue()`, `transferQueue()` (`VulkanQueue(vkQueue, queueFamilyIndex)`).
  - **`vma()`**: MC's VMA allocator. `lwjgl-vma` ships with MC.
  - `createCommandEncoder()`: returns the **single persistent** `VulkanCommandEncoder`.
- **`VulkanCommandEncoder`**:
  - Public: `allocateAndBeginTransientCommandBuffer()`, **`execute(VkCommandBuffer)`**, `waitSemaphore` / `signalSemaphore(sem, value, stage)`, **`queueForDestroy(Destroyable)`**, and a static `memoryBarrier(cmd, stack)`. MC itself uses `allocate…` + `execute` in `VulkanTransientMemory` and `VulkanGpuSurface`, and `queueForDestroy` everywhere.
  - Private: `currentCommandBuffer`.
- **Handle getters**: `VulkanGpuBuffer.vkBuffer()`, `VulkanGpuTexture.vkImage()`, `VulkanGpuTextureView.vkImageView()`, `VulkanGpuSampler.vkSampler()`, `VulkanConst.toVk(GpuFormat)`.
- **Device creation**: `private static VulkanBackend.createDevice(Collection<String>, VulkanPhysicalDevice, Set<VulkanFeature>)`.
  - Requires Vulkan 1.2.
  - Required extensions: `VK_KHR_dynamic_rendering`, `VK_KHR_push_descriptor`, `VK_KHR_synchronization2`, `VK_EXT_vertex_attribute_divisor`, `VK_KHR_swapchain` (plus `multi_draw`, `portability_subset` and the checkpoint extensions when present).
  - Features: `multiDrawIndirect, fillModeNonSolid, samplerAnisotropy, shaderDrawParameters, timelineSemaphore, hostQueryReset, synchronization2, dynamicRendering, vertexAttributeInstanceRateDivisor`. **No way to request more except a mixin.**

### 2.3 Behaviour of MC's Vulkan backend that Voxy depends on

| # | Contract (verified in 26.2 source) | Where Voxy relies on it |
|---|---|---|
| D1 | Every `VulkanGpuTexture` goes `UNDEFINED→GENERAL` once at creation and stays `GENERAL` forever | `VkFrameHost.MC_IMAGE_LAYOUT` (MC depth/colour/lightmap/atlas access) |
| D2 | Every encoder operation **ends** with a full `ALL_COMMANDS / MEMORY_READ\|WRITE` barrier; nothing is ordered *before* a pass | `VkFrameCtx.endFrame` must end with `fullBarrier()` |
| D3 | `VulkanDevice.createCommandEncoder()` returns one persistent encoder; at the Sodium OPAQUE `TAIL` hook its command buffer is open and no render pass is active | `MinecraftVkHostAdapter.frameCommandBuffer()` |
| D4 | One submit per frame at the end of `Minecraft.renderFrame`; `MAX_SUBMITS_IN_FLIGHT = 2`, and `submit()` blocks on N−2 with a 5 s timeout | frame retirement, deferred destroys, renderer creation at `renderFrame` HEAD |
| D5 | MC's device enables push descriptors, dynamic rendering, sync2 and timeline semaphores | `VkShaderPipeline` (push descriptors), `VkFrameCtx` (dynamic rendering, timeline) — Voxy never enables these itself |
| D6 | MC only touches its graphics queue from the render thread | `VkFrameCtx.flushImmediate()` calls `vkQueueSubmit` on MC's queue directly |
| D7 | Blaze3D VK pipelines are compiled with `depthAttachmentFormat = VK_FORMAT_D32_SFLOAT` (or none) and never a stencil format | limits which Voxy passes can become Blaze3D passes |
| D8 | The block atlas has `COPY_SRC` usage and is `RGBA8_UNORM` | `VkAtlasTextureReader` |
| D9 | `gameRenderer.levelLightmap()` is a 2D sampleable `GpuTextureView` | `VkFrameHost.lightmapView()` |
| D10 | Reverse-Z is signalled by `DepthStencilState.DEFAULT` being `GREATER_THAN_OR_EQUAL`; `isZZeroToOne()` is true on VK | `RenderProperties.useReverseZ()` (shared GL/VK) |
| D11 | Sodium draws opaque terrain through `SodiumWorldRenderer.drawChunkLayer(OPAQUE, …)` into `group.outputTarget()` on MC's device, with its render pass closed at `TAIL` | `MixinSodiumOpaqueVkFrame` |

### 2.4 Corrections to the old `BLAZE3D_GAP_ANALYSIS.md`

- **Indirect draws exist** (`drawIndirect`, `drawIndexedIndirect`, `USAGE_INDIRECT_PARAMETERS`). Only indirect-**count** is missing.
- **Timestamp queries exist** (`createTimestampQueryPool`, `writeTimestamp`).
- **Raw handles are extractable** through public getters (`vkImage()`, `vkImageView()`, `vkBuffer()`, `vkSampler()`, `vkDevice()`, `vma()`). It listed this as an open question.
- **Texel buffers exist** (`UNIFORM_TEXEL_BUFFER` / `UniformType.TEXEL_BUFFER`).
- There is no `FeatureRenderer` hook for terrain, and `GlTextureView` is GL-only.
- **Not listed there but real blockers:** no stencil, the D32-only depth attachment in VK pipelines, 2D-only textures, and push constants and SSBOs invisible to MC's shader reflection.
- The VK backend is **opt-in**. `PreferredGraphicsApi.DEFAULT` tries OpenGL first, and the UI calls Vulkan *"Experimental"*.

---

## 3. How the Voxy VK path uses Blaze3D today

### 3.1 Frame flow

```
MC VulkanDevice.<init>  ──(MixinVulkanDevice TAIL)──▶ MinecraftVkHost.register(adapter)
VulkanBackend.createDevice (private) ──(MixinVulkanBackend HEAD)──▶ + shaderInt64, fragmentStoresAndAtomics,
                                                                     drawIndirectFirstInstance, drawIndirectCount?
Minecraft.renderFrame HEAD ──(MixinMinecraftFrameStart)──▶ create pending VkRenderCore (atlas readback = own vkQueueSubmit)
Sodium drawChunkLayer(OPAQUE) TAIL ──(MixinSodiumOpaqueVkFrame)──▶ VkRenderCore.renderFrame(outputTarget, …)
    cmd = MC's currentCommandBuffer (AccessorVulkanCommandEncoder — private field)
    ALL raw Vulkan, recorded into MC's cmd:
      setup depth/stencil ▸ chunk bounds ▸ opaque terrain ▸ HiZ ▸ node mgmt + traversal (compute)
      ▸ prep/raster-cull/cmdgen/prefix-sort (compute+raster) ▸ temporal ▸ SSAO (compute)
      ▸ translucent ▸ composite into MC colour/depth
    fullBarrier() ; VulkanCommandEncoder.signalSemaphore(voxyTimeline, frame)   (ends MC's cmd buffer)
VulkanDevice.close HEAD ──(MixinVulkanDevice)──▶ Voxy shutdown + VulkanContext.destroy
```

### 3.2 Reliance in numbers

| Measure | Value |
|---|---|
| VK path size | 35 files, ~5,050 lines (`core/vk` 30 files / 4,862; `mixin/vk` 5 / 187) |
| Raw Vulkan call sites | ~122 (63 distinct `vk*` functions, excluding accessor methods like `vkImage()`) |
| Files that touch Blaze3D at all | 11 of 35. Most use only one or two Blaze3D types; the rest of their code is raw Vulkan |
| Files using `com.mojang.blaze3d.vulkan.*` internals | 7: `MinecraftVkHostAdapter`, `VkDeviceFeatures`, `VkAtlasTextureReader`, `render/VkFrameHost`, `mixin/vk/{AccessorVulkanCommandEncoder, MixinVulkanBackend, MixinVulkanDevice}` |
| Mixins/accessors into Blaze3D-VK internals | 3 (plus 2 frame hooks on `Minecraft` and Sodium) |
| Pipelines | 19 = 13 compute + 6 graphics, all built by Voxy's own `VkShaderPipeline` / `ShadercCompiler` |

For comparison, the GL path: 44 files import `org.lwjgl.opengl` (~9,100 lines, ~590 raw `gl*` calls). 6 live files use `com.mojang.blaze3d.opengl` internals: `GlTexture.glId()` in `LightMapHelper` and `GlAtlasTextureReader`; `GlTextureView.glId()` in `MixinDefaultChunkRenderer` and the nvidium `MixinRenderPipeline`; `GlStateManager` / `GlConst` in `VoxyRenderSystem`; `GlDebug` in `MixinGlDebug`. `BakedBlockEntityModel.java` also casts to `GlTexture`, but the whole file is commented out.

### 3.3 Touchpoint inventory, by fragility

**Tier A — stable public API (low risk)**
- `RenderSystem.tryGetDevice().getDeviceInfo().backendName()` — `core/vk/MinecraftVkHost.java`
- `RenderTarget.getColorTextureView()/getDepthTextureView()/width/height` — `core/vk/render/VkRenderCore.java:199`
- `gameRenderer.levelLightmap()` (`GpuTextureView`) — `core/vk/render/VkFrameHost.java:31`
- `GpuTexture` atlas handed to `IAtlasTextureReader`; `GpuTextureView.texture().getFormat()`; `GpuSampler` (in a hook signature)
- `DepthStencilState.DEFAULT`, `CompareOp`, `DeviceInfo.isZZeroToOne()` — `core/RenderProperties.java:62-69` (shared)

**Tier B — public classes of the experimental VK backend (medium risk)**
- `VulkanDevice.instance().vkInstance() / vkDevice() / graphicsQueue() / createCommandEncoder()` — `core/vk/MinecraftVkHostAdapter.java`
- `VulkanCommandEncoder.signalSemaphore(…)` — `core/vk/MinecraftVkHostAdapter.java:48`
- `VulkanGpuTexture.vkImage()`, `VulkanGpuTextureView.vkImageView()`, `VulkanConst.toVk(GpuFormat)` — `core/vk/render/VkFrameHost.java`, `core/vk/VkAtlasTextureReader.java:33`
- `VulkanFeature`, `VulkanBackend.VK10_FEATURES_STRUCT / VK12_FEATURES_STRUCT`, `VulkanPhysicalDevice.vkPhysicalDevice()` — `core/vk/VkDeviceFeatures.java`

**Tier C — private internals via mixins (high risk; most of these crash the game if they stop matching)**
- `@Accessor("currentCommandBuffer")` on `VulkanCommandEncoder` — `mixin/vk/AccessorVulkanCommandEncoder.java`
- `@Inject` at the HEAD of the private static `VulkanBackend.createDevice(Collection, VulkanPhysicalDevice, Set)`, matched by descriptor string — `mixin/vk/MixinVulkanBackend.java` (`require = 0`, so it fails soft)
- `@Inject` on `VulkanDevice.<init>` TAIL and `close` HEAD — `mixin/vk/MixinVulkanDevice.java`
- Frame hooks: `Minecraft.renderFrame` HEAD; Sodium `SodiumWorldRenderer.drawChunkLayer` TAIL (`remap = false`)
- `client.voxy.mixins.json` sets `defaultRequire: 1`, so any Tier C injector without `require = 0` crashes on a mismatch.

**Tier D — unwritten behavioural contracts:** D1–D11 in [§2.3](#23-behaviour-of-mcs-vulkan-backend-that-voxy-depends-on). These break silently, as corruption or validation errors rather than crashes.

### 3.4 Who does what today

| Concern | Blaze3D 26.2 offers | Voxy VK path today | Blaze3D could take it? |
|---|---|---|---|
| Instance / device / queue | `VulkanDevice` | adopts MC's (Tier B) | ✅ already |
| Extra device features | none | mixin into private `createDevice` (C) | ❌ no API — keep the mixin |
| Frame command buffer | persistent `VulkanCommandEncoder` | records into MC's *current* cmd via private accessor (C) | ✅ via public `allocate…()`+`execute()` → **1.1** |
| GPU-completion tracking | `createFence()`, `queueFencedTask` | own timeline semaphore + `signalSemaphore` (B) | ✅ public & backend-neutral → **1.2** |
| Deferred destruction | `queueForDestroy` (B) | own list keyed to the timeline | ✅ → **1.3** |
| Memory allocation | VMA via `VulkanDevice.vma()` (B) | raw `vkAllocateMemory` per resource, own `findMemoryType` | ✅ → **1.4** |
| Storage buffers | ❌ | own `VkBuffer` | ❌ (see option O1) |
| Sampled / attachment textures | `GpuTexture` (2D, GENERAL) | own `VkImage2D` + layout tracking | ✅ atlas, depth-bound, colour → **3.x** |
| Storage images, D32S8 attachments | ❌ | own `VkImage2D` | ❌ |
| Samplers | `GpuSampler` + `vkSampler()` | own cache in `VulkanContext` + one in `VkModelStore` | ✅ except the HiZ nearest-mip sampler → **1.5** |
| GLSL → SPIR-V | MC `GlslCompiler` (vert/frag only) | own `ShadercCompiler` + `VkShaderSource` | ⚠️ only for Blaze3D pipelines |
| Compute pipelines / dispatch | ❌ | `VkShaderPipeline`, `vkCmdDispatch*` | ❌ |
| Graphics pipelines | `RenderPipeline` | `VkShaderPipeline` | ⚠️ 2 of 6 today → **4.x** |
| Descriptors | push descriptors by name (UBO, sampler, texel buffer) | own push-descriptor `Binder` (+SSBO, storage image) | ⚠️ follows the pipeline |
| Draws | direct / multi / indirect | indexed-indirect**-count**, dispatch-indirect | ⚠️ no indirect-count |
| Barriers & layouts | implicit, full barrier after every op | fine-grained, manual | ❌ raw work needs its own |
| Staging uploads | `TransientMemory`, `StagingBuffer` | own `VkUploadStream` | ❌ (targets are storage buffers) |
| Readbacks | `copyTextureToBuffer` + async callback | `VkDownloadStream`, `VkAtlasTextureReader` | ⚠️ atlas yes → **2.3**; buffer readbacks no |
| Writing MC's framebuffer | `RenderPass` on `RenderTarget` views | raw dynamic rendering + manual barriers on MC images | ✅ → **4.2** |
| GPU timing | timestamp query pools | none on VK | ✅ → **2.1** |

---

## 4. What can move, pass by pass

### 4.1 Pipelines

| VK pipeline (file) | Resources used | Blaze3D-expressible on 26.2? |
|---|---|---|
| chunk bounds (`VkBoundRenderer`, `chunkoutline/outline.*`) | UBO, **SSBO chunk positions**, depth-only `D32_SFLOAT`, instanced | ✅ **with changes**: positions become an instanced vertex buffer (`VertexFormat.builder(1)`) or a texel buffer; target becomes a Blaze3D `D32_FLOAT` texture; depth-only via `withUnusedColorAttachment()` + `withUnusedColorTargetState(0)` |
| composite (`VkCompositor.composite`, `post/fullscreen2.vert` + `blit_texture_depth_cutout.frag`) | 2 samplers, 1 std140 UBO, blend = `BlendFunction.TRANSLUCENT`, depth test+write into MC's D32 | ✅ **once its inputs are Blaze3D textures** (colourSSAO is a storage image today; Voxy depth is D32S8) |
| depth/stencil setup (`VkCompositor.setupDepthStencil`) | sampler, **push constant**, **stencil write**, D32S8 | ❌ (stencil). ✅ if stencil masking goes away (**5.1**); the push constant becomes a UBO |
| terrain opaque / translucent (`VkTerrainRenderer`, `quads3.vert`/`quads.frag`) | SSBO vertex pulling (geometry up to 2 GB), `shaderInt64`, **indexed-indirect-count**, stencil test, D32S8 | ❌ |
| raster occlusion cull (`lod/gl46/cull/raster.*`) | fragment shader **writes an SSBO** | ❌ |
| 13 compute pipelines: prep, cmdgen, prefixsum (`inital3_vk`/`simple`), buildtranslucents, traversal_dev, sort_visibility, result_transformer, batch_visibility_set, scatter, memcpy, hiz_reduce, hiz_subgroup, ssao | SSBOs, storage images, push constants, dispatch(-indirect) | ❌ no compute in Blaze3D |

### 4.2 GL/VK duplicate pairs

Lines per class. With GL in maintenance mode, the GL column is frozen. ★ marks VK classes that a Blaze3D implementation can replace. That implementation could later serve GL too, but only as a drop-in.

| GL class (frozen) | VK twin | VK twin replaceable via Blaze3D? |
|---|---|---|
| `BoundRenderer` 143 | `VkBoundRenderer` 165 | ★ step 4.1 |
| `NormalRenderPipeline` (setup + blit) 168 | `VkCompositor` 313 | ★ composite part, step 4.2 (setup only after 5.1) |
| `GlAtlasTextureReader` 35 | `VkAtlasTextureReader` 72 | ★ step 2.3 (the GL reader stays, behind the new async interface) |
| `GPUTiming` 209 | *(none)* | ★ step 2.1 adds Blaze3D timing for VK; GL keeps `GPUTiming` |
| `ModelStore` 102 | `VkModelStore` 134 | ★ texture half only, step 3.1 |
| `SSAO` 175 | `VkSSAO` 189 | only if SSAO becomes a fragment pass (5.2) |
| `HiZBuffer2` 148 | `VkHiZ` 193 | ❌ |
| `MDICSectionRenderer` 397 | `VkTerrainRenderer` 506 | ❌ |
| `HierarchicalOcclusionTraverser` 407 | `VkTraversal` 280 | ❌ |
| `NodeCleaner` 195 | `VkNodeCleaner` 168 | ❌ |
| `GlNodeGpuOps` 85 | `VkNodeGpuOps` 104 | ❌ |
| `BasicSectionGeometryData` 179 | `VkSectionGeometryData` 94 | ❌ |
| `UploadStream` / `DownloadStream` 184 / 177 | `VkUploadStream` / `VkDownloadStream` 167 / 185 | retirement logic only (step 1.2) |
| `GlBuffer` / `GlTexture` / `Shader` 108 / 164 / 234 | `VkBuffer` / `VkImage2D` / `VkShaderPipeline` 141 / 179 / 330 | partly (1.4, 3.x) |
| `Capabilities` 223 | `VulkanContext` 238 | vendor/limits part (2.2) |
| `AbstractRenderPipeline` 283 | `VkRenderCore` 390 | ❌ orchestration |

---

## 5. Roadmap — actionable steps

Every step targets the VK path. Per the [Decisions](#decisions-2026-09-25), GL only gets the minimal seam adapters a step calls out explicitly, and no rewrites.

Every step should land on its own (build, test, commit). Verify each step on:
- **GL** (Graphics API = Default): a regression check only, to make sure shared-code changes didn't break it.
- **VK** (Graphics API = *Prefer Vulkan*). For validation, add MC's launch flags `--vulkanValidation --renderDebugLabels`; the Khronos validation layer must be installed.
- **Production jar** in the Modrinth App on the M2 Max (MoltenVK: no `drawIndirectCount`, D32S8). `runClient` isn't enough.
- A desktop GPU, for the `drawIndirectCount` path.

### Phase 0 — Fence off and document (no behaviour change)

**0.1 Route every Blaze3D-VK internal through the host seam — ✅ done (`6fc22f19`)**
- *As built:* `IVkHost` gained `vkImage`/`vkImageView`/`vkFormat`, and the feature request moved to `MinecraftVkHostAdapter.requestDeviceFeatures`. `vma()` and `vkSampler()` come with 1.4/1.5.
- *Goal:* a version port touches one adapter file plus the vk mixins, and nothing else.
- *How:*
  - Extend `IVkHost` with `long vkImage(GpuTexture)`, `long vkImageView(GpuTextureView)`, `int vkFormat(GpuFormat)`, `long vkSampler(GpuSampler)`, `long vma()`, `VkCommandBuffer beginSegment()`/`endSegment(cb)` (for 1.1), and a `lightmapView()` accessor.
  - Implement them in `MinecraftVkHostAdapter`.
  - Make `render/VkFrameHost.java` and `VkAtlasTextureReader.java` call the host instead of casting to `VulkanGpuTexture`/`VulkanGpuTextureView`/`VulkanConst`.
  - Move the `VulkanFeature` / `VulkanBackend.*_FEATURES_STRUCT` references from `VkDeviceFeatures.java` into the mixin or adapter, leaving `VkDeviceFeatures` a plain record of what got enabled.
- *Done when:* `grep -rl "com.mojang.blaze3d.vulkan" src/main/java` lists only `MinecraftVkHostAdapter` and `mixin/vk/*`.

**0.2 Write the contracts down and assert the cheap ones — ✅ done (`b81829ec`)**
- *As built:* the D5 check runs in the device-creation hook instead of through `DeviceInfo`. It records whether MC's extension list and feature set contain `VK_KHR_push_descriptor`, dynamic rendering and timeline semaphores, and `VkDeviceFeatures.missingRequired()` reports any that are missing. `isZZeroToOne()` isn't asserted, because `RenderProperties` handles both depth ranges.
- *Goal:* a port can't silently violate D1–D11.
- *How:*
  - Copy the §2.3 table into `IVkHost`'s header comment.
  - In `VulkanContext` construction, check `DeviceInfo.underlyingExtensions()` for `VK_KHR_push_descriptor`, `VK_KHR_dynamic_rendering`, `VK_KHR_synchronization2` (D5; entries carry a ` (D)` suffix), and check `isZZeroToOne()`. On failure, report "unsupported" through `VulkanBackend.unsupportedReason` instead of crashing.
- *Done when:* launching with one assertion forced false shows the unsupported reason in the log, and the game keeps running.

**0.3 Keep this document current.** Update [§2](#2-what-blaze3d-262-actually-provides) and [§7](#7-porting-checklist-per-minecraft-version) on every MC bump.

### Phase 1 — Swap private hooks for the public ones MC itself uses (VK)

**1.1 Record Voxy's frame into its own command buffers, spliced in with `execute()` — ✅ done (`16678071`)**
- *As built:* one segment per frame, through `IVkHost.beginSegment()/endSegment()`. Voxy's frame makes no Blaze3D encoder calls yet, so gotcha (a) below only matters once Phase 4 mixes in Blaze3D passes.
- *Goal:* delete `AccessorVulkanCommandEncoder`, the only private-field accessor.
- *How:*
  - A frame becomes one or more *segments*: `cb = encoder.allocateAndBeginTransientCommandBuffer()` → record → `vkEndCommandBuffer(cb)` → `encoder.execute(cb)`. This is exactly how MC's `VulkanTransientMemory` and `VulkanGpuSurface` inject work.
  - `execute()` ends MC's current buffer and appends Voxy's to the same submission, so submission order preserves ordering.
  - Put this in `VkFrameCtx.beginFrame/endFrame` (`core/vk/VkFrameCtx.java:111,118`) and `MinecraftVkHostAdapter.frameCommandBuffer()` (`:39`).
- *Gotchas:*
  - (a) Any **Blaze3D** encoder call made during Voxy's frame (a `RenderPass`, `writeTimestamp`, `createTexture`'s init barrier) is recorded into MC's *next* buffer. Close the current segment before a Blaze3D call and open a new one after it. This also enables mixing Blaze3D passes with raw passes in Phase 4.
  - (b) `execute()` throws inside a render pass (same precondition as today, D3).
  - (c) The "no frame command buffer at hook point" skip in `VkRenderCore.renderFrame` goes away.
- *Done when:* the accessor mixin is removed from `client.voxy.mixins.json`, frames look identical, and validation is clean.

**1.2 Frame retirement through public `GpuFence` — ✅ done (`dded36b2`)**
- *As built:* as described below. `IVkHost.signalSemaphore` is gone, and so is the timeline-semaphore part of the D5 check.
- *Goal:* drop the Voxy timeline semaphore and `signalSemaphore` (Tier B), and get a retirement mechanism the GL streams can share later.
- *How:*
  - At `endFrame`, `fences.add(frameIdx, RenderSystem.getDevice().createCommandEncoder().createFence())`.
  - `pollRetired()` pops while `fence.awaitCompletion(0)` is true and notifies the listeners (upload recycle, download callbacks).
  - Hard sync (`waitIdleRetireAll`): after `vkDeviceWaitIdle`, poll again. Never wait with a timeout on the *current* submit, which throws *"Cannot wait on a fence for the current submit"*.
- *Why public:* `createFence()` is on the backend-neutral `CommandEncoder`, and MC's own `RenderSystem.queueFencedTask` is built on it.
- *Done when:* `VkFrameCtx` has no semaphore, upload/download streams still recycle and readbacks still fire, and nothing hitches on world unload.

**1.3 Use MC's destruction queue for deferred destroys — ✅ done (`7a56ff41`)**
- *As built:* `VkFrameCtx.deferDestroy(Runnable)` goes through `IVkHost.deferDestroy`, which is MC's `queueForDestroy`. One addition to the plan: `VkFrameCtx.beginTeardown()` idles the device on shutdown, and when no Voxy frame is still unsubmitted it destroys every later free immediately. Without that, a renderer rebuilt right away (e.g. on a server's view-distance change at join) would allocate its 2 GB geometry buffer while the old one still waited in MC's queue. `cmd()` refuses to record after teardown begins.
- *How:* replace `VkFrameCtx.pendingDestroys` (`deferDestroy*`, `:265`) with `encoder.queueForDestroy(() -> …)`. MC destroys them two submits later, which matches D4. At game exit, anything still queued is run by MC's own `commandEncoder.destroy()`. `VulkanDevice.close` calls that before `vkDestroyDevice`, and after `MixinVulkanDevice`'s HEAD hook, so the device is still alive. Re-check that ordering on each port (§7).
- *Done when:* `PendingDestroy` is deleted and there are no leaks or use-after-free under validation across repeated world join/leave.

**1.4 Allocate through MC's VMA instead of raw `vkAllocateMemory` — ✅ done (`e14ef866`)**
- *As built:* this doesn't use MC's `AUTO_PREFER_DEVICE` flags. It uses `VMA_MEMORY_USAGE_UNKNOWN` with the same required/preferred flags as before, so VMA picks the same memory types the manual search did. Host-visible buffers are persistently mapped (`VMA_ALLOCATION_CREATE_MAPPED_BIT`). The `vmaGetHeapBudgets` bonus is skipped, because MC doesn't enable `VK_EXT_memory_budget` and VMA would only report estimates.
- *How:*
  - Add `compileOnly("org.lwjgl:lwjgl-vma:${lwjglVersion}")`. Don't bundle it; MC ships it, like `lwjgl-vulkan`.
  - `VkBuffer` (`core/vk/VkBuffer.java:51`) and `VkImage2D` call `Vma.vmaCreateBuffer/vmaCreateImage(host.vma(), …)`, mirroring the flags MC uses in `VulkanGpuBuffer.Direct` (AUTO_PREFER_DEVICE, plus host-access flags for mapped buffers).
  - Delete `VulkanContext.findMemoryType` and the memory-properties cache.
- *Bonus:* `vmaGetHeapBudgets` gives the VK path real memory budgets for `VkRenderCore.geometryCapacity` (`:180`), which only the GL path has today via `Capabilities`.
- *Risk:* the 2 GB geometry buffer has to get a dedicated allocation. VMA does this automatically for large sizes, but check it with `vmaGetAllocationInfo`.

**1.5 (optional) Samplers from Blaze3D**
- Use `GpuDevice.createSampler`/`RenderSystem.getSamplerCache()` plus `host.vkSampler()` for the nearest/linear samplers and the model-atlas sampler.
- The atlas sampler matches Blaze3D's mapping: NEAREST min/mag, `maxLod = LAYERS-1` ⇒ LINEAR mip.
- The HiZ nearest-mip sampler can't be expressed (§2.1) and stays raw.
- Low value: do it only if touching those files anyway.

### Phase 2 — Small pieces onto the public Blaze3D API (VK first)

**2.1 GPU timing on Blaze3D queries (VK) — ✅ done (`6dac2b9b`)**
- *As built:*
  - `VkGpuTiming` uses only the public API: `createTimestampQueryPool`, `CommandEncoder.writeTimestamp`, `GpuQueryPool.getValues` and `DeviceInfo.timestampPeriod()`.
  - `VkFrameCtx.gpuMarker(label)` splits the frame there: it splices the segment recorded so far with `execute()`, the timestamp lands in MC's next buffer, and a new segment begins. A marker right after a segment began needs no split, because the empty segment follows MC's current buffer anyway.
  - Timing runs only while F3's `voxy:gpu_debug` entry is showing (`DebugEntries.isGpuDebugShown()`, "In F3" and F3 open, or "Always"). Otherwise nothing is split or written.
  - Results are read when the frame retires (a `GpuFence` retire listener). MC's `writeTimestamp` resets the query on the host, so a frame's queries are reused only after that; 4 slots of 32 queries.
  - The F3 line uses GL's labels where the pass is the same (`RO`, `I`, `OT`, `CG`, `TS`, `TP`, `ao`, `RT`; VK splits GL's `I` into `hiz` + `I`). It averages over one second, with the average and worst frame total. GL shows a decaying peak instead.
  - GL keeps `core/util/GPUTiming.java` as it is (maintenance mode).
- *Original plan:* build a VK timing path on `GpuDevice.createTimestampQueryPool`, `CommandEncoder.writeTimestamp` and `DeviceInfo.timestampPeriod()`, with the timestamps placed between segments (1.1), because they are recorded into MC's buffer.

**2.2 Vendor/limits from `DeviceInfo` (only where VK needs it) — ✅ done (`c09df914`)**
- *As built:* the VK path never used the GL-only `Capabilities`. `VulkanContext` now takes the device name, `maxMemoryAllocationSize` and the uniform-buffer alignment from `DeviceInfo`, and logs `vendorName()`, `type()` and `driverInfo()`. `DeviceInfo` has no subgroup properties, storage-buffer range or alignment, depth-stencil formats or memory heaps, so those are still queried from Vulkan.
- *Original plan:* where the VK path needs vendor or limits, use `DeviceInfo.vendorName()/name()/type()/limits()` instead of `Capabilities`. Leave GL's `Capabilities` probes untouched.

**2.3 Asynchronous atlas readback via Blaze3D — ✅ done (`1ce62302`); the follow-up below is still open**
- *As built:* `IAtlasTextureReader.readAsync` and `Blaze3DAtlasTextureReader`. `ModelBakerySubsystem` starts the readback before its processing thread starts, and `ModelFactory.processAllThings()` waits for `SoftwareModelTextureBakery.isTextureReady()`. Deferred renderer creation stays for now: it also guarantees no MC render pass is open when the copy is recorded.
- *What it's for:* the model bakery's software rasterizer samples MC's block atlas (`minecraft:textures/atlas/blocks.png`, RGBA8) from a CPU copy, via `SoftwareModelTextureBakery.setupTexture()` → `IAtlasTextureReader`. The copy is taken once per renderer creation (world join, resource reload).
- *Today it's synchronous on both backends:*
  - GL: `glFinish()` + `glGetTextureImage`.
  - VK: `VkAtlasTextureReader` records a copy into Voxy's own command buffer, `vkQueueSubmit`s it straight onto MC's queue, and blocks on a fence.
  - That VK submit jumps ahead of MC's unsubmitted work. A mid-frame renderer creation after a resource reload would therefore read a stale atlas, which is why VK renderer creation is deferred to the next frame (`MixinMinecraftFrameStart` + `voxy$pendingCreate` in `MixinLevelRenderer`).
- *How:*
  1. Make `IAtlasTextureReader` asynchronous: `void readAsync(GpuTexture atlas, int w, int h, Consumer<int[]> onReady)`.
  2. **VK implementation, public Blaze3D API only:** `buf = RenderSystem.getDevice().createBuffer(() -> "voxy atlas readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * 4)`, then `createCommandEncoder().copyTextureToBuffer(atlas, buf, 0, callback, 0)`. The atlas has `COPY_SRC`: `TextureAtlas.createTexture` uses usage 15. The copy lands in MC's own command stream, after MC's atlas upload.
  3. The callback runs on the render thread inside MC's `submit()` about one frame later, after the GPU has finished the copy. In it:
     - Read the ints in native byte order, as the current reader does with `MemoryUtil.memIntBuffer`: `try (var v = buf.map(true, false)) { v.data().order(ByteOrder.nativeOrder()).asIntBuffer().get(pixels); }`.
     - Then `buf.close()`. The mapping must be closed first, because Blaze3D throws when closing a mapped buffer.
     - Publish `pixels` to the rasterizer through a volatile field, then unpark the "Model factory processor" thread.
  4. Gate baking on the atlas: `ModelFactory.processAllThings()` returns early until the atlas is set. Bake requests just queue up meanwhile, and `RenderGenerationService` already retries sections whose models aren't baked yet (`IdNotYetComputedException`). LODs simply start appearing a frame or two later.
  5. **GL (maintenance mode):** keep `GlAtlasTextureReader` as it is. Its `readAsync` calls the existing synchronous read and invokes the callback immediately, so GL behaviour doesn't change. Upstream deliberately avoids Blaze3D there (`GlAtlasTextureReader.java:21`: *"doing it with b3d has some issues"*), which is one more reason to leave it alone.
- *Edge cases:*
  - The renderer can shut down before the callback fires (a quick world leave or reload). Check a closed flag and just free the buffer.
  - At game exit, MC's `commandEncoder.destroy()` still runs pending callbacks, so the callback must not touch freed Voxy objects.
  - The buffer is `w×h×4` bytes, which HD packs can push to tens of MB (a 4096×4096 atlas is 64 MB). It only lives for about a frame.
- *Payoff:*
  - No GPU stall on world join or reload.
  - Deletes `VkAtlasTextureReader`'s raw copy and its `VulkanGpuTexture` cast (Tier B).
  - Removes the atlas's direct `vkQueueSubmit` on MC's queue (D6).
- *Follow-up — decided 2026-09-25: keep deferred creation.* It was introduced because the synchronous readback could read a stale atlas, but it still does two jobs:
  - `CommandEncoder.copyTextureToBuffer` throws inside an open render pass, and a world join or reload can start renderer creation mid-frame. `Minecraft.renderFrame` HEAD is a point with no pass open.
  - Construction ends with `VkFrameCtx.flushImmediate()`, a direct submit on MC's queue. At the frame boundary it cannot jump ahead of MC's unsubmitted work.
  - The cost is LODs starting one frame later.
- *Done when:*
  - After a world join and a resource-pack reload, LOD block textures are correct on VK and MoltenVK.
  - The atlas no longer goes through `VkFrameCtx.flushImmediate()`.
  - GL behaves exactly as before.

### Phase 3 — Create textures through Blaze3D, keep binding them raw

**3.1 Model atlas as a `GpuTexture`**
- *How:*
  - `createTexture(USAGE_TEXTURE_BINDING|USAGE_COPY_DST, RGBA8_UNORM, w, h, 1, LAYERS)`, then `CommandEncoder.writeToTexture(tex, buf, mip, 0, x, y, w, h)` per mip.
  - Bind raw with `host.vkImageView(view)` in layout `GENERAL` (D1).
  - Delete the upload/transition half of `VkModelStore`. The GL `ModelStore` stays as it is.
  - Uploads go through MC's per-submit `TransientMemory`, so check peak per-frame upload size during heavy baking.
- *Gotcha:* the texture's init barrier is recorded into MC's current buffer at creation, so create it outside a Voxy segment (1.1).

**3.2 Depth-bound and main colour targets as `GpuTexture`s**
- *How:*
  - `depthBound`: `D32_FLOAT`, `RENDER_ATTACHMENT|TEXTURE_BINDING`.
  - `colour`: `RGBA8_UNORM`, `RENDER_ATTACHMENT|TEXTURE_BINDING`.
  - These stay in `GENERAL`, so stop transitioning them in `VkViewport`/`VkCompositor`/`VkBoundRenderer`.
- *Stay raw:* `colourSSAO` (storage) until 5.2; `depthStencil` (D32S8, and MC's init barrier only covers the depth aspect); the HiZ pyramid (storage, per-mip storage views).
- *Prerequisite for:* 4.1 and 4.2.

### Phase 4 — Blaze3D render passes

**4.1 Pilot: chunk-bounds renderer as a Blaze3D pass**
- *Shader:*
  - Port `chunkoutline/outline.vsh/.fsh` to MC's dialect: `#version 330`, a named std140 block, and a **per-instance vertex attribute** for the chunk position instead of the `ChunkPosBuffer` SSBO.
  - Ship it as `assets/voxy/shaders/core/<name>.vsh/.fsh`. MC's `ShaderManager` lists `shaders/` in every namespace, so the default `ShaderSource` finds it as `voxy:core/<name>`. Don't depend on `precompilePipeline(pipeline, customSource)`: `ShaderManager` calls `clearPipelineCache()` on resource reload, and the pipeline then recompiles from the default source.
- *Pipeline:*
  - `DepthStencilState(reverseZ ? LESS_THAN : GREATER_THAN, true)` (the "further" test).
  - No colour: `withUnusedColorTargetState(0)`.
  - `TRIANGLES`, cull off, `withVertexBinding(0, instanceFormat)` using `VertexFormat.builder(1)`.
- *Pass:*
  - `RenderPassDescriptor.withUnusedColorAttachment().withDepthAttachment(depthBoundView, inverseClearDepth)`.
  - The index buffer is a `GpuBuffer(USAGE_INDEX)` of the 36 cube indices.
  - The `StreamedBoundStore` storage becomes `GpuBuffer(USAGE_VERTEX|USAGE_COPY_DST)` filled via `writeToBuffer`/`TransientMemory`.
- *Done when:* `VkBoundRenderer` is replaced by the Blaze3D class, and LOD fragments behind vanilla terrain are still discarded on VK and MoltenVK. GL's `BoundRenderer` stays. Switching GL to the new class is optional, and only if it's a drop-in.
- *Why first:* it's small (about 150 lines), self-contained, depth-only, and proves the whole toolchain: MC-dialect shaders, custom `ShaderSource`, Blaze3D textures bound raw elsewhere, and segment splicing.

**4.2 Composite pass into MC's framebuffer via Blaze3D**
- *How:*
  - `RenderPipeline`: `fullscreen` vert + `blit_texture_depth_cutout` frag, ported to MC dialect.
  - Blend `BlendFunction.TRANSLUCENT`, which equals today's `SRC_ALPHA/ONE_MINUS_SRC_ALPHA/ONE/ONE_MINUS_SRC_ALPHA`.
  - Depth closer-or-equal plus write, `TRIANGLE_STRIP` with 4 vertices.
  - Samplers `depthTex`, `colourTex`; UBO `CompositeParams`; colour target format taken from MC's colour view.
  - The render pass targets `RenderTarget.getColorTextureView()/getDepthTextureView()`.
- *Prerequisites:* the sampled colour must be a Blaze3D texture (needs 5.2) and the sampled depth must be one too (needs 5.1, or a D32 depth copy).
- *Payoff:* the one pass that writes MC's framebuffer follows MC's layouts, barriers and formats automatically, including a future HDR main target. It also deletes `VkFrameHost.mcImageBarrier`. The GL blit stays.

### Phase 5 — Research and decisions that unlock more

**5.1 Stencil-free LOD masking**
- *Today:* setup writes stencil 0 where vanilla depth exists, and terrain draws require stencil == 1.
- *Alternative:* at vanilla pixels, write the *nearest* depth value, so every LOD fragment fails the closer-or-equal test there. The effect is the same with no stencil.
- *Evaluate:*
  - (a) HiZ/traversal: nodes hidden behind vanilla get culled, which should be harmless because they were masked anyway.
  - (b) SSAO halos at the vanilla/LOD seam.
  - (c) Translucent LODs.
- *If viable:* Voxy depth becomes `D32_FLOAT` (Blaze3D-creatable), the setup pass becomes Blaze3D-expressible (its push constant becomes a UBO), and 4.2's depth input is unblocked.

**5.2 SSAO as a fullscreen fragment pass (VK).** Drops the storage-image requirement on `colourSSAO` (needed for 3.2 and 4.2) and lets `VkSSAO` become a Blaze3D pass. GL's `SSAO` stays. Needs a performance comparison against compute on desktop and on the M2 Max.

**5.3 Long-term fate of the GL backend — decided: maintenance mode until MC drops OpenGL** (see [Decisions](#decisions-2026-09-25)). When GL is finally removed:
- Delete `client/core/gl`, the GL column of [§4.2](#42-glvk-duplicate-pairs), `Capabilities`, and the GL-only mixins (the GL branch of `MixinDefaultChunkRenderer`, `MixinGlDebug`, nvidium).
- The backend-neutral seams (`IDeviceBuffer`, `IRenderList`, `Abstract{Upload,Download}Stream`, …) exist only for GL/VK parity. Once GL is gone they can collapse into their VK implementations.
- Decide what happens to the GL-only Iris integration before then.

### Options considered but not recommended yet

- **O1 — Storage usage through Blaze3D.** Two tiny mixins on `VulkanConst.bufferUsageToVk` / `textureUsageToVk` could map a Voxy-only usage bit to `STORAGE_BUFFER`/`STORAGE_IMAGE`. That would let *all* of the VK path's resources be created via `GpuDevice` (VMA, labels, deferred destroy), while still being bound raw. It adds new Tier C coupling, so only reach for it if 1.4 + 3.x still leave too much custom resource code.
- **Texel buffers instead of SSBOs in raster shaders.** The terrain geometry buffer (up to 2 GB) exceeds `maxTexelBufferElements` on many devices, so this doesn't work for terrain. It *does* work for small inputs like chunk positions (alternative to the instanced attribute in 4.1).
- **Hooking MC's frame graph instead of Sodium.** Sodium cancels vanilla `renderGroup` at HEAD (D11), so Voxy has to run after Sodium's opaque draw. The Sodium hook stays.

### Stays raw Vulkan until Blaze3D grows (26.2 blockers)

| Work | Missing Blaze3D feature |
|---|---|
| All 13 compute pipelines | compute pipelines / dispatch |
| Terrain opaque/translucent/temporal draws | storage buffers in VS/FS, indirect-count, stencil, D32S8 attachments, `shaderInt64` |
| Raster occlusion cull | fragment-shader storage writes |
| Geometry, metadata, node, visibility, draw-command buffers | storage-buffer usage |
| HiZ pyramid | storage images + nearest-mip sampling |
| Extra device features (`MixinVulkanBackend`) | no API to request features; the mixin is unavoidable |

---

## 6. Watchlist — Blaze3D features that would unlock more

Run these against freshly decompiled sources on every MC bump. Any hit means a row in the table above may move.

```bash
B=/tmp/mc-src/com/mojang/blaze3d
grep -n "COMPUTE" $B/shaders/ShaderType.java                                   # compute shaders
grep -n "STORAGE" $B/buffers/GpuBuffer.java $B/textures/GpuTexture.java $B/shaders/UniformType.java
grep -in "stencil" $B/pipeline/DepthStencilState.java                          # stencil state
grep -in "indirectcount\|dispatch" $B/systems/RenderPass.java $B/systems/CommandEncoder.java
grep -n "not yet supported" $B/systems/GpuDevice.java $B/systems/CommandEncoder.java  # array textures
grep -n "depthAttachmentFormat\|stencilAttachmentFormat" $B/vulkan/VulkanRenderPipeline.java
grep -n "pushConstant\|PushConstant" -r $B/pipeline $B/systems
```

---

## 7. Porting checklist (per Minecraft version)

1. `./gradlew genSources`, then extract `com/mojang/blaze3d` ([§1](#1-how-this-was-checked-and-how-to-re-check)).
2. **Tier C hooks.** Confirm that the following still exist with the same shape; mismatches crash the game (`defaultRequire: 1`), except `MixinVulkanBackend`:
   - `VulkanBackend.createDevice(Collection, VulkanPhysicalDevice, Set)`
   - `VulkanDevice.<init>` / `close`
   - `Minecraft.renderFrame`
   - Sodium `SodiumWorldRenderer.drawChunkLayer(ChunkSectionLayerGroup, ChunkRenderMatrices, double, double, double, GpuSampler)`, and on the GL path `DefaultChunkRenderer.render`
3. **Tier B getters.** Confirm `VulkanDevice.{instance,vkDevice,graphicsQueue,createCommandEncoder,vma}`, `VulkanCommandEncoder.{signalSemaphore,execute,allocateAndBeginTransientCommandBuffer,queueForDestroy}`, `VulkanGpuTexture.vkImage`, `VulkanGpuTextureView.vkImageView`, `VulkanGpuSampler.vkSampler`, `VulkanConst.toVk(GpuFormat)`, `VulkanFeature`, and `VulkanBackend.VK10/VK12_FEATURES_STRUCT`.
4. **Tier D contracts.** D5 is checked automatically at device creation; if the log says *"Minecraft's Vulkan device lacks features Voxy requires"*, start there. Re-read the code behind the rest (the list is on `IVkHost`):
   - `VulkanGpuTexture` constructor (layout)
   - `VulkanCommandEncoder.memoryBarrier` and its callers
   - `VulkanCommandEncoder.submit` (in-flight count)
   - `VulkanBackend.REQUIRED_DEVICE_EXTENSIONS/FEATURES`
   - `VulkanRenderPipeline.compile` (depth format)
   - `GameRenderer.levelLightmap`
   - the block atlas creation usage flags
   - `DepthStencilState.DEFAULT`
5. **Public API churn.** Diff `CommandEncoder`, `RenderPass`, `RenderPipeline`, `GpuDevice`, `GpuBuffer`/`GpuTexture` usage constants, `BindGroupLayout` and `DeviceInfo` against the previous version.
6. Run the watchlist in §6 and update §4/§5 if anything unlocked.
7. **Test:**
   - VK (Prefer Vulkan) with `--vulkanValidation`, plus a GL (Default) regression check.
   - The built jar in the Modrinth App on the M2 Max (MoltenVK).
   - A desktop GPU with `drawIndirectCount`.
   - World join/leave twice and a resource-pack reload (renderer re-creation and atlas readback).

---

## 8. Open questions for you

Two questions are already settled (see [Decisions](#decisions-2026-09-25)): GL is in maintenance mode, and the atlas readback goes async.

1. **Is a temporary visual difference at the vanilla/LOD seam acceptable** while evaluating stencil-free masking (5.1)?
2. **Tolerance for new internal mixins:** is O1 acceptable if it deletes a lot of custom resource code, or is "fewest Blaze3D-internal hooks" the priority?

---

## Appendix A — VK path file inventory

| File (under `client/`) | Lines | Blaze3D usage (tier) | What it does itself | Step |
|---|---|---|---|---|
| `core/vk/IVkHost.java` | 34 | — | host seam | 0.1 |
| `core/vk/MinecraftVkHost.java` | 38 | `RenderSystem.tryGetDevice` (A) | detects MC-on-Vulkan | — |
| `core/vk/MinecraftVkHostAdapter.java` | 53 | `VulkanDevice`, `VulkanCommandEncoder` (B, C) | handles, frame cmd, semaphore signal | 0.1 ✅, 1.1 ✅, 1.2 (now also holds the feature request and handle lookups) |
| `core/vk/VulkanBackend.java` | 71 | — | adoption lifecycle | — |
| `core/vk/VulkanContext.java` | 238 | — | caps (subgroups, limits, formats), cmd pool, pipeline cache, samplers, set-layout cache, memory types | 1.4 ✅, 1.5, 2.2 ✅ (name and two limits now from `DeviceInfo`) |
| `core/vk/VkDeviceFeatures.java` | 97 | `VulkanFeature`, `VulkanBackend` statics (B) | extra feature request/record | 0.1 |
| `core/vk/VkFrameCtx.java` | 337 | via `IVkHost` | recording target, retirement, deferred destroy, barriers, immediate submit | 1.1–1.3 ✅, 2.1 ✅ (segment splits for timing) |
| `core/vk/VkGpuTiming.java` | new | timestamp queries (A) | F3 `GpuTime` for the VK path | 2.1 ✅ |
| `core/vk/VkBuffer.java` | 141 | — | buffer + dedicated memory | 1.4 |
| `core/vk/VkImage2D.java` | 179 | — | image + memory + views + layout tracking | 1.4, 3.x |
| `core/vk/VkShaderPipeline.java` | 330 | — | compute+graphics pipelines, push descriptors/constants, stencil | stays (compute); 4.x replaces two graphics users |
| `core/vk/ShadercCompiler.java` | 55 | — | GLSL→SPIR-V incl. compute | stays |
| `core/vk/VkShaderSource.java` | 98 | — | `#import` expansion, version forcing, define injection | stays |
| `core/vk/VkUploadStream.java` | 167 | — | persistently mapped staging + arena | 1.2 |
| `core/vk/VkDownloadStream.java` | 185 | — | readback arena + callbacks | 1.2 |
| `core/vk/VkAtlasTextureReader.java` | 72 | `GpuTexture` (A), `VulkanGpuTexture` (B) | image→buffer copy + immediate submit | deleted in 2.3 ✅ (now `core/model/bakery/Blaze3DAtlasTextureReader.java`, public API only) |
| `core/vk/VkCmd.java`, `VkUtil.java` | 42, 13 | — | helpers | — |
| `core/vk/render/VkRenderCore.java` | 390 | `RenderTarget` (A) | orchestration, resource ownership | 1.1 |
| `core/vk/render/VkFrameHost.java` | 63 | `GpuTextureView` (A); `VulkanGpuTexture/View`, `VulkanConst` (B) | MC handle extraction, barriers on MC images | 0.1, 4.2 |
| `core/vk/render/VkTerrainRenderer.java` | 506 | — | prep/cmdgen/prefix/translucent compute, raster cull, terrain indirect(-count) draws | stays |
| `core/vk/render/VkTraversal.java` | 280 | — | hierarchical traversal compute | stays |
| `core/vk/render/VkNodeCleaner.java` | 168 | — | cleaner compute | stays |
| `core/vk/render/VkNodeGpuOps.java` | 104 | — | scatter / multi-memcpy compute | stays |
| `core/vk/render/VkHiZ.java` | 193 | — | HiZ pyramid compute | stays |
| `core/vk/render/VkSSAO.java` | 189 | — | SSAO compute | 5.2 |
| `core/vk/render/VkCompositor.java` | 313 | `GpuTextureView` (A) | stencil setup + composite into MC target | 4.2, 5.1 |
| `core/vk/render/VkBoundRenderer.java` | 165 | — | depth-only instanced chunk-bound raster | 4.1 |
| `core/vk/render/VkModelStore.java` | 134 | — | model/colour SSBOs + atlas upload | 3.1 |
| `core/vk/render/VkViewport.java` | 113 | — | per-viewport buffers + offscreen targets | 3.2 |
| `core/vk/render/VkSectionGeometryData.java` | 94 | — | geometry + metadata buffers | 1.4 |
| `mixin/vk/AccessorVulkanCommandEncoder.java` | 16 | private field (C) | — | deleted in 1.1 ✅ |
| `mixin/vk/MixinVulkanBackend.java` | 35 | private method (C) | feature request | 0.1 (keep) |
| `mixin/vk/MixinVulkanDevice.java` | 60 | ctor/close (C) | host register/teardown | keep |
| `mixin/vk/MixinMinecraftFrameStart.java` | 30 | `Minecraft.renderFrame` | deferred renderer creation | keep |
| `mixin/vk/MixinSodiumOpaqueVkFrame.java` | 46 | Sodium internal | frame entry point | keep |
