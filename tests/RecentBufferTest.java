package cn.classroom.assistant;

import java.util.Arrays;

/** JVM tests for retention and ordering, independent of Android hardware. */
public final class RecentBufferTest {
    public static void main(String[] args) {
        RecentBuffer buffer = new RecentBuffer(5, 180000);
        buffer.append(new short[]{1,2,3}, 3);
        require(Arrays.equals(buffer.audio(10), new short[]{1,2,3}), "underfilled audio");
        buffer.append(new short[]{4,5,6,7}, 4);
        require(Arrays.equals(buffer.audio(10), new short[]{3,4,5,6,7}), "wrapped audio order");
        require(Arrays.equals(buffer.audio(2), new short[]{6,7}), "recent audio window");
        require(buffer.sampleCount() == 5, "bounded audio capacity");
        buffer.finish("旧上下文", 1000);
        buffer.finish("老师刚才的问题", 100000);
        buffer.partial("还在讲的内容", 110000);
        require(buffer.text(110000, 30000).equals("老师刚才的问题\n还在讲的内容"), "window and partial");
        buffer.finish("完整句子", 120000);
        require(!buffer.text(120000, 30000).contains("还在讲"), "final replaces partial");
        require(!buffer.text(190001, 180000).contains("旧上下文"), "retention expires old text");
        require(buffer.text(400001, 180000).isEmpty(), "all text expires");
        buffer.clear();
        require(buffer.audio(5).length == 0 && buffer.text(400001, 180000).isEmpty(), "clear data");
        for (int i=0; i<1000; i++) buffer.finish("x", 500000);
        require(buffer.text(500000, 180000).split("\n").length == 300, "bounded segment count");
        System.out.println("PASS: audio wrapping, recent windows, partial replacement, retention, clearing, bounded transcript");
    }
    private static void require(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
