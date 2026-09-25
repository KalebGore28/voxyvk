# VoxyVK: Vulkan Bug Audit (Opus 5)

**Branch / commit audited:** `pr-614` @ `fa478b6c`
**Date:** 2026-09-25
**Scope:** Everything Vulkan-related:
- `client/core/vk/**` and `client/mixin/vk/**` (~4.6k lines)
- the Vulkan-facing refactors in shared code (upload/download streams, model store/factory, `AsyncNodeManager`, `Viewport`, bound store)
- every `VOXY_VULKAN` shader branch and the VK-only shaders
- the build wiring for LWJGL Vulkan and shaderc

The OpenGL path, Iris, and the storage backends are out of scope except where they touch the VK path.

**Method:**
1. I read every VK source file in full and diffed `pr-614` against its upstream merge base (`534d58ec`).
2. I checked Minecraft 26.2's Blaze3D Vulkan backend and Sodium 0.9.2 by disassembling the exact jars Loom resolved for this project (`javap -c`). Every "MC does X" statement below comes from that bytecode, not from memory.
3. Nothing was run. This is a static audit.
4. No repository files were changed. This report is the only file I created.

Line numbers refer to the commit above.

---

## TL;DR

1. **Voxy disagrees with Minecraft about image layouts.** MC 26.2 keeps *every* texture in `VK_IMAGE_LAYOUT_GENERAL` forever. Voxy transitions MC's depth, colour, lightmap, and block atlas as if they were in `*_ATTACHMENT_OPTIMAL` / `SHADER_READ_ONLY_OPTIMAL`, and it *leaves them there*, so MC's own later passes become invalid too. MoltenVK mostly ignores layouts, which is very likely why this "runs mostly fine" on a Mac but is a real risk on Windows/Linux drivers. (VK-01)
2. **Voxy uses device features MC never enabled.** MC creates its `VkDevice` with a zeroed feature struct and switches on only its own list. Voxy then uses `drawIndirectCount`, `drawIndirectFirstInstance`, `shaderInt64`, `fragmentStoresAndAtomics`, and `vertexPipelineStoresAndAtomics`. On MoltenVK it also uses `VkEvent` without the portability-subset `events` feature. (VK-02)
3. **Teardown destroys GPU pipelines while frames may still be in flight.** `VkRenderCore.shutdown()` calls `AsyncNodeManager.stop()` before idling the device, and `stop()` destroys two VK pipelines immediately. (VK-03)
4. **Voxy's frame doesn't end with a barrier, but MC's command stream relies on one.** MC ends every op with a full memory barrier and never puts one *before* a render pass. Voxy's composite writes MC's colour/depth, and nothing orders MC's next pass after those writes. (VK-04)
5. **The subgroup capability query is broken.** It chains a *properties* struct into `vkGetPhysicalDeviceFeatures2`, so it always reads zero and every subgroup fast path is dead code. Fixing it naively switches on three latent shader/Java bugs. (VK-05, VK-05a–c)

---

## Background: how Minecraft 26.2's Vulkan backend behaves (verified from bytecode)

These facts drive most of the findings.

| Topic | What MC 26.2 does | Where I saw it |
|---|---|---|
| Command recording | One persistent `VulkanCommandEncoder` (`VulkanDevice.createCommandEncoder()` just returns a field). Its command buffer is started lazily by `commandBuffer()` and stored in the private field `currentCommandBuffer`. | `VulkanDevice`, `VulkanCommandEncoder.commandBuffer()` |
| Submission | `submit()` ends the command buffer, signals a timeline semaphore (`submitSemaphore`/`currentSubmitIndex`), then **waits for submit `index-2` with a 5 s timeout** (it throws with GPU checkpoints on timeout) and resets that slot's command pool. | `VulkanCommandEncoder.submit()` |
| Rendering model | Dynamic rendering (`vkCmdBeginRenderingKHR`) plus sync2. **Every** `submitRenderPass()`, copy, and clear *ends* with `vkCmdPipelineBarrier2(ALL_COMMANDS, MEMORY_READ\|WRITE → ALL_COMMANDS, MEMORY_READ\|WRITE)`. `createRenderPass()` emits **no** leading barrier. | `VulkanCommandEncoder.memoryBarrier`, `submitRenderPass`, `createRenderPass` |
| Image layouts | Every texture is transitioned `UNDEFINED → GENERAL` once at creation. Attachments, descriptors, copies, and clears all use `GENERAL`, and **MC never transitions again**. | `VulkanGpuTexture.<init>`, `createRenderPass` (`imageLayout=1`), `VulkanRenderPass` descriptors, `vkCmdCopy*`/`vkCmdClear*` |
| Enabled device features | A **calloc'd** `VkPhysicalDeviceFeatures2`, with only these set: `multiDrawIndirect`, `fillModeNonSolid`, `samplerAnisotropy`, `shaderDrawParameters`, `timelineSemaphore`, `hostQueryReset`, `synchronization2`, `dynamicRendering`, `vertexAttributeInstanceRateDivisor`, plus `multiDraw` when supported. **Everything else is disabled.** | `VulkanBackend.REQUIRED_DEVICE_FEATURES`, `VulkanBackend.createDevice(...)` |
| Required extensions | `VK_KHR_dynamic_rendering`, `VK_KHR_push_descriptor`, `VK_KHR_synchronization2`, `VK_EXT_vertex_attribute_divisor`, `VK_KHR_swapchain`. `VK_KHR_portability_subset` is enabled on MoltenVK, but none of its features are. | `VulkanBackend.REQUIRED_DEVICE_EXTENSIONS` |
| Main render target | `RGBA8_UNORM` colour and `D32_FLOAT` depth. Views are single-aspect (colour or depth). | `MainTarget`, `RenderTarget`, `VulkanGpuTextureView` |
| Viewport | Positive height: `(0, 0, w, h, 0..1)`, the same convention Voxy uses. | `VulkanRenderPass.<init>` |
| Queue use | Only the render thread submits. `VulkanQueue` has no locking. | `VulkanQueue`, `VulkanQueue$Submission` |
| Public encoder API | `execute(VkCommandBuffer)`, `signalSemaphore(sem, value, stage)`, `waitSemaphore(...)`. Each safely ends MC's current command buffer and appends to the pending submission. | `VulkanCommandEncoder` |
| Shutdown order | `GameRenderer.close()` → `LevelRenderer.close()` (Voxy tears down here) → … → `RenderSystem.shutdownRenderer()`. | `Minecraft.close()` |
| Bundled LWJGL | MC 26.2 already ships `lwjgl-vulkan` (with macOS/MoltenVK natives), `lwjgl-shaderc`, `lwjgl-vma`, and `lwjgl-spvc`, all 3.4.1. | Loom's `mojang_minecraft_info.json` |

---

## Summary table

Severity: **High** means a spec violation or race that can corrupt rendering or crash in normal use on at least one platform. **Medium** means incorrect in less common paths, or a robustness gap. **Low** means latent, cleanup, or portability risk. **Info** means a note.
Platforms: **Desk** = native Vulkan on Windows/Linux (NVIDIA/AMD/Intel). **Mac** = MoltenVK.

| ID | Sev | Platforms | Title |
|---|---|---|---|
| VK-01 | High | Desk (masked on Mac) | MC images are always `GENERAL`; Voxy uses and leaves other layouts |
| VK-02 | High | Desk + Mac | Voxy uses device features MC never enabled |
| VK-03 | High | Desk | Node-manager pipelines destroyed before device idle / after device loss |
| VK-04 | Medium | Desk | No trailing barrier after Voxy's frame (MC relies on one) |
| VK-05 | Medium | Both | Subgroup properties queried via the *features* query, so always zero |
| VK-05a | Medium (latent) | Both | `inital3_vk.comp` cross-subgroup scan data race and OOB |
| VK-05b | Medium (latent) | Both | `hiz_subgroup.comp` needs CLUSTERED (gate only checks ARITHMETIC) and hard-codes max, which is wrong under reverse-Z |
| VK-05c | Medium (latent) | Both | HiZ level sizes wrong after the subgroup pass |
| VK-06 | Medium | Both | `waitIdleRetireAll()` treats the unsubmitted current frame as finished |
| VK-07 | Medium/Low | Desk | Barrier gaps (request-buffer WAR, SSAO compute vs. fragment masks, upload scopes) |
| VK-08 | Medium | Both | An exception mid-recording leaves MC's command buffer invalid |
| VK-09 | Medium | Both | A failed `VkRenderCore` construction wedges Voxy until restart |
| VK-10 | Medium | Both | 2 GiB geometry SSBO vs. device limits; handle leak on allocation retry |
| VK-11 | Medium | Both | "Immediate" submissions run ahead of MC work already recorded this frame |
| VK-12 | Low | Both | `VulkanBackend.shutdown()` never called; Voxy objects outlive MC's device |
| VK-13 | Low | Both | Teardown delivers download callbacks into a stopped node manager (leak) |
| VK-14 | Low | Both | Invalid mutable-format list on the D32S8 image; D32S8 hard-coded |
| VK-15 | Low (latent) | Both | Descriptor-set-layout cache is keyed by a hash |
| VK-16 | Low (latent) | Both | `VkShaderSource` hoists `#extension` out of `#ifdef` guards |
| VK-17 | Low (latent) | Both | `VkDownloadStream.waitDiscard()` fires callbacks (GL discards) |
| VK-18 | Low (latent) | Both | `ModelFactory` refactor dropped the `hasMips` guard (native over-read) |
| VK-19 | Low | Both | Backend detection trusts a registered host over the live device |
| VK-20 | Low | Both | Build bundles LWJGL modules MC already ships |
| VK-21 | Info | Both | Performance notes |
| VK-22 | Info | n/a | Stale or misleading comments |
| D-1 | Design | Both | Use MC's public `execute()`/`signalSemaphore()` instead of the private command buffer + `VkEvent` |

---

## Detailed findings

### VK-01: MC's images are always `VK_IMAGE_LAYOUT_GENERAL`, but Voxy transitions and uses them as other layouts

**Severity:** High. **Platforms:** desktop Vulkan (MoltenVK largely ignores layouts, so it's masked on Mac).

**Where**

- `VkFrameHost.java:34-40`: the comment says MC's attachments "live in ATTACHMENT_OPTIMAL otherwise". `transitionMcImage` is at `:41-88`.
- `VkCompositor.java`:
  - `:143-144`: MC depth transitioned `DEPTH_STENCIL_ATTACHMENT_OPTIMAL → SHADER_READ_ONLY_OPTIMAL`
  - `:176`: descriptor layout `SHADER_READ_ONLY_OPTIMAL`
  - `:189-190`: transitioned back to `DEPTH_STENCIL_ATTACHMENT_OPTIMAL` and **left there**
  - `:262-271`: the composite attaches MC colour as `COLOR_ATTACHMENT_OPTIMAL` and MC depth as `DEPTH_STENCIL_ATTACHMENT_OPTIMAL`
- `VkSSAO.java:145-146, 155, 162-163`: the same pattern for MC depth in BETTER/BEST SSAO.
- `VkTerrainRenderer.java:424`: MC's lightmap is bound with `SHADER_READ_ONLY_OPTIMAL`.
- `VkAtlasTextureReader.java:22-24, 39-41, 51`: assumes MC's block atlas is in `SHADER_READ_ONLY_OPTIMAL`. It transitions `SRO → TRANSFER_SRC → SRO`, so after world load the atlas is left in `SHADER_READ_ONLY_OPTIMAL`.

**What's wrong**

MC transitions each texture to `GENERAL` once and declares `GENERAL` everywhere afterwards (attachments, descriptors, copies, clears). So:

1. Every Voxy `oldLayout` for an MC image is wrong.
2. Every Voxy descriptor/attachment layout for an MC image is wrong.
3. Because Voxy *leaves* MC's depth in `DEPTH_STENCIL_ATTACHMENT_OPTIMAL` (every frame) and the block atlas in `SHADER_READ_ONLY_OPTIMAL` (after world load), **MC's own later passes**, which declare `GENERAL`, now use the wrong layout too. Voxy makes MC's rendering invalid, not just its own.

**Impact**

- This is undefined behaviour on every frame.
- On desktop drivers, layouts can decide compression and metadata handling (for example depth HTILE or colour DCC on AMD). A transition from the wrong old layout can produce corrupted depth (holes or z-fighting at the LOD/vanilla seam), wrong SSAO near the seam, or garbage when sampling the atlas.
- On MoltenVK it's invisible, which is consistent with "runs mostly fine" on a Mac.

**Suggested fix**

- Treat MC-owned images as permanently `GENERAL`. Replace the transitions with plain memory barriers (image barriers with `oldLayout = newLayout = GENERAL`, or a global `VkMemoryBarrier`).
- Use `GENERAL` in `VkRenderingAttachmentInfo.imageLayout` for MC colour/depth, and in the descriptors for MC depth and the lightmap.
- In `VkAtlasTextureReader`, copy with `srcImageLayout = GENERAL` and don't transition at all.
- Give `transitionMcImage` explicit stage masks (see VK-07b), or delete it.

---

### VK-02: Voxy uses device features that MC never enabled

**Severity:** High. **Platforms:** desktop and Mac.

MC's `VulkanBackend.createDevice(...)` starts from a zeroed `VkPhysicalDeviceFeatures2` and sets only its own list (see Background). Voxy adopts that device but checks *physical-device support*, or nothing at all:

| Voxy usage | Required feature | Enabled by MC? | Where |
|---|---|---|---|
| `vkCmdDrawIndexedIndirectCount` on desktop | `drawIndirectCount` (VK 1.2) | **No** | Support is queried at `VulkanContext.java:72-79` and used at `VkTerrainRenderer.java:429-437`. The comment at `:439-442` says MC doesn't enable it. |
| Per-draw data in `firstInstance` (`BASE_INSTANCE = gl_InstanceIndex` → `positionBuffer[BASE_INSTANCE]`) of GPU-written indirect commands | `drawIndirectFirstInstance` | **No** | `quads3.vert:17-21, 61` |
| 64-bit quads (`#define Quad uint64_t`, `GL_ARB_gpu_shader_int64`) | `shaderInt64` | **No** | `lod/quad_format.glsl:2`, `quads3.vert:2-5`, `cull/raster.vert:2`, `cmdgen.comp:2`, `buildtranslucents.comp:2` |
| SSBO write from the fragment stage | `fragmentStoresAndAtomics` | **No** | `lod/gl46/cull/raster.frag:3-14` |
| Writable SSBO declared in the vertex stage (no `readonly`, so no `NonWritable`) | `vertexPipelineStoresAndAtomics` | **No** | `cull/raster.vert:3` (`#define VISIBILITY_ACCESS` is empty) |
| `vkCreateEvent` on MoltenVK, where `VK_KHR_portability_subset` is enabled | portability-subset `events` | **No** (MC enables the extension but none of its features) | `VkFrameCtx.java:193-203` |

**Impact**

- All of these are invalid usage and undefined behaviour.
- They will flood validation output, which hides the errors you actually want to find when debugging with layers.
- Current NVIDIA/AMD/Intel drivers and MoltenVK generally don't gate these at runtime, which is why it works today. Drivers that do honour the feature bits will misbehave. For example, a driver that forces `firstInstance = 0` would draw every quad at position index 0.

**Suggested fix**

- **Enable what Voxy needs at MC's device creation.** Mixin into `com.mojang.blaze3d.vulkan.VulkanBackend#createDevice(Collection, VulkanPhysicalDevice, Set<VulkanFeature>)` (private static) and add Voxy's features to the set when supported. MC already has `VulkanFeature`/`VulkanPNextStruct` plumbing for the VK 1.0/1.1/1.2 feature structs. Record what was *actually enabled* and gate every code path on that, not on physical-device support.
- If a feature is unavailable:
  - The fixed-count path already exists for `drawIndirectCount`.
  - For `drawIndirectFirstInstance`, index per-draw data with `gl_DrawID` (DrawIndex). That needs `shaderDrawParameters`, which MC **does** enable.
  - Keep `VISIBILITY_ACCESS readonly` in `raster.vert` under `VOXY_VULKAN`.
- For events on MoltenVK, either enable the portability `events` feature or switch retirement to a timeline semaphore (see D-1). `timelineSemaphore` *is* enabled.

---

### VK-03: Node-manager GPU pipelines are destroyed before the device is idle, and even after MC's device is gone

**Severity:** High. **Platforms:** desktop (Metal retains objects referenced by in-flight command buffers, so Mac is likely safe).

**Where**

- `VkRenderCore.java:254-267`: the block commented "CPU-only stop first" calls `this.nodeManager.stop()` (`:263`).
- `AsyncNodeManager.java:740`: `stop()` ends with `this.gpuOps.free()`.
- `VkNodeGpuOps.java:99-103`: frees the `scatter` and `memcpy` pipelines.
- `VkShaderPipeline.java:304-314`: calls `vkDestroyPipeline`/`vkDestroyPipelineLayout` **immediately**, not deferred.
- The device idle (`frameCtx.waitIdleRetireAll()`, `:280`) and the `deviceAlive` guard (`:275`) only run **after** `stop()`.

**Impact**

- The scatter/memcpy pipelines are used in most frames that have geometry or node updates, and MC keeps up to 2 submissions in flight.
- So on world unload, dimension change, `/voxy reload`, F3+A, or a video-settings reload (`LevelExtractor.allChanged` → recreate), those pipelines can be destroyed while the GPU is still executing them. That's a GPU use-after-free, and a likely device loss on drivers that free shader memory eagerly.
- In the "MC's device already destroyed" case the guard was written for, these two `vkDestroy*` calls still run on the dead device.

**Suggested fix**

- Split `AsyncNodeManager.stop()` into "join thread / drain queues" and "free GPU ops", and call the second part after `waitIdleRetireAll()`.
- More robust: route `VkShaderPipeline.free()` through `VkFrameCtx`'s deferred-destroy queue, like buffers and images, so it's safe from any call site.

---

### VK-04: Voxy's frame doesn't end with a barrier, but MC's command stream assumes every operation does

**Severity:** Medium. **Platforms:** desktop (likely masked by hardware raster ordering or Metal hazard tracking, but not guaranteed).

**Where**

- Voxy's last pass, `VkCompositor.composite()` (`VkCompositor.java:277-288`), writes MC's colour and depth.
- After that, `VkRenderCore.renderFrame` (`:222-229`) only records transfer/compute work and then `vkCmdSetEvent` in `VkFrameCtx.endFrame()` (`VkFrameCtx.java:88-98`).
- None of those trailing barriers include `COLOR_ATTACHMENT_OUTPUT` or `LATE_FRAGMENT_TESTS` in their *source* scope. A `FRAGMENT_SHADER` source stage does not cover those logically later stages.

**Why it matters**

MC's invariant is "every op ends with a full barrier; nothing before a render pass". MC's next passes on the same attachments (entities, block entities, translucent terrain, particles) therefore have **no dependency** on Voxy's composite writes. The result is WAW/RAW races on MC's colour and depth, which can show up as depth-test flicker at LOD/vanilla boundaries.

**Suggested fix**

- At the end of `endFrame()` (before `vkCmdSetEvent`), record the same global barrier MC uses: `ALL_COMMANDS, MEMORY_WRITE → ALL_COMMANDS, MEMORY_READ|MEMORY_WRITE`.
- Once VK-01 is fixed, no layout restore is needed here.

---

### VK-05: Subgroup properties are queried through the *features* query, so they are always zero

**Severity:** Medium. **Platforms:** both.

**Where:** `VulkanContext.java:81-91`. It chains `VkPhysicalDeviceSubgroupProperties` into `VkPhysicalDeviceFeatures2` and calls `vkGetPhysicalDeviceFeatures2`. A properties struct must be chained into `VkPhysicalDeviceProperties2` and queried with `vkGetPhysicalDeviceProperties2`.

**Impact**

- The pNext chain is invalid. Drivers ignore the unknown struct, so it stays zero-initialized, which gives `subgroupArithmetic = false` and `subgroupSize = 0` on **every** device.
- Quick check: the startup log line from `VulkanContext.java:66-69` should read `subgroupSize=0`.
- The subgroup HiZ (`VkHiZ.java:47`), the subgroup prefix sum (`VkTerrainRenderer.java:116-118`), and the 64-thread traversal (`VkTraversal.java:43-47`) never run. The comment at `VkTerrainRenderer.java:113-115` claiming these run "virtually everywhere" is therefore wrong.

> ⚠️ **Fixing this query switches on three latent bugs (VK-05a/b/c).** Fix them in the same change, or keep the gates forced off until you do.

#### VK-05a: `inital3_vk.comp` cross-subgroup scan is racy and can index out of bounds

- `util/prefixsum/inital3_vk.comp:65-70`: the cross-subgroup scan is guarded by `subInv < numSubgroups`, **not** `subgroupId == 0`. So *every* subgroup reads `subgroupSums[]`, scans it, and writes it back. Nothing separates one subgroup's writes from another subgroup's reads, so the prefix sums are nondeterministic. That corrupts translucent draw ordering and offsets.
- `:68`: `subgroupBarrier()` is inside non-uniform control flow.
- `:27`: `subgroupSums[8]` assumes subgroupSize ≥ 32. With 8- or 16-wide subgroups (for example Intel with a varying SIMD width, or many mobile GPUs) it writes out of bounds.
- **Fix:** do the cross-subgroup scan in one subgroup only (`subgroupId == 0 && subInv < numSubgroups`), size the shared array for the worst case, and keep barriers in uniform control flow.

#### VK-05b: `hiz_subgroup.comp` needs CLUSTERED (the gate only checks ARITHMETIC) and reduces with the wrong operator under reverse-Z

- The shader uses `subgroupClusteredMax` (`hiz/vk/hiz_subgroup.comp:20, 94, 99`). `VulkanContext.java:51` only tests `VK_SUBGROUP_FEATURE_ARITHMETIC_BIT`, and never checks that `supportedStages` includes COMPUTE.
- ~~As far as I know, MoltenVK does not advertise `VK_SUBGROUP_FEATURE_CLUSTERED_BIT`.~~ **Correction (verified on an Apple M2 Max, 2026-09-25):** the MoltenVK bundled with MC 26.2 (LWJGL 3.4.1) *does* advertise CLUSTERED (`supportedOperations = 0x6ff`, compute stage included). So once VK-05 is fixed, this path **runs on Macs too**, which makes the next bullet matter there.
- **Found while fixing:** the shader hard-codes `subgroupClusteredMax`/`subgroupMax`, but `REDUCTION` is `min` under reverse-Z, and MC 26.2 is reverse-Z (`DepthStencilState.DEFAULT` uses `GREATER_THAN_OR_EQUAL`). The subgroup levels would keep the *nearest* depth, making the HiZ non-conservative (over-culling). Executed on the M2 Max against a CPU reference: the original shader was wrong in 10/10 reverse-Z runs, the fixed one correct in 80/80.
- **Fix:** gate on `ARITHMETIC | CLUSTERED` and `supportedStages & COMPUTE`, and use `subgroupClusteredMin`/`subgroupMin` under `USE_REVERSE_Z`.

#### VK-05c: HiZ level sizes are wrong after the subgroup pass

- `VkHiZ.java:141-144`: the subgroup dispatch fills mips 1..6, and the per-level loop then continues at level 7. But it sets `cw/ch = width>>6` (that's level 6's size) and `sw/sh = width>>5`.
- `hiz_reduce.comp` computes its UVs from `dstSize`, so levels 7+ are computed at twice their real size, and each is built from only the top-left quarter of the level above. The coarse HiZ is wrong, so occlusion culling of large nodes is wrong (holes or over-draw).
- **Fix:** `cw = max(width>>7,1)`, `ch = max(height>>7,1)`, `sw = max(width>>6,1)`, `sh = max(height>>6,1)`.

---

### VK-06: `waitIdleRetireAll()` treats the current, unsubmitted frame as finished

**Severity:** Medium. **Platforms:** both.

**Where:** `VkFrameCtx.java:164-175`.
- The comment says "every recorded frame, including the one currently being recorded, has completed." That's false. `vkDeviceWaitIdle` only waits for **submitted** work, and inside the render hook the current frame is still sitting in MC's unsubmitted command buffer.
- The method sets `retiredCounter = frameCounter` and runs listeners and destroys for the current frame.
- It's called mid-frame by the overflow recovery in `VkUploadStream.rawUploadAddress` (`VkUploadStream.java:74-84`) and in `VkDownloadStream.download` (`VkDownloadStream.java:60-72`).

**Impact**

- **Download overflow:**
  1. The first loop iteration's `tick()` moves this frame's already-recorded copies into `frames`, tagged with the current frame.
  2. The second iteration's `waitIdleRetireAll()` retires them.
  3. The callbacks then read readback memory the GPU hasn't written yet, so garbage node-request/removal batches go to `AsyncNodeManager`.
  4. The freed arena ranges can be handed to another copy in the same frame.
- **Destroys:** any resource freed after being used in the same frame would be destroyed while the unsubmitted command buffer still references it. No current code path hits this, but the API invites it.
- **Upload overflow:** it's only safe because upload frames happen to be tagged one tick later. That's fragile.

**Suggested fix**

- While inside the hook (`frameCmd != null`), cap retirement at `frameCounter - 1`, and never run current-frame callbacks or destroys.
- For real recovery, grow or rotate the staging buffers, or allocate a temporary overflow staging buffer.

---

### VK-07: Barrier gaps

**Severity:** Medium/Low. **Platforms:** mostly desktop.

**a) Request buffer is read, then reset, with no ordering between them (Medium).**
- `VkTraversal.java:241-250`: `downloadStream.download(requestBuffer)` records a copy followed by a `TRANSFER → HOST` barrier. Then `vkCmdFillBuffer(requestBuffer, 0, 4, 0)` resets the counter.
- A HOST-only destination scope does **not** order the fill after the copy (a WAR hazard). So the fill can zero the request count before the copy reads it, and that frame's node requests are silently dropped (LOD streaming becomes slower or stalls, and it's hard to diagnose). GL's implicit ordering hid this.
- **Fix:** add a `TRANSFER → TRANSFER` barrier before the fill.

**b) SSAO reads MC depth in COMPUTE, but the barriers target FRAGMENT (Medium).**
- `VkSSAO.java:144-147, 161-164` use `VkFrameHost.transitionMcImage`, which hard-codes `FRAGMENT_SHADER` as the consumer and producer (`VkFrameHost.java:49-75`). The SSAO pass is a **compute** dispatch.
- As a result, neither the transition into `SHADER_READ_ONLY` nor the transition back is ordered against the compute read.
- This affects BETTER/BEST SSAO, and AUTO resolves to BEST at 1080p and BETTER at 1440p (`VkSSAO.java:78-92`).
- **Fix:** pass stage masks explicitly. After VK-01 this becomes a plain `LATE_FRAGMENT_TESTS → COMPUTE_SHADER` memory barrier.

**c) Upload-stream barrier scopes (Low).**
- `VkUploadStream.java:110-112, 121-123`: the post-copy destination omits `VERTEX_SHADER`, and the pre-copy source omits vertex-stage and indirect readers (a WAR hazard).
- Later barriers mostly compensate (`VkTerrainRenderer.java:408-411`, `VkBoundRenderer.java:113-115`). The exception is the terrain MVP **UBO**, which is read in the vertex stage:
  - The only barrier with `UNIFORM_READ` doesn't include the vertex stage.
  - The only barrier with the vertex stage doesn't include `UNIFORM_READ`.
  - In sync1, `SHADER_READ` does not cover uniform buffers.
- **Fix:** make the upload barriers conservative (destination `VERTEX_SHADER | FRAGMENT_SHADER | COMPUTE | DRAW_INDIRECT | VERTEX_INPUT` with `UNIFORM_READ`), or add `UNIFORM_READ` to `renderTerrain`'s barrier.

---

### VK-08: An exception mid-recording leaves MC's command buffer invalid

**Severity:** Medium. **Platforms:** both.

**Where**

- `MixinSodiumOpaqueVkFrame.java:39-44` catches any `Throwable` to "never take down MC's frame", and `renderFrame`'s `finally` calls `endFrame()`.
- Several calls that can throw sit **between** `vkCmdBeginRenderingKHR` and `vkCmdEndRenderingKHR`:
  - `VkTerrainRenderer.java:413-416`: the `VkFrameHost.lightmapView()` cast happens *after* `beginRendering`.
  - `VkBoundRenderer.java:138-145`: the `(VkBuffer) store.getBuffer()` cast happens after the begin.
  - The binder pushes inside open rendering.

**Impact**

- Voxy leaves a dynamic-rendering instance open.
- `vkCmdSetEvent`, which is illegal inside rendering, then gets recorded.
- MC then begins its next pass inside Voxy's open one.
- The command buffer is invalid, which likely means device loss. That's the opposite of the stated intent.
- The CPU-side layout tracking in `VkImage2D` can also diverge from what was actually recorded.

**Suggested fix:** wrap every begin/end pair in `try/finally`. Better: record Voxy's frame into its own command buffer and hand it to MC only when recording completed (D-1).

---

### VK-09: A failed `VkRenderCore` construction wedges Voxy until the game restarts

**Severity:** Medium. **Platforms:** both.

**Where**

- `VkRenderCore.java:77-87` installs the `AbstractUploadStream`, `AbstractDownloadStream`, and `IAtlasTextureReader` singletons.
- The `catch` at `:127-130` only releases the world ref, and only catches `RuntimeException`.

**Impact**

- Any later failure leaks every VK object already created and leaves the singletons set. Examples: a shader compile error, the geometry allocation failing at the 256 MiB floor, or pipeline creation failing.
- The next attempt throws "Upload stream already initialized", so Voxy can't be recreated until the game restarts.
- `MixinLevelRenderer.voxy$createEngineDirect` rethrows unless Iris is active.

**Suggested fix:** build into locals. On failure, free whatever was created (after `waitIdleRetireAll()`) and call `clearInstance()` on all three singletons. Catch `Throwable`.

---

### VK-10: The 2 GiB geometry buffer vs. device limits, plus a handle leak on allocation retry

**Severity:** Medium (portability). **Platforms:** both.

**Where:** `VkRenderCore.java:133-136`, `VkSectionGeometryData.java:24-33`, `VkShaderPipeline.java:268-269` (SSBO range = whole buffer), `VkTerrainRenderer.java:419`, `VkNodeGpuOps.java:66`.

**Problems**

1. The geometry SSBO is bound with a 2 GiB range. Vulkan only guarantees `maxStorageBufferRange ≥ 2^27` (128 MiB), and real devices vary. Voxy never reads `maxStorageBufferRange` or `maxMemoryAllocationSize`, and the halving loop only reacts to *allocation* failure, not to descriptor-range limits.
2. The full 2 GiB is committed up front. The GL path grows a sparse buffer instead. That's significant pressure on 8 GB unified-memory Macs.
3. `VkBuffer.java:50-65`: if `vkAllocateMemory` fails, the already-created `VkBuffer` handle is never destroyed. Each failed attempt in the halving loop leaks one handle.

**Suggested fix:** clamp the capacity to the minimum of the relevant limits, destroy the buffer handle when allocation fails, and consider sub-allocating through MC's VMA (`VulkanDevice.vma()`; LWJGL VMA is already on the classpath).

---

### VK-11: "Immediate" submissions run ahead of MC work already recorded this frame

**Severity:** Medium. **Platforms:** both.

**Where:** `VkFrameCtx.java:103-141`. Outside the render hook, Voxy records into its own command buffer and immediately calls `vkQueueSubmit` on MC's queue (`flushImmediate`). If that happens mid-frame, those commands execute **before** MC's already-recorded but not-yet-submitted work for the same frame. Renderer re-creation can happen mid-frame via `LevelExtractor.allChanged`.

**Concrete scenario (needs in-game confirmation)**

1. `VkAtlasTextureReader.read()` runs during `VkRenderCore` construction.
2. If MC re-stitched or uploaded the block atlas earlier in that same frame (resource-pack change → `allChanged`), MC's `writeToTexture` commands are still pending in MC's command buffer.
3. Voxy therefore reads the old or empty atlas and bakes wrong LOD textures until the next reload. GL executes commands in order, so the GL path doesn't have this problem.

Also note that `VkAtlasTextureReader` silently assumes it runs outside the hook. Inside the hook, `cmd()` returns MC's frame command buffer and `flushImmediate()` does nothing, so it would read uninitialized staging memory. It deserves an assertion.

**Suggested fix:** route this work through MC's submission (`execute()` plus a timeline value, see D-1), or defer model-bakery construction to a frame boundary.

---

### VK-12: `VulkanBackend.shutdown()` is never called, so Voxy objects outlive MC's device

**Severity:** Low. **Platforms:** both.

**Where**

- Nothing calls `VulkanBackend.shutdown()`.
- `MixinVulkanDevice.voxy$clearHost` (`MixinVulkanDevice.java:35-38`) only clears the host.
- The static caches `VkImage2D.SAMPLER_CACHE` (`VkImage2D.java:174-194`) and `VkShaderPipeline.LAYOUT_CACHE` (`:33`) are keyed by `device.address()` and are never destroyed.

**Impact**

- At exit, `vkDestroyDevice` runs while Voxy's command pool, cached samplers, and descriptor-set layouts are still alive, which triggers validation errors. The malloc'd property structs also leak.
- If MC ever recreates its device in-process, `VulkanBackend` keeps the stale `VulkanContext` (because `supported` stays cached), and the caches could hand out handles from the destroyed device if the new `VkDevice` lands at the same address.

**Suggested fix:** at `VulkanDevice.close` HEAD, shut down any live Voxy renderer, then call `VulkanBackend.shutdown()` and destroy and clear both caches.

---

### VK-13: Teardown delivers download callbacks into a stopped node manager

**Severity:** Low. **Platforms:** both.

**Where**

- `VkRenderCore.java:263` stops the node manager.
- Only later do `:280` (`waitIdleRetireAll`) and `:295` (`flushWaitClear`) fire the traversal and cleaner callbacks.
- Those callbacks call `AsyncNodeManager.submitRequestBatch`/`submitRemoveBatch` (`AsyncNodeManager.java:615-641`), which enqueue `MemoryBuffer`s with no `running` check, after `stop()` already drained the queues.
- The GL path flushes the download stream *first* (`VoxyRenderSystem.java:599`).

**Impact:** native memory leaks on every world unload or reload.

**Suggested fix:** flush downloads before `nodeManager.stop()`, as the GL path does, or drop batches when the manager isn't running.

---

### VK-14: Invalid mutable-format list on Voxy's depth-stencil image; D32S8 assumed

**Severity:** Low. **Platforms:** both.

**Where:** `VkViewport.java:76-86`, `VkImage2D.java:62-67`, `VkTerrainRenderer.java:137-138`.

**Problems**

- The image uses `VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT` with `VkImageFormatListCreateInfo{D32_SFLOAT_S8_UINT, D32_SFLOAT}`. Depth/stencil formats are only compatible with themselves, so listing `D32_SFLOAT` is invalid.
- The list is also unnecessary: the depth-only sampling view is (correctly) created with the image's own format and `ASPECT_DEPTH`.
- `D32_SFLOAT_S8_UINT` is hard-coded without `vkGetPhysicalDeviceFormatProperties`. The spec only guarantees that D24S8 *or* D32S8 is supported as a depth attachment.

**Suggested fix:** drop the flag and the list, and choose D32S8 or D24S8 with a format-properties query.

---

### VK-15: The descriptor-set-layout cache is keyed by a hash

**Severity:** Low (latent). **Platforms:** both.

**Where:** `VkShaderPipeline.java:198-225`.
- The key is `stages*31^n + Σ(binding*7 + type)`. Two different binding tables can produce the same key, and the cache would then return a layout with the wrong descriptor types.
- I computed the keys for all 18 binding tables in use today and **none collide**. But, for example, `[ubo(0), ubo(5)]` and `[ssbo(0), image(1)]` with the same stages do collide (both give `30979`).

**Suggested fix:** key by `(stages, List<Binding>)`. `Binding` is a record, so `equals`/`hashCode` already work.

---

### VK-16: `VkShaderSource` hoists `#extension` lines out of `#ifdef` guards

**Severity:** Low (latent). **Platforms:** both.

**Where:** `VkShaderSource.java:31-34`.
- `quads.frag:8-10` declares `#extension GL_NV_fragment_shader_barycentric : require` inside `#ifdef USE_NV_BARRY`. After hoisting it becomes unconditional. `quads3.vert:9-11` (`GL_NV_gpu_shader5`) has the same problem.
- It's harmless today: glslang knows both extensions, and the NV built-ins aren't used.
- But any guarded `: require` of an extension that glslang rejects, or one that emits a SPIR-V capability, would break compilation on every device.

**Suggested fix:** only hoist `#extension` lines that appear before the first non-directive line and outside conditionals. Alternatively, insert the defines right after `#version` and leave everything else in place.

---

### VK-17: `VkDownloadStream.waitDiscard()` fires callbacks instead of discarding them

**Severity:** Low (latent). **Platforms:** both.

`VkDownloadStream.java:137-143` calls `waitIdleRetireAll()`, which runs the retire listener, which **invokes** the callbacks. The GL version waits and frees *without* invoking them. The VK version also doesn't clear `thisFrame*` or `downloadList`. Nothing calls `waitDiscard()` today, but it will surprise whoever calls it first.

---

### VK-18: The shared `ModelFactory` refactor dropped the `hasMips` guard

**Severity:** Low (latent). **Platforms:** both.

- At the merge base, `ModelFactory.java:379` looped `lvl < (hasMips ? LAYERS : 1)`.
- Now both `VkModelStore.uploadModelTexture` (`VkModelStore.java:93-99`) and GL `ModelStore.uploadModelTexture` always copy all `LAYERS` mip levels.
- When `rasterUV` is true, `ModelBakeResultUpload.texture` only holds mip 0, so the copy reads past the end of that native allocation.
- `rasterUV` is hard-coded `false` (`ModelFactory.java:141`), so this is latent.

**Suggested fix:** pass `hasMips` (or the mip count) through `IModelStore.uploadModelTexture`.

---

### VK-19: Backend detection trusts a registered host over the live device

**Severity:** Low. **Platforms:** both.

- `MinecraftVkHost.java:22-25`: `isMinecraftOnVulkan()` returns true whenever a host is registered. Registration happens at `VulkanDevice.<init>` TAIL.
- If MC ever constructed a `VulkanDevice` and then fell back to GL without calling `close()`, Voxy would take the VK path while MC runs on GL. Today MC constructs `VulkanDevice` as the very last step of `createDevice`, so this is unlikely.
- Relatedly, `VulkanBackend.shouldUseVulkan() == false` means both "MC is on GL" and "MC is on VK but Voxy's VK path is unusable". `VoxyClient.initVoxyClientVulkan` handles the second case at init. If the state changed later, `VoxyRenderSystem`'s constructor (`VoxyRenderSystem.java:93`) would fall through to the GL path with no GL context.

**Suggested fix:** derive the backend from `RenderSystem.getDevice()` each time, and use an explicit tri-state: GL, VK-usable, VK-unusable.

---

### VK-20: The build bundles LWJGL modules MC already ships

**Severity:** Low. **Platforms:** both.

`build.gradle:374-383` Jar-in-Jar-includes `lwjgl-vulkan` (plus the macOS MoltenVK natives) and `lwjgl-shaderc` 3.4.1. MC 26.2 already ships both, plus `lwjgl-vma` and `lwjgl-spvc`, all at 3.4.1, and MC's shaderc also has `windows-arm64` natives.
- The bundled copies are redundant.
- They become a module-version-skew hazard the day Mojang bumps LWJGL.

**Suggested fix:** make these `compileOnly`.

---

### VK-21: Performance notes (not bugs)

- **Readback memory:** `HOST_VISIBLE | HOST_COHERENT` first-fit usually selects *uncached* memory on discrete GPUs, which makes CPU reads slow. Prefer `HOST_CACHED` (`VkDownloadStream.java:46`, `VkBuffer.java:56-58`).
- **Per-call barriers:** `VkDownloadStream.download()` commits per call, so each readback costs 2 barriers plus a copy (`:81`).
- **Duplicate upload:** the terrain uniform is uploaded twice per frame with identical content (`VkTerrainRenderer.java:226, 382`).
- **MoltenVK zero-fill:** the fixed-count fallback zero-fills 12 MB of draw commands every frame (`VkTerrainRenderer.java:239-244`). It could be bounded by last frame's highest count.
- **Shader compilation:** a new shaderc compiler is created per shader, with no optimization level and no `VkPipelineCache`. The terrain, composite, and HiZ pipelines are compiled lazily inside the first frame, which causes a hitch. On Mac that also means a SPIR-V → MSL → Metal compile on every world join.
- **Allocation strategy:** one dedicated `vkAllocateMemory` per resource. MC's VMA instance is available and would be a better fit.

---

### VK-22: Stale or misleading comments

These matter for an educational fork, because they teach the wrong model.

- `VkFrameHost.java:34-40`: "they live in ATTACHMENT_OPTIMAL otherwise". False; see VK-01.
- `VkAtlasTextureReader.java:22-24`: "keeps it in SHADER_READ_ONLY_OPTIMAL between frames". False; see VK-01.
- `VkFrameCtx.java:171-172`: "including the one currently being recorded — has completed". False; see VK-06.
- `VkRenderCore.java:254-258`: "CPU-only stop first". `nodeManager.stop()` frees GPU pipelines; see VK-03.
- `IRenderList.java:3-8`: describes a GL-imported shared buffer used by a still-GL traversal. No longer true, and `VkBuffer.glId()` throws.
- `VkShaderPipeline.java:22-23`: refers to `assets/voxy/shaders/lod/vk/`, which doesn't exist.
- `IVkHost.java:15-17` and `CHANGELOG.md`: mention a GL-interop hybrid (`VK_KHR_external_memory_fd`) and a "Hybrid GL/Vulkan renderer with config toggle". Neither exists on this branch.
- `VkTerrainRenderer.java:113-115`: says the subgroup prefix sum runs on "virtually every VK 1.1+ device". It never runs; see VK-05.

---

## D-1 (design): use MC's public encoder API instead of the private command buffer and `VkEvent`

MC's `VulkanCommandEncoder` already exposes `execute(VkCommandBuffer)`, `signalSemaphore(semaphore, value, stageMask)`, and `waitSemaphore(...)`. Each safely ends MC's current command buffer and appends to the pending submission (verified in bytecode). Voxy could:

1. Record its frame into its **own** primary command buffer, from a small per-frame ring allocated from its own pool, and end it with MC's global barrier (VK-04).
2. At the hook, call `encoder.execute(voxyCmd)` **only if recording finished without error**.
3. Call `encoder.signalSemaphore(voxyTimeline, frameIdx, ALL_COMMANDS)`, and retire frames by polling `vkGetSemaphoreCounterValue`. `timelineSemaphore` is enabled by MC, unlike portability `events` on MoltenVK (VK-02).

What this fixes or simplifies:
- It removes the `AccessorVulkanCommandEncoder` dependency on a private field.
- It makes VK-08 recoverable: just drop the command buffer.
- It removes the "immediate submit reorders work" class of bugs (VK-11), because everything goes through MC's own submission in order.

---

## Verified OK (checked and found correct)

These are useful to cross-check against the other audit.

- **Hook point.** Sodium 0.9.2 `SodiumWorldRenderer.drawChunkLayer(OPAQUE, ...)` runs the SOLID pass and then the CUTOUT pass, and each closes its render pass before returning. At TAIL, MC's command buffer is recording, outside dynamic rendering, and ends with MC's global barrier. The `@Inject` signature matches.
- **`frameCommandBuffer()`.** `createCommandEncoder()` returns MC's single encoder (a field getter), so calling it per frame does not create encoders.
- **Threading.** All `VkFrameCtx` use happens on the render thread: `AsyncNodeManager.tick` (GPU ops and TLN callbacks), `ModelFactory` upload processing, and stream ticks. MC also submits only from the render thread, so Voxy's `vkQueueSubmit`/`vkDeviceWaitIdle` don't race MC's (unlocked) queue.
- **MC state tracking.** MC tracks the bound pipeline, uniforms, and textures per `VulkanRenderPass` object, so Voxy's binds between passes don't corrupt MC's state.
- **Extensions and features Voxy relies on that MC does enable:** `VK_KHR_push_descriptor`, `VK_KHR_dynamic_rendering`, `VK_KHR_synchronization2`, and `multiDrawIndirect`. The last one makes the MoltenVK fixed-count `vkCmdDrawIndexedIndirect` with `drawCount > 1` legal.
- **Depth aspect.** MC's main depth is `D32_FLOAT` with depth-only views, so Voxy's DEPTH-only aspect masks on it are valid.
- **Orientation.** MC and Voxy both use a positive-height viewport, so the LOD image and MC's frame have the same orientation.
- **Atlas usage flags.** MC's block atlas is created with `COPY_SRC`, which maps to `TRANSFER_SRC`, so the readback copy itself is allowed. Only the layouts are wrong (VK-01).
- **Exit ordering.** On game exit, `LevelRenderer.close()` (Voxy teardown) runs before `RenderSystem.shutdownRenderer()`, so MC's device is alive during Voxy's normal teardown.
- **Storage images.** They declare format qualifiers (`r32f`, `rgba8`), so `shaderStorageImageWriteWithoutFormat` isn't needed.
- **Cube index offset.** `prep.comp`'s VK `firstIndex` (in u16 units) matches the u16 cube indices at `CUBE_INDEX_OFFSET` in `VkTerrainRenderer`'s index buffer.
- **UBO layouts.** The std140 blocks match the Java writes for SSAO, the composite, and the bound renderer.
- **Layout cache.** None of the 18 descriptor-set-layout keys in use collide today (VK-15 is latent).

---

## Suggested fix order

1. **VK-01 + VK-04 + VK-07b.** Small, mechanical changes that remove the largest class of desktop-only corruption.
2. **VK-03 and VK-13** (teardown ordering / deferred pipeline destruction).
3. **VK-02.** Enable the needed features via a mixin on MC's `createDevice`, and gate on what was actually enabled.
4. **VK-07a and VK-07c.**
5. **VK-08 and VK-09** robustness, or go straight to **D-1**, which subsumes much of it.
6. **VK-05 together with VK-05a/b/c.** Never land VK-05 alone.
7. **VK-06, VK-10, VK-11**, then the Low items.

---

## How to confirm these findings

- **Validation layers.**
  - Windows/Linux: install the LunarG Vulkan SDK and launch with `VK_INSTANCE_LAYERS=VK_LAYER_KHRONOS_validation`. Turn on *synchronization validation* in `vkconfig` to catch VK-04 and VK-07.
  - macOS: LWJGL loads MoltenVK directly, bypassing the loader. Install the Vulkan SDK, source its `setup-env.sh`, and point LWJGL at the SDK loader with `-Dorg.lwjgl.vulkan.libname=<path to libvulkan.1.dylib>` so the layers load.
  - MC 26.2's `VulkanInstance` also has a built-in validation-layer switch (`GpuDebugOptions.useValidationLayers()`). How it's set depends on your launch configuration.
- **VK-05:** check the startup log for `Voxy Vulkan context adopted Minecraft device: ... subgroupSize=0`.
- **VK-07a:** temporarily log `count` in `VkTraversal.forwardDownloadResult` next to the number of requests the shader enqueued. Look for frames where the count is 0 but requests were enqueued.
- **VK-11:** on Vulkan, change the resource pack while in a world, then compare the LOD textures before and after `/voxy reload`.
- **VK-05b:** confirmed with a capability probe on an Apple M2 Max: MoltenVK advertises `CLUSTERED` (see the correction in VK-05b).

---

## Fix status (branch `vulkan-audit-fixes`, 2026-09-25)

Every finding was addressed on branch `vulkan-audit-fixes` (from `pr-614`), except where noted. Line numbers above refer to the pre-fix commit.

| ID | Status | What changed |
|---|---|---|
| VK-01 | Fixed | `VkFrameHost.MC_IMAGE_LAYOUT = GENERAL` is used for every access to an MC image (setup/composite attachments, MC depth + lightmap descriptors, atlas copy). Transitions of MC images were replaced by `mcImageBarrier` (memory-only, layout unchanged). |
| VK-02 | Fixed | New `MixinVulkanBackend` + `VkDeviceFeatures` append `shaderInt64`, `fragmentStoresAndAtomics`, `drawIndirectFirstInstance` (required) and `drawIndirectCount` (optional) to MC's enabled-feature set when supported. `VulkanContext` refuses to adopt a device missing a required one and gates `hasDrawIndirectCount` on *enabled*. `raster.vert` is `readonly` under Vulkan. `VkEvent` is gone (see D-1), so the portability `events` feature isn't needed. |
| VK-03 | Fixed | `VkShaderPipeline.free()` defers pipeline + layout destruction through `VkFrameCtx`, and shader modules are destroyed right after pipeline creation. `nodeManager.stop()` is now safe before the device is idle or after it's gone. |
| VK-04 | Fixed | `VkFrameCtx.endFrame()` records a full `ALL_COMMANDS` memory barrier before handing the command buffer back to MC. |
| VK-05 | Fixed | Subgroup properties are queried via `vkGetPhysicalDeviceProperties2`. New gates are `supportsSubgroupPrefixSum()` and `supportsSubgroupHiZ()`. |
| VK-05a | Fixed | `inital3_vk.comp`: only subgroup 0 scans the per-subgroup totals, the shared array is sized for subgroups ≥ 16 wide, and subgroup ops are in uniform control flow. Verified on the M2 Max: 300/300 random runs match a CPU scan. |
| VK-05b | Fixed | Gate requires CLUSTERED + compute. The shader uses `subgroupClusteredMin`/`subgroupMin` under reverse-Z. Verified on the M2 Max: 160/160 runs match a CPU reference, both depth conventions. |
| VK-05c | Fixed | Level 7+ sizes corrected. The subgroup path also requires both base dimensions ≥ 64. |
| VK-06 | Fixed | Retirement reads the timeline counter. `waitIdleRetireAll()` retires only frames that actually completed, never the unsubmitted one. |
| VK-07a/b/c | Fixed | `TRANSFER → TRANSFER` barrier before the request-buffer reset. SSAO's MC-depth barrier targets `COMPUTE`. Upload post-copy scope includes `VERTEX_SHADER`. |
| VK-08 | Fixed | `VkFrameCtx.beginRendering/endRendering` track the open instance, and `endFrame()` closes it. Throwing calls (lightmap view, bound-store cast) moved before `beginRendering`. |
| VK-09 | Fixed | `VkRenderCore` constructor keeps an undo stack. On any `Throwable` it releases everything in reverse order and clears the singletons. |
| VK-10 | Fixed | Geometry capacity is clamped to `maxStorageBufferRange` / `maxMemoryAllocationSize`. `VkBuffer`/`VkImage2D` destroy their handles if allocation fails. |
| VK-11 | Mitigated | On Vulkan, `voxy$createRenderer` defers creation to the next `Minecraft.renderFrame` HEAD (`MixinMinecraftFrameStart`), when MC has submitted everything it recorded. The atlas reader also refuses to run inside the render hook. Needs in-game confirmation (resource-pack change). |
| VK-12 | Fixed | `MixinVulkanDevice.close` shuts down the renderer, then `VulkanBackend.shutdown()`, which destroys the command pool, pipeline cache, and the (now per-context) sampler and descriptor-set-layout caches. |
| VK-13 | Fixed | Shutdown flushes readbacks before stopping the node manager. `submitRequestBatch`/`submitRemoveBatch` free batches after `stop()`. |
| VK-14 | Fixed | The depth-stencil format is queried (D32S8, else D24S8) and the mutable-format list was removed. |
| VK-15 | Fixed | Layout cache keyed by `(stages, List<Binding>)`. |
| VK-16 | Fixed | `VkShaderSource.assemble()` hoists only unconditional `#extension` lines. |
| VK-17 | Fixed | `waitDiscard()` marks pending frames discard-only. |
| VK-18 | Fixed | `IModelStore.uploadModelTexture(..., mipLevels)` on both backends. |
| VK-19 | Fixed | The live device's backend is authoritative. `VoxyRenderSystem` and the renderer creation never take the GL path while MC is on Vulkan. |
| VK-20 | Fixed | `lwjgl-vulkan`/`lwjgl-shaderc` are `compileOnly`; the jar no longer nests them. |
| VK-21 | Partly | Done: `HOST_CACHED` readback memory, `VkPipelineCache`, one reused shaderc compiler, once-per-frame terrain uniform upload. Not done: batching download commits, bounding the MoltenVK zero-fill, VMA. |
| VK-22 | Fixed | Comments listed above corrected. (Historical `CHANGELOG` entries left as-is.) |
| D-1 | Partly | Retirement now uses a timeline semaphore signalled through MC's public `signalSemaphore()`. Voxy still records into MC's command buffer via the accessor rather than `execute()`-ing its own. |

**Verification done:** `./gradlew build` passes. All 54 VK shader permutations compile with the project's `VkShaderSource.assemble()` and cross-compile to MSL via SPIRV-Cross; the SPIR-V capabilities are unchanged. The newly enabled subgroup prefix sum and subgroup HiZ were **executed on an Apple M2 Max** through MoltenVK and matched CPU references. Mixin targets were checked against the 26.2 bytecode.

**Not verified (needs a real game session):** in-game rendering on MoltenVK and on a desktop Vulkan driver; validation-layer output; the resource-pack-reload case (VK-11).
