package me.cortex.voxy.client.core.vk.render;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkCmd;
import me.cortex.voxy.client.core.vk.VkDownloadStream;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkImage2D;
import me.cortex.voxy.client.core.vk.VkShaderPipeline;
import me.cortex.voxy.client.core.vk.VkShaderSource;
import me.cortex.voxy.client.core.vk.VkUploadStream;
import me.cortex.voxy.common.Logger;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfoKHR;
import org.lwjgl.vulkan.VkRenderingInfoKHR;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirectCount;

//Pure-VK mirror of MDICSectionRenderer: the same six GPU passes (prep,
// raster-cull, command generation, translucency prefix sort + build, then
// opaque / temporal / translucent indexed-indirect-count draws) against the
// same buffer layouts, recorded into MC's frame command buffer with dynamic
// rendering over Voxy's offscreen colour + depth-stencil targets.
public class VkTerrainRenderer {
    //Draw-command offsets shared with the cmdgen/translucent shader defines.
    private static final int TRANSLUCENT_OFFSET = VkViewport.OPAQUE_DRAW_COUNT;
    private static final int TEMPORAL_OFFSET = TRANSLUCENT_OFFSET + VkViewport.TRANSLUCENT_DRAW_COUNT;

    private final VkFrameCtx ctx;
    private final VkUploadStream uploadStream;
    private final VkDownloadStream downloadStream;
    private final RenderProperties properties;
    private final VkSectionGeometryData geometry;
    private final VkModelStore modelStore;

    //MoltenVK fixed-count fallback state (see DrawBudget), fed by the per-pass draw
    // counts read back from drawCountCallBuffer each frame. On desktop the GPU sources
    // these counts itself via vkCmdDrawIndexedIndirectCount.
    private final DrawBudget opaqueBudget = new DrawBudget(1024, true);
    private final DrawBudget translucentBudget = new DrawBudget(256, true);
    private final DrawBudget temporalBudget = new DrawBudget(256, false);
    //Where the camera looked, and its vertical FOV (radians), when cmdgen built the latest
    // draw lists and the lists before them: what the budgets are sized for
    private final Vector3f listDir = new Vector3f();
    private final Vector3f prevListDir = new Vector3f();
    private float listFov, prevListFov;
    //Lists left whose FOV changed recently (spyglass, sprinting): their counts follow no
    // history yet, so the budgets stay conservative until those counts are read back
    private int fovUnsettledLists;

    private final VkBuffer uniform;
    private final VkBuffer distanceCountBuffer;
    private final VkBuffer indexBuffer;
    private final long depthBoundSampler;
    private final long lightmapSampler;

    private final VkShaderPipeline prep;
    private final VkShaderPipeline cmdGen;
    private final VkShaderPipeline prefixSum;
    private final VkShaderPipeline translucentGen;
    private final VkShaderPipeline cullRaster;
    private VkShaderPipeline terrainOpaque;
    private VkShaderPipeline terrainTranslucent;
    private int pipelineColorFormat = -1, pipelineDepthFormat = -1;

    public VkTerrainRenderer(VkFrameCtx ctx, VkUploadStream uploadStream, VkDownloadStream downloadStream,
                             RenderProperties properties, VkSectionGeometryData geometry, VkModelStore modelStore) {
        this.ctx = ctx;
        this.uploadStream = uploadStream;
        this.downloadStream = downloadStream;
        this.properties = properties;
        this.geometry = geometry;
        this.modelStore = modelStore;

        this.uniform = new VkBuffer(ctx, 1024).zero();
        this.distanceCountBuffer = new VkBuffer(ctx, 1024L * 4 + VkViewport.TRANSLUCENT_DRAW_COUNT * 4L).zero();

        //Shared index buffer: u16 quad pattern + u16 cube indices at CUBE_INDEX_OFFSET
        this.indexBuffer = new VkBuffer(ctx, SharedIndexBuffer.CUBE_INDEX_OFFSET + 6 * 2 * 3 * 2L);
        {
            var quads = SharedIndexBuffer.generateQuadIndicesShort(16380);
            long ptr = uploadStream.upload(this.indexBuffer, 0, this.indexBuffer.size());
            quads.cpyTo(ptr);
            VkCmd.writeCubeIndicesU16(ptr + SharedIndexBuffer.CUBE_INDEX_OFFSET);
            quads.free();
            uploadStream.commit();
            ctx.flushImmediate();
        }
        this.depthBoundSampler = VkImage2D.createSampler(ctx.vk(), false, false);
        this.lightmapSampler = VkImage2D.createSampler(ctx.vk(), false, true);

        //================= compute pipelines =================
        this.prep = new VkShaderPipeline(ctx, "prep.comp",
                VkShaderSource.load("voxy:lod/gl46/prep.comp", VkShaderSource.defs().build()),
                0, List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(2)));

        this.cmdGen = new VkShaderPipeline(ctx, "cmdgen.comp",
                VkShaderSource.load("voxy:lod/gl46/cmdgen.comp", VkShaderSource.defs()
                        .def("TRANSLUCENT_WRITE_BASE", 1024)
                        .def("TEMPORAL_OFFSET", TEMPORAL_OFFSET)
                        .def("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 7)
                        .build()),
                0, List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(2),
                        VkShaderPipeline.ssbo(3), VkShaderPipeline.ssbo(4), VkShaderPipeline.ssbo(5),
                        VkShaderPipeline.ssbo(6), VkShaderPipeline.ssbo(7)));

        //Subgroup prefix sum when compute shaders have subgroup arithmetic and a
        // subgroup is wide enough (>= 16) to scan every per-subgroup total of the
        // 256-wide group. Falls back to the shared-memory Hillis-Steele scan otherwise.
        boolean useSubgroup = ctx.vk().supportsSubgroupPrefixSum();
        this.prefixSum = new VkShaderPipeline(ctx, "prefixsum.comp",
                VkShaderSource.load(useSubgroup ? "voxy:util/prefixsum/inital3_vk.comp" : "voxy:util/prefixsum/simple.comp",
                        VkShaderSource.defs().def("IO_BUFFER", 0).build()),
                0, List.of(VkShaderPipeline.ssbo(0)));

        this.translucentGen = new VkShaderPipeline(ctx, "buildtranslucents.comp",
                VkShaderSource.load("voxy:lod/gl46/buildtranslucents.comp", VkShaderSource.defs()
                        .def("TRANSLUCENT_WRITE_BASE", 1024)
                        .def("TRANSLUCENT_DISTANCE_BUFFER_BINDING", 5)
                        .def("TRANSLUCENT_OFFSET", TRANSLUCENT_OFFSET)
                        .build()),
                0, List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(2),
                        VkShaderPipeline.ssbo(3), VkShaderPipeline.ssbo(4), VkShaderPipeline.ssbo(5)));

        //================= raster cull pipeline (depth-only, no writes) =================
        var cullDesc = new VkShaderPipeline.GfxDesc();
        cullDesc.name = "cullraster";
        cullDesc.vertGlsl = VkShaderSource.load("voxy:lod/gl46/cull/raster.vert", VkShaderSource.defs().props(properties).build());
        cullDesc.fragGlsl = VkShaderSource.load("voxy:lod/gl46/cull/raster.frag", VkShaderSource.defs().props(properties).build());
        cullDesc.colorFormat = VK_FORMAT_UNDEFINED;
        cullDesc.depthFormat = ctx.vk().depthStencilFormat;//matches VkViewport.depthStencil
        cullDesc.stencilFormat = ctx.vk().depthStencilFormat;
        cullDesc.depthTest = true;
        cullDesc.depthWrite = false;
        cullDesc.colorWrite = false;
        cullDesc.depthCompare = VkCmd.closerEqual(this.properties);
        cullDesc.bindings = List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1),
                VkShaderPipeline.ssbo(2), VkShaderPipeline.ssbo(3));
        this.cullRaster = new VkShaderPipeline(ctx, cullDesc);
    }

    private void ensureTerrainPipelines(VkViewport viewport) {
        int cf = viewport.colour.format;
        int df = viewport.depthStencil.format;
        if (this.terrainOpaque != null && cf == this.pipelineColorFormat && df == this.pipelineDepthFormat) return;
        if (this.terrainOpaque != null) {
            this.terrainOpaque.free();
            this.terrainTranslucent.free();
        }
        var cardinalLight = net.minecraft.client.Minecraft.getInstance().level.cardinalLighting();
        String vert = VkShaderSource.load("voxy:lod/gl46/quads3.vert", VkShaderSource.defs().props(this.properties)
                .def("NO_SHADE_FACE_TINT", cardinalLight.up())
                .def("UP_FACE_TINT", cardinalLight.up())
                .def("DOWN_FACE_TINT", cardinalLight.down())
                .def("Z_AXIS_FACE_TINT", cardinalLight.north())
                .def("X_AXIS_FACE_TINT", cardinalLight.east())
                .build());

        var bindings = List.of(VkShaderPipeline.ubo(0), VkShaderPipeline.ssbo(1), VkShaderPipeline.ssbo(3),
                VkShaderPipeline.ssbo(4), VkShaderPipeline.ssbo(5),
                VkShaderPipeline.sampler(8), VkShaderPipeline.sampler(9), VkShaderPipeline.sampler(10));

        var opaque = new VkShaderPipeline.GfxDesc();
        opaque.name = "terrain-opaque";
        opaque.vertGlsl = vert;
        opaque.fragGlsl = VkShaderSource.load("voxy:lod/gl46/quads.frag", VkShaderSource.defs().props(this.properties).build());
        opaque.colorFormat = cf;
        opaque.depthFormat = df;
        opaque.stencilFormat = df;
        opaque.depthTest = true;
        opaque.depthWrite = true;
        opaque.depthCompare = VkCmd.closerEqual(this.properties);
        opaque.blend = false;
        opaque.stencilTestEqual1 = true;//only render where vanilla terrain is absent
        opaque.bindings = bindings;
        this.terrainOpaque = new VkShaderPipeline(this.ctx, opaque);

        var translucent = new VkShaderPipeline.GfxDesc();
        translucent.name = "terrain-translucent";
        translucent.vertGlsl = vert;
        translucent.fragGlsl = VkShaderSource.load("voxy:lod/gl46/quads.frag", VkShaderSource.defs().props(this.properties)
                .def("TRANSLUCENT").build());
        translucent.colorFormat = cf;
        translucent.depthFormat = df;
        translucent.stencilFormat = df;
        translucent.depthTest = true;
        translucent.depthWrite = true;
        translucent.depthCompare = VkCmd.closerEqual(this.properties);
        translucent.blend = true;
        translucent.stencilTestEqual1 = true;
        translucent.bindings = bindings;
        this.terrainTranslucent = new VkShaderPipeline(this.ctx, translucent);

        this.pipelineColorFormat = cf;
        this.pipelineDepthFormat = df;
    }

    //==================================================================================

    private final Matrix4f uniformScratch = new Matrix4f();
    private VkViewport uniformViewport;
    private int uniformFrameId;
    public void uploadUniform(VkViewport viewport) {
        //renderOpaque and buildDrawCalls both need it with identical contents; upload
        // once per frame (the buffer is not written again until the next frame)
        if (this.uniformViewport == viewport && this.uniformFrameId == viewport.frameId) return;
        this.uniformViewport = viewport;
        this.uniformFrameId = viewport.frameId;
        long ptr = this.uploadStream.upload(this.uniform, 0, 1024);
        var mat = this.uniformScratch.set(viewport.MVP);
        mat.translate(-viewport.innerTranslation.x, -viewport.innerTranslation.y, -viewport.innerTranslation.z);
        mat.getToAddress(ptr); ptr += 4 * 4 * 4;
        viewport.section.getToAddress(ptr); ptr += 4 * 3;
        if (viewport.frameId < 0) {
            Logger.error("Frame ID negative, this will cause things to break, wrapping around");
            viewport.frameId &= 0x7fffffff;
        }
        MemoryUtil.memPutInt(ptr, viewport.frameId & 0x7fffffff); ptr += 4;
        viewport.innerTranslation.getToAddress(ptr);
        this.uploadStream.commit();
    }

    /** Mirrors MDIC buildDrawCalls: prep -> raster cull -> cmdgen -> translucency sort. */
    public void buildDrawCalls(VkViewport viewport) {
        if (this.geometry.getSectionCount() == 0) return;
        var cmd = this.ctx.cmd();
        this.uploadUniform(viewport);

        if (!this.ctx.vk().hasDrawIndirectCount) {
            //Fixed-count fallback (MoltenVK): renderTerrain issues
            // vkCmdDrawIndexedIndirect with maxDrawCount rather than a GPU-sourced
            // count, so trailing slots past the actual command count would be read
            // as stale draw commands from the previous frame. Zero the three
            // drawCallBuffer slices (opaque / temporal / translucent) here so any
            // un-overwritten slot reads as instanceCount=0 (a no-op draw). The
            // cmdgen compute below then writes the real commands on top; the fill
            // completes (with a barrier) before the cmdgen dispatch reads the buffer.
            // Gated to the fallback path only — the tight-count path (desktop Vulkan)
            // never reads past the actual count, so it pays nothing here.
            long stride = 5L * 4;
            vkCmdFillBuffer(cmd, viewport.drawCallBuffer.buffer, 0L, VkViewport.OPAQUE_DRAW_COUNT * stride, 0);
            vkCmdFillBuffer(cmd, viewport.drawCallBuffer.buffer, TEMPORAL_OFFSET * stride, VkViewport.TEMPORAL_DRAW_COUNT * stride, 0);
            vkCmdFillBuffer(cmd, viewport.drawCallBuffer.buffer, TRANSLUCENT_OFFSET * stride, VkViewport.TRANSLUCENT_DRAW_COUNT * stride, 0);
            this.ctx.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT);
        }

        {//prep
            this.prep.bind(cmd);
            try (var b = this.prep.binder()) {
                b.ubo(0, this.uniform)
                        .ssbo(1, viewport.drawCountCallBuffer)
                        .ssbo(2, viewport.indirectLookupBuffer)
                        .push(cmd);
            }
            vkCmdDispatch(cmd, 1, 1, 1);
            //prep (compute) wrote the cull indirect args; the raster-cull draw
            // reads them. Scope to COMPUTE -> DRAW_INDIRECT|VERTEX_INPUT.
            this.ctx.computeToDrawBarrier();
        }

        //GPU timing sections (F3 GpuTime, same labels as GL). A marker can begin a new
        // command buffer, so cmd is fetched again after each one.
        this.ctx.gpuMarker("OT");
        cmd = this.ctx.cmd();
        {//raster occlusion test into the visibility buffer (depth-tested box draw, no writes)
            this.beginRendering(viewport, 0L, true);//depth-only
            this.cullRaster.bind(cmd);
            VkCmd.setViewportScissor(cmd, viewport.width, viewport.height);
            try (var b = this.cullRaster.binder()) {
                b.ubo(0, this.uniform)
                        .ssbo(1, this.geometry.metadataBuffer())
                        .ssbo(2, viewport.visibilityBuffer)
                        .ssbo(3, viewport.indirectLookupBuffer)
                        .push(cmd);
            }
            vkCmdBindIndexBuffer(cmd, this.indexBuffer.buffer, 0, VK_INDEX_TYPE_UINT16);
            vkCmdDrawIndexedIndirect(cmd, viewport.drawCountCallBuffer.buffer, 6 * 4, 1, 20);
            this.ctx.endRendering();
            //The raster-cull draw wrote visibilityData (SSBO) from the fragment
            // shader; the consumer is the cmdgen compute. Scope to those stages
            // instead of the previous fullBarrier (ALL_COMMANDS -> ALL_COMMANDS)
            // which forced a full pipeline stall on every frame.
            this.ctx.barrier(VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        }

        this.ctx.gpuMarker("CG");
        cmd = this.ctx.cmd();
        {//command generation (indirect dispatch sized by prep)
            vkCmdFillBuffer(cmd, this.distanceCountBuffer.buffer, 0, 1024L * 4, 0);
            this.ctx.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
            this.cmdGen.bind(cmd);
            try (var b = this.cmdGen.binder()) {
                b.ubo(0, this.uniform)
                        .ssbo(1, viewport.drawCallBuffer)
                        .ssbo(2, viewport.drawCountCallBuffer)
                        .ssbo(3, this.geometry.metadataBuffer())
                        .ssbo(4, viewport.visibilityBuffer)
                        .ssbo(5, viewport.indirectLookupBuffer)
                        .ssbo(6, viewport.positionScratchBuffer)
                        .ssbo(7, this.distanceCountBuffer)
                        .push(cmd);
            }
            vkCmdDispatchIndirect(cmd, viewport.drawCountCallBuffer.buffer, 0);
            //cmdgen -> prefixsum: both compute. The draw that consumes these
            // indirect commands is guarded by renderTerrain's own barrier.
            this.ctx.computeToComputeBarrier();
        }

        this.ctx.gpuMarker("TS");
        cmd = this.ctx.cmd();
        {//translucency sorting
            this.prefixSum.bind(cmd);
            try (var b = this.prefixSum.binder()) {
                b.ssbo(0, this.distanceCountBuffer).push(cmd);
            }
            vkCmdDispatch(cmd, 1, 1, 1);
            //prefixsum -> translucentGen: both compute.
            this.ctx.computeToComputeBarrier();

            this.translucentGen.bind(cmd);
            try (var b = this.translucentGen.binder()) {
                b.ubo(0, this.uniform)
                        .ssbo(1, viewport.drawCallBuffer)
                        .ssbo(2, viewport.drawCountCallBuffer)
                        .ssbo(3, this.geometry.metadataBuffer())
                        .ssbo(4, viewport.indirectLookupBuffer)
                        .ssbo(5, this.distanceCountBuffer)
                        .push(cmd);
            }
            vkCmdDispatchIndirect(cmd, viewport.drawCountCallBuffer.buffer, 0);
            //No trailing barrier here: the translucent commands written into
            // drawCallBuffer are read by renderTranslucent's renderTerrain, which
            // issues its own COMPUTE|TRANSFER -> DRAW_INDIRECT|VERTEX|FRAGMENT
            // barrier (line ~355) before drawing. The previous computeToAllBarrier
            // here was redundant with that draw barrier and serialised the GPU.
        }

        if (!this.ctx.vk().hasDrawIndirectCount) {
            //Read the three real per-pass draw counts back to the CPU so the fixed-count
            // multi-draws track them instead of the worst-case section cap (see
            // DrawBudget). The counts live at opaque@12 / translucent@16 / temporal@20 in
            // drawCountCallBuffer; download those 12 bytes on the same async,
            // frame-retired path the traversal request readback already uses (commit()
            // scopes its own COMPUTE->TRANSFER->HOST barriers). The callback runs on the
            // render thread from pollRetired, so the plain field writes are race-free.
            // Desktop (drawIndirectCount) neither needs nor issues this.
            this.prevListDir.set(this.listDir);
            this.prevListFov = this.listFov;
            //-Z of the view rotation is where the camera looks, in world space
            viewport.modelView.positiveZ(this.listDir).negate().normalize();
            this.listFov = (float) (2 * Math.atan(1 / Math.abs(viewport.vanillaProjection.m11())));
            if (this.fovChanged()) {
                this.fovUnsettledLists = DrawBudget.RECENT_SAMPLES;
            } else if (this.fovUnsettledLists > 0) {
                this.fovUnsettledLists--;
            }
            long listFrame = this.ctx.currentFrame();
            float dx = this.listDir.x, dy = this.listDir.y, dz = this.listDir.z, fov = this.listFov;
            this.downloadStream.download(viewport.drawCountCallBuffer, 12, 12, (ptr, size) -> {
                long now = System.currentTimeMillis();
                this.opaqueBudget.record(listFrame, MemoryUtil.memGetInt(ptr), dx, dy, dz, fov, now);
                this.translucentBudget.record(listFrame, MemoryUtil.memGetInt(ptr + 4), dx, dy, dz, fov, now);
                this.temporalBudget.record(listFrame, MemoryUtil.memGetInt(ptr + 8), dx, dy, dz, fov, now);
            });
        }
    }

    //Fixed-count draw budget for one terrain pass on MoltenVK, which lacks
    // drawIndirectCount. The GPU writes the real draw count, but the CPU has to pick
    // the multi-draw count when it records, and only learns the real counts from a
    // readback 2-3 frames later. Every slot past the real count is a zeroed no-op that
    // still costs a Metal draw (MoltenVK encodes each one on the render thread, and the
    // GPU still walks it); a slot short of it drops a visible section: holes that flicker
    // while turning.
    //Counts depend on where the camera looks and how wide its FOV is, so each read-back
    // count keeps both for its list, and the opaque and translucent budgets cover:
    //  - the newest few counts (the readback lag), whatever the view;
    //  - counts from the last 16 s looking within 30 degrees of the list being drawn, with
    //    an FOV within 10% of its FOV, so turning back to a view seen moments ago (panning
    //    between a mountain side and the vista past it) is covered, while looking somewhere
    //    quieter is not charged for it;
    //  - when no count looked that way (a quick turn into a new view), or while the FOV is
    //    changing (a spyglass, sprinting: each step re-picks the LOD of the whole view), the
    //    largest count of the last 16 s from any view, until the new view's counts arrive.
    // Holding the largest count from ANY view for 8 s, as a first version did, left most
    // slots empty after every quick turn: 176k Metal draws for 42k real ones on an M2 Max.
    //Temporal draws are the sections the last list lacked, so their count follows how fast
    // the view changes rather than where it points: that budget covers the newest few
    // counts, plus an estimate from the turn or FOV change since the last list (see
    // predictedNewDraws).
    //All budgets get 25% growth plus fixed headroom (-Dvoxy.vk.drawBudgetGrowth, 1.0-4.0).
    // Desktop Vulkan sources the count on the GPU and always gets the cap.
    private static final class DrawBudget {
        private static final int SAMPLES = 2048;//read-back counts kept: 16 s up to 128 fps
        static final int RECENT_SAMPLES = 4;//covers the readback lag
        private static final long HOLD_MS = 16_000;
        private static final float SAME_VIEW_COS = 0.866f;//cos(30 degrees)
        private static final float SAME_FOV = 0.1f;//relative FOV difference
        private static final long SHORT_WINDOW_MS = 10_000;
        private static final double GROWTH = growthFromProperty();

        private final int headroom;
        private final boolean directional;
        //Ring of read-back counts, newest at next-1: when each arrived, and where the camera
        // looked (and its FOV) when its list was built
        private final long[] times = new long[SAMPLES];
        private final int[] counts = new int[SAMPLES];
        private final float[] dirs = new float[SAMPLES * 3];
        private final float[] fovs = new float[SAMPLES];
        private int next, size;
        //Diagnostics: the budget each recent list was drawn with, checked against its real
        // count once that is read back
        private final long[] budgetFrames = new long[8];
        private final int[] budgets = new int[8];
        private final ArrayDeque<Long> shortfalls = new ArrayDeque<>();
        private int lastCount, lastBudget;

        DrawBudget(int headroom, boolean directional) {
            this.headroom = headroom;
            this.directional = directional;
            Arrays.fill(this.budgetFrames, -1);
        }

        /**
         * Largest read-back count that applies to a list built looking along {@code dir} with
         * vertical FOV {@code fov} (see the class comment); {@code unsettled} while the FOV is
         * changing. -1 before the first readback.
         */
        int held(Vector3fc dir, float fov, boolean unsettled, long now) {
            if (this.size == 0) return -1;
            int recent = 0, near = -1, any = 0;
            for (int age = 0; age < this.size; age++) {
                int i = Math.floorMod(this.next - 1 - age, SAMPLES);
                if (age >= RECENT_SAMPLES && (!this.directional || now - this.times[i] > HOLD_MS)) break;
                int count = this.counts[i];
                if (age < RECENT_SAMPLES) recent = Math.max(recent, count);
                if (this.directional) {
                    any = Math.max(any, count);
                    float dot = this.dirs[3 * i] * dir.x() + this.dirs[3 * i + 1] * dir.y() + this.dirs[3 * i + 2] * dir.z();
                    if (dot >= SAME_VIEW_COS && Math.abs(this.fovs[i] - fov) <= SAME_FOV * fov) {
                        near = Math.max(near, count);
                    }
                }
            }
            if (!this.directional) return recent;
            return near < 0 || unsettled ? Math.max(recent, any) : Math.max(recent, near);
        }

        /** Multi-draw count for a list built with that view: at least {@code floor} draws plus headroom, at most {@code cap}. */
        int budget(Vector3fc dir, float fov, boolean unsettled, long now, int floor, int cap) {
            int held = this.held(dir, fov, unsettled, now);
            if (held < 0) return cap;//before the first readback
            long budget = Math.max((long) (held * GROWTH), floor) + this.headroom;
            return (int) Math.min(cap, budget);
        }

        private static double growthFromProperty() {
            String value = System.getProperty("voxy.vk.drawBudgetGrowth", "");
            if (value.isEmpty()) return 1.25;
            try {
                double growth = Math.max(1.0, Math.min(4.0, Double.parseDouble(value)));
                Logger.info("Voxy VK: MoltenVK draw budget growth set to " + growth + " (voxy.vk.drawBudgetGrowth)");
                return growth;
            } catch (NumberFormatException e) {
                Logger.warn("Voxy VK: ignoring invalid voxy.vk.drawBudgetGrowth=" + value);
                return 1.25;
            }
        }

        //The draw of the list cmdgen wrote in listFrame used this budget
        void noteBudget(long listFrame, int budget) {
            int slot = (int) (listFrame & 7);
            this.budgetFrames[slot] = listFrame;
            this.budgets[slot] = budget;
            this.lastBudget = budget;
        }

        void record(long listFrame, int count, float dirX, float dirY, float dirZ, float fov, long nowMillis) {
            count = Math.max(0, count);
            int i = this.next;
            this.times[i] = nowMillis;
            this.counts[i] = count;
            this.dirs[3 * i] = dirX;
            this.dirs[3 * i + 1] = dirY;
            this.dirs[3 * i + 2] = dirZ;
            this.fovs[i] = fov;
            this.next = (i + 1) % SAMPLES;
            this.size = Math.min(this.size + 1, SAMPLES);
            this.lastCount = count;

            int budgetSlot = (int) (listFrame & 7);
            if (this.budgetFrames[budgetSlot] == listFrame && count > this.budgets[budgetSlot]) {
                this.shortfalls.addLast(nowMillis);
            }
            while (!this.shortfalls.isEmpty() && this.shortfalls.peekFirst() < nowMillis - SHORT_WINDOW_MS) {
                this.shortfalls.pollFirst();
            }
        }

        String describe() {
            return this.lastCount + "/" + this.lastBudget + (this.shortfalls.isEmpty() ? "" : " short " + this.shortfalls.size());
        }
    }

    //Whether the FOV differs between the latest two lists (by more than float noise)
    private boolean fovChanged() {
        return Math.abs(this.listFov - this.prevListFov) > 0.005f * this.listFov;
    }

    //Temporal draws are the sections visible now that the opaque pass's list (built last
    // frame) lacked, and their count arrives too late to size this frame's draw. So estimate
    // it from the draws the opaque pass needs for this view (with the usual growth), times
    // the share of the view that is new:
    //  - all of it when the FOV changed: every step of a spyglass zoom or a sprint's FOV
    //    effect re-picks the LOD of the whole view, and a widening FOV adds new edges;
    //  - after a turn, twice the angle turned over the FOV, capped at all of it. The margin
    //    covers sections moving across the screen and changing LOD, and turns that are
    //    not purely vertical.
    private int predictedNewDraws() {
        if (this.listFov <= 0) return 0;
        float exposed;
        if (this.fovChanged()) {
            exposed = 1;
        } else {
            float turned = (float) Math.acos(Math.max(-1, Math.min(1, this.listDir.dot(this.prevListDir))));
            exposed = Math.min(1, 2 * turned / this.listFov);
            if (exposed < 0.01f) return 0;
        }
        int opaque = this.opaqueBudget.held(this.listDir, this.listFov, this.fovUnsettledLists > 0, System.currentTimeMillis());
        return opaque < 0 ? 0 : (int) (exposed * opaque * DrawBudget.GROWTH);
    }

    /** F3 lines: the MoltenVK draw budgets (desktop sources its draw counts on the GPU). */
    public void addDebugInfo(List<String> debug) {
        if (this.ctx.vk().hasDrawIndirectCount) return;
        debug.add("VK draws/budget (10s shortfalls): O " + this.opaqueBudget.describe()
                + " T " + this.temporalBudget.describe() + " X " + this.translucentBudget.describe());
    }

    public void renderOpaque(VkViewport viewport, boolean clearTargets) {
        //Build pipelines BEFORE the section-count guard: within a frame geometry is
        // uploaded after this call (nodeManager.tick/buildDrawCalls), so renderTemporal/
        // renderTranslucent can see sectionCount>0 later this same frame. If we skipped
        // ensure here when the count is momentarily 0, those calls would bind a null
        // pipeline. ensureTerrainPipelines is idempotent (no-op once built for the format).
        this.ensureTerrainPipelines(viewport);
        if (this.geometry.getSectionCount() == 0) return;
        this.uploadUniform(viewport);
        int cap = Math.min((int) (this.geometry.getSectionCount() * 4.4 + 128), VkViewport.OPAQUE_DRAW_COUNT);
        //Draws the list cmdgen wrote last frame (listDir and listFov are still that list's)
        int maxDraw = this.drawCount(this.opaqueBudget, this.ctx.currentFrame() - 1, cap, 0);
        this.renderTerrain(viewport, viewport.colour.view, this.terrainOpaque, 0, 4 * 3, maxDraw, clearTargets);
    }

    public void renderTemporal(VkViewport viewport) {
        this.ensureTerrainPipelines(viewport);
        if (this.geometry.getSectionCount() == 0) return;
        int cap = Math.min(this.geometry.getSectionCount(), VkViewport.TEMPORAL_DRAW_COUNT);
        int maxDraw = this.drawCount(this.temporalBudget, this.ctx.currentFrame(), cap,
                this.ctx.vk().hasDrawIndirectCount ? 0 : this.predictedNewDraws());
        this.renderTerrain(viewport, viewport.colour.view, this.terrainOpaque, TEMPORAL_OFFSET * 5L * 4, 4 * 5, maxDraw, false);
    }

    /** Translucents draw onto the SSAO output (mirrors the GL fbSSAO target). */
    public void renderTranslucent(VkViewport viewport) {
        this.ensureTerrainPipelines(viewport);
        if (this.geometry.getSectionCount() == 0) return;
        int cap = Math.min(this.geometry.getSectionCount(), VkViewport.TRANSLUCENT_DRAW_COUNT);
        int maxDraw = this.drawCount(this.translucentBudget, this.ctx.currentFrame(), cap, 0);
        this.renderTerrain(viewport, viewport.colourSSAO.view, this.terrainTranslucent, TRANSLUCENT_OFFSET * 5L * 4, 4 * 4, maxDraw, false);
    }

    //Multi-draw count for one pass over the list cmdgen wrote in listFrame: the section-
    // derived cap on desktop, where the GPU sources the real count and the cap is only a
    // ceiling; the fixed-count budget on MoltenVK.
    private int drawCount(DrawBudget budget, long listFrame, int cap, int floor) {
        if (this.ctx.vk().hasDrawIndirectCount) return cap;
        int count = budget.budget(this.listDir, this.listFov, this.fovUnsettledLists > 0, System.currentTimeMillis(), floor, cap);
        budget.noteBudget(listFrame, count);
        return count;
    }

    private void renderTerrain(VkViewport viewport, long colorView, VkShaderPipeline pipeline,
                               long indirectOffset, long drawCountOffset, int maxDrawCount, boolean clear) {
        var cmd = this.ctx.cmd();
        this.ctx.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
                VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_INDEX_READ_BIT);

        //Resolve everything that can throw BEFORE opening the rendering instance
        long lightmapView = VkFrameHost.lightmapView();
        long depthBoundView = VkFrameHost.vkView(viewport.depthBoundView);
        this.beginRendering(viewport, colorView, !clear);//LOAD unless first pass (which cleared via compositor setup)
        pipeline.bind(cmd);
        VkCmd.setViewportScissor(cmd, viewport.width, viewport.height);
        try (var b = pipeline.binder()) {
            b.ubo(0, this.uniform)
                    .ssbo(1, this.geometry.geometryBuffer())
                    .ssbo(3, this.modelStore.modelBuffer)
                    .ssbo(4, this.modelStore.modelColourBuffer)
                    .ssbo(5, viewport.positionScratchBuffer)
                    .sampler(8, this.modelStore.atlas.view, this.modelStore.atlasSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
                    //MC's lightmap is an MC-owned image: always in MC_IMAGE_LAYOUT
                    .sampler(9, lightmapView, this.lightmapSampler, VkFrameHost.MC_IMAGE_LAYOUT)
                    //Blaze3D texture (Blaze3DBoundRenderer): always in MC_IMAGE_LAYOUT too
                    .sampler(10, depthBoundView, this.depthBoundSampler, VkFrameHost.MC_IMAGE_LAYOUT)
                    .push(cmd);
        }
        vkCmdBindIndexBuffer(cmd, this.indexBuffer.buffer, 0, VK_INDEX_TYPE_UINT16);
        if (this.ctx.vk().hasDrawIndirectCount) {
            //Tight-count path: the GPU reads the actual draw count from the count
            // buffer each frame. Only taken when the drawIndirectCount feature was
            // ENABLED on MC's device (Voxy requests it at device creation where
            // supported: desktop NVIDIA/AMD/Intel, not MoltenVK).
            vkCmdDrawIndexedIndirectCount(cmd,
                    viewport.drawCallBuffer.buffer, indirectOffset,
                    viewport.drawCountCallBuffer.buffer, drawCountOffset,
                    maxDrawCount, 5 * 4);
        } else {
            //Fixed-count fallback (MoltenVK/macOS has no drawIndirectCount). The
            // drawCallBuffer slice for this pass is zeroed per frame in buildDrawCalls
            // (gated to this fallback path) so trailing slots past the actual command
            // count read instanceCount=0 (no-op draws); a fixed-count multi-draw over
            // the clamped maxDrawCount is therefore correct, just less tight.
            vkCmdDrawIndexedIndirect(cmd, viewport.drawCallBuffer.buffer, indirectOffset, maxDrawCount, 5 * 4);
        }
        this.ctx.endRendering();
    }

    /**
     * Begin dynamic rendering over the offscreen targets (always LOAD; clears
     * happen in the depth-setup pass). {@code colorView} selects the colour
     * attachment (main colour vs SSAO output); 0 = depth-only.
     */
    private void beginRendering(VkViewport viewport, long colorView, boolean load) {
        try (MemoryStack stack = stackPush()) {
            var depthAttach = VkRenderingAttachmentInfoKHR.calloc(stack).sType$Default()
                    .imageView(viewport.depthStencil.view)
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            var stencilAttach = VkRenderingAttachmentInfoKHR.calloc(stack).sType$Default()
                    .imageView(viewport.depthStencil.view)
                    .imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            var info = VkRenderingInfoKHR.calloc(stack).sType$Default()
                    .renderArea(VkRect2D.calloc(stack).extent(e -> e.width(viewport.width).height(viewport.height)))
                    .layerCount(1)
                    .pDepthAttachment(depthAttach)
                    .pStencilAttachment(stencilAttach);
            if (colorView != 0L) {
                var colorAttach = VkRenderingAttachmentInfoKHR.calloc(1, stack).sType$Default()
                        .imageView(colorView)
                        .imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE);
                info.pColorAttachments(colorAttach);
            }
            this.ctx.beginRendering(info);
        }
    }

    public void free() {
        this.prep.free();
        this.cmdGen.free();
        this.prefixSum.free();
        this.translucentGen.free();
        this.cullRaster.free();
        if (this.terrainOpaque != null) {
            this.terrainOpaque.free();
            this.terrainTranslucent.free();
        }
        //depthBoundSampler/lightmapSampler come from VkImage2D.createSampler's
        // device-lifetime cache (shared handles); never destroy them per-object
        // (multi-free vkDestroySampler -> SIGSEGV on world unload).
        this.uniform.free();
        this.distanceCountBuffer.free();
        this.indexBuffer.free();
    }
}
