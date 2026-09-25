package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkFenceCreateInfo;
import org.lwjgl.vulkan.VkMemoryBarrier;
import org.lwjgl.vulkan.VkRenderingInfoKHR;
import org.lwjgl.vulkan.VkSemaphoreCreateInfo;
import org.lwjgl.vulkan.VkSemaphoreTypeCreateInfo;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.util.ArrayList;

import static me.cortex.voxy.client.core.vk.VkUtil.check;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_SEMAPHORE_TYPE_TIMELINE;
import static org.lwjgl.vulkan.VK12.vkGetSemaphoreCounterValue;

//The per-frame Vulkan recording context for the pure-VK path.
//
//ALL of Voxy's GPU work is recorded into MC's live frame command buffer between
// MC's own render passes (upload copies -> compute -> raster passes -> draws ->
// readback copies), ordered with pipeline barriers. This class owns:
//
//  - the current recording target (cmd()), either MC's frame command buffer
//    (inside the render hook) or a one-shot immediate buffer (resource
//    construction outside a frame);
//  - frame completion tracking: each Voxy frame ends by asking MC's encoder
//    (public Blaze3D API) to signal Voxy's timeline semaphore with the frame's
//    index. The counter can only reach that value once MC has submitted the frame
//    AND the GPU has executed it, so frames retire by reading the counter. Upload /
//    download streams and deferred destruction retire on it;
//  - deferred destruction of buffers/images/pipelines still referenced by frames
//    in flight;
//  - two invariants MC's command stream relies on: MC ends every operation with a
//    full memory barrier and never emits one before a pass, so Voxy's frame ends
//    the same way; and a rendering instance left open by an exception is closed
//    before the command buffer goes back to MC.
//
//Thread model: render thread only (MC records its VK frame on its render thread);
// enforced in cmd().
public final class VkFrameCtx {
    public interface FrameRetireListener {
        /** Called when GPU work for every frame {@code <= retiredUpToInclusive} has completed. */
        void onFramesRetired(long retiredUpToInclusive);
    }

    private final VulkanContext ctx;
    private final IVkHost host;
    private final Thread ownerThread;
    private final long timeline;           //timeline semaphore, signalled with each frame's index
    private VkCommandBuffer frameCmd;      //MC's frame command buffer while inside the hook
    private VkCommandBuffer immediateCmd;  //one-shot fallback outside the hook
    private boolean anyWorkThisFrame;
    private boolean renderingActive;       //a vkCmdBeginRenderingKHR without its end has been recorded

    //Frame indices start at 1 so the timeline's initial value (0) means "nothing completed"
    private long frameCounter = 1;         //index of the frame currently being recorded
    private long lastSignaled = 0;         //highest frame index whose signal was handed to MC
    private long retiredCounter = 0;       //all frames <= this have completed on the GPU

    private final ArrayList<PendingDestroy> pendingDestroys = new ArrayList<>();
    private final ArrayList<FrameRetireListener> retireListeners = new ArrayList<>();

    private record PendingDestroy(long frameIdx, long buffer, long image, long imageView, long memory,
                                  long pipeline, long pipelineLayout) {}

    public VkFrameCtx(VulkanContext ctx, IVkHost host) {
        this.ctx = ctx;
        this.host = host;
        this.ownerThread = Thread.currentThread();
        try (MemoryStack stack = stackPush()) {
            var type = VkSemaphoreTypeCreateInfo.calloc(stack).sType$Default()
                    .semaphoreType(VK_SEMAPHORE_TYPE_TIMELINE)
                    .initialValue(0);
            var sci = VkSemaphoreCreateInfo.calloc(stack).sType$Default().pNext(type.address());
            var pSemaphore = stack.mallocLong(1);
            check(vkCreateSemaphore(ctx.device, sci, null, pSemaphore), "vkCreateSemaphore(timeline)");
            this.timeline = pSemaphore.get(0);
        }
    }

    public VulkanContext vk() {
        return this.ctx;
    }

    public void addRetireListener(FrameRetireListener listener) {
        this.retireListeners.add(listener);
    }

    /** Frame index currently being recorded; work recorded now completes when this frame retires. */
    public long currentFrame() {
        return this.frameCounter;
    }

    /** True while inside the render hook (commands go into MC's frame command buffer). */
    public boolean isRecordingFrame() {
        return this.frameCmd != null;
    }

    //==================================================================================
    // Recording targets

    /** Enter the render hook: record into MC's frame command buffer. */
    public void beginFrame(VkCommandBuffer mcFrameCommandBuffer) {
        if (this.frameCmd != null) throw new IllegalStateException("Frame already begun");
        if (mcFrameCommandBuffer == null) throw new IllegalArgumentException("No MC frame command buffer");
        this.frameCmd = mcFrameCommandBuffer;
    }

    /** Leave the render hook: close anything left open, restore MC's barrier invariant, schedule the retire signal. */
    public void endFrame() {
        if (this.frameCmd == null) throw new IllegalStateException("No frame begun");
        try {
            if (this.renderingActive) {
                //Only reachable when recording threw mid-pass. MC would otherwise record
                // its next passes (and Voxy its barrier) inside Voxy's rendering instance.
                Logger.error("Voxy VK frame ended inside a rendering instance; closing it");
                vkCmdEndRenderingKHR(this.frameCmd);
                this.renderingActive = false;
            }
            if (this.anyWorkThisFrame) {
                //MC orders its passes solely by the full barrier each op ENDS with, so its
                // next pass is ordered after Voxy's writes to MC's colour/depth only if
                // Voxy's frame ends the same way
                this.fullBarrier();
                //Ends MC's current command buffer; the signal fires once everything
                // recorded so far (MC's work and Voxy's frame) has executed
                this.host.signalSemaphore(this.timeline, this.frameCounter);
                this.lastSignaled = this.frameCounter;
                this.frameCounter++;
                this.anyWorkThisFrame = false;
            }
        } finally {
            this.frameCmd = null;
        }
    }

    //The command buffer to record into. Inside the render hook this is MC's
    // frame command buffer; outside it, a one-shot immediate command buffer is
    // begun on demand and submitted synchronously by flushImmediate().
    public VkCommandBuffer cmd() {
        if (Thread.currentThread() != this.ownerThread) {
            throw new IllegalStateException("VkFrameCtx used off the render thread (" + Thread.currentThread().getName() + ")");
        }
        this.anyWorkThisFrame = true;
        if (this.frameCmd != null) return this.frameCmd;
        if (this.immediateCmd == null) {
            try (MemoryStack stack = stackPush()) {
                var cbai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                        .commandPool(this.ctx.commandPool)
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                        .commandBufferCount(1);
                var pCmd = stack.mallocPointer(1);
                check(vkAllocateCommandBuffers(this.ctx.device, cbai, pCmd), "vkAllocateCommandBuffers(immediate)");
                this.immediateCmd = new VkCommandBuffer(pCmd.get(0), this.ctx.device);
                var begin = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                check(vkBeginCommandBuffer(this.immediateCmd, begin), "vkBeginCommandBuffer(immediate)");
            }
        }
        return this.immediateCmd;
    }

    /** Submits + waits any pending immediate commands (resource init outside a frame). */
    public void flushImmediate() {
        if (this.immediateCmd == null) return;
        var cmd = this.immediateCmd;
        this.immediateCmd = null;
        if (this.renderingActive && this.frameCmd == null) {
            vkCmdEndRenderingKHR(cmd);
            this.renderingActive = false;
        }
        try (MemoryStack stack = stackPush()) {
            check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer(immediate)");
            var fci = VkFenceCreateInfo.calloc(stack).sType$Default();
            var pFence = stack.mallocLong(1);
            check(vkCreateFence(this.ctx.device, fci, null, pFence), "vkCreateFence(immediate)");
            long fence = pFence.get(0);
            var submit = VkSubmitInfo.calloc(stack).sType$Default()
                    .pCommandBuffers(stack.pointers(cmd));
            check(vkQueueSubmit(this.ctx.queue, submit, fence), "vkQueueSubmit(immediate)");
            check(vkWaitForFences(this.ctx.device, fence, true, Long.MAX_VALUE), "vkWaitForFences(immediate)");
            vkDestroyFence(this.ctx.device, fence, null);
            vkFreeCommandBuffers(this.ctx.device, this.ctx.commandPool, cmd);
        }
    }

    //==================================================================================
    // Dynamic rendering (tracked so endFrame can close an instance an exception left open)

    public void beginRendering(VkRenderingInfoKHR info) {
        if (this.renderingActive) throw new IllegalStateException("Rendering instance already open");
        vkCmdBeginRenderingKHR(this.cmd(), info);
        this.renderingActive = true;
    }

    public void endRendering() {
        if (!this.renderingActive) throw new IllegalStateException("No rendering instance open");
        vkCmdEndRenderingKHR(this.cmd());
        this.renderingActive = false;
    }

    //==================================================================================
    // Frame retirement

    private long completedFrame() {
        try (MemoryStack stack = stackPush()) {
            var pValue = stack.mallocLong(1);
            check(vkGetSemaphoreCounterValue(this.ctx.device, this.timeline, pValue), "vkGetSemaphoreCounterValue");
            return pValue.get(0);
        }
    }

    /** Retire every frame the GPU has finished (recycles staging space, fires readbacks, runs destroys). */
    public void pollRetired() {
        long completed = this.completedFrame();
        if (completed > this.retiredCounter) {
            this.retiredCounter = completed;
            this.runRetirement();
        }
    }

    //Hard sync: submit pending immediate work, wait for the device to go idle, then
    // retire every frame that has completed. vkDeviceWaitIdle only covers SUBMITTED
    // work: a frame still in MC's unsubmitted command buffer (e.g. when called from
    // inside the render hook) has not run, so it is NOT retired; its staging space,
    // readbacks and deferred destroys stay pending until it really completes.
    public void waitIdleRetireAll() {
        this.flushImmediate();
        vkDeviceWaitIdle(this.ctx.device);
        this.retiredCounter = Math.max(this.retiredCounter, this.completedFrame());
        //Always run: callers (e.g. download flushWaitClear) may have just queued work
        // tagged with an already-completed frame and expect it retired now
        this.runRetirement();
    }

    private void runRetirement() {
        for (var l : this.retireListeners) {
            l.onFramesRetired(this.retiredCounter);
        }
        this.pendingDestroys.removeIf(d -> {
            if (d.frameIdx <= this.retiredCounter) {
                if (d.pipeline != VK_NULL_HANDLE) vkDestroyPipeline(this.ctx.device, d.pipeline, null);
                if (d.pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(this.ctx.device, d.pipelineLayout, null);
                if (d.buffer != VK_NULL_HANDLE) vkDestroyBuffer(this.ctx.device, d.buffer, null);
                if (d.imageView != VK_NULL_HANDLE) vkDestroyImageView(this.ctx.device, d.imageView, null);
                if (d.image != VK_NULL_HANDLE) vkDestroyImage(this.ctx.device, d.image, null);
                if (d.memory != VK_NULL_HANDLE) vkFreeMemory(this.ctx.device, d.memory, null);
                return true;
            }
            return false;
        });
    }

    //==================================================================================
    // Deferred destruction (objects may still be referenced by frames in flight)

    public void deferDestroy(long buffer, long memory) {
        this.pendingDestroys.add(new PendingDestroy(this.frameCounter, buffer, VK_NULL_HANDLE, VK_NULL_HANDLE, memory, VK_NULL_HANDLE, VK_NULL_HANDLE));
    }

    public void deferDestroyImage(long image, long view, long memory) {
        this.pendingDestroys.add(new PendingDestroy(this.frameCounter, VK_NULL_HANDLE, image, view, memory, VK_NULL_HANDLE, VK_NULL_HANDLE));
    }

    public void deferDestroyPipeline(long pipeline, long pipelineLayout) {
        this.pendingDestroys.add(new PendingDestroy(this.frameCounter, VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE, VK_NULL_HANDLE, pipeline, pipelineLayout));
    }

    //==================================================================================
    // Command helpers

    public void fillBuffer(VkBuffer buffer, long offset, long size, int value) {
        vkCmdFillBuffer(this.cmd(), buffer.buffer, offset, size, value);
        this.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
    }

    /** Global memory barrier in the current command buffer. */
    public void barrier(int srcStage, int srcAccess, int dstStage, int dstAccess) {
        try (MemoryStack stack = stackPush()) {
            var mb = VkMemoryBarrier.calloc(1, stack).sType$Default()
                    .srcAccessMask(srcAccess).dstAccessMask(dstAccess);
            vkCmdPipelineBarrier(this.cmd(), srcStage, dstStage, 0, mb, null, null);
        }
    }

    /** Everything before (any stage, any write) is complete and visible to everything after. */
    public void fullBarrier() {
        this.barrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
    }

    /** Compute-write -> compute-read barrier (compute-to-compute chaining). */
    public void computeToComputeBarrier() {
        this.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
    }

    /** Compute-write -> draw-indirect/vertex-read barrier (cmdgen -> raster draw). */
    public void computeToDrawBarrier() {
        this.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
                VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_SHADER_READ_BIT);
    }

    /** Compute-write -> transfer-read barrier (compute output -> download copy). */
    public void computeToTransferBarrier() {
        this.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
    }

    public void free() {
        this.waitIdleRetireAll();
        if (this.frameCmd == null && this.completedFrame() >= this.lastSignaled) {
            //Everything Voxy recorded has executed and nothing is being recorded, so
            // objects freed since the last frame (tagged with the upcoming index) are
            // unused too: retire everything, then drop the semaphore
            this.retiredCounter = Long.MAX_VALUE;
            this.runRetirement();
            vkDestroySemaphore(this.ctx.device, this.timeline, null);
        } else {
            //A frame whose signal MC has not submitted yet still references Voxy's
            // objects (and the semaphore): leaking them is the only safe option
            Logger.error("VkFrameCtx freed while a recorded frame is unsubmitted; leaking "
                    + this.pendingDestroys.size() + " pending destroys and the timeline semaphore");
        }
    }
}
