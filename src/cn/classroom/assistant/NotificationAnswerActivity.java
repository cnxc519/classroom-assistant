package cn.classroom.assistant;

/** Only the app-owned notification PendingIntent can open this entry point. */
public final class NotificationAnswerActivity extends MainActivity {
    @Override public void onCreate(android.os.Bundle state) {
        getIntent().setAction("internal.notification.answer");
        super.onCreate(state);
    }
}
