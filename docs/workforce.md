# Workforce: clock, attendance notices and leave

Lives in finance-service, alongside the rota and tills; notices go out as `NoticeEvent`s that messaging-service turns
into in-app notifications. See also `docs/operating-day.md` for the rota, leave and clock endpoints.

## Clock

Staff clock in and out on **My shifts**, and the front desk can also do it from **Today**.

| Endpoint | What it does |
|---|---|
| `POST /workforce/clock-in {note?}` | Starts the clock. |
| `POST /workforce/clock-out {note?}` | Stops it. |
| `GET /workforce/clock/me` | `{clockedIn, since, todayMinutes}` |
| `GET /workforce/clock?date` | Today's entries for the manager: who is on and since when. |

A clock-in counts towards a rota shift when it falls between 3 hours before the shift starts and the end of the shift.
It is **late** when it comes more than the hotel's `STAFF.lateMinutes` after the start (Hotel setup → Staff & shifts,
15 minutes by default).

## Notices to the managers

All notices use kind `ATTENDANCE`.

| When | Who is told | Text |
|---|---|---|
| A late clock-in | Managers | "Victor Adeyemi clocked in late" · 06:22 · shift 06:00–14:00 · 22 min late |
| Every clock-in, when `STAFF.notifyEveryClockIn` is on (off by default) | Managers | "Victor Adeyemi clocked in" · 06:00 · shift … (or "not on today's rota") · the note, if any |
| A rota shift has passed its start plus the late allowance, it hasn't ended, and nobody is clocked in for it | The person and the managers, once | "You haven't clocked in" / "Victor Adeyemi hasn't clocked in" |
| A clock-in is still open 1 hour after its shift ends (14 hours after clocking in when it has no shift) | The person and the managers, once | "You're still clocked in" / "… is still clocked in" |
| A clock-in is still open 24 hours after clocking in | The person and the managers | "… never clocked out": it is closed at the shift's end (12 hours after clocking in if there was no shift), with the note "Closed automatically: no clock-out. Check the hours" |

`AttendanceWatch` runs the last three checks every 10 minutes (`hospio.attendance.check-ms`) for every hotel, as the
system. Each check marks its row with a guarded update (`shifts.not_in_notified_at`, `clock_entries.reminded_at`, the
auto close), so a second run or a second instance never tells anyone twice. Jobs run on one instance at a time anyway
(`common/scheduling`).

## Leave

| Endpoint | Who | What it does |
|---|---|---|
| `POST /workforce/leave {fromDate, toDate, reason?}` | anyone | Sends the request. **Managers and owners** are told: "X asked for leave · dates · reason". |
| `POST /workforce/leave/{id}/withdraw` | the requester | Takes back a PENDING or CHANGE_REQUESTED request. Its status becomes WITHDRAWN, and managers and owners are told "X withdrew a leave request". A decided request can't be withdrawn (400). |
| `POST /workforce/leave/{id}/decide {decision, note?}` | manager, owner | Answers with APPROVED, DECLINED or CHANGE_REQUESTED. Approving cancels the person's rota shifts in those dates. The person is told. |
