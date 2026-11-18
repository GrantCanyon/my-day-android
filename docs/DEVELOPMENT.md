# Development notes

## Responsibilities

| File | Responsibility |
| --- | --- |
| `MainActivity.java` | Day navigation, timeline/list rendering, task editing and file-picker flows |
| `TaskStore.java` | SQLite schema, transactional edits, ordering, progress and JSON/Markdown conversion |
| `ReminderScheduler.java` | Bounded set of inexact local alarms for unfinished timed tasks |
| `ReminderReceiver.java` | Recheck a task before posting its notification and refresh the alarm window |
| `ReminderBootReceiver.java` | Reschedule enabled reminders after reboot |
| `app/src/debug/.../PlannerDataSelfTest.java` | Device-only checks against isolated disposable data |

The app uses the native Android UI toolkit and has no third-party application runtime dependency. Background work uses a local executor; `TaskStore` handles persistence and file conversion.

## Public demonstration data

The bundled schedule contains 14 invented tasks across seven days. On a fresh installation its dates are rebased to the current day in `Europe/Moscow`. This gives a usable example week without publishing a real person's classes, appointments, notes or source documents.

JSON export is a backup and data exchange format; import merges tasks by ID. Markdown export is for reading and is not round-trippable. Before importing an unfamiliar file, keep a JSON backup of data you want to retain.

## Reminder behavior

Reminders are opt-in. Scheduling uses `setAndAllowWhileIdle`, not exact-alarm permission or a background service. Notification permission is checked on Android 13+. The scheduler bounds the number of outstanding alarms and refreshes them when the app opens or an alarm fires. The receiver checks whether a task still exists, is unfinished and has a relevant time before showing a notification.

Delivery remains subject to Android and device battery policies. A successful build or data self-test does not prove notification delivery.

## Validation boundaries

CI assembles a debug APK. The custom debug instrumentation can exercise actual Android SQLite and data conversions using isolated data; it is available to run but has not been executed for this latest public snapshot. It does not automate the full UI or alarm delivery.

Before calling a build device-verified, record the APK version and device/Android version, then check:

1. Launch with fresh app data and confirm that the synthetic sample starts on the current date.
2. Switch both views, edit notes/times, and change progress through 0%, 50% and 100%.
3. Drag to reorder; move a task and confirm it lands at the end of the destination day.
4. Restart the process and confirm data and ordering persist.
5. Export/import JSON and inspect the Markdown export, including invalid-file handling.
6. Run `PlannerDataSelfTest` as described in the [README](../README.md).
7. Enable reminders, grant permission where required, test edits/deletions and check behavior after reboot and under battery restrictions.

These are acceptance criteria, not completed test results. Earlier manual phone testing helped shape the application but does not establish runtime behavior of this snapshot.
