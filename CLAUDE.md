# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Voxy is a Level of Detail (LoD) rendering mod for Minecraft built on Fabric, designed to enable extreme render distances through GPU-accelerated hierarchical voxel rendering. It integrates with Sodium for chunk rendering optimization and Iris for shader pack support.

**Tech Stack:** Java 25, Fabric API, LWJGL 3.4.1, OpenGL 4.6 compute shaders, Gradle build system

## Development Commands

### Building

```bash
# Build the mod (creates JAR in build/libs/)
./gradlew build

# Clean build artifacts
./gradlew clean

# Build without running tests
./gradlew jar

# Assemble all outputs
./gradlew assemble
```

### Running and Testing

```bash
# Launch Minecraft client with the mod
./gradlew runClient

# Launch dedicated server
./gradlew runServer

# Run test suite
./gradlew test

# Generate IDE run configurations
./gradlew vscode        # For VSCode
./gradlew genEclipseRuns # For Eclipse
```

### Development Setup

```bash
# Download game assets (required before first run)
./gradlew downloadAssets

# Decompile Minecraft sources for reference
./gradlew genSources

# Configure client launch files
./gradlew configureClientLaunch
```

### JMH Benchmarking

```bash
# Run JMH benchmarks (currently includes SaveLoadJMH)
./gradlew jmh
```

### Gradle Properties

Key configuration in `gradle.properties`:
- `minecraft_version` - Currently 26.2
- `sodium_version_modrinth` - Sodium dependency version
- `mod_version` - Current version (0.2.21+mc26.2)
- `includeOtherArchs` - Set to "true" to include ARM64 natives

## Architecture Overview

### High-Level Design

Voxy replaces traditional CPU-based chunk meshing with a GPU-centric hierarchical rendering pipeline:

1. **Hierarchical Voxel Coordinate System**: World data organized into 5 LOD levels (0-4)
   - Level 0: 32×32×32 block sections (highest detail)
   - Level 4: Entire world quadrants (lowest detail)

2. **GPU-Driven Rendering**: Uses compute shaders for culling and draw call generation
   - Hierarchical occlusion culling with HZB (Hierarchical Z-Buffer) pyramid
   - Multi-Draw Indirect rendering eliminates CPU-GPU synchronization bottlenecks

3. **Asynchronous Mesh Building**: Off-render-thread geometry processing
   - 10-thread pool for mesh generation (shared with Sodium when enabled)
   - Lock-free synchronization via VarHandle for minimal contention

4. **Pluggable Storage**: Persistent voxel data with multiple backend options
   - RocksDB (default, SSD-optimized)
   - LMDB (memory-mapped)
   - Redis (distributed/network)
   - Compression via zstd/LZ4 reduces storage ~80%

### Core Components

**VoxyInstance** (`me.cortex.voxy.commonImpl.VoxyInstance`, `VoxyClientInstance`)
- Singleton lifecycle manager, creates WorldEngine per game world
- Manages thread pools, storage backends, and services
- Factory pattern with weak reference caching

**WorldEngine** (`me.cortex.voxy.common.world.WorldEngine`)
- Multi-level hierarchical coordinate system for voxel data
- Section tracking, caching, and dirty state management
- Each section stores 32×32×32 voxels as packed long[] arrays (32768 longs)

**RenderGenerationService** (`me.cortex.voxy.client.core.rendering.building.RenderGenerationService`)
- Worker thread pool converting voxel data → GPU mesh data
- Priority queue sorted by LOD level and camera distance
- LRU cache for computed geometry reuse

**AsyncNodeManager** (`me.cortex.voxy.client.core.rendering.hierachical.AsyncNodeManager`)
- Off-render-thread async processing of hierarchical LOD tree
- Lock-free CPU-GPU state synchronization via VarHandle
- Coordinates geometry upload/download streaming

**HierarchicalOcclusionTraverser** (`me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser`)
- GPU compute shader-based hierarchical culling
- HZB pyramid for logarithmic depth testing
- Generates visible mesh list (supports 200K+ sections per frame)

**RenderPipeline** (`me.cortex.voxy.client.core.RenderPipelineFactory`)
- Selects between NormalRenderPipeline and IrisVoxyRenderPipeline
- Iris integration handles shader pack uniforms, render targets, and SSBO bindings
- Shader patching for custom GLSL code injection

**ModelBakerySubsystem** (`me.cortex.voxy.client.core.model.ModelBakerySubsystem`)
- Converts Minecraft block models → voxel-space geometry
- Texture atlasing, lighting, and biome color application
- Async processing thread with LockSupport parking

### Integration Points

**Minecraft Integration** (via Mixins in `me.cortex.voxy.client.mixin.minecraft.*`)
- `MixinLevelRenderer`: Creates/destroys VoxyRenderSystem per world, implements IVoxyRenderSystemHolder
- `MixinClientLevel`: Patches block breaking animations
- `MixinRenderSystem`: Hooks rendering calls, captures camera matrices

**Sodium Integration** (`me.cortex.voxy.client.mixin.sodium.*`)
- `MixinChunkJobQueue`: Shares unified thread pool with Voxy via MultiThreadPrioritySemaphore
- Reduces total thread count from 18+ to ~12 when both mods active

**Iris Shader Pack Integration** (`me.cortex.voxy.client.iris.*`, mixins in `me.cortex.voxy.client.mixin.iris.*`)
- `IrisVoxyRenderPipelineData`: Uniform layout generation, sampler binding, SSBO management
- Dynamic shader patching for compatibility with shader pack syntax
- TAA (Temporal Anti-Aliasing) configuration support

### Thread Model

**1. Unified Service Thread Pool** (VoxyInstance)
- Default 3 threads, auto-adjusted for Sodium integration
- Shared by RenderGenerationService, SectionSavingService, VoxelIngestService

**2. AsyncNodeManager Thread**
- Single dedicated background thread
- Syncs with render thread at frame boundaries

**3. ModelBakerySubsystem Thread**
- Single thread for block model processing

**4. World Cleaner Thread**
- Daemon thread (lowest priority)
- Periodically unloads idle WorldEngine instances (every 1000ms)

**Synchronization**: VarHandle (lock-free atomics), StampedLock (optimistic reads), ReentrantLock (caches), ConcurrentCollections (queues)

### Rendering Pipeline Data Flow

```
Frame Start → Camera Update → Mark Dirty Sections
    ↓
RequestMesh (Priority Queue by LOD + Distance)
    ↓
┌─────────────────────────────────────────────┐
│ Background Threads (RenderGenerationService)│
│  - Acquire WorldSection                     │
│  - Request missing models (ModelBakery)     │
│  - Mesh to GPU format                       │
│  - Upload via UploadStream                  │
│  - Emit BuiltSection                        │
└─────────────────────────────────────────────┘
    ↓
┌─────────────────────────────────────────────┐
│ AsyncNodeManager Thread                     │
│  - Integrate geometry into hierarchy        │
│  - VarHandle atomic CPU<->GPU state sync    │
└─────────────────────────────────────────────┘
    ↓
GPU Compute: OcclusionCull (HierarchicalOcclusionTraverser)
  → HZB pyramid testing
  → Generate visible mesh list
    ↓
GPU Compute: BuildDrawCalls (MDICSectionRenderer)
  → Generate indirect draw parameters
    ↓
GPU Draw: Render
  → Opaque pass
  → Temporal pass (motion vectors)
  → Translucent pass (back-to-front blending)
  → Post-processing
    ↓
Sync: AsyncNodeManager.syncRenderThread()
  → VarHandle exchange CPU/GPU state
```

### Shader System

**Location:** `src/main/resources/assets/voxy/shaders/`

**Structure:**
- `lod/*.glsl` - Core LOD rendering utilities (block models, frustum culling, lighting, sections)
- `lod/gl46/bindings.glsl` - OpenGL 4.6 binding declarations
- `lod/hierarchical/*.glsl` - Hierarchical traversal (node management, queue, screenspace)
- `util/*.glsl` - Utility functions (depth utilities)
- `hiz/*.vsh/*.fsh` - HZB pyramid generation
- `chunkoutline/*.vsh/*.fsh` - Debug chunk outline rendering

**Shader Processor:** `me.cortex.voxy.client.core.gl.shader.IShaderProcessor` for dynamic GLSL modification

### Configuration

**VoxyConfig** (`me.cortex.voxy.client.config.VoxyConfig`)
- `enabled` - Toggle entire mod
- `dontUseSodiumBuilderThreads` - Disable thread pool sharing with Sodium
- `ingestEnabled` - Enable voxelization of Minecraft world
- `serviceThreads` - Dedicated thread count
- `isRenderingEnabled()` - Enable/disable rendering

**Storage Configuration:**
- Backend selection (RocksDB/Redis/LMDB/In-Memory)
- Compression algorithm (zstd/LZ4)
- Cache sizes

### Debugging

**Debug Flags:**
```java
voxy.verifyNodeManager=true                  // Validates node hierarchy integrity
voxy.verifyWorldSectionExecution=true        // Checks section access rules
voxy.hierarchicalShaderDebug=true            // Debug compute shader execution
```

**Debug Statistics:**
- Frame time breakdown (geometry, culling, rendering)
- Section cache hits/misses
- Model baking queue status
- Thread pool utilization

Access via debug screen entries (`me.cortex.voxy.client.VoxyDebugScreenEntry`)

### Coordinate System

Voxy uses 64-bit section IDs with bit-packing:
```
Bits [63-60] = LOD Level (0-4)
Bits [59-52] = Y coordinate (0-255)
Bits [51-28] = Z coordinate (0-16M)
Bits [27-4]  = X coordinate (0-16M)
Bits [3-0]   = Spare bits
```

See `me.cortex.voxy.common.world.WorldCoordinate` for encoding/decoding utilities.

### Performance Characteristics

**GPU:** 3-11ms GPU time per frame, enabling 120+ FPS with extreme render distances
**Memory:** ~2-4GB VRAM (geometry + textures), ~1-2GB RAM (geometry cache + sections)
**Storage:** 2-20GB per world (RocksDB/Redis backend with compression)
**CPU:** Minimal render thread impact due to async processing

### Common Development Patterns

**Adding New Shader Features:**
1. Add GLSL files to `src/main/resources/assets/voxy/shaders/`
2. Use `ShaderLoader.load()` to compile shaders
3. Bind uniforms via `AutoBindingShader` or manual binding
4. For Iris integration, update `IrisVoxyRenderPipelineData` uniform layout

**Modifying Storage Backend:**
1. Implement `IStorageBackend` interface
2. Register in storage configuration system
3. Handle compression adaptor wrapping if needed

**Changing LOD Behavior:**
1. Modify `WorldEngine` section level definitions
2. Update `HierarchicalOcclusionTraverser` traversal logic
3. Adjust `RenderGenerationService` priority calculations

**Debugging Rendering Issues:**
1. Enable `hierarchicalShaderDebug` flag
2. Check `VoxyDebugScreenEntry` statistics
3. Use `PrintfDebugUtil` for GPU-side debugging (requires GL_ARB_debug_output)
4. Monitor `RenderStatistics` and `TimingStatistics`

### Entry Points

**Mod Initialization:**
- `me.cortex.voxy.client.VoxyClient` - Fabric client entrypoint
- `me.cortex.voxy.commonImpl.VoxyCommon` - Common entrypoint

**Config UI:**
- `me.cortex.voxy.client.config.ModMenuIntegration` - ModMenu integration
- `me.cortex.voxy.client.config.VoxyConfigMenu` - Sodium config API integration

**Commands:**
- `me.cortex.voxy.client.VoxyCommands` - In-game commands registration

### Known Limitations

- Requires OpenGL 4.6 with compute shader support
- Incompatible with certain AMD GPUs (broken depth sampler detection)
- Requires Sodium (hard dependency)
- High memory usage with large geometry caches
- Storage backend must support concurrent read/write access

### Build Artifacts

The build process includes custom tasks:
- `makeExcludedRocksDB` - Filters RocksDB native libraries to exclude unsupported architectures
- `processIncludeJars` - Bundles included dependencies (LWJGL, RocksDB, Jedis, etc.)
- JAR naming includes git commit hash (first 7 chars) when not in GitHub Actions

Git commit hash is embedded in `fabric.mod.json` via `processResources` task expansion.
