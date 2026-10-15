package cn.classroom.assistant;

import com.k2fsa.sherpa.onnx.*;
import java.io.File;
import java.util.concurrent.*;

/** Capture thread runs VAD only; a separate bounded worker does expensive recognition. */
public final class SpeechPipeline implements AutoCloseable {
    public interface Listener {
        void text(String text, long endSample);
        void error(String message);
    }
    private final SenseVoiceEngine engine;
    private final Vad vad;
    private final Listener listener;
    private final ThreadPoolExecutor worker;
    private long acceptedSamples, epochStart;
    private volatile boolean closed;
    public SpeechPipeline(File modelDirectory, Listener listener) {
        this.listener = listener;
        engine = new SenseVoiceEngine(modelDirectory, 2);
        Vad created;
        try { created = new Vad(VadModelConfig.builder().setSampleRate(16000).setNumThreads(1).setProvider("cpu").setDebug(false)
            .setSileroVadModelConfig(SileroVadModelConfig.builder()
                .setModel(new File(modelDirectory,"silero_vad.onnx").getAbsolutePath())
                .setThreshold(0.45f).setMinSpeechDuration(0.20f).setMinSilenceDuration(0.55f)
                .setMaxSpeechDuration(12.0f).setWindowSize(512).build()).build());
        } catch (RuntimeException | LinkageError error) { engine.close(); throw error; }
        vad = created;
        worker = new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<Runnable>(12), r -> {
            Thread thread = new Thread(r,"sensevoice-decode"); thread.setPriority(Thread.NORM_PRIORITY-1); return thread;
        }, (r,executor) -> {
            if (closed || executor.isShutdown()) return;
            // Preserve completion barriers, prefer recent speech if a phone cannot keep up.
            for (Runnable queued : executor.getQueue()) {
                if (queued instanceof DecodeTask && executor.getQueue().remove(queued)) {
                    listener.error("识别速度暂时跟不上，正在优先处理最近的讲话");
                    executor.execute(r); return;
                }
            }
            listener.error("识别任务繁忙，请稍后重试");
        });
    }
    public void accept(short[] samples, int count) {
        if (closed) return;
        float[] block = new float[count];
        for (int i=0; i<count; i++) block[i] = samples[i]/32768.0f;
        acceptedSamples += count; vad.acceptWaveform(block); drain();
    }
    private void drain() {
        while (!vad.empty()) {
            SpeechSegment segment = vad.front();
            final long end = epochStart + segment.getStart() + segment.getSamples().length;
            float[] audio = segment.getSamples(); vad.pop();
            worker.execute(new DecodeTask(audio,end));
        }
    }
    /** Must be called by the capture thread. Flushes pending speech before the answer callback. */
    public void flush(Runnable completed) {
        if (closed) return;
        vad.flush(); drain(); vad.reset(); epochStart = acceptedSamples;
        worker.execute(() -> { if (!closed) completed.run(); });
    }
    public boolean speechDetected() { return !closed && vad.isSpeechDetected(); }
    private final class DecodeTask implements Runnable {
        final float[] samples; final long end;
        DecodeTask(float[] samples,long end) { this.samples=samples; this.end=end; }
        @Override public void run() {
            if (closed) return;
            try {
                String text = engine.transcribe(samples);
                if (!closed && !text.isEmpty()) listener.text(text,end);
            } catch (Exception e) { if (!closed) listener.error("这一段识别失败，可以回听后重新识别"); }
        }
    }
    @Override public void close() {
        closed=true; worker.shutdownNow(); vad.release(); engine.close();
    }
}
