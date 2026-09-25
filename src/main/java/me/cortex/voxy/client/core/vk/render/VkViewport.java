package me.cortex.voxy.client.core.vk.render;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.IRenderList;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkImage2D;

import static org.lwjgl.vulkan.VK10.*;

//Pure-VK viewport: the MDICViewport buffer set as VkBuffers, plus the
// offscreen render targets (colour + depth-stencil), the depth-bound image
// (vanilla-coverage optimisation), and the HiZ pyramid.
public class VkViewport extends Viewport<VkViewport> {
    public static final int OPAQUE_DRAW_COUNT = 400_000;
    public static final int TRANSLUCENT_DRAW_COUNT = 100_000;
    public static final int TEMPORAL_DRAW_COUNT = 100_000;

    private final VkFrameCtx ctx;

    public final VkBuffer drawCountCallBuffer;
    public final VkBuffer drawCallBuffer;
    public final VkBuffer positionScratchBuffer;
    public final VkBuffer indirectLookupBuffer;
    public final VkBuffer visibilityBuffer;

    //Offscreen targets, lazily (re)created on resize
    public VkImage2D colour;
    public VkImage2D depthStencil;
    public long depthSampleView;//DEPTH-aspect view of depthStencil for sampling
    public VkImage2D depthBound;
    public long depthBoundSampleView;
    //SSAO output colour: the compute pass reads `colour` and writes the AO-modulated
    // result (alpha sanitized to 1/0) here; translucents then draw onto it and the
    // compositor samples it — mirroring the GL colourTex/colourSSAOTex pair.
    public VkImage2D colourSSAO;
    public final VkHiZ hiZ;

    public VkViewport(VkFrameCtx ctx, RenderProperties properties, int maxSectionCount) {
        super(properties);
        this.ctx = ctx;
        this.drawCountCallBuffer = new VkBuffer(ctx, 1024).zero();
        this.drawCallBuffer = new VkBuffer(ctx, 5L * 4 * (OPAQUE_DRAW_COUNT + TRANSLUCENT_DRAW_COUNT + TEMPORAL_DRAW_COUNT)).zero();
        this.positionScratchBuffer = new VkBuffer(ctx, 8L * 400000).zero();
        this.indirectLookupBuffer = new VkBuffer(ctx, HierarchicalOcclusionTraverser.MAX_QUEUE_SIZE * 4L + 4).zero();
        this.visibilityBuffer = new VkBuffer(ctx, maxSectionCount * 4L).zero();
        this.hiZ = new VkHiZ(ctx, properties);
        ctx.flushImmediate();
    }

    @Override
    protected boolean useGlViewportHelpers() {
        return false;
    }

    /** (Re)creates the offscreen targets on size change; true if recreated. */
    public boolean ensureTargets() {
        if (this.width <= 0 || this.height <= 0) return false;
        if (this.colour != null && this.colour.width == this.width && this.colour.height == this.height) return false;
        if (this.colour != null) {
            this.colour.free();
            this.colourSSAO.free();
            this.depthStencil.free();
            this.depthBound.free();
        }
        this.colour = new VkImage2D(this.ctx, this.width, this.height, 1,
                VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT, false);
        this.colourSSAO = new VkImage2D(this.ctx, this.width, this.height, 1,
                VK_FORMAT_R8G8B8A8_UNORM,
                VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_STORAGE_BIT,
                VK_IMAGE_ASPECT_COLOR_BIT, false);
        //D32S8 when the device can attach + sample it, else D24S8 (VulkanContext).
        // The depth-only sampling view uses the same format with ASPECT_DEPTH; no
        // mutable-format flag is needed (or valid) for that.
        this.depthStencil = new VkImage2D(this.ctx, this.width, this.height, 1,
                this.ctx.vk().depthStencilFormat,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT, false);
        this.depthSampleView = this.depthStencil.createAspectView(VK_IMAGE_ASPECT_DEPTH_BIT);
        this.depthBound = new VkImage2D(this.ctx, this.width, this.height, 1,
                VK_FORMAT_D32_SFLOAT,
                VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                VK_IMAGE_ASPECT_DEPTH_BIT, false);
        this.depthBoundSampleView = this.depthBound.view;
        return true;
    }

    @Override
    protected void delete0() {
        super.delete0();
        if (this.colour != null) {
            this.colour.free();
            this.colourSSAO.free();
            this.depthStencil.free();
            this.depthBound.free();
        }
        this.hiZ.free();
        this.visibilityBuffer.free();
        this.indirectLookupBuffer.free();
        this.drawCountCallBuffer.free();
        this.drawCallBuffer.free();
        this.positionScratchBuffer.free();
    }

    @Override
    public IRenderList getRenderList() {
        return this.indirectLookupBuffer;
    }
}
