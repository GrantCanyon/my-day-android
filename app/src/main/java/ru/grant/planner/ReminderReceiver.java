package ru.grant.planner;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

public class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        PendingResult result = goAsync();
        new Thread(() -> {
            try {
                if (!ReminderScheduler.enabled(context)) return;
                if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
                String id = intent.getStringExtra("taskId"); TaskStore store = new TaskStore(context.getApplicationContext());
                TaskStore.Task found = null;
                for (TaskStore.Task task : store.getAll()) if (task.id.equals(id)) { found = task; break; }
                if (found != null && found.progress < 1) {
                    long start = ReminderScheduler.startMillis(found, java.time.ZoneId.of(store.getTimeZone())), now = System.currentTimeMillis();
                    // Ignore alarms invalidated by a moved task and stale alarms from an old day.
                    if (start > 0 && start <= now + 60_000 && now - start < 12 * 60 * 60 * 1000L) {
                        Intent open = new Intent(context, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .setData(Uri.parse("myday://day/" + found.date + "/" + Uri.encode(found.id))).putExtra("date", found.date);
                        PendingIntent tap = PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                        Notification notification = new Notification.Builder(context, ReminderScheduler.CHANNEL).setSmallIcon(R.drawable.ic_calendar)
                            .setContentTitle(found.title).setContentText(found.startTime + " МСК · " + (found.notes == null || found.notes.isEmpty() ? "Открыть план дня" : found.notes))
                            .setContentIntent(tap).setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER).build();
                        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                        if (manager != null) manager.notify(found.id, 1, notification);
                    }
                }
                ReminderScheduler.reschedule(context, store);
            } catch (Exception ignored) {
                // A reminder must never crash the app or damage the schedule.
            } finally { result.finish(); }
        }, "planner-reminder").start();
    }
}
