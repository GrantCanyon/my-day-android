package ru.grant.planner;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.database.DatabaseErrorHandler;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** No production database is opened: every context points at a fresh cache subdirectory. */
public final class PlannerDataSelfTest extends Instrumentation {
    private int assertions;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            runChecks();
            result.putBoolean("success", true);
            result.putInt("assertions", assertions);
            result.putString("stream", "Planner data checks passed: " + assertions + " assertions\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putBoolean("success", false);
            result.putInt("assertions", assertions);
            result.putString("error", error.toString());
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void runChecks() throws Exception {
        Context isolated = isolatedContext("source");
        TaskStore store = new TaskStore(isolated);
        List<TaskStore.Task> seed = store.getAll();
        check(seed.size() == 14, "The synthetic demo must contain 14 example tasks");
        String firstDate = seed.get(0).date;
        String lastDate = LocalDate.parse(firstDate).plusDays(6).toString();
        check(firstDate.equals(LocalDate.now(ZoneId.of(store.getTimeZone())).toString()),
                "First installation must anchor the demo at today's schedule date");
        Set<String> dates = new HashSet<>();
        Set<String> ids = new HashSet<>();
        TaskStore.Task night = null;
        for (TaskStore.Task task : seed) {
            dates.add(task.date);
            ids.add(task.id);
            if (task.nextDay && night == null) night = new TaskStore.Task(task);
        }
        check(dates.size() == 7, "All seven demo days must be available");
        check(ids.size() == seed.size(), "Demo entries must have unique stable IDs");
        check("Europe/Moscow".equals(store.getTimeZone()), "Demo timezone must survive import");
        check(store.listForDate(lastDate).size() == 2, "The final demo day must retain its examples");
        check(night != null, "The demo must include a night entry with +1");

        TaskStore.Task edited = new TaskStore.Task(seed.get(0));
        String originalSource = edited.source;
        edited.notes = "Выполнена половина.\nПроверить результат *без подсказки*.";
        store.save(edited);
        store.setProgress(edited.id, 0.5);
        store = new TaskStore(isolated);
        TaskStore.Task persisted = find(store.getAll(), edited.id);
        check(persisted.progress == 0.5, "Partial completion must survive reopening SQLite");
        check(persisted.notes.equals(edited.notes), "Multiline notes must survive reopening SQLite");
        check(persisted.source.equals(originalSource), "Editing must preserve the source label");
        check(find(store.getAll(), edited.id).date.equals(firstDate), "Reopening must not rebase existing task dates");

        String destination = LocalDate.parse(firstDate).plusDays(2).toString();
        int destinationCount = store.listForDate(destination).size();
        store.move(persisted.id, destination);
        List<TaskStore.Task> movedDay = store.listForDate(destination);
        TaskStore.Task moved = movedDay.get(movedDay.size() - 1);
        check(moved.id.equals(persisted.id), "Moved task must append after all destination entries");
        check(movedDay.size() == destinationCount + 1, "Move must not duplicate destination tasks");
        check(moved.startTime.isEmpty() && moved.endTime.isEmpty() && !moved.nextDay,
                "Move must clear start/end and the old +1 marker");
        check(moved.progress == 0.5 && moved.notes.equals(edited.notes), "Move must preserve notes and 50% completion");
        check(moved.source.equals(originalSource), "Move must preserve source provenance");
        check(store.getAll().size() == 14, "Move must preserve total task count");
        check(!contains(store.listForDate(edited.date), edited.id), "Moved task must leave its original day");

        TaskStore.Task retainedNight = find(store.getAll(), night.id);
        check(retainedNight.nextDay && retainedNight.date.equals(night.date)
                        && retainedNight.startTime.equals(night.startTime)
                        && retainedNight.endTime.equals(night.endTime),
                "Unedited +1 task must remain associated with its original day");

        ArrayList<String> reversed = new ArrayList<>();
        for (int i = movedDay.size() - 1; i >= 0; i--) reversed.add(movedDay.get(i).id);
        store.reorder(destination, reversed);
        List<TaskStore.Task> reordered = store.listForDate(destination);
        for (int i = 0; i < reordered.size(); i++) {
            check(reordered.get(i).id.equals(reversed.get(i)) && reordered.get(i).position == i,
                    "Manual day order and position must persist at index " + i);
        }
        String snapshot = tasksJson(store);
        ArrayList<String> stale = new ArrayList<>(reversed);
        stale.remove(stale.size() - 1);
        boolean badOrderRejected = false;
        try { store.reorder(destination, stale); }
        catch (IllegalArgumentException expected) { badOrderRejected = true; }
        check(badOrderRejected, "A stale reorder must be rejected rather than losing a task");
        check(tasksJson(store).equals(snapshot), "Rejected reorder must leave every task untouched");

        String exported = store.exportJson();
        JSONObject exportedRoot = new JSONObject(exported);
        check(exportedRoot.getInt("schemaVersion") == 1, "Backup must declare the supported schema");
        check("Europe/Moscow".equals(exportedRoot.getString("timezone")), "Backup must keep source timezone");
        TaskStore restored = new TaskStore(isolatedContext("roundtrip"));
        int imported = restored.importJson(exported);
        check(imported == 14 && restored.getAll().size() == 14, "Backup merge must reuse IDs instead of duplicating seed entries");
        check(tasksJson(restored).equals(snapshot), "Backup roundtrip must restore exact task state and order");
        check(restored.importJson(exported) == 14 && restored.getAll().size() == 14,
                "Repeated imports must be idempotent by ID");

        JSONObject malformed = new JSONObject(exported);
        JSONArray malformedTasks = malformed.getJSONArray("tasks");
        malformedTasks.getJSONObject(0).put("notes", "This must never be committed");
        malformedTasks.getJSONObject(malformedTasks.length() - 1).put("date", "2026-02-30");
        boolean malformedRejected = false;
        try { restored.importJson(malformed.toString()); }
        catch (Exception expected) { malformedRejected = true; }
        check(malformedRejected, "An invalid task at the end of an import must reject the whole file");
        check(tasksJson(restored).equals(snapshot), "Invalid import must not partially update earlier valid tasks");

        JSONObject duplicate = new JSONObject(exported);
        duplicate.getJSONArray("tasks").put(duplicate.getJSONArray("tasks").getJSONObject(0));
        boolean duplicateRejected = false;
        try { restored.importJson(duplicate.toString()); }
        catch (Exception expected) { duplicateRejected = true; }
        check(duplicateRejected && tasksJson(restored).equals(snapshot), "Duplicate incoming IDs must reject atomically");

        String markdown = restored.exportMarkdown();
        check(markdown.contains(firstDate) && markdown.contains(lastDate), "Markdown export must contain the rebased demo week dates");
        check(markdown.contains("50%") && markdown.contains("Выполнена половина."), "Markdown export must include fractional status and notes");
        check(markdown.contains("+1") && markdown.contains("Europe/Moscow"), "Chat export must explain nights and Moscow timezone");
        check(markdown.contains("# Расписание и выполнение"), "Markdown export must include its general plan heading");

        TaskStore empty = new TaskStore(isolatedContext("delete-all"));
        Context emptyContext = lastIsolated;
        for (TaskStore.Task task : empty.getAll()) empty.delete(task.id);
        check(empty.getAll().isEmpty(), "Deleting all tasks must produce a valid empty database");
        check(new TaskStore(emptyContext).getAll().isEmpty(), "Reopening an intentionally empty database must not silently reseed the demo");
    }

    private Context lastIsolated;

    private Context isolatedContext(String purpose) {
        File directory = new File(getTargetContext().getCacheDir(), "planner-selftest/" + purpose + "-" + UUID.randomUUID());
        if (!directory.mkdirs()) throw new IllegalStateException("Cannot create isolated test directory");
        lastIsolated = new ContextWrapper(getTargetContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getDatabasePath(String name) { return new File(directory, name); }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory) {
                return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory);
            }
            @Override public SQLiteDatabase openOrCreateDatabase(String name, int mode, SQLiteDatabase.CursorFactory factory,
                                                                 DatabaseErrorHandler handler) {
                return SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).getPath(), factory, handler);
            }
            @Override public boolean deleteDatabase(String name) { return SQLiteDatabase.deleteDatabase(getDatabasePath(name)); }
        };
        return lastIsolated;
    }

    private static TaskStore.Task find(List<TaskStore.Task> tasks, String id) {
        for (TaskStore.Task task : tasks) if (task.id.equals(id)) return task;
        throw new AssertionError("Missing task " + id);
    }

    private static boolean contains(List<TaskStore.Task> tasks, String id) {
        for (TaskStore.Task task : tasks) if (task.id.equals(id)) return true;
        return false;
    }

    private static String tasksJson(TaskStore store) throws Exception {
        return new JSONObject(store.exportJson()).getJSONArray("tasks").toString();
    }

    private void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        assertions++;
    }
}
