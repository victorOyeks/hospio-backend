# My profile & security

The Profile screen (`/dashboard/profile`, hotel app and admin portal) for every signed-in person: their details and photo,
password, where they are signed in, 2-step sign-in with an authenticator app, and which emails they get. Every route here
acts on **the caller's own account only** (the user id comes from the access token, never from the path or body); the one
exception is reading someone's photo, below.

Web: `hotel-internal-system-admin-FE`, screen `packages/ui/src/screens/profile.tsx`, QA script `docs/qa/profile.md` there.

## Details

`GET /identity/users/me` (unchanged fields plus): `language` (`en`, `yo`, `ha`, `ig`, `pcm` or null), `photoVersion`
(number or null: changes whenever the photo does; null means no photo), `passwordChangedAt` (null until the first change
after this feature), `jobTitle`.

`PUT /identity/users/me` `{ fullName?, phone?, language? }` → the new `me`. Only what is sent changes.

- Full name: two or more characters with at least two letters (`Phones.meaningfulName`), at most 150. 400 otherwise.
- Phone: `Phones.clean` (a real number, 10-15 digits, the last ten not all one digit). An empty string clears it. A number
  another account already uses (compared on the last ten digits) is **409** "This phone number is already in use on another
  account", as at sign-up. A changed phone is no longer "verified".
- Language: one of the codes above; empty clears it.
- Email is not editable here ("Ask your manager to change your email"). There is no preferred-name field.

## Photo

- `POST /identity/users/me/photo` (multipart, part `file`) → the new `me`. JPEG or PNG only (the bytes are decoded; a file
  that is not really an image is refused), at most 2 MB (400 "The photo is too big · keep it under 2 MB"). The photo is
  cropped to the middle square and resized to 256×256 JPEG, which also drops EXIF (location) data.
- `DELETE /identity/users/me/photo` → the new `me`; the stored file is deleted.
- `GET /identity/users/{id}/photo` → `image/jpeg`. Allowed for the person themselves, anyone of the same business, and
  platform staff; 404 when there is no photo (or the caller may not see it). Stored through `ObjectStorage`
  (`STORAGE_TYPE` / `STORAGE_DIR`, key `identity/<business|platform>/photos/<user>-<version>.jpg`).
- `photoVersion` is on `me` and on every person in `GET /identity/users` (the Staff, Users and Roles lists), so the web
  shows `/api/people/<id>/photo?v=<photoVersion>` and falls back to initials when it is null.

## Password

`POST /identity/auth/change-password` `{ currentPassword, newPassword (8-72) }` → an `AuthResponse` for **this** session
(fresh access and refresh token, same session id). Every other session of the person ends (their refresh tokens are revoked
and their access tokens refused, see the window below). A wrong current password is 400 "Current password is wrong".
`passwordChangedAt` is set. The temporary-password flow (`/reset-password`) uses the same call.

## Sessions

A **session** is one sign-in: a password sign-in, an accepted invite, a sign-up or a PIN unlock on a shared device. Its
refresh tokens rotate on every refresh but keep one `session_id`; the access token carries it as the claim `sid`.

- `GET /identity/users/me/sessions` → `[{ id, kind: "PASSWORD" | "PIN", device, deviceName, location, signedInAt,
  lastUsedAt, current }]`, newest use first. `device` is read from the browser's user agent ("Chrome on macOS", "Safari on
  iPhone"); the web forwards it as `X-Client-Agent` on sign-in, accept-invite, sign-up, PIN unlock and 2-step. `deviceName`
  is the shared device's name for PIN sessions (the web shows "Bar tablet · PIN"). `location` is null: no IP address is
  stored or shown, and there is no geo lookup yet. `lastUsedAt` is the last sign-in or token refresh (refreshes happen
  about hourly while the app is open), not every request. `current` marks the caller's own session.
- `DELETE /identity/users/me/sessions/{id}` → ends that session. 404 when it is not one of the caller's live sessions; 400
  for the current one (use Sign out).
- `POST /identity/users/me/sessions/sign-out-others` → `{ ended, session: AuthResponse }`: ends every other session and
  returns fresh tokens for this one.
- `POST /identity/auth/logout` is unchanged: it ends **every** session of the person (docs/shared-devices.md relies on it).

**When an ended session really stops.** Its refresh token is revoked in the same transaction, so its next refresh is 401
"Session expired. Sign in again". Its access token is refused through `AccessRevocations`: identity publishes an
`AccessChangedEvent` (`SESSIONS_ENDED`) for the person and every service then refuses that person's access tokens issued
before that second with 401 "Your access has changed". The sessions that are still live refresh once (the web does this by
itself and stays on the page) and carry on; the ended one fails to refresh and lands on sign-in (or the lock screen on a
shared device). The followers poll Kafka every second, so in practice this is a few seconds. If Kafka is down the access
token keeps working until it expires: at most `JWT_EXPIRATION_MS` (60 minutes). A refresh racing the sign-out cannot
outlive it: both take a lock on the person's account row, and a refresh of a session marked ended is refused.

## 2-step sign-in (authenticator app, RFC 6238)

TOTP with SHA-1, 6 digits, 30-second steps, one step of clock drift either way; a code is accepted once (the last used step
is kept). The secret is 160 random bits, stored AES-GCM encrypted (`hospio.auth.two-step-key` / env `TWO_STEP_KEY`; when
unset the key is derived from `JWT_SECRET`, so changing that secret means everyone turns 2-step on again). 10 recovery codes
(`xxxxx-xxxxx`, 50 bits each) are stored as SHA-256 hashes and work once each.

- `GET /identity/users/me/two-step` → `{ enabled, enabledAt, recoveryCodesLeft, required }`. `required` is always false
  today: `hospio.auth.two-step.required-roles` (e.g. `OWNER,MANAGER`) is read and reported but **not enforced** yet.
- `POST /identity/users/me/two-step/setup` → `{ secret, otpauthUri, qrSvg }`. Starts (or restarts) setup; 2-step is not on
  until confirmed. 400 when already on.
- `POST /identity/users/me/two-step/enable` `{ code }` → `{ recoveryCodes: [10] }` (shown once). 400 "That code is wrong"
  when the code doesn't match the secret from setup.
- `POST /identity/users/me/two-step/disable` `{ password, code }` → status. The code may be an app code or a recovery code.
- `POST /identity/users/me/two-step/recovery-codes` `{ code }` → `{ recoveryCodes }`: new codes, the old ones stop working.
- **Sign-in.** `POST /identity/auth/sign-in` with the right password, for a person with 2-step on, answers `accessToken:
  null`, `refreshToken: null`, `twoStepRequired: true` and `twoStepToken` (valid 5 minutes, one per person: a new password
  sign-in replaces it). `POST /identity/auth/sign-in/two-step` `{ twoStepToken, code }` → the normal `AuthResponse`. An app
  code or a recovery code (any input that is not 6 digits is tried as a recovery code).
- **Wrong codes** count with wrong passwords on the same counter as sign-in (`failed_sign_in_attempts`, 5 →
  `sign_in_locked_until` for 15 minutes); a right password does not reset the counter while a code is still owed. Wrong codes
  when turning 2-step off or making new codes count the same way.
- **PIN unlock skips 2-step.** A shared device is set up through a full sign-in (with 2-step when the person setting it up has
  it on), and a PIN only switches between that hotel's people on it. Platform "view as owner" tokens are issued by the
  platform and are not affected.

## Notification preferences

`GET /identity/users/me/notification-preferences` → `{ inApp: true, email: { bookings, approvals, money, stock,
maintenance, staff, reports }, sms: "COMING_SOON", whatsapp: "COMING_SOON" }`. Every email category defaults to `true`.

`PUT /identity/users/me/notification-preferences` `{ email: { <category>: boolean, ... } }` → the same shape. Unknown
categories are 400. In-app notices are always on and can't be turned off.

**For email delivery (docs/email.md):** messaging-service sends whatever it is handed and does not filter by person, so
the **producer** checks the person's preference before building a categorised email. Identity's internal contact lookups
(`GET /internal/users/{id}/contact`, `/internal/users/contacts`, `/internal/users/with-role`) carry
`emailPreferences: { bookings: true, approvals: true, money: true, stock: true, maintenance: true, staff: true, reports: true }`.
A missing map or category means "send". Account emails (invites, temporary passwords, password resets) are always sent.

| Category      | What it covers                                                                  |
|---------------|---------------------------------------------------------------------------------|
| `bookings`    | New, changed and cancelled bookings, arrivals and departures                    |
| `approvals`   | Something waiting for my decision, and the answer to something I asked for     |
| `money`       | Payments, refunds, tills, day close and banking                                 |
| `stock`       | Low stock, requests, deliveries and counts                                      |
| `maintenance` | Work orders, rooms out of order and preventive jobs                             |
| `staff`       | Leave, shift swaps, rota and people changes                                     |
| `reports`     | Daily and monthly reports and the night audit                                   |

## Activity log

Profile, photo and preference changes are not logged. Ending sessions and changing the password publish
`AccessChangedEvent` with `change` `SESSIONS_ENDED` (detail "password changed", "signed out other devices" or "signed out one
device"). Turning 2-step on or off is not logged (it would also make every live token of the person stale).

## Limits

- No location: no IP is stored and there is no geo lookup (a `CF-IPCountry` header would be shown if a proxy ever adds one).
- `lastUsedAt` moves on sign-in and refresh (about hourly while the app is open), not on every request.
- `POST /auth/logout` still ends every session of the person (the web's Sign out); ending only this device is the lock on a
  shared device.
- The body log masks `code`, `password`, `twoStepToken`, `secret`, `qrSvg` and `otpauthUri`; the web's staging API log
  (`NEXT_PUBLIC_API_LOG`) records whole responses, so it must stay off in production.
- 2-step is never required yet (`hospio.auth.two-step.required-roles` is reported only). No SMS or WhatsApp codes.
- Photos are stored once per version; replacing or removing one deletes the old file. A JPEG or PNG only (no HEIC/WebP).
