package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkImageMemoryBarrier;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;

import static me.cortex.voxy.client.core.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

//2D image + full view (+ optional per-mip views) for the pure-VK path:
// offscreen colour/depth targets, the HiZ mip pyramid, and the model atlas.
// Tracks the current layout for whole-image transitions (Voxy transitions
// whole subresource ranges only, keeping parity with the GL path's coarse
// barrier usage).
public final class VkImage2D {
    private final VkFrameCtx ctx;
    public final long image;
    public final long memory;
    public final long view;
    public final long[] mipViews;//null unless requested
    public final int width, height, mipLevels;
    public final int format;
    public final int aspect;
    private int currentLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    //A depth-only sampling view of a depth+stencil image needs no mutable-format
    // flag: it is created with the image's own format and ASPECT_DEPTH
    // (createAspectView). Depth/stencil formats are only format-compatible with
    // themselves, so listing e.g. D32_SFLOAT as a view format of D32_SFLOAT_S8_UINT
    // (as this used to) is invalid.
    public VkImage2D(VkFrameCtx ctx, int width, int height, int mipLevels, int format, int usage, int aspect, boolean perMipViews) {
        this.ctx = ctx;
        this.width = width;
        this.height = height;
        this.mipLevels = mipLevels;
        this.format = format;
        this.aspect = aspect;
        var vctx = ctx.vk();
        try (MemoryStack stack = stackPush()) {
            var ici = VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D)
                    .format(format)
                    .extent(e -> e.width(width).height(height).depth(1))
                    .mipLevels(mipLevels).arrayLayers(1)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(usage)
                    .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            var pImg = stack.mallocLong(1);
            check(vkCreateImage(vctx.device, ici, null, pImg), "vkCreateImage");
            long image = pImg.get(0);
            long memory = VK_NULL_HANDLE;
            var views = new java.util.ArrayList<Long>();
            try {
                var req = VkMemoryRequirements.calloc(stack);
                vkGetImageMemoryRequirements(vctx.device, image, req);
                var mai = VkMemoryAllocateInfo.calloc(stack).sType$Default()
                        .allocationSize(req.size())
                        .memoryTypeIndex(vctx.findMemoryType(req.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT));
                var pMem = stack.mallocLong(1);
                check(vkAllocateMemory(vctx.device, mai, null, pMem), "vkAllocateMemory(image)");
                memory = pMem.get(0);
                check(vkBindImageMemory(vctx.device, image, memory, 0), "vkBindImageMemory");

                views.add(createView(stack, vctx, image, format, aspect, 0, mipLevels));
                if (perMipViews) {
                    for (int i = 0; i < mipLevels; i++) {
                        views.add(createView(stack, vctx, image, format, aspect, i, 1));
                    }
                }
            } catch (RuntimeException e) {
                for (long v : views) vkDestroyImageView(vctx.device, v, null);
                if (memory != VK_NULL_HANDLE) vkFreeMemory(vctx.device, memory, null);
                vkDestroyImage(vctx.device, image, null);
                throw e;
            }
            this.image = image;
            this.memory = memory;
            this.view = views.get(0);
            if (perMipViews) {
                this.mipViews = new long[mipLevels];
                for (int i = 0; i < mipLevels; i++) {
                    this.mipViews[i] = views.get(i + 1);
                }
            } else {
                this.mipViews = null;
            }
        }
    }

    private static long createView(MemoryStack stack, VulkanContext vctx, long image, int format, int aspect, int baseMip, int mipCount) {
        var vci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                .image(image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format);
        vci.subresourceRange().aspectMask(aspect).baseMipLevel(baseMip).levelCount(mipCount).baseArrayLayer(0).layerCount(1);
        var pView = stack.mallocLong(1);
        check(vkCreateImageView(vctx.device, vci, null, pView), "vkCreateImageView");
        return pView.get(0);
    }

    /** Whole-image layout transition recorded into the current frame commands. */
    public void transition(int newLayout, int srcStage, int srcAccess, int dstStage, int dstAccess) {
        try (MemoryStack stack = stackPush()) {
            var imb = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                    .oldLayout(this.currentLayout).newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(this.image);
            imb.subresourceRange().aspectMask(this.aspect).levelCount(this.mipLevels).layerCount(1);
            vkCmdPipelineBarrier(this.ctx.cmd(), srcStage, dstStage, 0, null, null, imb);
            this.currentLayout = newLayout;
        }
    }

    //Batched transition: records multiple images' layout changes in a single
    // vkCmdPipelineBarrier (one call instead of N). Each image's transition is
    // described by its own (newLayout, srcStage, srcAccess, dstStage, dstAccess)
    // tuple; the call uses the union of all src stages -> union of all dst stages
    // so the single barrier covers every image's dependency.
    public record BatchEntry(VkImage2D image, int newLayout, int srcAccess, int dstAccess) {}
    public static void transitionBatch(java.util.List<BatchEntry> entries, int unionSrcStage, int unionDstStage) {
        if (entries.isEmpty()) return;
        try (MemoryStack stack = stackPush()) {
            var imbs = VkImageMemoryBarrier.calloc(entries.size(), stack);
            for (int i = 0; i < entries.size(); i++) {
                var e = entries.get(i);
                imbs.get(i).sType$Default()
                        .srcAccessMask(e.srcAccess).dstAccessMask(e.dstAccess)
                        .oldLayout(e.image.currentLayout).newLayout(e.newLayout)
                        .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                        .image(e.image.image)
                        .subresourceRange().aspectMask(e.image.aspect).levelCount(e.image.mipLevels).layerCount(1);
            }
            //Use the first image's ctx (all images share the same VkFrameCtx in Voxy).
            var cmd = entries.get(0).image.ctx.cmd();
            vkCmdPipelineBarrier(cmd, unionSrcStage, unionDstStage, 0, null, null, imbs);
            for (var e : entries) e.image.currentLayout = e.newLayout;
        }
    }

    private final java.util.ArrayList<Long> extraViews = new java.util.ArrayList<>();

    /** Additional full-image view with a different aspect (e.g. DEPTH-only sampling view of a depth-stencil image). */
    public long createAspectView(int viewAspect) {
        try (MemoryStack stack = stackPush()) {
            var vci = VkImageViewCreateInfo.calloc(stack).sType$Default()
                    .image(this.image).viewType(VK_IMAGE_VIEW_TYPE_2D).format(this.format);
            vci.subresourceRange().aspectMask(viewAspect).baseMipLevel(0).levelCount(this.mipLevels).baseArrayLayer(0).layerCount(1);
            var pView = stack.mallocLong(1);
            check(vkCreateImageView(this.ctx.vk().device, vci, null, pView), "vkCreateImageView(aspect)");
            long view = pView.get(0);
            this.extraViews.add(view);
            return view;
        }
    }

    public void free() {
        if (this.mipViews != null) {
            for (long v : this.mipViews) {
                this.ctx.deferDestroyImage(0, v, 0);
            }
        }
        for (long v : this.extraViews) {
            this.ctx.deferDestroyImage(0, v, 0);
        }
        this.ctx.deferDestroyImage(this.image, this.view, this.memory);
    }

    /** Simple sampler factory (nearest/clamped or nearest-mipmap for HiZ etc).
     *  Shared per (mipmapNearest, linear) and owned by the VulkanContext, which
     *  destroys them with the rest of its objects before MC destroys the device. */
    public static long createSampler(VulkanContext ctx, boolean mipmapNearest, boolean linear) {
        return ctx.sampler(mipmapNearest, linear);
    }
}
