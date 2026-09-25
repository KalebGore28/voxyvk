package me.cortex.voxy.client.core.vk.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.TransientMemory;
import com.mojang.blaze3d.vertex.VertexFormat;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.bounding.StreamedBoundStore;
import me.cortex.voxy.client.core.vk.VkCmd;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalDouble;

//Rasterizes an AABB per Sodium-visible chunk section into the viewport's depth-bound
// target with a "further" depth test, capturing the far bound of the volume vanilla terrain
// covers. The terrain fragment shader (quads.frag, sampler binding 10) then discards LOD
// fragments behind it, saving overdraw. The OpenGL path keeps its BoundRenderer.
//
//The Vulkan path's first pass built entirely on Blaze3D's public API (migration step 4.1):
// a RenderPipeline with Minecraft-format shaders (voxy:core/chunk_bounds), a RenderPass on
// Blaze3D textures (VkViewport.ensureBoundTargets), and per-frame data in Blaze3D's
// transient memory. Blaze3D records it into MC's command stream, so VkFrameCtx.blaze3d
// places it in Voxy's frame.
//
//Two Blaze3D gaps shape it (26.2):
//  - no depth-only passes. CommandEncoder.createRenderPass reads the first colour
//    attachment's size without a null check, so withUnusedColorAttachment() cannot come
//    first, and RenderPass.setPipeline requires the pipeline's colour targets to match the
//    pass's attachments while every pipeline has at least one. The pass therefore carries an
//    R8 colour target that is cleared (never loaded) and that the pipeline never writes;
//  - no storage buffers, so the section positions are a per-instance vertex attribute.
//As in the raw VK renderer this replaces: one 36-index box per instance instead of GL's
// 32-box batches, and no face culling (with the further compare the back faces win anyway).
public final class Blaze3DBoundRenderer {
    private static final String UNIFORM_BLOCK = "VoxyChunkBounds";
    private static final int UNIFORM_SIZE = 96;//std140: mat4 MVP, ivec4 camera block, vec4 inner block + radius

    //One per depth direction for the game's lifetime: Blaze3D caches each RenderPipeline
    // object's compiled pipeline until the next resource reload, so building one per renderer
    // would pile up compiled pipelines
    private static RenderPipeline pipelineForwardZ, pipelineReverseZ;

    private final VkFrameCtx ctx;
    private final RenderProperties properties;
    private final RenderPipeline pipeline;
    private final GpuBuffer boxIndices;//36 u16 indices over the 8 box corners
    private boolean reportedInvalid;

    //Per-frame uniform scratch (reused to avoid heap allocs)
    private final Vector3i cameraBlock = new Vector3i();
    private final Vector3f innerBlock = new Vector3f();
    private final Matrix4f mvpScratch = new Matrix4f();
    private final Vector4f clearColour = new Vector4f();

    public Blaze3DBoundRenderer(VkFrameCtx ctx, RenderProperties properties) {
        this.ctx = ctx;
        this.properties = properties;
        this.pipeline = pipeline(properties.isReverseZ());
        ByteBuffer indices = MemoryUtil.memAlloc(6 * 2 * 3 * 2);
        try {
            VkCmd.writeCubeIndicesU16(MemoryUtil.memAddress(indices));
            //Uploaded through MC's command stream (constructed at a frame boundary, see
            // MixinMinecraftFrameStart)
            this.boxIndices = RenderSystem.getDevice().createBuffer(() -> "voxy chunk-bound box indices", GpuBuffer.USAGE_INDEX, indices);
        } finally {
            MemoryUtil.memFree(indices);
        }
    }

    private static synchronized RenderPipeline pipeline(boolean reverseZ) {
        RenderPipeline pipeline = reverseZ ? pipelineReverseZ : pipelineForwardZ;
        if (pipeline == null) {
            pipeline = RenderPipeline.builder()
                    .withLocation(Identifier.fromNamespaceAndPath("voxy", reverseZ ? "pipeline/chunk_bounds_reverse_z" : "pipeline/chunk_bounds"))
                    .withVertexShader(Identifier.fromNamespaceAndPath("voxy", "core/chunk_bounds"))
                    .withFragmentShader(Identifier.fromNamespaceAndPath("voxy", "core/chunk_bounds"))
                    .withShaderDefine("CLOSER_SIGN", reverseZ ? 1.0f : -1.0f)
                    .withBindGroupLayout(BindGroupLayout.builder().withUniform(UNIFORM_BLOCK, UniformType.UNIFORM_BUFFER).build())
                    //Step rate 1: one packed section position per box instance
                    .withVertexBinding(0, VertexFormat.builder(1).addAttribute("ChunkPos", GpuFormat.RG32_SINT).build())
                    .withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R8_UNORM, ColorTargetState.WRITE_NONE))
                    //"further" compare: keep the farthest fragment (the AABB back face)
                    .withDepthStencilState(new DepthStencilState(reverseZ ? CompareOp.LESS_THAN : CompareOp.GREATER_THAN, true))
                    .withCull(false)
                    .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                    .build();
            if (reverseZ) {
                pipelineReverseZ = pipeline;
            } else {
                pipelineForwardZ = pipeline;
            }
        }
        return pipeline;
    }

    //Clears the viewport's depth-bound target to the "nothing covered" depth and rasterizes the
    // store's sections into it. Call before anything else in the frame reads the target.
    public void render(VkViewport viewport, StreamedBoundStore store) {
        viewport.ensureBoundTargets();
        if (viewport.depthBound == null) return;
        var device = RenderSystem.getDevice();
        //Compiled on first use from Minecraft's shader sources; a shader error leaves it invalid,
        // and binding an invalid pipeline throws, so the pass then only clears
        boolean valid = device.precompilePipeline(this.pipeline).isValid();
        if (!valid && !this.reportedInvalid) {
            this.reportedInvalid = true;
            Logger.error("Voxy VK: the chunk-bounds pipeline failed to compile (see the shader errors above); LODs render without vanilla-coverage culling");
        }
        int count = store.getCount();
        this.ctx.blaze3d(() -> {
            var encoder = device.createCommandEncoder();
            GpuBufferSlice uniforms = null, positions = null;
            if (valid && count != 0) {
                uniforms = this.writeUniforms(encoder.transientMemory(), viewport);
                positions = writePositions(encoder.transientMemory(), store, count);
            }
            var descriptor = RenderPassDescriptor.create(() -> "voxy chunk bounds")
                    .withColorAttachment(viewport.boundColourView, Optional.of(this.clearColour))
                    .withDepthAttachment(viewport.depthBoundView, OptionalDouble.of(this.properties.inverseClearDepth()))
                    .withRenderArea(new RenderPass.RenderArea(0, 0, viewport.width, viewport.height));
            try (var pass = encoder.createRenderPass(descriptor)) {
                if (positions != null) {
                    pass.setPipeline(this.pipeline);
                    pass.setUniform(UNIFORM_BLOCK, uniforms);
                    pass.setVertexBuffer(0, positions);
                    pass.setIndexBuffer(this.boxIndices, IndexType.SHORT);
                    pass.drawIndexed(6 * 2 * 3, count, 0, 0, 0);
                }
            }
        });
    }

    //Same layout as the GL BoundRenderer's uniform: MVP translated by the camera's position
    // inside its block, the camera's block, then that position and the render distance
    private GpuBufferSlice writeUniforms(TransientMemory memory, VkViewport viewport) {
        long alignment = RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment();
        try (var mapped = memory.allocateGpuMapped(UNIFORM_SIZE, alignment, GpuBuffer.USAGE_UNIFORM)) {
            long ptr = MemoryUtil.memAddress(mapped.data());
            final float renderDistance = Minecraft.getInstance().options.getEffectiveRenderDistance() * 16;//In blocks
            int bx = (int) Math.floor(viewport.cameraX);
            int by = (int) Math.floor(viewport.cameraY);
            int bz = (int) Math.floor(viewport.cameraZ);
            this.cameraBlock.set(bx, by, bz).getToAddress(ptr + 64);
            var inner = this.innerBlock.set(
                    (float) (viewport.cameraX - bx),
                    (float) (viewport.cameraY - by),
                    (float) (viewport.cameraZ - bz));
            inner.getToAddress(ptr + 80);
            MemoryUtil.memPutFloat(ptr + 92, renderDistance);
            viewport.MVP.translate(-inner.x, -inner.y, -inner.z, this.mvpScratch).getToAddress(ptr);
            return mapped.slice();
        }
    }

    private static GpuBufferSlice writePositions(TransientMemory memory, StreamedBoundStore store, int count) {
        try (var mapped = memory.allocateGpuMapped(count * 8L, 8, GpuBuffer.USAGE_VERTEX)) {
            MemoryUtil.memIntBuffer(MemoryUtil.memAddress(mapped.data()), count * 2)
                    .put(store.packedPositions(), 0, count * 2);
            return mapped.slice();
        }
    }

    public void free() {
        //Blaze3D destroys it once the submission being recorded now has completed
        this.boxIndices.close();
    }
}
