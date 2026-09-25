package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import me.cortex.voxy.client.core.model.bakery.IAtlasTextureReader;
import me.cortex.voxy.client.core.vk.render.VkFrameHost;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

//Vulkan block-atlas readback: copies MC's stitched atlas image (mip 0) into a
// host-visible staging buffer via vkCmdCopyImageToBuffer and reads it into an
// int[] in the same RGBA8 byte order (R,G,B,A) the GL path produced. The atlas
// is VK_FORMAT_R8G8B8A8_UNORM, so the raw texel bytes match GL RGBA/UNSIGNED_BYTE
// one-for-one and the software rasterizer sees identical data.
//
//Runs at model-bakery construction, outside any frame, through the frame ctx's
// immediate command buffer (submitted + waited synchronously). MC creates the
// block atlas with COPY_SRC (-> TRANSFER_SRC) usage and keeps it, like every MC
// texture, in GENERAL (VkFrameHost.MC_IMAGE_LAYOUT): the copy reads it in place and
// never changes its layout.
public final class VkAtlasTextureReader extends IAtlasTextureReader {
    private final VkFrameCtx frameCtx;

    public VkAtlasTextureReader(VkFrameCtx frameCtx) {
        this.frameCtx = frameCtx;
    }

    @Override
    public int[] read(GpuTexture atlas, int width, int height) {
        if (this.frameCtx.isRecordingFrame()) {
            //Inside the render hook cmd() is MC's (unsubmitted) frame command buffer
            // and flushImmediate() would not run the copy before the read below
            throw new IllegalStateException("Atlas readback must run outside the Voxy render hook");
        }
        long image = ((VulkanGpuTexture) atlas).vkImage();
        long size = (long) width * height * 4;
        var staging = new VkBuffer(this.frameCtx, size, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
        try {
            var cmd = this.frameCtx.cmd();
            //MC's atlas uploads -> this copy
            this.frameCtx.barrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            try (MemoryStack stack = stackPush()) {
                var region = VkBufferImageCopy.calloc(1, stack)
                        .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
                region.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(0).baseArrayLayer(0).layerCount(1);
                region.imageOffset().set(0, 0, 0);
                region.imageExtent().set(width, height, 1);
                vkCmdCopyImageToBuffer(cmd, image, VkFrameHost.MC_IMAGE_LAYOUT, staging.buffer, region);
            }
            //copy -> CPU read of the staging memory
            this.frameCtx.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
            this.frameCtx.flushImmediate();

            var out = new int[width * height];
            long ptr = staging.map();
            MemoryUtil.memIntBuffer(ptr, out.length).get(out);
            return out;
        } finally {
            //Deferred destroy; vkFreeMemory implicitly unmaps the staging map above.
            staging.free();
        }
    }
}
