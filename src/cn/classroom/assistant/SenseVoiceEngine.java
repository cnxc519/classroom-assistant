package cn.classroom.assistant;

import java.io.File;
import com.k2fsa.sherpa.onnx.*;

/** Shared by desktop comparison, Android live recognition, and recorded-file tests. */
public final class SenseVoiceEngine implements AutoCloseable {
    private final OfflineRecognizer recognizer;
    public SenseVoiceEngine(File directory, int threads) {
        OfflineSenseVoiceModelConfig voice = OfflineSenseVoiceModelConfig.builder()
            .setModel(new File(directory, "model.int8.onnx").getAbsolutePath())
            .setLanguage("zh").setInverseTextNormalization(true).build();
        OfflineModelConfig model = OfflineModelConfig.builder().setSenseVoice(voice)
            .setTokens(new File(directory, "tokens.txt").getAbsolutePath())
            .setNumThreads(threads).setProvider("cpu").setDebug(false).build();
        recognizer = new OfflineRecognizer(OfflineRecognizerConfig.builder().setOfflineModelConfig(model)
            .setFeatureConfig(FeatureConfig.builder().setSampleRate(16000).setFeatureDim(80).build()).build());
    }
    public synchronized String transcribe(float[] audio) {
        if (audio.length < 1600) return "";
        OfflineStream stream = recognizer.createStream();
        try {
            stream.acceptWaveform(normalize(audio), 16000);
            recognizer.decode(stream);
            return recognizer.getResult(stream).getText().replaceAll("<\\|[^>]*\\|>", "").trim();
        } finally { stream.release(); }
    }
    public static float[] normalize(float[] input) {
        float[] output = input.clone();
        double mean = 0, energy = 0; float peak = 0;
        for (float v : output) mean += v;
        mean /= Math.max(1, output.length);
        for (int i=0; i<output.length; i++) {
            output[i] -= (float) mean; energy += output[i] * output[i]; peak = Math.max(peak, Math.abs(output[i]));
        }
        // Do not amplify silence; cap gain to avoid amplifying room noise excessively.
        double rms = Math.sqrt(energy / Math.max(1, output.length));
        float gain = rms >= 0.002 ? (float) Math.min(4.0, Math.min(0.10 / rms, 0.95 / Math.max(0.001, peak))) : 1.0f;
        gain = Math.max(0.25f, gain);
        for (int i=0; i<output.length; i++) output[i] = Math.max(-1, Math.min(1, output[i] * gain));
        return output;
    }
    @Override public synchronized void close() { recognizer.release(); }
}
