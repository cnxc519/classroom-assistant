package cn.classroom.assistant;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ListeningService extends Service {
    public static final String START = "listen.start", STOP = "listen.stop", ASK = "listen.ask", CANCEL = "listen.cancel";
    private final AtomicBoolean active = new AtomicBoolean();
    private volatile AudioRecord recorder;
    private volatile AnswerClient client;
    private PowerManager.WakeLock wakeLock;
    private Thread recordThread;
    private final Object answerLock = new Object();
    private int answerGeneration;
    private final AtomicInteger flushGeneration = new AtomicInteger(-1);
    @Override public IBinder onBind(Intent intent) { return null; }
    @Override public void onCreate() {
        super.onCreate();
        NotificationChannel channel = new NotificationChannel("listening", "听课录音", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("显示录音状态和停止入口"); channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        String action = intent.getAction();
        if (START.equals(action)) {
            if (active.get()) return START_NOT_STICKY;
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                Session.status = "需要麦克风权限才能听课"; Session.changed(); stopSelf(); return START_NOT_STICKY;
            }
            if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(11, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(11, notification());
            if (recordThread != null && recordThread.isAlive()) { Session.status = "正在结束上一段录音，请稍后再开始"; Session.changed(); return START_NOT_STICKY; }
            synchronized (Session.captureLock) { active.set(true); Session.captureOwner = this; Session.buffer.clear(); Session.listening = true; }
            Session.answer = ""; Session.question = ""; Session.startedAt = 0; Session.status = "正在准备中文识别模型…"; Session.changed();
            wakeLock = ((PowerManager) getSystemService(POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Classroom:Listening");
            wakeLock.acquire();
            recordThread = new Thread(this::listen, "classroom-audio"); recordThread.start();
        } else if (STOP.equals(action)) {
            endSession(true); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
        } else if (CANCEL.equals(action)) {
            cancelAnswer(); Session.status = Session.listening ? "正在听课 · 已取消回答" : "已取消回答"; Session.changed();
            if (!active.get()) stopSelf();
        } else if (ASK.equals(action)) {
            String edited = intent.getStringExtra("text");
            if (edited != null) ask(edited.trim());
            else if (active.get()) {
                synchronized (answerLock) {
                    if (!Session.answering && !Session.preparing) {
                        Session.preparing = true; flushGeneration.set(++answerGeneration);
                        Session.status = "正在识别老师刚才的问题…"; Session.changed();
                    }
                }
            } else ask(Session.buffer.text(SystemClock.elapsedRealtime(), AppSettings.seconds(this) * 1000L));
        }
        return START_NOT_STICKY;
    }
    private Notification notification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent view = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent ask = new Intent(this, NotificationAnswerActivity.class);
        PendingIntent answer = PendingIntent.getActivity(this, 1, ask, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 2, new Intent(this, ListeningService.class).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, "listening").setSmallIcon(cn.classroom.assistant.R.drawable.ic_app)
            .setContentTitle("课堂助手 · 正在听课").setContentText("最近三分钟保存在手机，点击打开")
            .setContentIntent(view).setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(new Notification.Action.Builder(null, "回答刚才的问题", answer).build())
            .addAction(new Notification.Action.Builder(null, "停止听课", stop).build()).build();
    }
    private void listen() {
        AudioRecord audio = null; SpeechPipeline pipeline = null;
        try {
            File modelDir = ModelFiles.prepare(this, new ModelFiles.Progress() {
                public void update(int percent) { Session.status = "首次准备中文模型… " + percent + "%"; Session.changed(); }
                public boolean cancelled() { return !active.get(); }
            });
            if (!active.get()) return;
            Session.status = "正在加载 SenseVoice 中文识别…"; Session.changed();
            pipeline = new SpeechPipeline(modelDir, new SpeechPipeline.Listener() {
                public void text(String text, long endSample) {
                    synchronized (Session.captureLock) {
                        if (active.get() && Session.captureOwner == ListeningService.this)
                            Session.buffer.finish(text, Session.startedAt + endSample * 1000L / 16000);
                    }
                }
                public void error(String message) { if (active.get()) { Session.recognitionStatus = message; Session.changed(); } }
            });
            int minimum = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) throw new Exception("手机不支持当前录音格式");
            audio = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum * 4, 32000));
            recorder = audio;
            if (audio.getState() != AudioRecord.STATE_INITIALIZED) throw new Exception("无法打开麦克风，可能正被其他应用占用");
            boolean preferPhone = AppSettings.phoneMic(this);
            if (preferPhone) {
                AudioManager manager = (AudioManager)getSystemService(AUDIO_SERVICE);
                for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                    if (device.getType() == AudioDeviceInfo.TYPE_BUILTIN_MIC) { audio.setPreferredDevice(device); break; }
                }
            }
            if (!active.get()) return;
            audio.startRecording();
            if (audio.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) throw new Exception("录音未能启动");
            Session.startedAt = SystemClock.elapsedRealtime(); Session.status = "正在听课 · 可以锁屏"; Session.changed();
            Session.recognitionStatus = "SenseVoice 中文识别 · 停顿后显示文字";
            short[] block = new short[1600]; long lastRouteUpdate=0;
            while (active.get()) {
                int count = audio.read(block, 0, block.length, AudioRecord.READ_BLOCKING);
                if (!active.get()) break;
                if (count <= 0) throw new Exception("麦克风录音中断，请重新开始听课");
                synchronized (Session.captureLock) {
                if (!active.get() || Session.captureOwner != this) break;
                Session.buffer.append(block, count);
                long square = 0;
                for (int i = 0; i < count; i++) square += (long) block[i] * block[i];
                Session.level = Math.min(100, (int) (Math.sqrt(square / (double) count) / 120));
                Session.volumeDb = (int)Math.max(-96, 20*Math.log10(Math.max(1,Math.sqrt(square/(double)count))/32768.0));
                }
                // VAD is cheap. Recognition runs elsewhere and cannot block microphone reads.
                pipeline.accept(block,count);
                long now=SystemClock.elapsedRealtime();
                if (now-lastRouteUpdate>1000) { updateInputDevice(audio,preferPhone); lastRouteUpdate=now; }
                int pending=flushGeneration.getAndSet(-1);
                if (pending>=0) pipeline.flush(() -> {
                    synchronized (answerLock) {
                        if (!active.get() || pending!=answerGeneration) return;
                        Session.preparing=false;
                        ask(Session.buffer.text(SystemClock.elapsedRealtime(),AppSettings.seconds(this)*1000L));
                    }
                });
            }
        } catch (Exception | LinkageError error) {
            if (active.get()) {
                android.util.Log.e("ClassroomAudio", "Local audio/model error", error);
                Session.status = error.getMessage() != null && error.getMessage().startsWith("手机") ? error.getMessage() :
                    "录音或本地识别中断，请停止后重试，并检查麦克风权限和可用空间";
                Session.changed(); active.set(false);
                new android.os.Handler(getMainLooper()).post(() -> { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); });
            }
        } finally {
            if (audio != null) { try { audio.stop(); } catch (Exception ignored) {} audio.release(); }
            recorder = null;
            if (pipeline != null) pipeline.close();
            synchronized (Session.captureLock) {
                if (Session.captureOwner == this) { Session.level = 0; Session.listening = false; Session.buffer.clear(); }
            }
            releaseWakeLock();
        }
    }
    private void updateInputDevice(AudioRecord audio, boolean preferPhone) {
        AudioDeviceInfo route=audio.getRoutedDevice();
        if (route==null) { Session.inputDevice="录音来源：等待系统确认"; return; }
        int type=route.getType(); String label;
        if (type==AudioDeviceInfo.TYPE_BUILTIN_MIC) label="手机内置麦克风";
        else if (type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || type==AudioDeviceInfo.TYPE_BLE_HEADSET) label="蓝牙耳机麦克风";
        else if (type==AudioDeviceInfo.TYPE_WIRED_HEADSET || type==AudioDeviceInfo.TYPE_USB_HEADSET || type==AudioDeviceInfo.TYPE_USB_DEVICE) label="耳机 / USB 麦克风";
        else label="系统录音设备";
        Session.inputDevice="实际录音："+label + (preferPhone && type!=AudioDeviceInfo.TYPE_BUILTIN_MIC ? "（未切换成功，请断开耳机再试）" : "");
    }
    private void ask(String transcript) {
        synchronized (answerLock) {
            if (Session.answering || Session.preparing) return;
            if (transcript.isEmpty()) {
                Session.status = "还没有识别到文字，可以继续听课或手动输入问题"; Session.changed(); if (!active.get()) stopSelf(); return;
            }
            Session.answering = true; Session.question = transcript; Session.answer = "";
            Session.status = "正在回答…"; Session.changed();
            final int generation = ++answerGeneration;
            final AnswerClient requestClient = new AnswerClient(this); client = requestClient;
            new Thread(() -> {
                try {
                    String result = requestClient.answer(transcript, content -> {
                        synchronized (answerLock) { if (generation == answerGeneration) { Session.answer = content; Session.changed(); } }
                    });
                    synchronized (answerLock) {
                        if (generation == answerGeneration) { Session.answer = result; Session.status = "回答完成"; Session.changed(); }
                    }
                } catch (Exception e) {
                    synchronized (answerLock) {
                        if (generation == answerGeneration) {
                            String message = e.getMessage();
                            Session.status = message != null && message.length()<120 && !message.matches("(?s).*(https?://|\\.java:|Exception|SSL|socket).*") ? message : "连接失败，请检查网络后重试";
                            Session.changed();
                        }
                    }
                } finally {
                    synchronized (answerLock) { if (generation == answerGeneration) { Session.answering = false; client = null; Session.changed(); } }
                    if (!active.get()) new android.os.Handler(getMainLooper()).post(this::stopSelf);
                }
            }, "classroom-answer").start();
        }
    }
    private void cancelAnswer() {
        synchronized (answerLock) {
            answerGeneration++; flushGeneration.set(-1); AnswerClient c = client; client = null; if (c != null) c.cancel(); Session.answering = false; Session.preparing = false;
        }
    }
    private void endSession(boolean explicit) {
        active.set(false); cancelAnswer();
        AudioRecord audio = recorder; if (audio != null) { try { audio.stop(); } catch (Exception ignored) {} }
        synchronized (Session.captureLock) {
            if (Session.captureOwner == this) {
                Session.listening = false; Session.buffer.clear(); Session.startedAt = 0; Session.level = 0;
                Session.inputDevice="录音来源：开始后显示实际设备"; Session.volumeDb=-96;
                Session.captureOwner = null;
            }
        }
        if (explicit) { Session.question = ""; Session.status = "已停止听课 · 最近录音和转写已清除"; Session.changed(); }
        File replay = new File(getCacheDir(), "replay.wav"); if (replay.isFile()) replay.delete();
    }
    private void releaseWakeLock() { PowerManager.WakeLock lock = wakeLock; if (lock != null && lock.isHeld()) lock.release(); }
    @Override public void onDestroy() {
        endSession(false); releaseWakeLock(); super.onDestroy();
    }
}
