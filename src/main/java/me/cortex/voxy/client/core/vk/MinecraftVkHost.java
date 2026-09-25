package me.cortex.voxy.client.core.vk;

/**
 * Adapter registry for the vanilla 26.2 Blaze3D Vulkan backend.
 *
 * The Blaze3D-VK adapter mixin (MixinVulkanDevice) calls {@link #register} when
 * MC constructs its VulkanDevice and {@link #clear} when that device is closed.
 */
public final class MinecraftVkHost {
    private static volatile IVkHost host;

    public static void register(IVkHost h) { host = h; }
    public static void clear() { host = null; }
    /** Non-null only when MC has created its Vulkan device and the adapter mixin is active. */
    public static IVkHost get() { return host; }

    //Whether MC is currently rendering through its own Vulkan backend.
    //The live Blaze3D device is authoritative: it reflects the Graphics API setting
    // AFTER MC's capability fallback ladder has run. A registered host only proves a
    // VulkanDevice was constructed, so it is used only before a device exists.
    public static boolean isMinecraftOnVulkan() {
        Boolean live = detectActiveVulkan();
        return live != null ? live : host != null;
    }

    private static Boolean detectActiveVulkan() {
        try {
            var device = com.mojang.blaze3d.systems.RenderSystem.tryGetDevice();
            if (device == null) return null;
            String backend = device.getDeviceInfo().backendName();
            return backend != null && backend.toLowerCase(java.util.Locale.ROOT).contains("vulkan");
        } catch (Throwable t) {
            return null;
        }
    }

    private MinecraftVkHost() {}
}
