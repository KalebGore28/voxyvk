package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.rendering.IRenderList;
import me.cortex.voxy.client.core.rendering.util.IDeviceBuffer;
import me.cortex.voxy.common.util.TrackedObject;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VkBufferCreateInfo;

import static me.cortex.voxy.client.core.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.*;
import static org.lwjgl.vulkan.VK10.*;

//Device buffer on the pure-Vulkan path; the VK analogue of GlBuffer. All Voxy
// buffers get a superset of usage flags (storage/indirect/index/transfer) so a
// single class covers every role the GL path used raw buffer ids for. Memory
// comes from MC's VMA allocator: small buffers share its memory blocks, large
// ones get dedicated allocations.
//
//Freeing is DEFERRED through VkFrameCtx — a buffer may still be referenced by
// command buffers in flight when free() is called.
public class VkBuffer extends TrackedObject implements IDeviceBuffer, IRenderList {
    public static final int USAGE_DEFAULT = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT
            | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
            | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
            | VK_BUFFER_USAGE_INDEX_BUFFER_BIT
            | VK_BUFFER_USAGE_TRANSFER_SRC_BIT
            | VK_BUFFER_USAGE_TRANSFER_DST_BIT;

    private final VkFrameCtx ctx;
    public final long buffer;
    private final long allocation;//VmaAllocation
    private final long mappedPtr;//persistent mapping of host-visible buffers, else 0
    private final long size;

    private static int COUNT;
    private static long TOTAL_SIZE;

    public VkBuffer(VkFrameCtx ctx, long size) {
        this(ctx, size, USAGE_DEFAULT, false);
    }

    public VkBuffer(VkFrameCtx ctx, long size, int usage, boolean hostVisible) {
        this(ctx, size, usage,
                hostVisible ? (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) : VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                0);
    }

    //requiredMemory must all be present on the chosen memory type; preferredMemory is
    // used when some type also has it (e.g. HOST_CACHED for CPU readback buffers).
    // VMA picks the type from exactly these flags (no usage preset), as the manual
    // memory-type search did before.
    public VkBuffer(VkFrameCtx ctx, long size, int usage, int requiredMemory, int preferredMemory) {
        this.ctx = ctx;
        this.size = size;
        var vctx = ctx.vk();
        try (MemoryStack stack = stackPush()) {
            var bci = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            var aci = VmaAllocationCreateInfo.calloc(stack)
                    .usage(VMA_MEMORY_USAGE_UNKNOWN)
                    .requiredFlags(requiredMemory)
                    .preferredFlags(preferredMemory);
            if ((requiredMemory & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT) != 0) {
                aci.flags(VMA_ALLOCATION_CREATE_MAPPED_BIT);//persistently mapped, see map()
            }
            var pBuffer = stack.mallocLong(1);
            var pAllocation = stack.mallocPointer(1);
            var info = VmaAllocationInfo.calloc(stack);
            //Allocation can legitimately fail (VkSectionGeometryData retries smaller);
            // VMA creates nothing then, so there is nothing to clean up
            check(vmaCreateBuffer(vctx.vma, bci, aci, pBuffer, pAllocation, info), "vmaCreateBuffer");
            this.buffer = pBuffer.get(0);
            this.allocation = pAllocation.get(0);
            this.mappedPtr = info.pMappedData();
        }
        COUNT++;
        TOTAL_SIZE += size;
    }

    /** Address of the whole buffer's persistent mapping; only valid for hostVisible buffers. */
    public long map() {
        if (this.mappedPtr == 0) throw new IllegalStateException("VkBuffer is not host visible");
        return this.mappedPtr;
    }

    @Override
    public long sizeBytes() {
        return this.size;
    }

    public long size() {
        return this.size;
    }

    @Override
    public int glId() {
        throw new UnsupportedOperationException("VkBuffer has no GL id");
    }

    /** Records a fill of 0 across the given range into the current frame commands. */
    public VkBuffer zeroRange(long offset, long len) {
        this.ctx.fillBuffer(this, offset, len, 0);
        return this;
    }

    public VkBuffer zero() {
        return this.zeroRange(0, VK_WHOLE_SIZE);
    }

    public VkBuffer fill(int value) {
        this.ctx.fillBuffer(this, 0, VK_WHOLE_SIZE, value);
        return this;
    }

    @Override
    public void free() {
        this.free0();
        COUNT--;
        TOTAL_SIZE -= this.size;
        long vma = this.ctx.vk().vma;
        long buffer = this.buffer, allocation = this.allocation;
        this.ctx.deferDestroy(() -> vmaDestroyBuffer(vma, buffer, allocation));
    }

    public static int getCount() {
        return COUNT;
    }

    public static long getTotalSize() {
        return TOTAL_SIZE;
    }
}
