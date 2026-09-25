# Blaze3D API Gap Analysis for Voxy Vulkan Migration

**Document Version:** 1.0
**Last Updated:** 2026-09-17
**Status:** Analysis Complete

## Executive Summary

This document analyzes the feasibility of leveraging Minecraft's Blaze3D abstraction layer for Voxy's Vulkan migration, comparing Voxy's rendering requirements against Blaze3D's capabilities as of Minecraft 26.2.

**Key Finding:** Blaze3D provides a Vulkan backend but **cannot fully support Voxy's rendering architecture** due to missing compute shader capabilities. A **hybrid approach** is recommended, using Blaze3D where possible while maintaining direct API access for compute-intensive features.

---

## Minecraft 26.2 Blaze3D Capabilities

### What Blaze3D DOES Provide

#### 1. **Multi-Backend Abstraction** ✅
- Full OpenGL backend implementation (`com.mojang.blaze3d.opengl.*`)
- Experimental Vulkan backend (`com.mojang.blaze3d.vulkan.*`)
- Parallel class structure across both backends
- Runtime backend selection via user configuration
- Planned deprecation of OpenGL in favor of Vulkan

#### 2. **Buffer Management** ✅ (Partial)
- **GpuBuffer API**: Abstract buffer wrapper
  - `USAGE_VERTEX` - Vertex buffers
  - `USAGE_INDEX` - Index buffers
  - `USAGE_UNIFORM` - Uniform buffers (UBOs)
  - `USAGE_COPY_SRC/DST` - Data transfer
  - `USAGE_MAP_READ/WRITE` - Memory mapping
- **GpuBufferSlice**: Efficient buffer sub-allocation
- Memory-mapped buffer support
- Client storage optimization hints

**Limitations:**
- ❌ No explicit SSBO (Shader Storage Buffer Object) support
- ❌ No indirect draw buffer support mentioned
- ❌ No sparse buffer support
- ❌ No persistent mapped buffer API

#### 3. **Texture System** ✅
- **GlTextureView**: Texture view abstraction
- **GpuSampler**: Sampler state management
- **GpuFormat**: Unified format system (e.g., `RGBA32_SINT`, `D32_FLOAT_S8_UINT`)
- Texture arrays support (assumed based on GlTextureView)
- Mipmap generation capabilities

#### 4. **Pipeline State Management** ✅ (Partial)
- **DepthStencilState**: Depth testing configuration
  - CompareOp abstraction (LESS, GREATER, EQUAL, etc.)
  - Reverse-Z detection via `isZZeroToOne()`
- **BlendFactor/BlendOp**: Blend state management
- **VertexFormat**: Dynamic vertex attribute builders
  - Maximum 16 attributes per format
  - Multiple vertex buffer support per pipeline
- **BindGroupLayout**: Descriptor set abstraction
  - Separates sampler names and uniform descriptions
  - Decoupled from render pipelines

#### 5. **Resource Management** ✅
- **GraphicsResourceAllocator**: Resource lifecycle management
- Automatic cleanup via `AutoCloseable` interfaces
- Backend-agnostic device info (`GpuDevice`)

#### 6. **Rendering System** ✅ (Graphics Only)
- **FeatureRenderer**: Feature-based rendering architecture
- **RenderPipeline**: Pipeline abstraction
- **SubmitNode**: Command submission
- Matrix stacks (`PoseStack`, `Matrix3x2fStack`)
- Draw modes: `TRIANGLES`, `TRIANGLE_STRIP`, `QUADS`, `LINES`

### What Blaze3D DOES NOT Provide

#### 1. **Compute Shader Support** ❌ (CRITICAL)
- **No compute pipeline API**
- No compute shader compilation
- No dispatch commands
- No compute-specific resource bindings

**Impact on Voxy:**
- Cannot use Blaze3D for hierarchical occlusion culling
- Cannot use Blaze3D for draw command generation
- Cannot use Blaze3D for prefix sum operations
- Cannot use Blaze3D for translucent sorting

#### 2. **Shader Storage Buffers (SSBOs)** ❌ (CRITICAL)
- Blaze3D only exposes uniform buffers (`USAGE_UNIFORM`)
- No large, mutable, structured buffer support
- UBOs have size limits (~16-64KB typical)

**Impact on Voxy:**
- Node hierarchy storage requires SSBOs (16MB+ buffers)
- Indirect draw command buffers require SSBO-like semantics
- Visibility tracking buffers require read-write access

#### 3. **Multi-Draw Indirect (MDI)** ❌ (CRITICAL)
- No API for indirect draw calls
- No `GL_DRAW_INDIRECT_BUFFER` equivalent
- No `VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT` equivalent

**Impact on Voxy:**
- Cannot render 200K+ sections efficiently
- Would require CPU-side draw call generation
- Performance regression vs. current implementation

#### 4. **Custom Shader Compilation Pipeline** ❌
- No support for custom GLSL preprocessing
- No shader patching API
- No specialization constants
- Limited to Blaze3D's built-in shader system

**Impact on Voxy:**
- Cannot inject TAA (Temporal Anti-Aliasing) code
- Cannot patch Iris shader pack shaders
- Cannot customize shader defines per-pipeline

#### 5. **Advanced OpenGL/Vulkan Features** ❌
- No persistent mapped buffers
- No sparse buffers
- No timeline semaphores (for async uploads)
- No explicit memory barriers
- No query objects for GPU timing

---

## Voxy's Rendering Requirements

### Core Features and Their Dependencies

| Feature | OpenGL Dependency | Can Use Blaze3D? | Notes |
|---------|------------------|------------------|-------|
| **Hierarchical Occlusion Culling** | Compute shaders, SSBOs | ❌ No | Requires compute dispatch |
| **Draw Command Generation** | Compute shaders, SSBOs, MDI | ❌ No | Requires compute + MDI |
| **Prefix Sum Operations** | Compute shaders, SSBOs | ❌ No | Translucent sorting |
| **HZB Pyramid Generation** | Compute shaders or mipmap blit | ⚠️ Partial | Could use texture blitting |
| **Geometry Rendering (Opaque)** | Vertex/fragment shaders, VAO | ✅ Yes | Could use FeatureRenderer |
| **Geometry Rendering (Translucent)** | Vertex/fragment shaders, blending | ✅ Yes | Could use FeatureRenderer |
| **Texture Management** | Texture arrays, atlasing | ✅ Yes | GpuSampler, GlTextureView |
| **Depth Testing** | Depth state, reverse-Z | ✅ Yes | DepthStencilState |
| **Async Mesh Uploads** | Buffer uploads, fences | ⚠️ Partial | GpuBuffer + mapping |
| **Model Baking** | CPU-side, texture uploads | ✅ Yes | GraphicsResourceAllocator |
| **Chunk Bounding Rendering** | Vertex/fragment shaders | ✅ Yes | Could use FeatureRenderer |
| **TAA Integration** | Shader patching, uniforms | ❌ No | No shader patching API |
| **Iris Shader Pack Integration** | Shader patching, SSBO binding | ❌ No | Requires shader patching |
| **GPU Timing Queries** | Query objects | ❌ No | No query API exposed |

### Critical Path Analysis

**Compute-Dependent Features (Cannot Use Blaze3D):**
1. `HierarchicalOcclusionTraverser` (traversal_dev.comp)
   - Uses 10+ SSBOs
   - Dispatches compute shaders per LOD level
   - Requires atomic counters
2. `MDICSectionRenderer` compute shaders:
   - `cmdgen.comp` - Draw command generation
   - `prep.comp` - Preparation pass
   - `prefixsum.comp` - Prefix sum for sorting
   - `buildtranslucents.comp` - Translucent mesh building
3. `AsyncNodeManager` GPU uploads
   - Requires SSBO binding
   - Timeline semaphores for completion tracking
4. `IrisVoxyRenderPipeline` shader patching
   - Custom GLSL injection
   - SSBO binding for shader packs

**Graphics-Only Features (Can Use Blaze3D):**
1. Terrain rendering (opaque/translucent passes)
   - Vertex/fragment shaders only
   - Standard depth/blend state
2. Chunk bounding box rendering
   - Simple vertex rendering
3. Texture uploads and management
   - Texture arrays, mipmaps
4. Model baking CPU-side processing
   - No GPU-specific requirements

---

## Gap Analysis Summary

### Coverage Matrix

| Category | Blaze3D Support | Voxy Usage | Blocker? |
|----------|----------------|-----------|----------|
| **Compute Shaders** | ❌ Not supported | ✅ Critical | 🔴 YES |
| **SSBOs** | ❌ Not supported | ✅ Critical | 🔴 YES |
| **Multi-Draw Indirect** | ❌ Not supported | ✅ Critical | 🔴 YES |
| **Shader Patching** | ❌ Not supported | ✅ Critical (Iris) | 🔴 YES |
| **Vertex/Fragment Shaders** | ✅ Full support | ✅ Used | 🟢 NO |
| **Texture Management** | ✅ Full support | ✅ Used | 🟢 NO |
| **Buffer Management** | ⚠️ Partial (no SSBO) | ✅ Critical | 🟡 PARTIAL |
| **Pipeline State** | ✅ Full support | ✅ Used | 🟢 NO |
| **Depth/Stencil** | ✅ Full support | ✅ Used | 🟢 NO |
| **Resource Allocation** | ✅ Full support | ✅ Used | 🟢 NO |

### Quantitative Assessment

- **Features Fully Supported by Blaze3D:** ~30%
- **Features Partially Supported:** ~20%
- **Features Not Supported:** ~50%

**Conclusion:** Blaze3D can support less than half of Voxy's rendering pipeline.

---

## Migration Strategy Options

### Option 1: Full Blaze3D Migration ❌ (Not Viable)

**Approach:** Rewrite entire rendering pipeline using only Blaze3D APIs.

**Pros:**
- Full backend portability (OpenGL/Vulkan)
- Automatic Minecraft version compatibility
- No direct API dependencies

**Cons:**
- **Requires complete redesign** of core rendering architecture
- Must eliminate compute shaders (performance regression)
- Must eliminate MDI (severe performance regression)
- Must eliminate SSBOs (architectural impossibility)
- Estimated performance loss: **70-90%**
- Development time: **12-18 months**

**Verdict:** Not viable. Would destroy Voxy's performance advantages.

---

### Option 2: Hybrid Approach (Blaze3D + Direct API) ✅ (RECOMMENDED)

**Approach:** Use Blaze3D for graphics-only features, direct OpenGL/Vulkan for compute features.

#### Architecture:

```
┌─────────────────────────────────────────────────┐
│           Voxy Rendering Core                   │
└─────────────────────────────────────────────────┘
                      │
        ┌─────────────┴─────────────┐
        ▼                           ▼
┌──────────────────┐      ┌──────────────────┐
│  Blaze3D Layer   │      │  Direct API Layer│
│  (Graphics Only) │      │  (Compute Heavy) │
└──────────────────┘      └──────────────────┘
        │                           │
        ▼                           ▼
┌──────────────────┐      ┌──────────────────┐
│ GpuBuffer        │      │ Raw VkBuffer     │
│ FeatureRenderer  │      │ VkComputePipeline│
│ DepthStencilState│      │ VkCommandBuffer  │
└──────────────────┘      └──────────────────┘
        │                           │
        └──────────────┬────────────┘
                       ▼
              ┌────────────────┐
              │  Vulkan API    │
              └────────────────┘
```

#### What Uses Blaze3D:
1. **Terrain Rendering (Opaque/Translucent)**
   - Replace `MDICSectionRenderer` draw calls with `FeatureRenderer`
   - Use `VertexFormat` builders
   - Use `DepthStencilState` for depth configuration
2. **Texture Management**
   - Use `GpuSampler` for sampler state
   - Use `GlTextureView` for texture binding
3. **Model Baking**
   - Use `GraphicsResourceAllocator` for resource lifecycle
4. **Basic State Management**
   - Use `BlendFactor`/`BlendOp` for blend state
   - Use `RenderSystem` for global state queries

#### What Uses Direct Vulkan:
1. **Compute Pipelines**
   - `HierarchicalOcclusionTraverser` (occlusion culling)
   - `MDICSectionRenderer` compute passes (command gen, prefix sum)
2. **SSBO Management**
   - Node hierarchy buffers
   - Visibility tracking buffers
   - Indirect draw command buffers
3. **Multi-Draw Indirect**
   - Indirect draw call execution
4. **Shader Compilation & Patching**
   - TAA shader injection
   - Iris shader pack integration
5. **Advanced Features**
   - Timeline semaphores for async uploads
   - GPU timing queries
   - Memory barriers

#### Interop Strategy:
- **Shared Context:** Blaze3D and direct Vulkan share same `VkDevice`
- **Resource Synchronization:** Pipeline barriers between Blaze3D and compute passes
- **Handle Extraction:** Extract raw Vulkan handles from Blaze3D objects where needed
- **State Isolation:** Save/restore Blaze3D state around direct API usage

**Pros:**
- Leverages Blaze3D's Vulkan backend for graphics
- Maintains compute shader performance
- Reduces custom abstraction layer scope
- Partial Minecraft API compatibility

**Cons:**
- Complex interop between Blaze3D and direct API
- Still requires Vulkan expertise
- May break if Blaze3D makes breaking changes
- Synchronization overhead

**Estimated Development Time:** 4-6 months
**Performance Impact:** -5% to +10% (improved Vulkan integration)

---

### Option 3: Full Custom Abstraction (Original Plan) ⚠️

**Approach:** Implement full abstraction layer as per VULKAN_MIGRATION_PLAN.md, ignore Blaze3D.

**Pros:**
- Complete control over rendering pipeline
- No Blaze3D limitations or dependencies
- Can optimize for Voxy's specific needs
- No Minecraft version coupling

**Cons:**
- Duplicates work (Mojang already wrote Vulkan backend)
- Must maintain custom OpenGL + Vulkan backends
- Higher development cost (6-9 months)
- May conflict with future Minecraft changes

**Verdict:** Viable but inefficient. Use only if Hybrid approach fails.

---

## Recommended Approach: Hybrid Architecture

### Phase 1: Blaze3D Integration for Graphics (6-8 weeks)

#### 1.1 Assess Blaze3D Vulkan Backend Maturity
- Test Blaze3D Vulkan backend stability
- Verify feature parity with OpenGL backend
- Identify driver compatibility issues
- Performance benchmark vs. raw Vulkan

#### 1.2 Port Graphics-Only Rendering to Blaze3D
**Files to Modify:**
- `MDICSectionRenderer.java` - Use `FeatureRenderer` for draw calls
- `BoundRenderer.java` - Use Blaze3D vertex rendering
- `FullscreenBlit.java` - Use Blaze3D texture blitting

**New Classes:**
- `Blaze3DTerrainRenderer` extends `FeatureRenderer`
- `Blaze3DBufferManager` wraps `GpuBuffer`
- `Blaze3DTextureManager` wraps `GlTextureView`

#### 1.3 State Management Refactoring
- Replace manual GL state calls with `DepthStencilState`
- Use `BlendFactor`/`BlendOp` for blending
- Migrate texture binding to `GpuSampler`

### Phase 2: Direct Vulkan for Compute (10-12 weeks)

#### 2.1 Vulkan Compute Context Setup
- Initialize Vulkan instance (share with Blaze3D if possible)
- Create compute queue (separate from graphics queue)
- Setup memory allocator for compute resources

#### 2.2 Compute Pipeline Implementation
**Components:**
- `VulkanComputePipeline` - Wrapper for compute shaders
- `VulkanSSBO` - SSBO management
- `VulkanIndirectBuffer` - MDI buffer handling

**Port Compute Shaders:**
1. `traversal_dev.comp` → SPIR-V
2. `cmdgen.comp` → SPIR-V
3. `prep.comp` → SPIR-V
4. `prefixsum.comp` → SPIR-V
5. `buildtranslucents.comp` → SPIR-V

#### 2.3 Blaze3D ↔ Vulkan Synchronization
- Pipeline barriers between Blaze3D graphics and Vulkan compute
- Handle extraction from Blaze3D objects (if needed)
- Command buffer coordination

### Phase 3: Iris Integration (4-5 weeks)

#### 3.1 Shader Patching for Vulkan
- Adapt `IrisShaderPatch` to generate SPIR-V
- Runtime GLSL → SPIR-V compilation via shaderc
- Uniform/SSBO mapping for shader packs

#### 3.2 Render Target Coordination
- Map Iris gbuffer to Blaze3D FeatureRenderPhase
- Ensure Blaze3D doesn't interfere with Iris passes

### Phase 4: Testing & Optimization (3-4 weeks)

#### 4.1 Compatibility Testing
- Test with Sodium (ensure no conflicts)
- Test with Iris shader packs
- Test on NVIDIA, AMD, Intel GPUs

#### 4.2 Performance Validation
- Benchmark vs. current OpenGL implementation
- Profile Blaze3D overhead
- Optimize synchronization points

---

## Technical Considerations

### Blaze3D Vulkan Backend Unknowns

**Critical Questions:**
1. **Does Blaze3D expose raw Vulkan handles?**
   - Can we get `VkBuffer`, `VkImage`, `VkDevice` from Blaze3D objects?
   - Needed for interop with direct Vulkan compute
2. **What's the command buffer model?**
   - Does Blaze3D manage command buffers internally?
   - Can we inject Vulkan commands into Blaze3D's command stream?
3. **Synchronization primitives?**
   - Does Blaze3D expose semaphores/fences?
   - Can we synchronize with custom Vulkan work?
4. **Memory management?**
   - Does Blaze3D use VMA or custom allocator?
   - Can we share allocations with compute resources?

**Action:** Reverse-engineer Blaze3D Vulkan implementation to answer these.

### Iris Shader Pack Challenges

**Problem:** Iris shaders are written in GLSL with OpenGL assumptions.

**Hybrid Approach:**
- Graphics shaders → Translate to Blaze3D-compatible GLSL → SPIR-V
- Compute passes → Direct SPIR-V compilation
- Patching strategy: Inject Voxy code before SPIR-V compilation

**Fallback:** If shader pack incompatible, disable Voxy rendering (current behavior).

---

## Risk Assessment

| Risk | Severity | Mitigation |
|------|----------|------------|
| **Blaze3D Vulkan backend unstable** | 🔴 High | Fallback to OpenGL backend |
| **Cannot extract Vulkan handles** | 🔴 High | Implement full custom abstraction |
| **Blaze3D API changes in future MC versions** | 🟡 Medium | Version detection, fallback strategies |
| **Performance regression from Blaze3D overhead** | 🟡 Medium | Profile and optimize, bypass if needed |
| **Synchronization bugs between Blaze3D and compute** | 🟡 Medium | Extensive testing, validation layers |
| **Iris shader incompatibility** | 🟢 Low | Already have fallback mechanism |

---

## Success Criteria

### Functional Requirements
- ✅ Terrain rendering works via Blaze3D on Vulkan backend
- ✅ Compute shaders execute via direct Vulkan
- ✅ Blaze3D and compute work synchronizes correctly
- ✅ Iris shader packs remain functional
- ✅ Graceful fallback to OpenGL if Vulkan unavailable

### Performance Requirements
- ✅ Vulkan performance ≥ 95% of current OpenGL performance
- ✅ No CPU-GPU synchronization stalls
- ✅ Memory usage within 10% of OpenGL baseline

### Quality Requirements
- ✅ No Vulkan validation errors
- ✅ Works on 90%+ of Vulkan-capable GPUs
- ✅ No visual regressions

---

## Conclusion

**Blaze3D provides valuable infrastructure** for graphics rendering but **cannot replace compute-intensive features**. The recommended **Hybrid Approach** leverages Blaze3D's Vulkan backend for graphics while maintaining direct Vulkan access for compute shaders, SSBOs, and MDI.

### Key Recommendations:

1. **Adopt Blaze3D for:**
   - Terrain rendering (vertex/fragment)
   - Texture management
   - Depth/blend state
   - Resource allocation

2. **Keep Direct Vulkan for:**
   - Compute pipelines
   - SSBOs
   - Multi-Draw Indirect
   - Shader patching

3. **Next Steps:**
   - Reverse-engineer Blaze3D Vulkan backend to assess interop feasibility
   - Prototype Blaze3D terrain renderer
   - Test handle extraction and synchronization
   - If interop successful → Hybrid approach
   - If interop fails → Full custom abstraction

### Estimated Timeline:
- **Hybrid Approach:** 4-6 months
- **Full Custom Abstraction:** 6-9 months

**Decision Point:** After Phase 1 prototype (6-8 weeks), evaluate if hybrid approach provides sufficient benefits over full custom abstraction.

---

**Document Status:** Ready for review and decision-making.
