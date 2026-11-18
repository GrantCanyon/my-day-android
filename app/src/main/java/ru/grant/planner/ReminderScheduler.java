package ru.grant.planner;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Inexact local alarms: no network, exact-alarm access or background service. */
public final class ReminderScheduler {
    static final String PREFS = "planner_reminders";
    static final String CHANNEL = "planned_tasks";
    static final ZoneId ZONE = ZoneId.of("Europe/Moscow");
    private ReminderScheduler() { }

    public static boolean enabled(Context context) { return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false); }

    private static PendingIntent alarm(Context context, String id, int flags) {
        Intent intent = new Intent(context, ReminderReceiver.class).setData(Uri.parse("myday://reminder/" + Uri.encode(id))).putExtra("taskId", id);
        return PendingIntent.getBroadcast(context, 0, intent, flags | PendingIntent.FLAG_IMMUTABLE);
    }

    static long startMillis(TaskStore.Task task, ZoneId zone) {
        if (task.startTime == null || task.startTime.isEmpty()) return -1;
        try {
            LocalDate date = LocalDate.parse(task.date); LocalTime start = LocalTime.parse(task.startTime);
            // An early-morning +1 entry belongs to the following calendar date.
            if (task.nextDay && start.getHour() < 6) date = date.plusDays(1);
            return date.atTime(start).atZone(zone).toInstant().toEpochMilli();
        } catch (Exception ignored) { return -1; }
    }

    public static synchronized void reschedule(Context context, TaskStore store) {
        if (store == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        AlarmManager manager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (manager == null) return;
        for (String id : prefs.getStringSet("scheduled", new HashSet<>())) {
            PendingIntent old = alarm(context, id, PendingIntent.FLAG_NO_CREATE);
            if (old != null) { manager.cancel(old); old.cancel(); }
        }
        prefs.edit().remove("scheduled").apply();
        if (!enabled(context)) return;
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        NotificationManager notifications = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (notifications == null || !notifications.areNotificationsEnabled()) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Начало запланированных дел", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Начало дела в часовом поясе расписания"); notifications.createNotificationChannel(channel);
        ZoneId zone;
        try { zone = ZoneId.of(store.getTimeZone()); } catch (Exception ignored) { zone = ZONE; }
        final ZoneId alarmZone = zone;
        long now = System.currentTimeMillis();
        List<TaskStore.Task> upcoming = new ArrayList<>();
        for (TaskStore.Task task : store.getAll()) if (task.progress < 1 && startMillis(task, alarmZone) > now) upcoming.add(task);
        upcoming.sort(Comparator.comparingLong(task -> startMillis(task, alarmZone)));
        Set<String> ids = new HashSet<>();
        // Bound the number of system alarms; opening the app and each fired alarm refill the window.
        for (int i = 0; i < Math.min(upcoming.size(), 180); i++) {
            TaskStore.Task task = upcoming.get(i);
            try { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, startMillis(task, alarmZone), alarm(context, task.id, PendingIntent.FLAG_UPDATE_CURRENT)); ids.add(task.id); }
            catch (SecurityException ignored) { break; }
        }
        prefs.edit().putStringSet("scheduled", ids).apply();
    }
}
