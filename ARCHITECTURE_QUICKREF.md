# Voxy Architecture Quick Reference

## The 10-Second Summary

Voxy is a GPU-centric LOD rendering system that:
1. Converts Minecraft blocks → voxel hierarchy (5 levels, 0-4)
2. Builds GPU geometry asynchronously from voxel data
3. Performs hierarchical occlusion culling on GPU (HZB pyramid)
4. Renders visible sections via Multi-Draw Indirect
5. Streams geometry updates back-and-forth with async sync

**Result:** 60+ FPS rendering on 128+ render distance without destruction

---

## Core Subsystems (Who Does What)

| Component | Thread | Job | Timing |
|-----------|--------|-----|--------|
| **VoxyInstance** | Main | Lifecycle, world creation | Startup |
| **RenderGenerationService** | Pool (10) | Voxel → GPU geometry | ~1-5ms/frame |
| **ModelBakerySubsystem** | Dedicated | Block models → voxels | On-demand |
| **AsyncNodeManager** | Background | Hierarchy sync, uploads | ~1-2ms/frame |
| **HierarchicalOcclusionTraverser** | GPU | Culling via compute | ~0.5-1ms/frame |
| **AbstractSectionRenderer** | GPU | Draw calls, rendering | ~1-3ms/frame |
| **Storage** | Pool | Persistence (RocksDB) | Background |

---

## Thread Model

```
Main Thread (Render)
├─ Frame dispatch & GPU commands
├─ Sync point with AsyncNodeManager (VarHandle)
└─ ~2ms frame overhead

Background Threads
├─ RenderGenerationService (10 threads) - mesh building
├─ SectionSavingService - persistence
├─ VoxelIngestService - world import
├─ AsyncNodeManager (1) - hierarchy sync
├─ ModelBakerySubsystem (1) - baking
├─ WorldCleaner (1) - idle unload
└─ Total: ~16 threads (shares with Sodium if enabled)
```

---

## Key Classes & Patterns

### Data Flow Entry Points
- **VoxyClient.onInitializeClient()** - Fabric entry point
- **VoxyClientInstance** - Singleton factory for WorldEngine
- **MixinLevelRenderer** - Hooks renderer lifecycle

### Hierarchy Management
- **WorldEngine** - Multi-level coordinate system (4-bit level field)
- **WorldSection** - 32×32×32 voxel grid (long[] array)
- **NodeManager** - In-memory LOD tree structure

### Rendering
- **RenderPipelineFactory** - Selects Iris vs Normal pipeline
- **AbstractRenderPipeline** - Base render pipeline
- **HierarchicalOcclusionTraverser** - GPU-side culling engine
- **AbstractSectionRenderer** (MDIC) - Draw call generation

### Data Processing
- **RenderGenerationService** - Priority queue mesh builder
- **RenderDataFactory** - Converts voxels to GPU format
- **ModelBakerySubsystem** - Block model baker

---

## Coordinate System

**Section ID (64-bit, bit-packed):**
```
[63-60] Level (0-4)
[59-52] Y (0-255)
[51-28] Z (0-16M)
[27-4]  X (0-16M)
[3-0]   Spare
```

**Hierarchy Levels:**
- Level 0: 32×32×32 (highest detail)
- Level 1: 32×32×32 parent regions
- Level 2: 32×32×32 parent-parent regions
- Level 3: 32×32×32 parent-parent-parent
- Level 4: Global (entire world quadrant)

---

## GPU Pipeline Per-Frame

```
1. OcclusionCull (compute)
   - HZB pyramid depth test
   - Hierarchical traversal (5 iterations)
   - Output: visible mesh IDs

2. BuildDrawCalls (compute)
   - Multi-Draw Indirect parameters
   - Batch by material, sort by LOD

3. Render (draw)
   - Opaque pass
   - Temporal pass
   - Translucent pass

Total: ~3-11ms (120+ FPS capable)
```

---

## Synchronization & Concurrency

| Mechanism | Purpose | Where Used |
|-----------|---------|-----------|
| **VarHandle** | Lock-free atomic state | Node hierarchy |
| **StampedLock** | Optimistic reading | Task map |
| **ReentrantLock** | Thread-safe access | Geometry cache |
| **ConcurrentLinkedDeque** | Lock-free queues | Task queues |
| **AtomicInteger** | Counters | Work tracking |

**Frame Sync:**
- Render thread queues work
- Background threads process
- VarHandle exchanges state at sync point
- No explicit locking in critical path

---

## Storage System

**Backends:**
- RocksDB (default) - SSD optimized
- LMDB - Memory mapped
- Redis - Network distributed
- In-Memory - Testing

**Layers:**
- Compression (zstd/LZ4) - ~80% reduction
- Caching - Read-through
- Backend - Persistent storage

---

## Integration Points

### Minecraft
- **MixinLevelRenderer** - Create/destroy renderer per world
- **MixinClientLevel** - Block update integration
- **MixinRenderSystem** - Camera matrix capture

### Sodium
- **MixinChunkJobQueue** - Share thread pool
- Reduces total threads: 18+ → 12

### Iris
- **IrisVoxyRenderPipelineData** - Uniform/sampler binding
- **MixinProgramSet** - Shader interception
- **IrisVoxyRenderPipeline** - Full shader pack support

---

## Performance Targets

| Metric | Target |
|--------|--------|
| Frame Time (GPU) | 8ms (120 FPS) |
| Visible Sections | 200K max |
| Draw Calls | 1-10 indirect |
| GPU Memory | 2-4GB |
| CPU Memory | 1-2GB |
| Storage/World | 2-20GB |

---

## Configuration

**VoxyConfig Key Settings:**
- `enabled` - Toggle mod
- `ingestEnabled` - Enable voxelization
- `serviceThreads` - Dedicated threads
- `dontUseSodiumBuilderThreads` - Disable pool sharing

---

## Debugging Flags

```java
// Enable in system properties:
voxy.verifyNodeManager=true           // Validate hierarchy
voxy.verifyWorldSectionExecution=true // Check access rules
voxy.hierarchicalShaderDebug=true     // Compute debug
```

---

## Key Files Quick Map

**Entry:**
- `VoxyClient.java` - Fabric init
- `VoxyClientInstance.java` - Instance lifecycle
- `VoxyRenderSystem.java` - Per-world system

**Hierarchy:**
- `WorldEngine.java` - Voxel world model
- `WorldSection.java` - Section data (32×32×32)
- `NodeManager.java` - LOD tree

**Mesh Building:**
- `RenderGenerationService.java` - Mesh builder
- `RenderDataFactory.java` - Voxel → GPU
- `ModelBakerySubsystem.java` - Block models

**GPU Rendering:**
- `HierarchicalOcclusionTraverser.java` - Culling
- `AbstractSectionRenderer.java` - Draw calls
- `AbstractRenderPipeline.java` - Pipeline

**Integration:**
- `MixinLevelRenderer.java` - Minecraft hook
- `MixinChunkJobQueue.java` - Sodium pool
- `IrisVoxyRenderPipelineData.java` - Shader bridge

---

## Common Tasks

### Add a Renderer Backend
1. Extend `AbstractSectionRenderer<T extends Viewport<T>, J extends IGeometryData>`
2. Implement: `renderOpaque()`, `buildDrawCalls()`, `renderTranslucent()`, `createViewport()`
3. Register in `RenderPipelineFactory`

### Modify Storage Backend
1. Implement `StorageBackend` interface
2. Create config class extending `StorageConfig`
3. Configure in `StorageConfigUtil`

### Hook into Rendering
1. Create mixin targeting rendering class
2. Use `@Inject` at `@At` location
3. Call Voxy system via `VoxyCommon.getInstance()`

### Debug Performance
1. Use `TimingStatistics` and `RenderStatistics`
2. Enable debug screen (F3)
3. Monitor: section cache, model queue, thread pool

---

## TODOs (Future Work)

- [ ] Async upload stream (GPU-mapped persistent buffer)
- [ ] Persistent GPU threads (replace compute dispatch)
- [ ] Render cache (cache rendered geometry for static sections)
- [ ] Better model baking (texture download stream)
- [ ] Dynamic render distance (LOD adjustment)
- [ ] Reference counted worlds (better cleanup)

---

**Full Details:** See `CLAUDE.md` (648 lines, 27KB)
