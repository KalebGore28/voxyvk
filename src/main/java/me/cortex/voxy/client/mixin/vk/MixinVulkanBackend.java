package me.cortex.voxy.client.mixin.vk;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import me.cortex.voxy.client.core.vk.MinecraftVkHostAdapter;
import me.cortex.voxy.common.Logger;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Collection;
import java.util.Set;

//MC creates its VkDevice with ONLY the features in the set passed to this private
// helper (everything else stays disabled). Voxy adopts that device, so it appends
// the extra features its shaders/draws need (when supported) before vkCreateDevice.
//require = 0: if a future MC reshapes this method the device is created unchanged
// and Voxy's Vulkan path reports itself unsupported instead of crashing the game.
@Mixin(VulkanBackend.class)
public class MixinVulkanBackend {
    @Inject(method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At("HEAD"), require = 0)
    private static void voxy$requestDeviceFeatures(Collection<String> extensions, VulkanPhysicalDevice physicalDevice,
                                                   Set<VulkanFeature> features, CallbackInfoReturnable<VkDevice> cir) {
        try {
            MinecraftVkHostAdapter.requestDeviceFeatures(physicalDevice.vkPhysicalDevice(), extensions, features);
        } catch (Throwable t) {
            //Never break MC's device creation; Voxy's VK path will report the missing features
            Logger.warn("Voxy: failed to request Vulkan device features: " + t);
        }
    }
}
