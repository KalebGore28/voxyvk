# Changelog

All notable changes to VoxyVK (fork) will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project uses versioning in the format `MAJOR.MINOR.PATCH+mcVERSION`.

**Note:** This is a community-maintained fork. Original code © 2025 MCRcortex - All Rights Reserved.

## [Unreleased]

### Changed
- Vulkan: the chunk-bounds pass, which stops LODs from drawing inside the area regular chunks cover, now runs on Minecraft's own Blaze3D render API: a Blaze3D pipeline with Minecraft-format shaders (`voxy:core/chunk_bounds`), drawing into Blaze3D textures, with its per-frame data in Blaze3D's transient memory. It is Voxy's first Vulkan pass with no raw Vulkan calls (Blaze3D migration step 4.1). Rendering should look the same. If its shaders fail to compile, Voxy logs an error once and LODs render without that culling instead of failing
- Vulkan: Voxy's LOD colour target is created through Blaze3D (migration step 3.2) and stays in `GENERAL` layout like Minecraft's own textures, so its layout transitions became plain memory barriers. Rendering should look the same

## [0.2.22+mc26.2] - 2026-09-25

Blaze3D migration phases 0-2 on the Vulkan backend (see `BLAZE3D_MIGRATION_AUDIT.md`), plus fixes for LODs blanking on Vulkan and for the Mac frame rate. Tested in-game on an M2 Max (MoltenVK).

### Added
- Vulkan: F3 shows how long Voxy's frame takes on the GPU (the `GpuTime` lines) while the `voxy:gpu_debug` debug entry is showing (F3+F6 to set it), as on OpenGL. It is measured with Minecraft's public GPU timestamp queries. The first line has the frame total, averaged over the last second, and the worst frame in that second; the lines below split it into Voxy's passes. On Apple GPUs only the total is reliable: Metal takes a timestamp after a draw pass while the draws still run, so their time shows under a later pass. Voxy only measures while the lines are on screen, which splits its frame into one command buffer per pass

### Changed
- Vulkan: Minecraft's block atlas is now read back asynchronously through Blaze3D's public API. The copy is recorded into Minecraft's own command stream instead of a separate queue submission that stalled until the GPU finished, and the model bakery starts baking once the pixels arrive, about a frame later. After joining a world or reloading resources, LODs can appear a frame or two later. OpenGL still reads the atlas synchronously, as before
- Vulkan: Voxy's frame is recorded into its own command buffer and spliced into Minecraft's frame through the encoder's public methods, instead of reading the encoder's private command buffer. It no longer skips a frame when Minecraft has no command buffer open at the hook
- Vulkan: Voxy tracks when its frames have finished on the GPU with Minecraft's public GPU fences instead of its own timeline semaphore
- Vulkan: GPU objects that frames in flight may still use are handed to Minecraft's destruction queue instead of a list Voxy kept itself. A renderer shut down while one of its frames was still unsubmitted no longer leaks them, and shutdown still frees everything at once, so a renderer rebuilt right away (e.g. when a server changes the view distance on join) never holds two geometry buffers
- Vulkan: Voxy's buffers and images are allocated from Minecraft's own Vulkan memory allocator (VMA) instead of one raw device allocation each. Small buffers share its memory blocks, large ones still get their own. Memory types are chosen from the same flags as before
- Vulkan: Voxy takes the GPU's name and the memory limits Minecraft already reports from Minecraft's device info instead of querying Vulkan for them again. The log line on adopting the device now also names the vendor, device type and driver (e.g. the MoltenVK version)

### Fixed
- Vulkan on macOS: turning toward terrain that was visible a few seconds earlier, or leaving a spyglass, no longer drops random LOD sections for a few frames. Without GPU-sourced draw counts (MoltenVK), the per-frame draw budget was based on a count read back 2-3 frames late, so a sudden jump in visible sections skipped the excess draws. The budget now remembers the counts of the last 16 seconds together with where the camera was looking and how wide its FOV was, and covers the largest one seen in about the same view (within 30°, FOV within 10%). Right after a quick turn into a new view, and while the FOV changes (a spyglass or sprinting re-picks the detail level of the whole view), it covers the largest count from any view instead. The draws for sections that just came into view are sized from how far the camera turned since the last frame, or cover the whole view while the FOV changes. On top of that it adds 25% (`-Dvoxy.vk.drawBudgetGrowth`, 1.0 to 4.0). Every budget slot beyond the real count is an empty Metal draw, so looking somewhere quieter no longer pays for the busiest view of the last seconds. The F3 screen shows each pass's draw count, its budget, and any shortfalls in the last 10 seconds
- Vulkan: in large LOD worlds, terrain that had just left the screen no longer has to reload (blank for up to a second or two) when you turn back to it or leave a spyglass. The geometry buffer was a flat 2 GB, so it stayed full and the cleaner kept evicting whatever was off screen. It is now sized like the OpenGL path: up to 4 GiB, limited by the device memory still available minus 1.5 GiB, and `-Dvoxy.geometryBufferSizeOverrideMB` works on Vulkan too

## [0.2.21+mc26.2] - 2026-09-25

### Changed
- Updated documentation to clarify license restrictions (no binary redistribution)
- Removed automated release workflow to comply with license
- `lwjgl-vulkan` / `lwjgl-shaderc` are now compile-only (Minecraft 26.2 ships them); the mod jar no longer bundles its own copies
- Vulkan renderer creation is deferred to the next frame boundary (so its block-atlas readback runs after Minecraft's pending GPU work, e.g. a resource reload)

### Fixed
- The mod jar now really bundles RocksDB's Apple Silicon native, so worlds load outside the dev environment on M-series Macs (was `librocksdbjni-osx-arm64.jnilib was not found inside JAR`). The native-filtering task had stayed UP-TO-DATE since a build from the dev branch, whose rule drops every macOS native, because Gradle doesn't see edits inside an `exclude {}` closure; the kept natives are now a declared task input
- A world whose storage fails to open no longer freezes the game. `VoxyInstance` only released its world lock on success, so the next chunk load waited forever on the render thread, and a failed RocksDB native load left RocksDB itself waiting forever on any retry. Voxy now reports the error once and stays disabled for that world until it is rejoined

### Fixed (Vulkan backend, see `VULKAN_BUG_AUDIT_OPUS5.md`)
- Minecraft's images are always `GENERAL`: Voxy no longer transitions MC's depth/colour/lightmap/block atlas to other layouts or leaves them there (VK-01)
- Voxy asks Minecraft to enable the device features it uses (`shaderInt64`, `fragmentStoresAndAtomics`, `drawIndirectFirstInstance`, and `drawIndirectCount` when supported) and gates on what was actually enabled; the raster-cull vertex shader no longer declares a writable SSBO (VK-02)
- Frame retirement uses a timeline semaphore signalled through Minecraft's encoder instead of `VkEvent`s (no portability-subset `events` dependency on MoltenVK) (VK-02)
- Pipelines are destroyed only after the frames using them retire; teardown no longer destroys them while the GPU may still run them (VK-03)
- Voxy's frame now ends with the full memory barrier Minecraft's command stream relies on (VK-04)
- Subgroup capabilities are queried through `vkGetPhysicalDeviceProperties2`; the subgroup prefix sum race, the subgroup HiZ reduction (min/max under reverse-Z) and its coarse-level sizes are fixed; the traversal stays at the GL path's 32-wide groups (VK-05)
- Stream-overflow recovery no longer treats the current, unsubmitted frame as complete (VK-06)
- Barrier fixes: node-request readback vs reset, SSAO's compute read of MC depth, vertex-stage visibility of uploads (VK-07)
- An exception mid-frame can no longer leave a rendering instance open in Minecraft's command buffer (VK-08)
- A failed Vulkan renderer construction releases everything it created and clears its global singletons (VK-09)
- Geometry buffer capacity respects `maxStorageBufferRange` / `maxMemoryAllocationSize`; failed allocations no longer leak handles (VK-10)
- Voxy's Vulkan objects (command pool, pipeline cache, samplers, descriptor set layouts) are destroyed before Minecraft closes its device (VK-12)
- Readbacks delivered during teardown are flushed before the node manager stops, and dropped (not leaked) after (VK-13)
- Depth-stencil format is queried (D32S8, else D24S8) and the invalid mutable-format list is gone (VK-14)
- Smaller fixes: exact-key descriptor set layout cache, guarded `#extension` lines stay guarded, `waitDiscard` discards, mip-count-aware model texture uploads, live-device backend detection (VK-15..VK-19)

## [0.2.18+mc26.2] - 2026-09-16

### Fork Improvements
- Rebranded to "VoxyVK" to distinguish from original Voxy
- Updated Sodium compatibility to 0.9.2
- Fixed build issues with Sodium 0.9.2's new terrain buffer system
- Removed deprecated internal Sodium mixin accessors
- Version scheme now includes Minecraft version: `VERSION+mcMC_VERSION`

### From PR-614 (Vulkan Implementation)
- Vulkan/MoltenVK rendering support
- macOS compatibility via MoltenVK
- Hybrid GL/Vulkan renderer with config toggle
- MoltenVK terrain rendering fixes
- First-frame loading glitch fixes on macOS

## Origins & Attribution

This fork is based on:
- **Original Voxy** by [MCRcortex](https://github.com/MCRcortex) - [Repository](https://github.com/MCRcortex/voxy)
- **PR-614** which added Vulkan support - [Pull Request](https://github.com/MCRcortex/voxy/pull/614)

**All original code and concepts © 2025 MCRcortex - All Rights Reserved.**

This fork exists to maintain the Vulkan implementation and keep it compatible with newer Minecraft/Sodium versions. No redistribution of compiled binaries is permitted per the original license.

---

For changes to the original Voxy before PR-614, see the [original repository](https://github.com/MCRcortex/voxy).
