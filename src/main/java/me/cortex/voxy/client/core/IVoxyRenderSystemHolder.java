package me.cortex.voxy.client.core;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

public interface IVoxyRenderSystemHolder {
    VoxyRenderSystem voxy$getRenderSystem();
    void voxy$shutdownRenderer();
    void voxy$createRenderer();
    /** Runs a renderer creation deferred to a frame boundary (Vulkan path), if one is pending. */
    void voxy$createPendingRenderer();
    //void voxy$reloadRenderer();
    void voxy$setWorld(Level level);

    static VoxyRenderSystem getNullable() {
        var lr = getNullableHolder();
        if (lr == null) return null;
        return lr.voxy$getRenderSystem();
    }

    static IVoxyRenderSystemHolder getNullableHolder() {
        return  (IVoxyRenderSystemHolder)Minecraft.getInstance().levelRenderer;
    }
}
