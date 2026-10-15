package cn.classroom.assistant;

final class Session {
    static final Object captureLock = new Object();
    static volatile Object captureOwner;
    static final RecentBuffer buffer = new RecentBuffer(16000 * 180, 180000);
    static volatile boolean listening, answering, preparing;
    static volatile String inputDevice = "录音来源：开始后显示实际设备", recognitionStatus = "SenseVoice 中文识别 · 停顿后显示文字";
    static volatile int volumeDb = -96;
    static volatile String status = "准备好后，开始听课", answer = "", question = "";
    static volatile long startedAt;
    static volatile int level;
    static volatile int revision;
    static void changed() { revision++; }
}
