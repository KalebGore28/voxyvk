package me.cortex.voxy.client.core.vk;

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
 * This is also the macOS path: Voxy's GL backend needs OpenGL 4.6, which macOS
 * does not have, so on Mac Voxy runs only when MC itself runs on Vulkan (MoltenVK).
 */
public interface IVkHost {
    VkInstance instance();
    VkPhysicalDevice physicalDevice();
    VkDevice device();
    VkQueue graphicsQueue();
    int graphicsQueueFamily();

    /** Command buffer currently recording for this frame's world rendering, at the LOD injection point. */
    VkCommandBuffer frameCommandBuffer();

    /**
     * Appends a timeline-semaphore signal to MC's pending queue submission. MC ends
     * its current command buffer first, so the signal fires only after everything
     * recorded before this call has executed on the GPU (and only once MC submits).
     */
    void signalSemaphore(long timelineSemaphore, long value);
}
