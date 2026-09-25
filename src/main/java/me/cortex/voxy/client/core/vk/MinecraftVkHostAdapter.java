package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.vulkan.VulkanDevice;
import me.cortex.voxy.client.mixin.vk.AccessorVulkanCommandEncoder;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;

import static org.lwjgl.vulkan.VK13.VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT;

//IVkHost backed by MC 26.2's live Blaze3D Vulkan device. The device-level
// handles (instance/physical device/device/queue+family) are pulled directly
// from MC's VulkanDevice and are stable for the device lifetime. The per-frame
// command buffer is resolved live from MC's persistent command encoder; the
// world colour/depth attachments are passed straight to the render core each
// frame from the Sodium hook's output target, so the adapter holds no per-frame
// state.
public final class MinecraftVkHostAdapter implements IVkHost {
    private final VulkanDevice device;

    public MinecraftVkHostAdapter(VulkanDevice device) {
        this.device = device;
    }

    /** True if this adapter wraps {@code device}. */
    public boolean wraps(VulkanDevice device) {
        return this.device == device;
    }

    @Override public VkInstance instance() { return this.device.instance().vkInstance(); }
    @Override public VkPhysicalDevice physicalDevice() { return this.device.vkDevice().getPhysicalDevice(); }
    @Override public VkDevice device() { return this.device.vkDevice(); }
    @Override public VkQueue graphicsQueue() { return this.device.graphicsQueue().vkQueue(); }
    @Override public int graphicsQueueFamily() { return this.device.graphicsQueue().queueFamilyIndex(); }

    @Override
    public VkCommandBuffer frameCommandBuffer() {
        //createCommandEncoder() returns MC's single persistent encoder (it does not
        // create one). This is the command buffer it is currently recording into;
        // null when MC has recorded nothing since it last ended one.
        var encoder = (AccessorVulkanCommandEncoder) (Object) this.device.createCommandEncoder();
        return encoder.voxy$currentCommandBuffer();
    }

    @Override
    public void signalSemaphore(long timelineSemaphore, long value) {
        //Public Blaze3D API: ends MC's current command buffer and adds the signal to
        // the pending submission (a later command buffer starts a new submit batch)
        this.device.createCommandEncoder().signalSemaphore(timelineSemaphore, value, VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT);
    }
}
