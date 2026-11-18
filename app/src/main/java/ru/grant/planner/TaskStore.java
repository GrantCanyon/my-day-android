package ru.grant.planner;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Local-only storage. Each public mutation commits all affected rows in one transaction. */
public final class TaskStore {
    private static final Object DATA_LOCK = new Object();
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_TASKS = 20000;
    private static final int MAX_IMPORT_BYTES = 25 * 1024 * 1024;
    private static final String[] COLUMNS = {
            "id", "title", "task_date", "start_time", "end_time", "notes", "source",
            "position", "progress", "next_day"
    };
    private static final Comparator<Task> ORDER = Comparator
            .comparing((Task task) -> task.date).thenComparingInt(task -> task.position);
    private final SQLiteDatabase database;

    public static class Task {
        public String id = "";
        public String title = "";
        public String date = "";
        public String startTime = "";
        public String endTime = "";
        public String notes = "";
        public String source = "";
        public int position = 0;
        public double progress = 0;
        /** +1 notation: an early-morning start or the end belongs to the following date. */
        public boolean nextDay = false;

        public Task() { }

        public Task(Task other) {
            id = other.id;
            title = other.title;
            date = other.date;
            startTime = other.startTime;
            endTime = other.endTime;
            notes = other.notes;
            source = other.source;
            position = other.position;
            progress = other.progress;
            nextDay = other.nextDay;
        }
    }

    public TaskStore(Context context) {
        Context app = context.getApplicationContext();
        synchronized (DATA_LOCK) {
            database = new StoreDatabase(app).getWritableDatabase();
            seedIfNeeded(app);
        }
    }

    public synchronized List<Task> listForDate(String date) {
        validateDate(date);
        synchronized (DATA_LOCK) {
            return query("task_date = ?", new String[]{date});
        }
    }

    public synchronized List<Task> getAll() {
        synchronized (DATA_LOCK) {
            return query(null, null);
        }
    }

    /** Schedule times use their saved time zone, independent of the device's current time zone. */
    public synchronized String getTimeZone() {
        synchronized (DATA_LOCK) {
            try (Cursor cursor = database.rawQuery("SELECT value FROM settings WHERE name = 'time_zone'", null)) {
                return cursor.moveToFirst() ? cursor.getString(0) : "Europe/Moscow";
            }
        }
    }

    /** New tasks insert at position; edits may also change the date or the position. */
    public synchronized void save(Task task) {
        if (task == null) throw new IllegalArgumentException("Не передано дело.");
        Task clean = validatedCopy(task, true);
        synchronized (DATA_LOCK) {
            database.beginTransaction();
            try {
                Task old = find(clean.id);
                List<Task> destination = query("task_date = ?", new String[]{clean.date});
                destination.removeIf(item -> item.id.equals(clean.id));
                clean.position = Math.min(clean.position, destination.size());
                destination.add(clean.position, clean);
                writeOrdered(destination);
                if (old != null && !old.date.equals(clean.date)) {
                    writeOrdered(query("task_date = ?", new String[]{old.date}));
                }
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
            task.id = clean.id;
            task.position = clean.position;
            task.title = clean.title;
            task.startTime = clean.startTime;
            task.endTime = clean.endTime;
            task.notes = clean.notes;
            task.source = clean.source;
            task.nextDay = clean.nextDay;
        }
    }

    public synchronized void delete(String id) {
        validateId(id);
        synchronized (DATA_LOCK) {
            database.beginTransaction();
            try {
                Task old = find(id);
                if (old != null) {
                    database.delete("tasks", "id = ?", new String[]{id});
                    writeOrdered(query("task_date = ?", new String[]{old.date}));
                }
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
        }
    }

    /** A transferred task is placed after every destination task and has no assigned time. */
    public synchronized void move(String id, String destinationDate) {
        validateId(id);
        validateDate(destinationDate);
        synchronized (DATA_LOCK) {
            Task moved = find(id);
            if (moved == null) throw new IllegalArgumentException("Дело уже удалено.");
            moved.date = destinationDate;
            moved.startTime = "";
            moved.endTime = "";
            moved.nextDay = false;
            moved.position = query("task_date = ?", new String[]{destinationDate}).size();
            save(moved);
        }
    }

    public synchronized void setProgress(String id, double progress) {
        validateId(id);
        validateProgress(progress);
        synchronized (DATA_LOCK) {
            ContentValues values = new ContentValues();
            values.put("progress", progress);
            if (database.update("tasks", values, "id = ?", new String[]{id}) == 0) {
                throw new IllegalArgumentException("Дело уже удалено.");
            }
        }
    }

    /** IDs must be an exact permutation of this day's tasks; stale UI data cannot drop rows. */
    public synchronized void reorder(String date, List<String> ids) {
        validateDate(date);
        if (ids == null) throw new IllegalArgumentException("Не передан порядок дел.");
        synchronized (DATA_LOCK) {
            database.beginTransaction();
            try {
                List<Task> existing = query("task_date = ?", new String[]{date});
                Map<String, Task> byId = new LinkedHashMap<>();
                for (Task task : existing) byId.put(task.id, task);
                if (ids.size() != byId.size() || new HashSet<>(ids).size() != ids.size()
                        || !byId.keySet().equals(new HashSet<>(ids))) {
                    throw new IllegalArgumentException("Список дел изменился. Откройте день заново.");
                }
                List<Task> ordered = new ArrayList<>();
                for (String id : ids) ordered.add(byId.get(id));
                writeOrdered(ordered);
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
        }
    }

    public synchronized String exportJson() {
        synchronized (DATA_LOCK) {
            try {
                JSONObject root = new JSONObject();
                root.put("schemaVersion", SCHEMA_VERSION);
                root.put("app", "Мой день");
                root.put("exportedAt", Instant.now().toString());
                root.put("timezone", getTimeZone());
                root.put("nightConvention", "+1 относится к следующей календарной дате; дело показано в предыдущем дне");
                JSONArray tasks = new JSONArray();
                for (Task task : query(null, null)) tasks.put(toJson(task));
                root.put("tasks", tasks);
                return root.toString(2);
            } catch (Exception error) {
                throw new IllegalStateException("Не удалось подготовить резервную копию.", error);
            }
        }
    }

    public synchronized String exportMarkdown() {
        synchronized (DATA_LOCK) {
            StringBuilder output = new StringBuilder("# Расписание и выполнение\n\n");
            output.append("Часовой пояс расписания: ").append(getTimeZone()).append(".\n")
                    .append("Статусы: 0% — не сделано, 50% — сделана половина, 100% — выполнено.\n")
                    .append("`+1` — следующие календарные сутки; ночные дела остаются в конце предыдущего дня.\n")
                    .append("Дела без времени добавлены в конец дня; порядок ниже сохранён из приложения.\n\n");
            String previous = "";
            DateTimeFormatter heading = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", new Locale("ru"));
            List<Task> tasks = query(null, null);
            if (tasks.isEmpty()) output.append("Дел пока нет.\n");
            for (Task task : tasks) {
                if (!previous.equals(task.date)) {
                    previous = task.date;
                    output.append("## ").append(LocalDate.parse(task.date).format(heading))
                            .append(" · ").append(task.date).append("\n\n");
                }
                output.append(task.progress == 1 ? "- [x] " : "- [ ] ")
                        .append("**").append(timeLabel(task)).append("** — ")
                        .append(markdown(task.title)).append(" · **")
                        .append((int) (task.progress * 100)).append("%**\n");
                if (!task.notes.isEmpty()) {
                    output.append("  Заметки: ").append(markdown(task.notes).replace("\n", "\n  ")).append("\n");
                }
                if (!task.source.isEmpty()) {
                    output.append("  Источник: ").append(markdown(task.source).replace("\n", " ")).append("\n");
                }
                output.append("  ID: `").append(task.id.replace("`", "")).append("`\n\n");
            }
            return output.toString();
        }
    }

    /** Validate the entire file first, then merge by stable ID in one database transaction. */
    public synchronized int importJson(String json) throws Exception {
        ImportData incoming = parseJson(json);
        synchronized (DATA_LOCK) {
            database.beginTransaction();
            try {
                merge(incoming.tasks);
                saveTimeZone(incoming.timezone);
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
        }
        return incoming.tasks.size();
    }

    private void seedIfNeeded(Context context) {
        try (Cursor cursor = database.rawQuery("SELECT value FROM settings WHERE name = 'seed_imported'", null)) {
            if (cursor.moveToFirst()) return;
        }
        String json;
        try (InputStream input = context.getAssets().open("bundled_schedule.json")) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_IMPORT_BYTES) throw new IllegalArgumentException("Исходное расписание слишком большое.");
                output.write(buffer, 0, count);
            }
            json = new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (FileNotFoundException missingAsset) {
            return; // Without the optional demo asset, start with an empty database.
        } catch (Exception error) {
            throw new IllegalStateException("Не удалось прочитать исходное расписание.", error);
        }
        try {
            ImportData seed = parseJson(json);
            // Demo dates move once on first installation; user edits and backups keep their dates.
            String demoAnchor = new JSONObject(json).optString("demoAnchorDate", "");
            if (!demoAnchor.isEmpty()) {
                LocalDate anchor = LocalDate.parse(demoAnchor);
                LocalDate today = LocalDate.now(ZoneId.of(seed.timezone));
                for (Task task : seed.tasks) {
                    long offset = java.time.temporal.ChronoUnit.DAYS.between(anchor, LocalDate.parse(task.date));
                    task.date = today.plusDays(offset).toString();
                }
            }
            database.beginTransaction();
            try {
                // Existing user edits win if an earlier build ran without the bundled asset.
                Set<String> present = new HashSet<>();
                for (Task task : query(null, null)) present.add(task.id);
                List<Task> additions = new ArrayList<>();
                for (Task task : seed.tasks) if (!present.contains(task.id)) additions.add(task);
                merge(additions);
                saveTimeZone(seed.timezone);
                ContentValues imported = new ContentValues();
                imported.put("name", "seed_imported");
                imported.put("value", "1");
                database.insertOrThrow("settings", null, imported);
                database.setTransactionSuccessful();
            } finally {
                database.endTransaction();
            }
        } catch (Exception error) {
            throw new IllegalStateException("Исходное расписание повреждено. Существующие дела сохранены.", error);
        }
    }

    private void merge(List<Task> incoming) {
        Map<String, Task> combined = new LinkedHashMap<>();
        for (Task task : query(null, null)) combined.put(task.id, task);
        for (Task task : incoming) combined.put(task.id, new Task(task));
        if (combined.size() > MAX_TASKS) throw new IllegalArgumentException("В расписании может быть не более " + MAX_TASKS + " дел.");
        List<Task> ordered = new ArrayList<>(combined.values());
        Collections.sort(ordered, ORDER); // Stable sort preserves the original order for equal positions.
        String date = "";
        int position = 0;
        for (Task task : ordered) {
            if (!task.date.equals(date)) { date = task.date; position = 0; }
            task.position = position++;
            if (database.insertWithOnConflict("tasks", null, values(task), SQLiteDatabase.CONFLICT_REPLACE) == -1) {
                throw new IllegalStateException("Не удалось сохранить импортированное дело.");
            }
        }
    }

    private static ImportData parseJson(String json) throws Exception {
        if (json == null || json.length() > MAX_IMPORT_BYTES || json.getBytes(StandardCharsets.UTF_8).length > MAX_IMPORT_BYTES) {
            throw new IllegalArgumentException("Файл пуст или слишком большой (не более 25 МБ).");
        }
        JSONObject root = new JSONObject(json);
        Object schema = root.opt("schemaVersion");
        if (!(schema instanceof Number) || ((Number) schema).doubleValue() != SCHEMA_VERSION) {
            throw new IllegalArgumentException("Неизвестный формат расписания. Нужен JSON schemaVersion: 1.");
        }
        JSONArray array = root.getJSONArray("tasks");
        if (array.length() > MAX_TASKS) throw new IllegalArgumentException("Слишком много дел в файле.");
        String rootSource = string(root, "source", "");
        String timezone = string(root, "timezone", string(root, "timeZone", ""));
        if (!timezone.isEmpty()) {
            try { ZoneId.of(timezone); }
            catch (Exception error) { throw new IllegalArgumentException("Неизвестный часовой пояс расписания."); }
        }
        Set<String> ids = new HashSet<>();
        List<Task> tasks = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            try {
                JSONObject item = array.getJSONObject(i);
                Task task = new Task();
                task.id = string(item, "id", "");
                task.title = string(item, "title", "");
                task.date = string(item, "date", "");
                task.startTime = string(item, "startTime", "");
                task.endTime = string(item, "endTime", "");
                task.notes = string(item, "notes", "");
                task.source = string(item, "source", rootSource);
                Object position = item.opt("position");
                if (position == null || position == JSONObject.NULL) task.position = i;
                else if (position instanceof Number && ((Number) position).doubleValue() == ((Number) position).intValue()) task.position = ((Number) position).intValue();
                else throw new IllegalArgumentException("Позиция должна быть целым числом.");
                Object progress = item.opt("progress");
                if (progress != null && progress != JSONObject.NULL) {
                    if (!(progress instanceof Number)) throw new IllegalArgumentException("Выполнение должно быть числом 0, 0.5 или 1.");
                    task.progress = ((Number) progress).doubleValue();
                }
                Object nextDay = item.opt("nextDay");
                if (nextDay != null && nextDay != JSONObject.NULL) {
                    if (!(nextDay instanceof Boolean)) throw new IllegalArgumentException("nextDay должно быть true или false.");
                    task.nextDay = (Boolean) nextDay;
                }
                if (task.id.trim().isEmpty()) {
                    String identity = task.source + "\n" + task.date + "\n" + task.startTime + "\n"
                            + task.endTime + "\n" + task.title + "\n" + task.position;
                    task.id = UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8)).toString();
                }
                task = validatedCopy(task, false);
                if (!ids.add(task.id)) throw new IllegalArgumentException("Повторяющийся ID дела.");
                tasks.add(task);
            } catch (Exception error) {
                throw new IllegalArgumentException("Дело № " + (i + 1) + ": " + error.getMessage(), error);
            }
        }
        return new ImportData(tasks, timezone);
    }

    private void saveTimeZone(String timezone) {
        if (timezone.isEmpty()) return;
        ContentValues values = new ContentValues();
        values.put("name", "time_zone");
        values.put("value", timezone);
        if (database.insertWithOnConflict("settings", null, values, SQLiteDatabase.CONFLICT_REPLACE) == -1) {
            throw new IllegalStateException("Не удалось сохранить часовой пояс.");
        }
    }

    private static final class ImportData {
        final List<Task> tasks;
        final String timezone;
        ImportData(List<Task> tasks, String timezone) { this.tasks = tasks; this.timezone = timezone; }
    }

    private static String string(JSONObject object, String key, String fallback) throws Exception {
        if (!object.has(key) || object.isNull(key)) return fallback;
        Object value = object.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("Поле «" + key + "» должно быть текстом.");
        return (String) value;
    }

    private static Task validatedCopy(Task source, boolean generateId) {
        Task task = new Task(source);
        task.id = safe(task.id).trim();
        if (generateId && task.id.isEmpty()) task.id = UUID.randomUUID().toString();
        validateId(task.id);
        task.title = safe(task.title).trim().replaceAll("[\\r\\n\\t]+", " ");
        if (task.title.isEmpty() || task.title.length() > 2000) throw new IllegalArgumentException("Название должно содержать от 1 до 2000 символов.");
        validateDate(task.date);
        task.startTime = safe(task.startTime).trim();
        task.endTime = safe(task.endTime).trim();
        validateTime(task.startTime);
        validateTime(task.endTime);
        if (task.startTime.isEmpty() != task.endTime.isEmpty()) throw new IllegalArgumentException("Укажите и начало, и конец либо оставьте оба поля пустыми.");
        if (task.startTime.isEmpty()) task.nextDay = false;
        else if (task.endTime.compareTo(task.startTime) < 0) task.nextDay = true;
        task.notes = safe(task.notes).replace("\r\n", "\n");
        task.source = safe(task.source);
        if (task.notes.length() > 100000) throw new IllegalArgumentException("Заметка слишком длинная (не более 100 000 символов).");
        if (task.source.length() > 2000) throw new IllegalArgumentException("Описание источника слишком длинное.");
        if (task.position < 0) throw new IllegalArgumentException("Позиция не может быть отрицательной.");
        validateProgress(task.progress);
        return task;
    }

    private static void validateDate(String date) {
        if (date == null || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("Дата должна быть в формате ГГГГ-ММ-ДД.");
        try { LocalDate.parse(date); }
        catch (Exception error) { throw new IllegalArgumentException("Такой даты не существует."); }
    }

    private static void validateTime(String time) {
        if (!time.isEmpty() && !time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]")) throw new IllegalArgumentException("Время должно быть в формате ЧЧ:ММ (00:00–23:59).");
    }

    private static void validateId(String id) {
        if (id == null || id.trim().isEmpty() || id.length() > 160 || id.matches(".*[\\p{Cntrl}].*")) throw new IllegalArgumentException("Некорректный ID дела.");
    }

    private static void validateProgress(double value) {
        if (value != 0 && value != 0.5 && value != 1) throw new IllegalArgumentException("Выполнение может быть только 0%, 50% или 100%.");
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static String markdown(String text) {
        return text.replace("\\", "\\\\").replace("*", "\\*")
                .replace("_", "\\_").replace("[", "\\[").replace("]", "\\]")
                .replace("<", "&lt;").replace(">", "&gt;").replace("`", "\\`");
    }

    private static String timeLabel(Task task) {
        if (task.startTime.isEmpty()) return "Без времени · в конце дня";
        return task.startTime + "–" + task.endTime + (task.nextDay ? " +1" : "");
    }

    private List<Task> query(String where, String[] arguments) {
        List<Task> result = new ArrayList<>();
        try (Cursor cursor = database.query("tasks", COLUMNS, where, arguments, null, null, "task_date ASC, position ASC, rowid ASC")) {
            while (cursor.moveToNext()) result.add(fromCursor(cursor));
        }
        return result;
    }

    private Task find(String id) {
        List<Task> rows = query("id = ?", new String[]{id});
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void writeOrdered(List<Task> ordered) {
        for (int i = 0; i < ordered.size(); i++) {
            Task task = ordered.get(i);
            task.position = i;
            if (database.insertWithOnConflict("tasks", null, values(task), SQLiteDatabase.CONFLICT_REPLACE) == -1) {
                throw new IllegalStateException("Не удалось сохранить дело.");
            }
        }
    }

    private static ContentValues values(Task task) {
        ContentValues values = new ContentValues();
        values.put("id", task.id);
        values.put("title", task.title);
        values.put("task_date", task.date);
        values.put("start_time", task.startTime);
        values.put("end_time", task.endTime);
        values.put("notes", task.notes);
        values.put("source", task.source);
        values.put("position", task.position);
        values.put("progress", task.progress);
        values.put("next_day", task.nextDay ? 1 : 0);
        return values;
    }

    private static Task fromCursor(Cursor cursor) {
        Task task = new Task();
        task.id = cursor.getString(0);
        task.title = cursor.getString(1);
        task.date = cursor.getString(2);
        task.startTime = cursor.getString(3);
        task.endTime = cursor.getString(4);
        task.notes = cursor.getString(5);
        task.source = cursor.getString(6);
        task.position = cursor.getInt(7);
        task.progress = cursor.getDouble(8);
        task.nextDay = cursor.getInt(9) != 0;
        return task;
    }

    private static JSONObject toJson(Task task) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", task.id);
        json.put("title", task.title);
        json.put("date", task.date);
        json.put("startTime", task.startTime);
        json.put("endTime", task.endTime);
        json.put("notes", task.notes);
        json.put("source", task.source);
        json.put("position", task.position);
        json.put("progress", task.progress);
        json.put("nextDay", task.nextDay);
        return json;
    }

    private static final class StoreDatabase extends SQLiteOpenHelper {
        StoreDatabase(Context context) { super(context, "day-planner.db", null, 1); }

        @Override public void onConfigure(SQLiteDatabase database) {
            database.enableWriteAheadLogging();
        }

        @Override public void onCreate(SQLiteDatabase database) {
            database.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, "
                    + "task_date TEXT NOT NULL, start_time TEXT NOT NULL DEFAULT '', end_time TEXT NOT NULL DEFAULT '', "
                    + "notes TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT '', position INTEGER NOT NULL, "
                    + "progress REAL NOT NULL DEFAULT 0 CHECK(progress IN (0,0.5,1)), "
                    + "next_day INTEGER NOT NULL DEFAULT 0 CHECK(next_day IN (0,1)))");
            database.execSQL("CREATE INDEX tasks_day_order ON tasks(task_date,position)");
            database.execSQL("CREATE TABLE settings (name TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)");
        }

        @Override public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
            throw new IllegalStateException("Нужна миграция базы данных; существующие дела не удалены.");
        }
    }
}
