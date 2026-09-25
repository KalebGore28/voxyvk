package me.cortex.voxy.client.mixin.vk;

import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.common.Logger;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

//Frame boundary for the Vulkan path. MC submits its command buffer once per frame,
// at the end of renderFrame, so at its HEAD everything MC recorded so far has been
// submitted and nothing new has been recorded. A Voxy Vulkan renderer requested
// mid-frame is created here instead (see MixinLevelRenderer.voxy$createRenderer):
// the work its construction submits immediately (e.g. the block-atlas readback) is
// then ordered after all of MC's pending GPU work.
@Mixin(Minecraft.class)
public class MixinMinecraftFrameStart {
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void voxy$createPendingRenderer(CallbackInfo ci) {
        var holder = IVoxyRenderSystemHolder.getNullableHolder();
        if (holder == null) return;
        try {
            holder.voxy$createPendingRenderer();
        } catch (RuntimeException e) {
            //Same outcome as a failed immediate creation, without taking the frame down
            Logger.error("Voxy: failed to create the Vulkan renderer", e);
        }
    }
}
