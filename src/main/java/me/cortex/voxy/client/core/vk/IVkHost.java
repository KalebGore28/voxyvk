package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

/**
 * The PURE-VK integration seam. When Minecraft 26.2+ itself runs on its
 * experimental Vulkan backend ("Prefer Vulkan" Graphics API setting), Voxy must
 * not create a second device: it adopts the game's device and records into the
 * game's frame. An adapter implements this against Blaze3D's Vulkan internals.
 *
 * MinecraftVkHostAdapter is the only class outside client/mixin/vk that may use
 * com.mojang.blaze3d.vulkan.*; everything else reaches MC's Vulkan objects through
 * this interface, so a Minecraft update touches the adapter and the mixins only
 * (see BLAZE3D_MIGRATION_AUDIT.md).
 *
 * This is also the macOS path: Voxy's GL backend needs OpenGL 4.6, which macOS
 * does not have, so on Mac Voxy runs only when MC itself runs on Vulkan (MoltenVK).
 */
public interface IVkHost {
    VkInstance instance();
    VkPhysicalDevice physicalDevice();
    VkDevice device();
    VkQueue graphicsQueue();
    int graphicsQueueFamily();

    /** VkImage behind one of MC's textures (MC keeps it in VkFrameHost.MC_IMAGE_LAYOUT). */
    long vkImage(GpuTexture texture);

    /** VkImageView behind one of MC's texture views. */
    long vkImageView(GpuTextureView view);

    /** The VkFormat MC creates images of this Blaze3D format with. */
    int vkFormat(GpuFormat format);

    /** Command buffer currently recording for this frame's world rendering, at the LOD injection point. */
    VkCommandBuffer frameCommandBuffer();

    /**
     * Appends a timeline-semaphore signal to MC's pending queue submission. MC ends
     * its current command buffer first, so the signal fires only after everything
     * recorded before this call has executed on the GPU (and only once MC submits).
     */
    void signalSemaphore(long timelineSemaphore, long value);
}
