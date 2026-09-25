package me.cortex.voxy.client.core.vk.render;

import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import net.minecraft.client.Minecraft;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageMemoryBarrier;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

//Accessors for MC's live Vulkan frame resources at the render hook point:
// the world colour/depth attachment views MC is rendering into and the
// lightmap texture view. All calls are render-thread only.
//
//Image layouts: MC 26.2's Vulkan backend transitions every texture it creates
// UNDEFINED -> GENERAL once and then declares GENERAL everywhere (render-pass
// attachments, descriptors, copies, clears); it never transitions again
// (VulkanGpuTexture / VulkanCommandEncoder / VulkanRenderPass). Voxy must use
// MC_IMAGE_LAYOUT for every access to an MC-owned image and must never change its
// layout: only memory barriers are needed to order Voxy's accesses against MC's.
public final class VkFrameHost {
    public static final int MC_IMAGE_LAYOUT = VK_IMAGE_LAYOUT_GENERAL;

    private VkFrameHost() {}

    /** VkImageView of MC's lightmap (bound as Voxy's terrain light sampler, layout MC_IMAGE_LAYOUT). */
    public static long lightmapView() {
        return ((VulkanGpuTextureView) Minecraft.getInstance().gameRenderer.levelLightmap()).vkImageView();
    }

    public static long vkView(GpuTextureView view) {
        return ((VulkanGpuTextureView) view).vkImageView();
    }

    public static int vkFormat(GpuTextureView view) {
        return VulkanConst.toVk(view.texture().getFormat());
    }

    //Execution + memory dependency on one of MC's colour/depth images. The layout
    // stays MC_IMAGE_LAYOUT; callers pass the stages that actually produced/consume
    // the access (e.g. SSAO reads MC's depth from a COMPUTE dispatch).
    public static void mcImageBarrier(VkCommandBuffer cmd, GpuTextureView view, boolean depth,
                                      int srcStage, int srcAccess, int dstStage, int dstAccess) {
        try (MemoryStack stack = stackPush()) {
            long image = ((VulkanGpuTexture) view.texture()).vkImage();
            var imb = VkImageMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(srcAccess).dstAccessMask(dstAccess)
                    .oldLayout(MC_IMAGE_LAYOUT).newLayout(MC_IMAGE_LAYOUT)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image);
            imb.subresourceRange()
                    .aspectMask(depth ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT)
                    .levelCount(VK_REMAINING_MIP_LEVELS)
                    .layerCount(VK_REMAINING_ARRAY_LAYERS);
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, imb);
        }
    }
}
