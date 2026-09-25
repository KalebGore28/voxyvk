package me.cortex.voxy.client.core.vk;

import com.mojang.blaze3d.systems.GpuQueryPool;
import com.mojang.blaze3d.systems.RenderSystem;

import java.util.Arrays;
import java.util.Locale;
import java.util.OptionalLong;

//GPU time per pass of Voxy's VK frame, from Blaze3D's public timestamp queries
// (GpuDevice.createTimestampQueryPool, CommandEncoder.writeTimestamp, GpuQueryPool.getValues).
// The VK counterpart of GL's GPUTiming: the same F3 entry (voxy:gpu_debug) turns it on and
// it shows a "GpuTime: [...]" line with GL's labels where the passes match. Unlike GL's
// decaying peak, each section shows its average over the last second, followed by the
// average and worst frame total, so steady cost and spikes read apart.
//
//Blaze3D records a timestamp into MC's command stream, never into Voxy's command buffer, so
// VkFrameCtx.gpuMarker splits the frame there: the Voxy commands recorded so far are spliced
// into MC's submission, the timestamp lands in MC's buffer right after them, and Voxy goes on
// in a fresh buffer. MC writes each timestamp once every earlier command has finished
// (ALL_COMMANDS), so a section runs from the end of the previous section's work to the end of
// its own. With timing off nothing is split and no query is written.
//
//Blaze3D resets a query on the host when it records the write, so a frame's queries are only
// reused once that frame has retired and its results were read.
final class VkGpuTiming {
    private static final int MAX_MARKERS = 32;//per frame, including the closing timestamp
    private static final int SLOTS = 4;//timed frames in flight (MC keeps at most 2 submits in flight)

    private static final class Slot {
        final int base;
        final String[] labels = new String[MAX_MARKERS];
        int count;
        long frameIdx;
        boolean pending;//timestamps written, results not read yet

        Slot(int base) {
            this.base = base;
        }
    }

    private final Slot[] slots = new Slot[SLOTS];
    private GpuQueryPool pool;
    private Slot recording;//the frame being recorded, when it is timed
    private boolean wasEnabled;

    //Sums over the current one-second window, and what the last full window averaged to
    private String[] labels = new String[0];
    private double[] sums = new double[0];
    private double totalSum;
    private float totalMax;
    private int frames;
    private long windowStart;
    private String shown = "GpuTime: [waiting]";

    VkGpuTiming() {
        for (int i = 0; i < SLOTS; i++) {
            this.slots[i] = new Slot(i * MAX_MARKERS);
        }
    }

    /** Times the frame about to be recorded (index {@code frameIdx}) when enabled and a slot is free. */
    void beginFrame(boolean enabled, long frameIdx) {
        if (this.recording != null) {
            //The last frame threw before closing its timing: its timestamps may still be
            // pending on the GPU, so the slot waits for that frame like any other
            this.recording.pending = true;
            this.recording = null;
        }
        if (enabled && !this.wasEnabled) {
            this.resetWindow(new String[0]);
            this.shown = "GpuTime: [waiting]";
        }
        this.wasEnabled = enabled;
        if (!enabled) return;
        for (var slot : this.slots) {
            if (!slot.pending) {
                if (this.pool == null) {
                    this.pool = RenderSystem.getDevice().createTimestampQueryPool(SLOTS * MAX_MARKERS);
                }
                slot.count = 0;
                slot.frameIdx = frameIdx;
                this.recording = slot;
                return;
            }
        }
        //Every slot still waits for its frame to retire: this frame goes untimed
    }

    /** Whether the frame being recorded is timed and can take another marker (one is kept for the closing timestamp). */
    boolean canMark() {
        return this.recording != null && this.recording.count < MAX_MARKERS - 1;
    }

    /** Records the timestamp that closes the previous section and opens {@code label}, into MC's command stream at this point. */
    void mark(String label) {
        var slot = this.recording;
        slot.labels[slot.count] = label;
        RenderSystem.getDevice().createCommandEncoder().writeTimestamp(this.pool, slot.base + slot.count);
        slot.count++;
    }

    /** Closes the last section, after Voxy's last command buffer has been spliced in. */
    void endFrame() {
        var slot = this.recording;
        if (slot == null) return;
        if (slot.count > 0) {
            this.mark(null);
            slot.pending = true;
        }
        this.recording = null;
    }

    /** Reads every timed frame up to {@code retiredUpToInclusive}, oldest first (VkFrameCtx retire listener). */
    void onFramesRetired(long retiredUpToInclusive) {
        if (this.pool == null) return;
        while (true) {
            Slot oldest = null;
            for (var slot : this.slots) {
                if (slot.pending && slot.frameIdx <= retiredUpToInclusive && (oldest == null || slot.frameIdx < oldest.frameIdx)) {
                    oldest = slot;
                }
            }
            if (oldest == null) return;
            oldest.pending = false;
            this.read(oldest);
        }
    }

    private void read(Slot slot) {
        if (slot.count < 2) return;
        OptionalLong[] values = this.pool.getValues(slot.base, slot.count);
        long[] ticks = new long[slot.count];
        for (int i = 0; i < slot.count; i++) {
            if (values[i].isEmpty()) return;//not written (e.g. the frame threw before it); drop the frame
            ticks[i] = values[i].getAsLong();
        }
        double msPerTick = RenderSystem.getDevice().getDeviceInfo().timestampPeriod() / 1_000_000.0;
        int sections = slot.count - 1;
        double[] frame = new double[sections];
        double total = 0;
        for (int i = 0; i < sections; i++) {
            long delta = ticks[i + 1] - ticks[i];
            if (delta < 0) return;//counter wrapped; drop the frame
            frame[i] = delta * msPerTick;
            total += frame[i];
        }

        String[] frameLabels = Arrays.copyOf(slot.labels, sections);
        if (!Arrays.equals(frameLabels, this.labels)) {
            //A different set of passes ran (e.g. no geometry yet): start a new window
            this.resetWindow(frameLabels);
        }
        for (int i = 0; i < sections; i++) {
            this.sums[i] += frame[i];
        }
        this.totalSum += total;
        this.totalMax = Math.max(this.totalMax, (float) total);
        this.frames++;

        long now = System.currentTimeMillis();
        if (now - this.windowStart >= 1000) {
            this.shown = this.format();
            this.resetWindow(this.labels);
            this.windowStart = now;
        }
    }

    private void resetWindow(String[] labels) {
        this.labels = labels;
        this.sums = new double[labels.length];
        this.totalSum = 0;
        this.totalMax = 0;
        this.frames = 0;
    }

    private String format() {
        var str = new StringBuilder("GpuTime: [");
        for (int i = 0; i < this.labels.length; i++) {
            if (i != 0) str.append(", ");
            str.append(this.labels[i]).append(':').append(String.format(Locale.ROOT, "%.2f", this.sums[i] / this.frames));
        }
        return str.append("] = ").append(String.format(Locale.ROOT, "%.2f", this.totalSum / this.frames))
                .append(" ms, worst ").append(String.format(Locale.ROOT, "%.2f", this.totalMax)).toString();
    }

    String getDebug() {
        return this.shown;
    }

    void free() {
        if (this.pool != null) {
            //Blaze3D destroys it once the submission being recorded now has completed
            this.pool.close();
            this.pool = null;
        }
        this.recording = null;
        for (var slot : this.slots) {
            slot.pending = false;
        }
    }
}
