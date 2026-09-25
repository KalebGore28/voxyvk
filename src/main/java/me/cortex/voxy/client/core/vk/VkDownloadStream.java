package me.cortex.voxy.client.core.vk;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import me.cortex.voxy.client.core.rendering.util.AbstractDownloadStream;
import me.cortex.voxy.client.core.rendering.util.IDeviceBuffer;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.AllocationArena;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkBufferCopy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;

import static me.cortex.voxy.common.util.AllocationArena.SIZE_LIMIT;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

//Pure-VK GPU->CPU readback: vkCmdCopyBuffer into a host-visible readback
// buffer at commit(); callbacks fire (from VkFrameCtx retirement) once the
// producing frame has retired (the copy provably completed). Mirrors the GL
// DownloadStream fence/frame model.
public class VkDownloadStream extends AbstractDownloadStream {
    private final VkFrameCtx ctx;
    private final VkBuffer readbackBuffer;
    private final long readbackPtr;

    private final AllocationArena allocationArena = new AllocationArena();
    private final Deque<DownloadFrame> frames = new ArrayDeque<>();
    private final LongArrayList thisFrameAllocations = new LongArrayList();
    private final Deque<DownloadData> downloadList = new ArrayDeque<>();
    private final ArrayList<DownloadData> thisFrameDownloadList = new ArrayList<>();

    private long caddr = -1;
    private long offset = 0;
    //The frame whose command buffer actually recorded the pending copies. tick()
    // runs at the START of the next render frame (after endFrame advanced the
    // counter), so currentFrame() there is one AHEAD of the frame the copy was
    // recorded into — tagging with it made readbacks retire (and fire their
    // node-visibility callbacks) a whole frame late. Capture the true recording
    // frame at commit() instead.
    private long recordFrame = -1;

    public VkDownloadStream(VkFrameCtx ctx, long size) {
        this.ctx = ctx;
        //The CPU reads this memory: prefer a HOST_CACHED type (on discrete GPUs the
        // first coherent type is usually uncached write-combined, which is very slow
        // to read). Coherent either way, so no invalidate is needed.
        this.readbackBuffer = new VkBuffer(ctx, size, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
        this.readbackPtr = this.readbackBuffer.map();
        this.allocationArena.setLimit(size);
        ctx.addRetireListener(this::retireUpTo);
    }

    @Override
    public void download(IDeviceBuffer buffer, long downloadOffset, long size, DownloadResultConsumer resultConsumer) {
        if (size > Integer.MAX_VALUE || size <= 0) throw new IllegalArgumentException();
        if (downloadOffset + size > buffer.sizeBytes()) throw new IllegalArgumentException();

        long addr;
        if (this.caddr == -1 || !this.allocationArena.expand(this.caddr, (int) size)) {
            this.caddr = this.allocationArena.alloc((int) size);
            if (this.caddr == SIZE_LIMIT) {
                Logger.warn("VK download stream full, force-idling the device to recover; this will hitch");
                this.commit();
                int attempts = 10;
                while (--attempts != 0 && this.caddr == SIZE_LIMIT) {
                    this.ctx.waitIdleRetireAll();
                    this.tick();
                    this.caddr = this.allocationArena.alloc((int) size);
                }
                if (this.caddr == SIZE_LIMIT) {
                    throw new IllegalStateException("Could not allocate readback space even after device idle");
                }
            }
            this.thisFrameAllocations.add(this.caddr);
            this.offset = size;
            addr = this.caddr;
        } else {
            addr = this.caddr + this.offset;
            this.offset += size;
        }
        this.downloadList.add(new DownloadData((VkBuffer) buffer, addr, downloadOffset, size, resultConsumer));
        this.commit();
    }

    @Override
    public void commit() {
        if (this.downloadList.isEmpty()) return;
        //Capture the frame currently being recorded — the copies below land in its
        // command buffer and complete when that frame retires.
        this.recordFrame = this.ctx.currentFrame();
        var cmd = this.ctx.cmd();
        //Source buffers are compute/raster/transfer outputs; narrow from ALL_COMMANDS
        // to the actual producing stages so unrelated GPU work overlaps the copy.
        this.ctx.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT | VK_ACCESS_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        try (MemoryStack stack = stackPush()) {
            for (var entry : this.downloadList) {
                var region = VkBufferCopy.calloc(1, stack)
                        .srcOffset(entry.targetOffset).dstOffset(entry.downloadStreamOffset).size(entry.size);
                vkCmdCopyBuffer(cmd, entry.target.buffer, this.readbackBuffer.buffer, region);
            }
        }
        this.ctx.barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
        this.thisFrameDownloadList.addAll(this.downloadList);
        this.downloadList.clear();
        this.caddr = -1;
        this.offset = 0;
    }

    @Override
    public void tick() {
        this.commit();
        if (!this.thisFrameAllocations.isEmpty()) {
            //Tag with the frame the copies were RECORDED in (captured at commit),
            // not currentFrame() — which at this early-in-frame tick() is one ahead.
            this.frames.add(new DownloadFrame(this.recordFrame,
                    new LongArrayList(this.thisFrameAllocations), new ArrayList<>(this.thisFrameDownloadList)));
            this.thisFrameAllocations.clear();
            this.thisFrameDownloadList.clear();
        }
        //pollRetired() is called once at the end of each frame by VkRenderCore;
        // polling here too was redundant (3x/frame).
    }

    private void retireUpTo(long retiredFrame) {
        while (!this.frames.isEmpty() && this.frames.peek().frameIdx <= retiredFrame) {
            var frame = this.frames.pop();
            if (!frame.discard) {
                for (var data : frame.data) {
                    data.resultConsumer.consume(this.readbackPtr + data.downloadStreamOffset, data.size);
                }
            }
            frame.allocations.forEach(this.allocationArena::free);
        }
    }

    //GL semantics: complete in-flight copies and drop every pending readback WITHOUT
    // invoking its callback. (This used to call waitIdleRetireAll first, whose retire
    // listener fired all the callbacks it was meant to discard.) A frame that cannot
    // retire yet (still unsubmitted) keeps its readback space until it does; only its
    // callbacks are dropped.
    @Override
    public void waitDiscard() {
        this.tick();
        for (var frame : this.frames) {
            frame.discard = true;
        }
        this.ctx.waitIdleRetireAll();
    }

    @Override
    public void flushWaitClear() {
        this.tick();
        this.ctx.waitIdleRetireAll();
        if (!this.frames.isEmpty()) {
            //waitIdleRetireAll retires via listener; anything left means listener ordering broke
            throw new IllegalStateException();
        }
    }

    @Override
    public void free() {
        this.readbackBuffer.free();
    }

    private static final class DownloadFrame {
        final long frameIdx;
        final LongArrayList allocations;
        final ArrayList<DownloadData> data;
        boolean discard;//retire without invoking the callbacks (waitDiscard)

        DownloadFrame(long frameIdx, LongArrayList allocations, ArrayList<DownloadData> data) {
            this.frameIdx = frameIdx;
            this.allocations = allocations;
            this.data = data;
        }
    }
    private record DownloadData(VkBuffer target, long downloadStreamOffset, long targetOffset, long size, DownloadResultConsumer resultConsumer) {}
}
