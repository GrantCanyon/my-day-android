package ru.grant.planner;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Single-activity Java interface. TaskStore owns persistence and file interchange. */
public class MainActivity extends Activity {
    private static final int EXPORT_MD = 101, EXPORT_JSON = 102, IMPORT_JSON = 103, NOTIFICATIONS = 104;
    private static final int INK = 0xff203730, MUTED = 0xff69796f, ACCENT = 0xff23695d;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Locale russian = new Locale("ru", "RU");
    private TaskStore store;
    private SharedPreferences preferences;
    private LocalDate day = LocalDate.now(ReminderScheduler.ZONE);
    private LinearLayout tasks;
    private TextView dateText, dateDetail, daySummary, sectionTitle, count;
    private ScrollView taskScroll;
    private String renderedDate = "";
    private boolean renderedListMode;
    private ProgressBar progress;
    private CheckBox incomplete;
    private boolean listMode;
    private String pendingExport = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("planner_ui", MODE_PRIVATE);
        if (state != null) {
            try { day = LocalDate.parse(state.getString("day", day.toString())); } catch (Exception ignored) { }
            pendingExport = state.getString("pendingExport", "");
        }
        acceptIntent(getIntent());
        setContentView(R.layout.activity_main);
        View root = findViewById(R.id.root);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(), insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        root.requestApplyInsets();
        tasks = findViewById(R.id.tasks);
        dateText = findViewById(R.id.date);
        dateDetail = findViewById(R.id.date_detail);
        daySummary = findViewById(R.id.day_summary);
        sectionTitle = findViewById(R.id.section_title);
        taskScroll = findViewById(R.id.task_scroll);
        count = findViewById(R.id.count);
        progress = findViewById(R.id.day_progress);
        incomplete = findViewById(R.id.incomplete);
        incomplete.setChecked(preferences.getBoolean("incomplete", false));
        listMode = preferences.getBoolean("listMode", false);
        RadioGroup mode = findViewById(R.id.mode);
        mode.check(listMode ? R.id.list : R.id.timeline);
        mode.setOnCheckedChangeListener((group, id) -> {
            listMode = id == R.id.list;
            preferences.edit().putBoolean("listMode", listMode).apply();
            render();
        });
        incomplete.setOnCheckedChangeListener((v, checked) -> {
            preferences.edit().putBoolean("incomplete", checked).apply(); render();
        });
        findViewById(R.id.previous).setOnClickListener(v -> { day = day.minusDays(1); render(); });
        findViewById(R.id.next).setOnClickListener(v -> { day = day.plusDays(1); render(); });
        findViewById(R.id.today).setOnClickListener(v -> { day = LocalDate.now(zone()); render(); });
        findViewById(R.id.date_picker).setOnClickListener(v -> chooseDate(day, selected -> { day = selected; render(); }));
        findViewById(R.id.menu).setOnClickListener(this::openMenu);
        findViewById(R.id.add).setOnClickListener(v -> { if (store != null) edit(null); });
        TextView loading = text("Открываем расписание…", 16, MUTED);
        loading.setPadding(dp(12), dp(36), dp(12), dp(36)); tasks.addView(loading);
        worker.execute(() -> {
            try {
                TaskStore loaded = new TaskStore(getApplicationContext());
                ui(() -> { store = loaded; render(); });
                ReminderScheduler.reschedule(getApplicationContext(), loaded);
            } catch (Exception e) { ui(() -> error("Не удалось открыть расписание", e)); }
        });
    }

    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); acceptIntent(intent); render(); }
    private void acceptIntent(Intent intent) {
        if (intent != null && intent.hasExtra("date")) {
            try { day = LocalDate.parse(intent.getStringExtra("date")); } catch (Exception ignored) { }
        }
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putString("day", day.toString()); state.putString("pendingExport", pendingExport); super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
    private void ui(Runnable action) { runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) action.run(); }); }


    private void render() {
        if (tasks == null || store == null) return;
        boolean resetScroll = !day.toString().equals(renderedDate) || renderedListMode != listMode;
        renderedDate = day.toString(); renderedListMode = listMode;
        dateText.setText(day.format(DateTimeFormatter.ofPattern("d MMMM", russian)));
        findViewById(R.id.date_picker).setContentDescription("Выбрать дату: " + day.format(DateTimeFormatter.ofPattern("d MMMM yyyy", russian)));
        String weekday = day.format(DateTimeFormatter.ofPattern("EEEE", russian));
        dateDetail.setText(Character.toUpperCase(weekday.charAt(0)) + weekday.substring(1) + " · " + day.getYear() + " · " + (zone().equals(ReminderScheduler.ZONE) ? "МСК" : zone().getId()));
        List<TaskStore.Task> all = store.listForDate(day.toString());
        int completed = 0; double total = 0;
        for (TaskStore.Task task : all) { total += task.progress; if (task.progress >= 1) completed++; }
        int percent = all.isEmpty() ? 0 : (int)Math.round(100 * total / all.size());
        count.setText(completed + " / " + all.size() + " · " + percent + "%"); progress.setProgress(percent);
        count.setContentDescription("Завершено " + completed + " из " + all.size() + " дел. Выполнение дня " + percent + " процентов");
        daySummary.setText(all.isEmpty() ? "Свободный день" : taskCount(all.size()) + " в плане");
        sectionTitle.setText(listMode ? "Мой порядок" : "Временная шкала");
        List<TaskStore.Task> displayed = new ArrayList<>(all);
        if (!listMode) displayed.sort(java.util.Comparator.comparingLong((TaskStore.Task task) -> {
            long start = ReminderScheduler.startMillis(task, zone()); return start < 0 ? Long.MAX_VALUE : start;
        }).thenComparingInt(task -> task.position));
        tasks.removeAllViews();
        int shown = 0; boolean untimedHeading = false;
        for (TaskStore.Task task : displayed) {
            if (incomplete.isChecked() && task.progress >= 1) continue;
            if (!listMode && !has(task.startTime) && !untimedHeading) {
                TextView heading = text("Без времени · в конце дня", 12, MUTED);
                heading.setPadding(dp(4), shown == 0 ? dp(2) : dp(10), 0, dp(10)); tasks.addView(heading);
                untimedHeading = true;
            }
            tasks.addView(listMode ? listCard(task, all) : timelineCard(task, all)); shown++;
        }
        if (shown == 0) {
            LinearLayout empty = column(); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(22), dp(38), dp(22), dp(38));
            TextView symbol = text(all.isEmpty() ? "○" : "✓", 38, ACCENT); symbol.setGravity(Gravity.CENTER); empty.addView(symbol);
            TextView title = text(all.isEmpty() ? "День ещё не заполнен" : "Все дела завершены", 20, INK);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); title.setGravity(Gravity.CENTER); title.setPadding(0, dp(10), 0, dp(10)); empty.addView(title);
            TextView note = text(all.isEmpty() ? "Добавьте новое дело или перенесите\nчто-нибудь с другого дня." : "Хорошая работа. Можно отдохнуть\nили отключить фильтр «Осталось».", 14, MUTED);
            note.setGravity(Gravity.CENTER); note.setLineSpacing(dp(3), 1); empty.addView(note); tasks.addView(empty);
        } else if (listMode) {
            TextView hint = text("Удерживайте дело, чтобы изменить порядок.\nНажатие на кружок отмечает выполнение.", 11, MUTED);
            hint.setPadding(dp(8), dp(10), dp(8), dp(8)); hint.setLineSpacing(dp(2), 1); tasks.addView(hint);
        } else if (displayed.stream().anyMatch(task -> task.nextDay)) {
            TextView hint = text("+1 — после полуночи, в конце этого дня.", 11, MUTED);
            hint.setPadding(dp(8), dp(8), dp(8), dp(8)); tasks.addView(hint);
        }
        if (resetScroll) taskScroll.post(() -> taskScroll.scrollTo(0, 0));
    }

    private View timelineCard(TaskStore.Task task, List<TaskStore.Task> all) {
        LinearLayout outer = row(); outer.setBaselineAligned(false);
        LinearLayout.LayoutParams outerParams = new LinearLayout.LayoutParams(-1, -2); outerParams.bottomMargin = dp(12); outer.setLayoutParams(outerParams);
        LinearLayout clock = column(); clock.setPadding(0, dp(10), dp(6), 0);
        TextView start = text(has(task.startTime) ? task.startTime : "—", 15, task.progress >= 1 ? MUTED : INK);
        start.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); clock.addView(start);
        if (has(task.endTime)) {
            TextView end = text(task.endTime, 12, MUTED); end.setPadding(0, dp(4), 0, 0); clock.addView(end);
        }
        if (task.nextDay) {
            TextView night = text("+1", 10, ACCENT); night.setPadding(0, dp(5), 0, 0); clock.addView(night);
        }
        int clockWidth = dp(Math.max(56, Math.min(88, Math.round(56 * getResources().getConfiguration().fontScale))));
        outer.addView(clock, new LinearLayout.LayoutParams(clockWidth, -2));
        View rail = new TimelineRail(task.progress);
        LinearLayout.LayoutParams railParams = new LinearLayout.LayoutParams(dp(12), -1); railParams.setMarginEnd(dp(10)); outer.addView(rail, railParams);
        LinearLayout card = column(); card.setPadding(dp(14), dp(8), dp(10), dp(14));
        card.setBackground(cardBackground(task.progress >= 1)); outer.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
        LinearLayout top = row(); top.setGravity(Gravity.CENTER_VERTICAL);
        TextView state = text(statusName(task.progress), 11, statusColor(task.progress)); state.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        state.setPadding(dp(8), dp(5), dp(8), dp(5)); state.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1823695d), shape(statusBackground(task.progress), 8), null));
        state.setContentDescription("Выполнение: " + statusName(task.progress) + ". Изменить отметку");
        state.setOnClickListener(v -> chooseProgress(task)); state.setClickable(true); state.setFocusable(true); state.setMinHeight(dp(48)); state.setGravity(Gravity.CENTER);
        LinearLayout stateArea = row(); stateArea.setGravity(Gravity.CENTER_VERTICAL);
        stateArea.addView(state, new LinearLayout.LayoutParams(-2, -2));
        top.addView(stateArea, new LinearLayout.LayoutParams(0, -2, 1));
        Button menu = taskMenuButton(task, all); top.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48))); card.addView(top);
        TextView title = taskTitle(task, 16); title.setPadding(0, dp(3), dp(3), 0); card.addView(title);
        if (has(duration(task))) {
            TextView duration = text(duration(task), 11, MUTED); duration.setPadding(0, dp(7), 0, 0); card.addView(duration);
        }
        if (has(task.notes)) {
            TextView note = text(task.notes.trim(), 12, MUTED); note.setMaxLines(3); note.setEllipsize(android.text.TextUtils.TruncateAt.END);
            note.setLineSpacing(dp(2), 1); note.setPadding(0, dp(8), dp(3), 0); card.addView(note);
        }
        card.setOnClickListener(v -> edit(task)); card.setOnLongClickListener(v -> { chooseProgress(task); return true; });
        return outer;
    }

    private View listCard(TaskStore.Task task, List<TaskStore.Task> all) {
        LinearLayout card = row(); card.setGravity(Gravity.CENTER_VERTICAL); card.setPadding(dp(4), dp(10), dp(4), dp(10));
        card.setBackground(cardBackground(task.progress >= 1));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2); cardParams.bottomMargin = dp(7); card.setLayoutParams(cardParams);
        CompletionMark check = new CompletionMark(task.progress);
        check.setOnClickListener(v -> mutate(() -> store.setProgress(task.id, task.progress >= 1 ? 0 : 1)));
        check.setOnLongClickListener(v -> { chooseProgress(task); return true; });
        card.addView(check, new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout body = column(); body.setPadding(dp(3), dp(2), dp(4), dp(2)); card.addView(body, new LinearLayout.LayoutParams(0, -2, 1));
        TextView title = taskTitle(task, 15); title.setMaxLines(4); title.setEllipsize(android.text.TextUtils.TruncateAt.END); body.addView(title);
        String meta = has(task.startTime) ? task.startTime + (has(task.endTime) ? "–" + task.endTime : "") + (task.nextDay ? " +1" : "") : "Без времени";
        if (task.progress > 0 && task.progress < 1) meta += " · сделано 50%";
        TextView time = text(meta, 11, task.progress > 0 && task.progress < 1 ? 0xff9b6b2d : MUTED);
        time.setPadding(0, dp(5), 0, 0); body.addView(time);
        if (has(task.notes)) {
            TextView note = text(task.notes.trim(), 12, MUTED); note.setMaxLines(2); note.setEllipsize(android.text.TextUtils.TruncateAt.END);
            note.setPadding(0, dp(5), 0, 0); body.addView(note);
        }
        Button menu = taskMenuButton(task, all); card.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));
        card.setOnClickListener(v -> edit(task));
        card.setOnLongClickListener(v -> {
            v.startDragAndDrop(ClipData.newPlainText("task", task.id), new View.DragShadowBuilder(card), task.id, 0); return true;
        });
        card.setOnDragListener((view, event) -> {
            if (event.getAction() == DragEvent.ACTION_DRAG_STARTED) return event.getLocalState() instanceof String;
            if (event.getAction() == DragEvent.ACTION_DRAG_ENTERED) view.setAlpha(.65f);
            if (event.getAction() == DragEvent.ACTION_DRAG_EXITED || event.getAction() == DragEvent.ACTION_DRAG_ENDED) view.setAlpha(1);
            if (event.getAction() == DragEvent.ACTION_DROP) {
                view.setAlpha(1);
                String moving = (String) event.getLocalState();
                if (!moving.equals(task.id)) reorderBefore(moving, task.id);
            }
            return true;
        });
        return card;
    }

    private TextView taskTitle(TaskStore.Task task, int size) {
        TextView title = text(task.title, size, task.progress >= 1 ? MUTED : INK);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); title.setLineSpacing(dp(2), 1);
        if (task.progress >= 1) title.setPaintFlags(title.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
        return title;
    }

    private Button taskMenuButton(TaskStore.Task task, List<TaskStore.Task> all) {
        Button menu = small("⋮", "Действия с делом «" + task.title + "»");
        menu.setTextColor(MUTED); menu.setTextSize(23);
        menu.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, v);
            popup.getMenu().add(0, 1, 0, "Изменить дело");
            popup.getMenu().add(0, 2, 1, "Перенести на другой день");
            popup.getMenu().add(0, 3, 2, "Отметить выполнение…");
            if (listMode) {
                popup.getMenu().add(0, 4, 3, "↑ Поднять выше").setEnabled(all.indexOf(task) > 0);
                popup.getMenu().add(0, 5, 4, "↓ Опустить ниже").setEnabled(all.indexOf(task) < all.size() - 1);
            } else popup.getMenu().add(0, 6, 3, "Изменить порядок в списке дел");
            popup.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 1: edit(task); break;
                    case 2: move(task); break;
                    case 3: chooseProgress(task); break;
                    case 4: shift(task.id, -1); break;
                    case 5: shift(task.id, 1); break;
                    case 6: ((RadioGroup)findViewById(R.id.mode)).check(R.id.list); break;
                }
                return true;
            }); popup.show();
        }); return menu;
    }

    private void chooseProgress(TaskStore.Task task) {
        int checked = task.progress >= 1 ? 2 : task.progress > 0 ? 1 : 0;
        new AlertDialog.Builder(this).setTitle("Выполнение")
            .setSingleChoiceItems(new String[]{"Не начато", "Сделано наполовину · 50%", "Готово · 100%"}, checked, (dialog, which) -> {
                dialog.dismiss(); mutate(() -> store.setProgress(task.id, which == 2 ? 1 : which == 1 ? .5 : 0));
            }).setNegativeButton("Закрыть", null).show();
    }

    private String statusName(double value) { return value >= 1 ? "Готово" : value > 0 ? "Сделано 50%" : "В плане"; }
    private int statusColor(double value) { return value > 0 && value < 1 ? 0xff926323 : ACCENT; }
    private int statusBackground(double value) { return value > 0 && value < 1 ? 0xfffaf0d9 : 0xffe9f2ed; }
    private android.graphics.drawable.Drawable cardBackground(boolean done) {
        GradientDrawable background = shape(done ? 0xffedf3ef : Color.WHITE, 16);
        background.setStroke(dp(1), done ? 0xffe2ebe4 : 0xffe3eae5);
        return new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1423695d), background, null);
    }
    private String taskCount(int count) {
        int mod100 = count % 100, mod10 = count % 10;
        return count + (mod100 >= 11 && mod100 <= 14 ? " дел" : mod10 == 1 ? " дело" : mod10 >= 2 && mod10 <= 4 ? " дела" : " дел");
    }
    private String duration(TaskStore.Task task) {
        if (!has(task.startTime) || !has(task.endTime)) return "";
        try {
            int minutes = java.time.LocalTime.parse(task.endTime).toSecondOfDay() / 60 - java.time.LocalTime.parse(task.startTime).toSecondOfDay() / 60;
            if (minutes < 0) minutes += 24 * 60;
            if (minutes == 0) return "";
            return minutes >= 60 ? minutes / 60 + " ч" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + " мин") : minutes + " мин";
        } catch (Exception ignored) { return ""; }
    }

    private final class CompletionMark extends View {
        private final double value; private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        CompletionMark(double progress) {
            super(MainActivity.this); value = progress; setClickable(true); setFocusable(true);
            setContentDescription(statusName(value) + ". Нажмите, чтобы " + (value >= 1 ? "вернуть в план" : "отметить готовым") + "; удерживайте для отметки 50%");
            setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1823695d), null, shape(Color.WHITE, 24)));
            setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info); info.setClassName(CheckBox.class.getName()); info.setCheckable(true); info.setChecked(value >= 1);
                }
            });
        }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float cx = getWidth()/2f, cy = getHeight()/2f, r = dp(11);
            paint.setStyle(android.graphics.Paint.Style.FILL); paint.setColor(value >= 1 ? ACCENT : Color.WHITE); canvas.drawCircle(cx, cy, r, paint);
            if (value > 0 && value < 1) {
                paint.setColor(0xfff3dfaf);
                canvas.drawArc(new android.graphics.RectF(cx-r,cy-r,cx+r,cy+r), -90, 180, true, paint);
            }
            paint.setStyle(android.graphics.Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(value > 0 ? statusColor(value) : 0xffaabbb0); canvas.drawCircle(cx,cy,r,paint);
            if (value >= 1) {
                paint.setColor(Color.WHITE); paint.setStrokeWidth(dp(2)); paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
                canvas.drawLine(cx-dp(5),cy,cx-dp(1),cy+dp(4),paint); canvas.drawLine(cx-dp(1),cy+dp(4),cx+dp(6),cy-dp(4),paint);
            }
            paint.setStyle(android.graphics.Paint.Style.FILL);
        }
    }

    private final class TimelineRail extends View {
        private final double value; private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        TimelineRail(double progress) { super(MainActivity.this); value = progress; setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            float x=getWidth()/2f, y=dp(22);
            paint.setColor(0xffdce7df); paint.setStrokeWidth(dp(2)); canvas.drawLine(x,y,x,getHeight(),paint);
            paint.setColor(value >= 1 ? ACCENT : value > 0 ? 0xffc29142 : 0xfff5f7f4); canvas.drawCircle(x,y,dp(4),paint);
            paint.setStyle(android.graphics.Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); paint.setColor(value > 0 ? statusColor(value) : 0xff8eafa0); canvas.drawCircle(x,y,dp(4),paint); paint.setStyle(android.graphics.Paint.Style.FILL);
        }
    }

    private void shift(String id, int amount) {
        List<TaskStore.Task> list = store.listForDate(day.toString()); List<String> ids = ids(list); int index = ids.indexOf(id), to = index + amount;
        if (index < 0 || to < 0 || to >= ids.size()) return;
        java.util.Collections.swap(ids, index, to); mutate(() -> store.reorder(day.toString(), ids));
    }
    private void reorderBefore(String moving, String target) {
        List<String> ids = ids(store.listForDate(day.toString()));
        if (!ids.contains(moving) || !ids.contains(target)) return;
        ids.remove(moving); ids.add(ids.indexOf(target), moving); mutate(() -> store.reorder(day.toString(), ids));
    }
    private List<String> ids(List<TaskStore.Task> list) { List<String> ids = new ArrayList<>(); for (TaskStore.Task task : list) ids.add(task.id); return ids; }
    private boolean mutate(Runnable change) {
        try { change.run(); render(); worker.execute(() -> { try { ReminderScheduler.reschedule(getApplicationContext(), store); } catch (Exception ignored) { } }); return true; }
        catch (Exception e) { error("Не удалось сохранить изменения", e); return false; }
    }

    private void move(TaskStore.Task task) {
        chooseDate(day.plusDays(1), destination -> new AlertDialog.Builder(this).setTitle("Перенести дело?")
            .setMessage(task.title + "\n\n" + destination.format(DateTimeFormatter.ofPattern("d MMMM yyyy", russian)) + " — в конец дня, без заданного времени. Заметки и отметка выполнения сохранятся.")
            .setNegativeButton("Отмена", null).setPositiveButton("Перенести", (dialog, which) -> {
                if (mutate(() -> store.move(task.id, destination.toString()))) { day = destination; render(); toast("Дело добавлено в конец дня"); }
            }).show());
    }

    private void edit(TaskStore.Task original) {
        TaskStore.Task draft = new TaskStore.Task();
        draft.id = original == null ? UUID.randomUUID().toString() : original.id;
        draft.date = original == null ? day.toString() : original.date;
        draft.position = original == null ? store.listForDate(day.toString()).size() : original.position;
        draft.progress = original == null ? 0 : original.progress;
        draft.source = original == null ? "Пользователь" : original.source;
        LinearLayout content = column(); content.setPadding(dp(20), dp(12), dp(20), dp(12));
        EditText title = input("Название дела", original == null ? "" : original.title, false); label(content, "Дело"); content.addView(title);
        Button date = action(draft.date); label(content, "День"); content.addView(date);
        date.setOnClickListener(v -> chooseDate(LocalDate.parse(draft.date), selected -> { draft.date = selected.toString(); date.setText(draft.date); }));
        LinearLayout times = row();
        Button start = action(original == null || !has(original.startTime) ? "Начало" : original.startTime);
        Button end = action(original == null || !has(original.endTime) ? "Конец" : original.endTime);
        start.setOnClickListener(v -> chooseTime(start)); end.setOnClickListener(v -> chooseTime(end));
        times.addView(start, new LinearLayout.LayoutParams(0, dp(52), 1)); times.addView(end, new LinearLayout.LayoutParams(0, dp(52), 1));
        label(content, "Время · можно оставить пустым"); content.addView(times);
        Button clear = action("Убрать время"); clear.setOnClickListener(v -> { start.setText("Начало"); end.setText("Конец"); }); content.addView(clear);
        CheckBox night = new CheckBox(this); night.setText("После полуночи / окончание на следующие сутки (+1)"); night.setTextSize(13); night.setChecked(original != null && original.nextDay); content.addView(night);
        label(content, "Заметки"); EditText notes = input("Что сделано, где остановился, что осталось", original == null ? "" : original.notes, true); content.addView(notes);
        if (original != null && has(original.source)) { TextView source = text("Источник: " + original.source, 11, MUTED); source.setPadding(0, dp(12), 0, 0); content.addView(source); }
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(original == null ? "Новое дело" : "Изменить дело").setView(scroll).setNegativeButton("Отмена", null).setPositiveButton("Сохранить", null);
        if (original != null) builder.setNeutralButton("Удалить", (dialog, which) -> new AlertDialog.Builder(this).setTitle("Удалить дело?").setMessage(original.title).setNegativeButton("Оставить", null).setPositiveButton("Удалить", (d, w) -> mutate(() -> store.delete(original.id))).show());
        AlertDialog dialog = builder.create(); dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = title.getText().toString().trim();
            if (name.isEmpty()) { title.setError("Напишите название дела"); return; }
            draft.title = name; draft.notes = notes.getText().toString();
            draft.startTime = start.getText().toString().matches("\\d{2}:\\d{2}") ? start.getText().toString() : "";
            draft.endTime = end.getText().toString().matches("\\d{2}:\\d{2}") ? end.getText().toString() : "";
            if (has(draft.startTime) != has(draft.endTime)) { toast("Укажите начало и конец или уберите оба времени"); return; }
            draft.nextDay = has(draft.startTime) && (night.isChecked() || draft.endTime.compareTo(draft.startTime) < 0);
            if (original != null && !draft.date.equals(original.date)) draft.position = store.listForDate(draft.date).size();
            try { store.save(draft); dialog.dismiss(); day = LocalDate.parse(draft.date); render(); worker.execute(() -> { try { ReminderScheduler.reschedule(getApplicationContext(), store); } catch (Exception ignored) { } }); }
            catch (Exception e) { error("Не удалось сохранить дело", e); }
        });
    }

    private void openMenu(View anchor) {
        if (store == null) return;
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Экспорт плана · Markdown");
        menu.getMenu().add(0, 2, 1, "Резервная копия · JSON");
        menu.getMenu().add(0, 3, 2, "Импорт / объединение JSON");
        menu.getMenu().add(0, 4, 3, "Напоминания: " + (ReminderScheduler.enabled(this) ? "включены" : "выключены"));
        menu.getMenu().add(0, 5, 4, "О приложении");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: export(false); break;
                case 2: export(true); break;
                case 3:
                    Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
                    open.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "application/octet-stream"}); startActivityForResult(open, IMPORT_JSON); break;
                case 4: reminders(); break;
                case 5: new AlertDialog.Builder(this).setTitle("Мой день").setMessage("При первом запуске добавляется демонстрационная неделя. Замените примеры своими делами: их можно менять, отмечать наполовину, переносить и переставлять.\n\nMarkdown — понятная сводка плана. JSON — полная резервная копия для восстановления.\n\nНочные дела (+1) остаются в конце предыдущего дня. Напоминания учитывают этот переход. Данные хранятся на телефоне и не отправляются в интернет.").setPositiveButton("Понятно", null).show(); break;
            }
            return true;
        }); menu.show();
    }

    private void export(boolean json) {
        String text = json ? store.exportJson() : store.exportMarkdown();
        String snapshotName = "planner-export-" + System.nanoTime() + (json ? ".json" : ".md");
        worker.execute(() -> {
            try {
                // Keep a snapshot on disk, not in the saved-state Bundle: large schedules
                // must also survive the system document chooser and Activity recreation.
                try (OutputStream output = new java.io.FileOutputStream(new java.io.File(getCacheDir(), snapshotName))) { output.write(text.getBytes(StandardCharsets.UTF_8)); }
                ui(() -> {
                    pendingExport = snapshotName;
                    Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(json ? "application/json" : "text/markdown");
                    create.putExtra(Intent.EXTRA_TITLE, "Мой_день_" + LocalDate.now(zone()) + (json ? ".json" : ".md"));
                    startActivityForResult(create, json ? EXPORT_JSON : EXPORT_MD);
                });
            } catch (Exception e) { ui(() -> error("Не удалось подготовить экспорт", e)); }
        });
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == EXPORT_MD || request == EXPORT_JSON) {
            String snapshotName = pendingExport; pendingExport = "";
            worker.execute(() -> {
                try (InputStream input = new java.io.FileInputStream(new java.io.File(getCacheDir(), snapshotName)); OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new Exception("Не удалось открыть файл для записи");
                    byte[] buffer = new byte[8192]; int n; while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                    ui(() -> toast("Файл сохранён"));
                } catch (Exception e) { ui(() -> error("Не удалось сохранить файл", e)); }
            });
        } else if (request == IMPORT_JSON) {
            worker.execute(() -> {
                try (InputStream input = getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new Exception("Не удалось открыть файл");
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
                    while ((n = input.read(buffer)) != -1) { if (bytes.size() + n > 8 * 1024 * 1024) throw new Exception("Файл слишком большой: предел 8 МБ"); bytes.write(buffer, 0, n); }
                    String json = bytes.toString("UTF-8");
                    ui(() -> new AlertDialog.Builder(this).setTitle("Объединить расписания?").setMessage("Новые дела добавятся. Дела с совпадающими идентификаторами обновятся из файла. Остальные записи сохранятся. Перед импортом можно отменить действие и сделать резервную копию.").setNegativeButton("Отмена", null).setPositiveButton("Импортировать", (d, w) -> worker.execute(() -> {
                        try { int added = store.importJson(json); ReminderScheduler.reschedule(getApplicationContext(), store); ui(() -> { render(); toast("Импортировано дел: " + added); }); }
                        catch (Exception e) { ui(() -> error("Импорт не выполнен", e)); }
                    })).show());
                } catch (Exception e) { ui(() -> error("Не удалось прочитать файл", e)); }
            });
        }
    }

    private void reminders() {
        boolean currently = ReminderScheduler.enabled(this);
        new AlertDialog.Builder(this).setTitle(currently ? "Выключить напоминания?" : "Включить напоминания?")
            .setMessage(currently ? "Расписание останется на месте. Уведомления о начале дел больше не будут приходить." : "Уведомления будут приходить к началу незавершённых дел с указанным временем. Ночные дела учитываются на следующих сутках. Android может задерживать уведомления при экономии батареи; точные будильники не используются.")
            .setNegativeButton("Отмена", null).setPositiveButton(currently ? "Выключить" : "Включить", (d, w) -> {
                if (!currently && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
                else enableReminders(!currently);
            }).show();
    }
    private void enableReminders(boolean enabled) {
        getSharedPreferences(ReminderScheduler.PREFS, MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply();
        worker.execute(() -> { ReminderScheduler.reschedule(getApplicationContext(), store); ui(() -> toast(enabled ? "Напоминания включены" : "Напоминания выключены")); });
    }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code == NOTIFICATIONS) { if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) enableReminders(true); else toast("Без разрешения уведомления не включены"); }
    }

    private interface DateChosen { void selected(LocalDate date); }
    private void chooseDate(LocalDate initial, DateChosen callback) { new DatePickerDialog(this, (v, y, m, d) -> callback.selected(LocalDate.of(y, m + 1, d)), initial.getYear(), initial.getMonthValue() - 1, initial.getDayOfMonth()).show(); }
    private void chooseTime(Button button) {
        int hour = 13, minute = 0; String value = button.getText().toString();
        if (value.matches("\\d{2}:\\d{2}")) { hour = Integer.parseInt(value.substring(0, 2)); minute = Integer.parseInt(value.substring(3)); }
        new TimePickerDialog(this, (v, h, m) -> button.setText(String.format(Locale.ROOT, "%02d:%02d", h, m)), hour, minute, true).show();
    }
    private EditText input(String hint, String value, boolean multiline) {
        EditText input = new EditText(this); input.setHint(hint); input.setText(value == null ? "" : value); input.setTextSize(16);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES));
        input.setMinLines(multiline ? 3 : 1); input.setMaxLines(multiline ? 8 : 3); input.setGravity(Gravity.TOP); return input;
    }
    private void label(LinearLayout parent, String value) { TextView label = text(value, 12, MUTED); label.setPadding(0, dp(12), 0, dp(3)); parent.addView(label); }
    private TextView text(String value, int size, int color) { TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); return text; }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private LinearLayout row() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.HORIZONTAL); return layout; }
    private Button action(String title) {
        Button button = new Button(this); button.setText(title); button.setTextSize(13); button.setTextColor(ACCENT);
        button.setAllCaps(false); button.setMinHeight(0); button.setMinWidth(0); button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(4), dp(12), dp(4)); button.setStateListAnimator(null);
        button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1823695d), shape(0xffeaf1ec, 12), null));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.topMargin=dp(4); params.bottomMargin=dp(4); button.setLayoutParams(params);
        return button;
    }
    private Button small(String title, String description) { Button button = action(title); button.setTextSize(20); button.setMinWidth(0); button.setPadding(0, 0, 0, 0); button.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48))); button.setContentDescription(description); button.setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x1823695d), null, shape(Color.WHITE, 24))); return button; }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density + .5f); }
    private java.time.ZoneId zone() { try { return java.time.ZoneId.of(store == null ? "Europe/Moscow" : store.getTimeZone()); } catch (Exception ignored) { return ReminderScheduler.ZONE; } }
    private boolean has(String value) { return value != null && !value.trim().isEmpty(); }
    private void toast(String message) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show(); }
    private void error(String title, Exception error) { new AlertDialog.Builder(this).setTitle(title).setMessage(error.getMessage() == null ? "Попробуйте ещё раз." : error.getMessage()).setPositiveButton("Понятно", null).show(); }
}
