package cn.classroom.assistant;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Bounded audio and transcript history. Time is monotonic elapsed time. */
public final class RecentBuffer {
    private final short[] samples;
    private int write, size;
    private final ArrayDeque<Segment> segments = new ArrayDeque<>();
    private final long retentionMs;
    private String partial = "";
    private long partialAt;
    public RecentBuffer(int capacity, long retentionMs) {
        this.samples = new short[capacity];
        this.retentionMs = retentionMs;
    }
    public synchronized void append(short[] data, int count) {
        for (int i = 0; i < count; i++) {
            samples[write] = data[i];
            write = (write + 1) % samples.length;
            size = Math.min(size + 1, samples.length);
        }
    }
    public synchronized short[] audio(int count) {
        int length = Math.min(count, size);
        short[] result = new short[length];
        int start = (write - length + samples.length) % samples.length;
        for (int i = 0; i < length; i++) result[i] = samples[(start + i) % samples.length];
        return result;
    }
    public synchronized int sampleCount() { return size; }
    public synchronized void partial(String value, long now) {
        partial = value; partialAt = now; prune(now);
    }
    public synchronized void finish(String value, long now) {
        if (!value.trim().isEmpty()) segments.add(new Segment(value.trim(), now));
        partial = ""; prune(now);
    }
    private void prune(long now) {
        while (!segments.isEmpty() && segments.peek().time < now - retentionMs) segments.remove();
        // Also bounded under unusually rapid segmentation.
        while (segments.size() > 300) segments.remove();
    }
    public synchronized String text(long now, long windowMs) {
        prune(now);
        List<String> lines = new ArrayList<>();
        for (Segment segment : segments) if (segment.time >= now - windowMs) lines.add(segment.text);
        if (!partial.isEmpty() && partialAt >= now - windowMs) lines.add(partial);
        StringBuilder out = new StringBuilder();
        for (String line : lines) { if (out.length() > 0) out.append('\n'); out.append(line); }
        // Keep prompts finite even with unexpected recognizer output.
        return out.length() > 8000 ? out.substring(out.length() - 8000) : out.toString();
    }
    public synchronized void clear() {
        java.util.Arrays.fill(samples, (short) 0);
        write = size = 0; segments.clear(); partial = "";
    }
    private static final class Segment {
        final String text; final long time;
        Segment(String text, long time) { this.text = text; this.time = time; }
    }
}
