package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRDynamicRendering;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.lwjgl.vulkan.VkQueue;

import java.util.Collection;
import java.util.Set;

import static me.cortex.voxy.client.core.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.vkEndCommandBuffer;

//IVkHost backed by MC 26.2's live Blaze3D Vulkan device. The device-level
// handles (instance/physical device/device/queue+family) are pulled directly
// from MC's VulkanDevice and are stable for the device lifetime. Voxy's per-frame
// command buffers come from MC's persistent command encoder and are spliced into
// its submission; the world colour/depth attachments are passed straight to the
// render core each frame from the Sodium hook's output target, so the adapter
// holds no per-frame state.
//
//This is the only class outside client/mixin/vk that may use Blaze3D's Vulkan
// backend classes (com.mojang.blaze3d.vulkan.*), see IVkHost.
public final class MinecraftVkHostAdapter implements IVkHost {
    //Device features Voxy's shaders and draws use beyond MC's own list (why: VkDeviceFeatures)
    private static final VulkanFeature SHADER_INT64 = new VulkanFeature(
            com.mojang.blaze3d.vulkan.VulkanBackend.VK10_FEATURES_STRUCT, "shaderInt64", VkPhysicalDeviceFeatures.SHADERINT64);
    private static final VulkanFeature FRAGMENT_STORES = new VulkanFeature(
            com.mojang.blaze3d.vulkan.VulkanBackend.VK10_FEATURES_STRUCT, "fragmentStoresAndAtomics", VkPhysicalDeviceFeatures.FRAGMENTSTORESANDATOMICS);
    private static final VulkanFeature FIRST_INSTANCE = new VulkanFeature(
            com.mojang.blaze3d.vulkan.VulkanBackend.VK10_FEATURES_STRUCT, "drawIndirectFirstInstance", VkPhysicalDeviceFeatures.DRAWINDIRECTFIRSTINSTANCE);
    private static final VulkanFeature DRAW_INDIRECT_COUNT = new VulkanFeature(
            com.mojang.blaze3d.vulkan.VulkanBackend.VK12_FEATURES_STRUCT, "drawIndirectCount", VkPhysicalDeviceVulkan12Features.DRAWINDIRECTCOUNT);

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

    @Override public long vkImage(GpuTexture texture) { return ((VulkanGpuTexture) texture).vkImage(); }
    @Override public long vkImageView(GpuTextureView view) { return ((VulkanGpuTextureView) view).vkImageView(); }
    @Override public int vkFormat(GpuFormat format) { return VulkanConst.toVk(format); }

    //createCommandEncoder() returns MC's single persistent encoder (it does not create
    // one). Its allocate-and-begin + execute pair is how MC itself splices extra command
    // buffers into a frame (VulkanTransientMemory, VulkanGpuSurface).
    @Override
    public VkCommandBuffer beginSegment() {
        return this.device.createCommandEncoder().allocateAndBeginTransientCommandBuffer();
    }

    @Override
    public void endSegment(VkCommandBuffer segment) {
        check(vkEndCommandBuffer(segment), "vkEndCommandBuffer(segment)");
        this.device.createCommandEncoder().execute(segment);
    }

    //Run by MixinVulkanBackend on MC's device extensions and (mutable) enabled-feature set
    // right before vkCreateDevice: appends every feature Voxy uses that the physical device
    // supports, then records in VkDeviceFeatures what the device will have enabled.
    public static void requestDeviceFeatures(VkPhysicalDevice physicalDevice, Collection<String> extensions,
                                             Set<VulkanFeature> enabledFeatures) {
        VkDeviceFeatures.recordHostFeatures(
                extensions.contains(KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME),
                extensions.contains(KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME)
                        && isEnabled(enabledFeatures, "dynamicRendering"));
        VkDeviceFeatures.record(
                enable(physicalDevice, enabledFeatures, SHADER_INT64),
                enable(physicalDevice, enabledFeatures, FRAGMENT_STORES),
                enable(physicalDevice, enabledFeatures, FIRST_INSTANCE),
                enable(physicalDevice, enabledFeatures, DRAW_INDIRECT_COUNT));
    }

    private static boolean isEnabled(Set<VulkanFeature> enabledFeatures, String name) {
        for (var feature : enabledFeatures) {
            if (feature.name().equals(name)) return true;
        }
        return false;
    }

    private static boolean enable(VkPhysicalDevice physicalDevice, Set<VulkanFeature> enabledFeatures, VulkanFeature feature) {
        if (enabledFeatures.contains(feature)) {
            return true;//MC enables it itself
        }
        if (!isSupported(physicalDevice, feature)) {
            return false;//requesting an unsupported feature would fail vkCreateDevice
        }
        enabledFeatures.add(feature);
        return true;
    }

    private static boolean isSupported(VkPhysicalDevice physicalDevice, VulkanFeature feature) {
        try (MemoryStack stack = stackPush()) {
            var features2 = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
            feature.struct().findOrCreateStructInPNextChain(features2, stack);
            VK11.vkGetPhysicalDeviceFeatures2(physicalDevice, features2);
            return feature.get(features2);
        }
    }
}
