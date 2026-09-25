package me.cortex.voxy.client.core.vk.render;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.rendering.IRenderList;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.hierachical.HierarchicalOcclusionTraverser;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkImage2D;

import static org.lwjgl.vulkan.VK10.*;

//Pure-VK viewport: the MDICViewport buffer set as VkBuffers, plus the
// offscreen render targets (colour + depth-stencil), the depth-bound target
// (vanilla-coverage optimisation, Blaze3D textures), and the HiZ pyramid.
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

    //Offscreen targets, lazily (re)created on resize.
    //LOD colour (opaque + temporal draws, read by SSAO): a Blaze3D texture, in GENERAL
    // layout for its whole life (contract D1); Voxy's raw passes use colourVkView
    public static final int COLOUR_FORMAT = VK_FORMAT_R8G8B8A8_UNORM;
    public GpuTexture colour;
    public GpuTextureView colourView;
    public long colourVkView;
    public VkImage2D depthStencil;
    public long depthSampleView;//DEPTH-aspect view of depthStencil for sampling
    //Depth-bound target (Blaze3DBoundRenderer): a Blaze3D D32 texture, in GENERAL layout for
    // its whole life (contract D1), that the terrain shaders sample raw; and the colour
    // attachment Blaze3D requires for that pass, which the pipeline never writes
    public GpuTexture depthBound;
    public GpuTextureView depthBoundView;
    public GpuTexture boundColour;
    public GpuTextureView boundColourView;
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
        if (this.colour != null && this.colour.getWidth(0) == this.width && this.colour.getHeight(0) == this.height) return false;
        if (this.colour != null) {
            this.freeColour();
            this.colourSSAO.free();
            this.depthStencil.free();
        }
        //Blaze3D records its layout initialisation into MC's command stream, where it runs
        // before anything Voxy records after this call
        var device = RenderSystem.getDevice();
        this.colour = device.createTexture("voxy lod colour",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM,
                this.width, this.height, 1, 1);
        this.colourView = device.createTextureView(this.colour);
        this.colourVkView = VkFrameHost.vkView(this.colourView);
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
        return true;
    }

    /**
     * (Re)creates the depth-bound targets on size change. Blaze3D records their layout
     * initialisation into MC's command stream, where it runs before anything Voxy records
     * after this call.
     */
    public void ensureBoundTargets() {
        if (this.width <= 0 || this.height <= 0) return;
        if (this.depthBound != null && this.depthBound.getWidth(0) == this.width && this.depthBound.getHeight(0) == this.height) return;
        this.freeBoundTargets();
        var device = RenderSystem.getDevice();
        this.depthBound = device.createTexture("voxy depth bound",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.D32_FLOAT,
                this.width, this.height, 1, 1);
        this.depthBoundView = device.createTextureView(this.depthBound);
        this.boundColour = device.createTexture("voxy depth bound (unused colour)",
                GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.R8_UNORM,
                this.width, this.height, 1, 1);
        this.boundColourView = device.createTextureView(this.boundColour);
    }

    //Blaze3D destroys it once the submission being recorded now has completed
    private void freeColour() {
        this.colourView.close();
        this.colour.close();
        this.colour = null;
        this.colourView = null;
        this.colourVkView = 0;
    }

    //Blaze3D destroys them once the submission being recorded now has completed
    private void freeBoundTargets() {
        if (this.depthBound == null) return;
        this.depthBoundView.close();
        this.depthBound.close();
        this.boundColourView.close();
        this.boundColour.close();
        this.depthBound = null;
        this.depthBoundView = null;
        this.boundColour = null;
        this.boundColourView = null;
    }

    @Override
    protected void delete0() {
        super.delete0();
        if (this.colour != null) {
            this.freeColour();
            this.colourSSAO.free();
            this.depthStencil.free();
        }
        this.freeBoundTargets();
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
