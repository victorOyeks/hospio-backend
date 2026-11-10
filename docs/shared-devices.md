# Shared devices and PIN switching

One tablet at the front desk, the bar or the kitchen, used by several people in a shift. The normal sign-in page stays the
only sign-in page (also when the web app is wrapped as a tablet app). A PIN is only a quick way to switch person on a device
that someone already set up through a normal sign-in, and every action is recorded under the person who unlocked.

Web: `hotel-internal-system-admin-FE` (hotel app), QA script `docs/qa/shared-devices.md` there.

## Rules

- **Setting up.** Someone signed in normally with **Full** access at the hotel, a **manager** of it or the **owner** picks
  "Use this device as a shared device" in the account menu, names it, picks the hotel and the roles it is for (any hotel role
  but the owner; a Full-access person only the roles they hold at Full there) and the auto-lock minutes (1-60, default 5).
  The device gets a random device token, shown once and kept by the web in the httpOnly cookie `hms.device` (400 days). Only
  its SHA-256 is stored. The person who set it up is then locked out of the device (only their session on this device ends).
- **The token grants nothing by itself.** With it a device can read its lock screen (`GET /auth/device`) and unlock with a
  PIN. Every other call needs the person's own access token.
- **Lock screen.** Hotel name, device name and a tile per person who has a PIN, an active account, and an active (not paused,
  not ended) role at the device's hotel that the device is for. Owners never appear. A tile shows when that person's PIN is
  locked. The search box filters the tiles on the device.
- **Unlock.** `POST /auth/pin-sign-in` with the device token. The person must belong to the device's business, hold a fitting
  role at the device's hotel, and be active: a paused or suspended person is refused exactly as at sign-in (403 with the same
  sentence). The session is a normal one: an access token with the person's roles and levels **at that hotel only** (the
  token's `hotels` is that hotel) and a refresh token that names the device. Everything they do is under their own name.
- **Same rules as sign-in.** PIN sessions follow `AccessRevocations`: a role given, changed, paused or removed makes the
  token stale (401 "Your access has changed") and the web refreshes. A device session refreshes only while the device is
  still set up and the person still holds a role that fits it; otherwise 401 and the web shows the lock screen.
  Suspending or pausing someone revokes all their refresh tokens (the PIN ones too) and the access-changed event refuses
  their live tokens. `POST /auth/logout` (signing out on any device) also revokes every refresh token of the person.
- **Locking.** "Lock" in the header, or the device's auto-lock after that many idle minutes (a warning toast 30 seconds
  before). Locking revokes **only this session's** refresh token (`POST /auth/device/lock`), never the person's other
  sessions. Unsaved form state may be lost.
- **PINs.** Set or changed by the person after a normal sign-in, from My profile & security, with their password. 4-6 digits,
  stored as BCrypt. Refused: one digit repeated (1111), a straight run up or down (1234, 4321, 56789), the last digits of
  their own phone, and the PIN they have now. No PINs for the owner or H.M.S staff.
- **Wrong PINs.** Counted per person, not per device. Each wrong PIN says how many tries are left; the 5th locks that
  person's PIN for 15 minutes (`hospio.auth.pin-max-attempts`, `hospio.auth.pin-lock-minutes`) and the managers of the
  device's hotel get an in-app notice (`hospio-notice`, roles MANAGER). The device is not locked; others keep unlocking.
  While locked the answer is "Your PIN is locked for N more minutes …". Setting a new PIN or a manager's reset lifts it.
- **Reset.** A manager (or the owner) resets a person's PIN from Staff: the PIN is cleared and the lock lifted; the person
  is told in-app and sets a new one after their next normal sign-in. Who may reset whom follows the staff-account rules
  (a manager not a manager; nobody the owner; nobody themselves).
- **Managing devices.** Hotel setup → Shared devices (managers of that hotel, the owner): name, roles, last used, last
  person, auto-lock. Rename, change the roles or the auto-lock, or remove. Removing revokes the device and every session
  opened on it; the device lands on the normal sign-in page at its next request (the web checks the device on each page and
  every 60 seconds on shared devices).
- **Activity log.** Topic `hospio-shared-device` (`SharedDeviceEvent`, in `KafkaTopics.ALL`, so platform-service logs it under
  the device's hotel): `SET_UP`, `CHANGED`, `REVOKED`, `LOCKED` (button or idle), `PIN_FAILED` ("2 of 5"), `PIN_LOCKED`,
  `PIN_SET`, `PIN_RESET`. A PIN unlock is a `UserSignedIn` with method "PIN on Bar tablet". Before anyone is signed in, the
  person whose PIN was tried stands as the actor.
- **Tenancy.** `identity.shared_devices` has forced row-level security with the standard `tenant_rw` policy on
  `business_id, hotel_id` (`app.protect`). The lock screen, unlock, lock and refresh run as the system, because nobody is
  signed in yet; they check the device, business and hotel themselves.

## Routes

Behind the gateway (`https://api-staging.hospio360.com`). `X-Device-Token` is the device token.

| Method | Path | Who | What |
|---|---|---|---|
| POST | `/identity/devices` | Full access at the hotel, manager, owner | `{name 2-40, hotelId, roles[], autoLockMinutes? 1-60}` → `{device, deviceToken}` (token once) |
| GET | `/identity/devices?hotelId=` | manager, owner | the hotel's devices (X-Hotel-Id hotel when no `hotelId`): `id, name, hotelId, hotelName, roles, autoLockMinutes, lastUsedAt, lastUserName, createdByName, createdAt` |
| PUT | `/identity/devices/{id}` | manager, owner | `{name?, roles?, autoLockMinutes?}` |
| POST | `/identity/devices/{id}/revoke` | manager, owner | removes the device and ends its sessions |
| GET | `/identity/auth/device` | device token | lock screen `{deviceId, name, hotelId, hotelName, roles, autoLockMinutes, people[{userId, fullName, jobTitle, roles, lockedUntil}]}`; 401 unknown or removed |
| POST | `/identity/auth/pin-sign-in` | device token | `{identifier (user id; phone or email work), pin}` → the usual `AuthResponse`. 401 wrong PIN / locked / unknown device, 403 paused, suspended, owner or no fitting role |
| POST | `/identity/auth/device/lock` | device token | `{refreshToken, reason: button or idle}` ends that session |
| POST | `/identity/auth/set-pin` | signed in | `{pin, password}`; 400 with the reason for a refused PIN or a wrong password, 403 owner or H.M.S |
| POST | `/identity/users/{id}/reset-pin` | manager, owner | clears the PIN and its lock; 400 when there is none |
| GET | `/identity/users/me` | signed in | now also `hasPin` |

`/auth/pin-sign-in` existed before (phone or email plus PIN, anywhere): it now needs a device token, so a PIN only ever works
on a device a manager or Full-access person set up at the person's hotel.

## Limits

- An access token lives an hour. Removing a device ends its refresh tokens at once; an access token already issued on it
  keeps working against the APIs until it expires, but the web drops it on its next page or check (60 s).
- The lock screen lists everyone who fits the device; a very large hotel gets a long list (the search box filters it).
- Unsaved form state is lost when the device locks.
- PINs are 4-6 digits: a wrong-try lock of 15 minutes after 5 tries is what keeps them safe, so the lock is per person.

## Progress

- [x] Migration `005-shared-devices.sql`: `identity.shared_devices` (RLS), `refresh_tokens.device_id`
- [x] Device setup, list, change, revoke (`SharedDeviceController`, `SharedDeviceService`)
- [x] Lock screen, PIN unlock bound to the device, device lock, device-aware refresh (`AuthService`)
- [x] PIN rules (`PinRules`), set with password, not the old PIN; manager reset (`StaffAccountService.resetPin`)
- [x] Wrong-PIN lock with tries left and the managers' notice
- [x] Activity log events (`SharedDeviceEvent`, topic `hospio-shared-device`)
- [x] Unit tests: `AuthServiceTest`, `PinRulesTest`, `SharedDeviceServiceTest`, `StaffAccountServiceTest`; schema IT checks the policy
- [x] Web: setup from the account menu, lock screen and PIN pad, Lock button, idle lock with warning, PIN in profile, reset in Staff, Hotel setup → Shared devices
- [x] Staging API and browser sweep (`docs/qa/shared-devices.md` in the web repo)
