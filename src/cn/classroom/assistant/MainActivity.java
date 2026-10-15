package cn.classroom.assistant;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaPlayer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileOutputStream;

public class MainActivity extends Activity {
    private static final int BG = Color.rgb(17,24,21), CARD = Color.rgb(28,38,32), TEXT = Color.rgb(237,244,239),
        MUTED = Color.rgb(159,180,166), GREEN = Color.rgb(156,219,196);
    private final Handler handler = new Handler();
    private TextView status, live, timer, inputInfo, engineInfo, fallbackAnswer;
    private AnswerView answer;
    private Button start, ask, replay, retry;
    private ProgressBar meter;
    private MediaPlayer player;
    private int seenRevision = -1;
    private boolean foreground;
    private final Runnable refresh = new Runnable() {
        @Override public void run() { update(); if (foreground) handler.postDelayed(this, 400); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        AppSettings.migrate(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(18), dp(20), dp(12));
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) { android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); top = bars.top; bottom = bars.bottom; }
            else { top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom(); }
            root.setPadding(dp(20), top + dp(12), dp(20), bottom + dp(12)); return insets;
        });
        setContentView(root);
        LinearLayout header = row();
        TextView name = text("课堂助手", 26, TEXT); name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        header.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        Button settings = button("设置", false); header.addView(settings); settings.setOnClickListener(v -> settings()); root.addView(header);
        root.addView(text("先听清问题，再组织回答", 13, MUTED));
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(false);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout listening = card(content);
        timer = text("尚未开始", 13, GREEN); listening.addView(timer);
        status = text(Session.status, 17, TEXT); status.setPadding(0, dp(8), 0, dp(12)); listening.addView(status);
        meter = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); meter.setMax(100); listening.addView(meter, new LinearLayout.LayoutParams(-1, dp(4)));
        inputInfo=text(Session.inputDevice,12,GREEN); listening.addView(inputInfo);
        listening.addView(text("锁屏继续听课 · 最近三分钟循环保留", 12, MUTED));
        start = button("开始听课", false); listening.addView(start); start.setOnClickListener(v -> toggleListening());
        LinearLayout source = card(content);
        source.addView(text("最近听到的内容", 14, GREEN));
        live = text("开始后，这里会逐步出现识别文字。老师问完后再点回答。", 16, TEXT);
        live.setMaxLines(6); live.setEllipsize(android.text.TextUtils.TruncateAt.END);
        live.setTextIsSelectable(true); live.setPadding(0, dp(12), 0, dp(12)); source.addView(live);
        engineInfo=text(Session.recognitionStatus,12,MUTED); source.addView(engineInfo);
        LinearLayout sourceActions = row();
        replay = button("回听最近30秒", false); sourceActions.addView(replay, new LinearLayout.LayoutParams(0, -2, 1));
        replay.setOnClickListener(v -> replay());
        Button edit = button("修改 / 输入", false); sourceActions.addView(edit, new LinearLayout.LayoutParams(0, -2, 1));
        edit.setOnClickListener(v -> editQuestion()); source.addView(sourceActions);
        LinearLayout result = card(content); result.addView(text("回答", 14, GREEN));
        try { answer=new AnswerView(this);result.addView(answer); }
        catch(Exception | LinkageError unavailable){fallbackAnswer=text("点击回答后，答案会在这里显示。",19,TEXT);fallbackAnswer.setTextIsSelectable(true);result.addView(fallbackAnswer);}
        Button copy=button("复制答案",false);result.addView(copy);
        copy.setOnClickListener(v->{if(!Session.answer.isEmpty()){((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("课堂回答",Session.answer));toast("已复制");}});
        retry = button("重试上一个问题", false); result.addView(retry);
        retry.setOnClickListener(v -> { if (!Session.question.isEmpty()) requestAnswer(Session.question); });
        content.addView(text("本地识别；点回答时，最近的识别文字经问答服务交给模型。录音不上传。",12,MUTED));
        ask = button("回答刚才的问题", true); root.addView(ask, new LinearLayout.LayoutParams(-1, dp(64)));
        ask.setOnClickListener(v -> {
            if (Session.answering || Session.preparing) send(ListeningService.CANCEL, null);
            else requestAnswer(null);
        });
        root.addView(text("使用前，请确认课堂允许录音及使用此类辅助工具。", 11, MUTED));
        handleIntent(getIntent());
        if(!AppSettings.introductionSeen(this))handler.post(()->showGuide(true));
    }
    private void handleIntent(Intent intent) {
        if (this instanceof NotificationAnswerActivity && intent != null && "internal.notification.answer".equals(intent.getAction())) {
            intent.setAction(null); handler.post(() -> requestAnswer(null));
        }
    }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); handleIntent(intent); }
    @Override protected void onResume() { super.onResume(); foreground = true; handler.removeCallbacks(refresh); handler.post(refresh); }
    @Override protected void onPause() { foreground = false; handler.removeCallbacks(refresh); stopPlayback(); super.onPause(); }
    private void update() {
        long now = SystemClock.elapsedRealtime();
        long seconds = Session.startedAt == 0 ? 0 : (now - Session.startedAt) / 1000;
        setText(timer, Session.listening ? (Session.startedAt == 0 ? "正在准备" : String.format(java.util.Locale.CHINA, "● 正在听课   %02d:%02d", seconds / 60, seconds % 60)) : "● 未在录音");
        setText(start, Session.listening ? "停止听课" : "开始听课");
        meter.setProgress(Session.level);
        replay.setEnabled(Session.buffer.sampleCount() >= 16000);
        retry.setEnabled(!Session.answering && !Session.preparing && !Session.question.isEmpty());
        setText(ask, Session.preparing ? "取消本次识别" : (Session.answering ? "取消本次回答" : "回答刚才的问题"));
        setText(inputInfo,Session.inputDevice + (Session.listening && Session.startedAt>0 ? "  ·  "+Session.volumeDb+" dB" : ""));
        setText(engineInfo,Session.recognitionStatus);
        String transcript = Session.buffer.text(now, AppSettings.seconds(this) * 1000L);
        if (!transcript.isEmpty()) setText(live, transcript);
        else setText(live, Session.listening ? "正在听… 讲话停顿后显示文字；点回答会先识别尚未完成的语音。" : "开始后，在讲话停顿时显示识别文字。也可以手动输入问题。");
        if (seenRevision != Session.revision) {
            seenRevision = Session.revision; status.setText(Session.status);
            String value=Session.answer.isEmpty()?(Session.answering?"正在请求回答…":"点击回答后，答案会在这里逐字显示。") : Session.answer;
            if(answer!=null)answer.setAnswer(value);else fallbackAnswer.setText(value);
        }
    }
    private void toggleListening() {
        if (Session.listening) { stopPlayback(); send(ListeningService.STOP, null); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 101); return;
        }
        startListening();
    }
    private void startListening() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 102);
        startForegroundService(new Intent(this, ListeningService.class).setAction(ListeningService.START));
    }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request == 101) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) startListening();
            else toast("需要允许麦克风权限才能听课；仍可手动输入问题");
        }
    }
    private void send(String action, String text) {
        Intent intent = new Intent(this, ListeningService.class).setAction(action);
        if (text != null) intent.putExtra("text", text); startService(intent);
    }
    private void requestAnswer(String text) {
        stopPlayback(); send(ListeningService.ASK, text);
    }
    private void settings() {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(24), dp(8), dp(24), dp(8));
        form.addView(text("已连接共享问答服务，无需填写密钥。",14,GREEN));
        form.addView(text("取最近多少秒（30～180）", 14, TEXT));
        EditText seconds = new EditText(this); seconds.setInputType(InputType.TYPE_CLASS_NUMBER); seconds.setText(String.valueOf(AppSettings.seconds(this))); form.addView(seconds);
        android.widget.CheckBox phoneMic = new android.widget.CheckBox(this); phoneMic.setText("优先手机内置麦克风（推荐）");
        phoneMic.setTextColor(TEXT); phoneMic.setChecked(AppSettings.phoneMic(this)); form.addView(phoneMic);
        form.addView(text("麦克风设置重新开始听课后生效。共享服务有每日额度，繁忙时请稍后重试。",12,MUTED));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("设置").setView(form).setPositiveButton("保存", null)
            .setNegativeButton("取消", null).setNeutralButton("使用说明",(d,which)->showGuide(false)).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                int count = Integer.parseInt(seconds.getText().toString());
                if (count < 30 || count > 180) { seconds.setError("请输入30～180"); return; }
                AppSettings.save(this,count,phoneMic.isChecked());dialog.dismiss();toast("已保存");
            } catch (NumberFormatException e) { seconds.setError("请输入数字"); }
            catch (Exception e) { toast("无法保存设置，请重试"); }
        })); dialog.show();
    }
    private void showGuide(boolean first){
        if(first)AppSettings.markIntroductionSeen(this);
        String guide="1. 上课前点「开始听课」，等待准备完成后，可以切到后台或锁屏继续使用。\n\n"
            +"2. 老师说完问题，就点「回答刚才的问题」，不用等识别文字显示出来。App 会先补齐最后一段识别，再请求答案。\n\n"
            +"3. 转写可能稍有延迟，少量同音字错误可能由上下文纠正。涉及数字、公式和关键条件时，请核对问题与答案；没听清可以回听，或点「修改 / 输入」。\n\n"
            +"4. 录音在手机本地识别；点击回答才发送最近的识别文字。录音不上传，也不保存整节课。下课后记得停止听课。\n\n"
            +"建议先断开耳机测试，确认「实际录音」显示手机内置麦克风。若系统清理后台，请在手机设置中允许后台运行。\n\n"
            +"使用前，请确认课堂允许录音及此类辅助工具。本说明以后可以在设置中再次查看。";
        TextView words=text(guide,15,TEXT);words.setPadding(dp(24),dp(12),dp(24),dp(12));
        ScrollView scroll=new ScrollView(this);scroll.addView(words);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle(first?"开始使用课堂助手":"使用说明").setView(scroll).setPositiveButton("知道了",null).create();
        dialog.show();
    }
    private void editQuestion() {
        EditText input = new EditText(this); input.setMinLines(4); input.setMaxLines(10); input.setGravity(Gravity.TOP); input.setPadding(dp(20), dp(16), dp(20), dp(16));
        input.setText(Session.buffer.text(SystemClock.elapsedRealtime(), AppSettings.seconds(this) * 1000L));
        input.setHint("修改识别文字，或直接输入问题");
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("检查问题文字").setView(input).setPositiveButton("回答", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) { input.setError("先输入问题"); return; }
            if (text.length() > 8000) { input.setError("文字过长，请保留最近的问题"); return; }
            if (Session.answering || Session.preparing) { toast("请先取消当前识别或回答"); return; }
            dialog.dismiss(); requestAnswer(text);
        })); dialog.show();
    }
    private void replay() {
        stopPlayback(); short[] samples = Session.buffer.audio(30 * 16000);
        if (samples.length == 0) { toast("还没有录音"); return; }
        File file = new File(getCacheDir(), "replay.wav");
        try {
            try (java.io.BufferedOutputStream out = new java.io.BufferedOutputStream(new FileOutputStream(file))) {
                out.write("RIFF".getBytes("US-ASCII")); write32(out, 36 + samples.length * 2); out.write("WAVEfmt ".getBytes("US-ASCII"));
                write32(out, 16); write16(out, 1); write16(out, 1); write32(out, 16000); write32(out, 32000); write16(out, 2); write16(out, 16);
                out.write("data".getBytes("US-ASCII")); write32(out, samples.length * 2);
                for (short sample : samples) write16(out, sample);
            }
            player = new MediaPlayer(); player.setDataSource(file.getAbsolutePath());
            player.setOnCompletionListener(p -> stopPlayback()); player.prepare(); player.start();
            toast("正在回听，避免靠近麦克风造成回声");
        } catch (Exception e) { stopPlayback(); toast("无法回听，请重试"); }
    }
    private void stopPlayback() {
        if (player != null) { player.release(); player = null; }
        File file = new File(getCacheDir(), "replay.wav"); if (file.isFile()) file.delete();
    }
    private void write16(java.io.OutputStream out, int n) throws Exception { out.write(n & 255); out.write((n >>> 8) & 255); }
    private void write32(java.io.OutputStream out, int n) throws Exception { write16(out, n); write16(out, n >>> 16); }
    private LinearLayout row() { LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); return row; }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(background(CARD, 20)); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = dp(16); parent.addView(card, lp); return card;
    }
    private TextView text(String value, int sp, int color) { TextView v = new TextView(this); v.setText(value); v.setTextSize(sp); v.setTextColor(color); v.setLineSpacing(dp(3), 1); return v; }
    private void setText(TextView view, String value) { if (!view.getText().toString().equals(value)) view.setText(value); }
    private Button button(String title, boolean primary) {
        Button button = new Button(this); button.setText(title); button.setAllCaps(false); button.setTextSize(primary ? 19 : 14);
        button.setTextColor(primary ? BG : GREEN); button.setBackground(background(primary ? GREEN : Color.TRANSPARENT, 18)); button.setMinHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8)); return button;
    }
    private GradientDrawable background(int color, int radius) { GradientDrawable drawable = new GradientDrawable(); drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable; }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    @Override protected void onDestroy(){if(answer!=null){answer.stopLoading();answer.destroy();answer=null;}super.onDestroy();}
}
