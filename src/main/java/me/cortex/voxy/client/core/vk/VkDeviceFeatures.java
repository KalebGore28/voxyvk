package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.common.Logger;

import java.util.ArrayList;

//Device features Voxy's Vulkan path uses beyond what Minecraft enables for itself.
//
//Voxy adopts MC's VkDevice, and a Vulkan device only has the features that were
// ENABLED at vkCreateDevice; physical-device support is not enough. MC 26.2 builds
// its VkPhysicalDeviceFeatures2 from zero and enables only its own list
// (VulkanBackend.REQUIRED_DEVICE_FEATURES), so MixinVulkanBackend has
// MinecraftVkHostAdapter.requestDeviceFeatures append the supported ones to MC's
// feature set right before vkCreateDevice. What actually got enabled is recorded
// here, and VulkanContext gates every code path on that.
public final class VkDeviceFeatures {
    private static volatile boolean requested;
    //Required: the terrain vertex shader uses 64-bit quads
    private static volatile boolean shaderInt64;
    //Required: the raster-cull fragment shader writes the visibility SSBO
    private static volatile boolean fragmentStores;
    //Required: terrain draws carry their position index in the GPU-written firstInstance
    private static volatile boolean firstInstance;
    //Optional: GPU-sourced draw counts (MoltenVK lacks it; a fixed-count path exists)
    private static volatile boolean drawIndirectCount;

    //Called by MinecraftVkHostAdapter.requestDeviceFeatures, before vkCreateDevice.
    public static void record(boolean shaderInt64, boolean fragmentStores, boolean firstInstance, boolean drawIndirectCount) {
        VkDeviceFeatures.shaderInt64 = shaderInt64;
        VkDeviceFeatures.fragmentStores = fragmentStores;
        VkDeviceFeatures.firstInstance = firstInstance;
        VkDeviceFeatures.drawIndirectCount = drawIndirectCount;
        requested = true;
        Logger.info("Voxy requested Vulkan device features: shaderInt64=" + shaderInt64
                + ", fragmentStoresAndAtomics=" + fragmentStores
                + ", drawIndirectFirstInstance=" + firstInstance
                + ", drawIndirectCount=" + drawIndirectCount);
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
