package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;

import java.util.ArrayList;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;

//Device features Voxy's Vulkan path uses beyond what Minecraft enables for itself.
//
//Voxy adopts MC's VkDevice, and a Vulkan device only has the features that were
// ENABLED at vkCreateDevice; physical-device support is not enough. MC 26.2 builds
// its VkPhysicalDeviceFeatures2 from zero and enables only its own list
// (VulkanBackend.REQUIRED_DEVICE_FEATURES), so MixinVulkanBackend hands MC's
// feature set to requestOn() right before vkCreateDevice. Supported features are
// appended to it; what actually got enabled is recorded here, and VulkanContext
// gates every code path on that.
public final class VkDeviceFeatures {
    //Required: the terrain vertex shader uses 64-bit quads
    private static final VulkanFeature SHADER_INT64 = new VulkanFeature(
            com.mojang.blaze3d.vulkan.VulkanBackend.VK10_FEATURES_STRUCT, "shaderInt64", VkPhysicalDeviceFeatures.SHADERINT64);
    //Required: the raster-cull fragment shader writes the visibility SSBO
    private static final VulkanFeature FRAGMENT_STORES = new VulkanFeature(
            com.mojang.blaze3d.vulkan.VulkanBackend.VK10_FEATURES_STRUCT, "fragmentStoresAndAtomics", VkPhysicalDeviceFeatures.FRAGMENTSTORESANDATOMICS);
    //Required: terrain draws carry their position index in the GPU-written firstInstance
    private static final VulkanFeature FIRST_INSTANCE = new VulkanFeature(
            com.mojang.blaze3d.vulkan.VulkanBackend.VK10_FEATURES_STRUCT, "drawIndirectFirstInstance", VkPhysicalDeviceFeatures.DRAWINDIRECTFIRSTINSTANCE);
    //Optional: GPU-sourced draw counts (MoltenVK lacks it; a fixed-count path exists)
    private static final VulkanFeature DRAW_INDIRECT_COUNT = new VulkanFeature(
            com.mojang.blaze3d.vulkan.VulkanBackend.VK12_FEATURES_STRUCT, "drawIndirectCount", VkPhysicalDeviceVulkan12Features.DRAWINDIRECTCOUNT);

    private static volatile boolean requested;
    private static volatile boolean shaderInt64;
    private static volatile boolean fragmentStores;
    private static volatile boolean firstInstance;
    private static volatile boolean drawIndirectCount;

    //Called from MixinVulkanBackend with MC's (mutable) enabled-feature set, before vkCreateDevice.
    public static void requestOn(VkPhysicalDevice physicalDevice, Set<VulkanFeature> enabledFeatures) {
        shaderInt64 = enable(physicalDevice, enabledFeatures, SHADER_INT64);
        fragmentStores = enable(physicalDevice, enabledFeatures, FRAGMENT_STORES);
        firstInstance = enable(physicalDevice, enabledFeatures, FIRST_INSTANCE);
        drawIndirectCount = enable(physicalDevice, enabledFeatures, DRAW_INDIRECT_COUNT);
        requested = true;
        Logger.info("Voxy requested Vulkan device features: shaderInt64=" + shaderInt64
                + ", fragmentStoresAndAtomics=" + fragmentStores
                + ", drawIndirectFirstInstance=" + firstInstance
                + ", drawIndirectCount=" + drawIndirectCount);
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

    /** Null when every feature Voxy requires was enabled on MC's device, else what is missing. */
    public static String missingRequired() {
        if (!requested) {
            return "feature request hook did not run (MixinVulkanBackend)";
        }
        var missing = new ArrayList<String>();
        if (!shaderInt64) missing.add("shaderInt64");
        if (!fragmentStores) missing.add("fragmentStoresAndAtomics");
        if (!firstInstance) missing.add("drawIndirectFirstInstance");
        return missing.isEmpty() ? null : String.join(", ", missing);
    }

    /** drawIndirectCount was ENABLED on MC's device (vkCmdDrawIndexedIndirectCount is valid). */
    public static boolean drawIndirectCount() {
        return requested && drawIndirectCount;
    }

    private VkDeviceFeatures() {}
}
