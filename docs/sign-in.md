# Sign-in across the two web apps (single sign-in)

Since 20 Nov 2026. One front door, two apps behind it: anyone signs in at the hotel app (web-staging.hospio360.com) or at the
admin console (admin-staging.hospio360.com) with the same flow (email or phone, password, 2-step code), and the account type
decides where they work. The apps are not merged and keep their own sessions; nothing is shared between the two hosts but a
one-time code carried in one redirect.

| Account | Roles | Works in |
|---|---|---|
| Hotel owner, manager, staff | OWNER, MANAGER, FRONT_OFFICE … CASHIER | hotel app (`apps/hotel`) |
| Hospio360 staff | SUPER_ADMIN, SUPPORT, FINANCE_ADMIN | admin console (`apps/admin`, `/admin` area) |
| Marketer | MARKETER | marketer console (same admin app, `/marketer` area) |

The marketer console lives inside the admin app, so "hand over to the marketer console" means "hand over to the admin app";
the role sends the marketer to `/marketer` there.

## No self sign-up (21 Nov 2026)

Business accounts are set up by the Hospio360 team after a demo (`docs/onboarding.md`, "Applications"). The hotel app's sign-in
page has no "Create your account" link; it offers "New to Hospio360? Book a demo", which opens the landing site's
`/book-a-demo`. The hotel app's `/sign-up` redirects there (keeping a marketer's `?ref=`), and `POST /identity/auth/sign-up`
answers 410 "Hospio360 accounts are set up by our team — book a demo at hospio360.com/book-a-demo" while
`SELF_SIGNUP_ENABLED` is off (the default everywhere). Everyone else arrives by invite exactly as before: the owner's handover
invite, staff invites and marketer invites (`/accept-invite?token=`), then signs in here. Owners who signed up before the switch
sign in as usual and finish their own setup wizard.

## The flow

1. The person signs in on either app (`AuthForm`, `packages/ui/src/screens/auth-form.tsx` in hospio-frontend, the same
   component on both). 2-step and the staging backup code (`159753`) work as before.
2. The app's sign-in action (`login` / `loginWithCode`) looks at the roles. This app's account: the session cookies are set
   here as before (first-password change, terms, onboarding redirects, PIN prompt, role picker all unchanged).
3. The other app's account: **no session is kept here.** The action calls `POST /identity/auth/handoff`
   `{target, next, endSession: true}` with the fresh access token; identity ends that sign-in's session and answers the target's
   `https://<target>/auth/handoff?code=…` (the address comes from `hospio.apps.hotel-url` / `hospio.apps.admin-url`, never
   from the request). The form shows "This is a hotel account. Taking you to your hotel app" ("… Hospio360 staff account.
   Taking you to the admin console", "… marketer account. Taking you to the marketer console") and navigates there with
   `location.replace` after about a second.
4. The target's `/auth/handoff` route handler (`packages/api/src/handoff.ts`) swaps the code server-side
   (`POST /identity/auth/handoff/exchange {code, target}`) for a new session of its own, sets its cookies and answers a `303` to
   the `next` page, the dashboard, or `/reset-password` for a temporary password. The code is gone from the address bar at once
   (it is never rendered); the response carries `Referrer-Policy: no-referrer` and `Cache-Control: no-store`.
5. Any refusal (unknown, expired, used, other app's code) lands on the target's sign-in page with "That sign-in link has expired
   or was already used. Sign in again." Nothing retries.

Deep links: `?next=` (or the older `?from=`) on either sign-in page is kept through the hand-over. It must be a relative
`/dashboard…` path (checked by `safeAppPath` on both apps and by identity's own pattern); anything else is dropped and the
person lands on the dashboard. No redirect target is ever taken from user input.

## API (identity-service)

| Method | Path | Who | Contract |
|---|---|---|---|
| POST | `/identity/auth/handoff` | signed in | `{target: "hotel"\|"admin", next?: string, endSession?: boolean}` → `{url, target, expiresIn}`. 400 unknown target; 403 an app the account has no role on, a support "view as owner" token, a PIN session on a shared device, an account not active; 401 a token without a session |
| POST | `/identity/auth/logout` | signed in | ends this browser's session family (both apps), not other devices |
| POST | `/identity/auth/logout-everywhere` | signed in, not a support view | ends every session of the person, `SESSIONS_ENDED` |
| POST | `/identity/auth/handoff/exchange` | public (the code is the credential); counted with sign-in by the per-address limit | `{code, target}` → `{accessToken, refreshToken, expiresIn, roles, mustChangePassword, next}`; the new session joins the asking browser's session family. 401 unknown, expired, already used, other app's code, source session signed out; 403 account paused or lost the role meanwhile |

Configuration: `hospio.apps.hotel-url` (`HOTEL_APP_URL`), `hospio.apps.admin-url` (`ADMIN_APP_URL`) are the allow-list of
target origins per environment (only scheme, host and port are used); `hospio.auth.handoff-seconds` (default 30, capped at 60).

## Security properties of the code

- 32 random bytes from `SecureRandom` (256 bits), base64url; only its SHA-256 is stored (`identity.handoff_codes`, platform-only
  RLS like `refresh_tokens`).
- Bound to one person (`user_id`) and one target app; the exchange signs in only that person, only on that app. A code shown to
  the wrong app is refused **and burnt**.
- Lives 30 s (never more than 60 s). Single use: the exchange marks it used in the database (`update … where used_at is null`)
  before anything else, so a replay or two racing requests get one session at most; every later try is a 401 and is logged.
- The person must still be active and still hold a role for the target app at the exchange; a code issued from a session that
  was signed out before the exchange is refused.
- Support "view as owner" tokens and shared-device PIN sessions can't ask for a code (they never leave their app or device).
- The target URL comes from server configuration; the web app checks it is an https `/auth/handoff` address before following it.
- Expired codes are deleted nightly (03:25 Lagos). The code does appear once in nginx's access log line for `/auth/handoff`;
  it is single-use and expires in 30 s.

## Sign-out (owner decision, 20 Nov 2026)

**Session family.** A sign-in starts a family (`refresh_tokens.family_id`, = the first session id); every refresh keeps it; a
hand-over code records the asking session's family (`handoff_codes.family_id`) and the session the target app opens with it joins
that family. So one browser's sessions in both apps are one family, even after the wrong-app sign-in's own session ended. Older
rows have no family: their session id is their family.

- **Sign out** (`POST /identity/auth/logout`, the existing button in both apps): ends **this browser's family only**: its refresh
  tokens are revoked and marked ended, and an access-changed event with `change: SESSION_FAMILY_ENDED` and `sessionIds` (the
  family's sessions) makes every service refuse the access tokens of exactly those sessions (`AccessRevocations` keeps ended
  sids; `JwtFilter` checks the token's `sid`), whenever they were issued. The other app in the same browser drops to sign-in on
  its next page; the person's other devices and shared-device PIN sessions keep working. A token without a `sid` (from before
  sessions) can't be placed in a family and signs out everywhere. A support "view as owner" token ends with its tab and signs
  nobody out.
- **Sign out everywhere** (`POST /identity/auth/logout-everywhere`, profile menu → "Sign out everywhere" behind a confirm, in the
  hotel app and the admin/marketer console): every session of the person ends on every device and both apps, plus
  `SESSIONS_ENDED` (every access token issued before that second is refused). Shared devices go back to their lock screen.
  Refused (403) for a support view token.
- Forced sign-outs are unchanged: a role change, pause or removal, a password change (other sessions end) and "End session" in
  My profile work as before.

## Wrong-app sessions and loops (the two bugs of 20 Nov 2026)

Before this, a session holding only the other app's roles was redirected by the middleware to the other app's `/auth?switch=`,
and the sign-in form did the same after a wrong-app sign-in, which left a cookie of the wrong account type behind on the app
where the person had signed in. With such a cookie on both hosts (a support account tried on web-staging, a hotel account on
admin-staging, in one browser), each app's middleware sent `/auth` → `/dashboard` → the other host and back, endlessly: Chrome
gave up with "redirected you too many times" on admin-staging (reported as "too many requests") and on web-staging (reported as
the hotel app crashing when a platform admin signs in). Now:

- a wrong-app sign-in never sets a cookie (step 3 above);
- the middleware never redirects to the other host; a leftover wrong-app cookie goes to `/api/session/handoff`, which deletes
  it and hands it over once; a second automatic hand-over within 20 s (`hms.handoff` guard cookie) ends at sign-in;
- the hand-over target keeps a session only when it holds that app's roles, so it can never send the browser back;
- the dashboard shell refuses to render for an account of the other app (no hotel or business is loaded for a platform
  account).

Related: sign-in calls from the Next servers now forward the browser's address as the first `X-Forwarded-For` hop. Identity's
per-address sign-in limit (`hospio.auth.rate-limit-per-minute`, 30) had been counting every sign-in from both apps against the
app server's own address.

QA: hospio-frontend `docs/qa/85-single-sign-in.md`.

## Admin security settings (21 Nov 2026)

Admin console → Settings → Security (SUPER_ADMIN saves it; platform-service `security` group, `SecuritySettings`). identity-service
enforces two of its fields on **Hospio360 staff**: anyone holding a platform role (SUPER_ADMIN, SUPPORT, FINANCE_ADMIN;
`AdminSecurityPolicy.isPlatformStaff`). Marketers are not staff here (they hold no platform role, their token is scope
`marketer`, and the marketer console is their own area), so a MARKETER-only account is never affected; someone who is both a
marketer and support is staff. Hotel accounts (owner, manager, staff, PIN sessions on shared devices) are never affected.

**How identity reads them.** `GET platform /internal/settings/security` (internal key) through `PlatformClient`, kept for
`hospio.auth.admin-security.cache-seconds` (30). Fail safe: when platform can't be read the last value read stays in force (a
"require 2-step" that is on is never dropped because platform is down), retried after `retry-seconds` (10). Before the first
successful read after a restart, the defaults apply: 2-step not required, 12 hours (a fresh database is the same).

**Require 2-step sign-in for every admin** (`require2Step`, default off).

- A staff member without 2-step who signs in with the right password (or arrives by a hand-over, or renews a session) gets
  tokens limited to setting it up: the access token carries `setup: "TWO_STEP"` and sign-in / hand-over exchange answer
  `twoStepSetupRequired: true`.
- Server side, `JwtFilter` (common, every service) refuses such a token with **403** "Set up 2-step sign-in to continue.
  Hospio360 requires it for every admin" (header `X-Hospio-Two-Step-Setup: required`) on every path except
  (`TwoStepSetupPaths`): `GET /identity/users/me`, `/identity/users/me/two-step` and its `/setup` and `/enable`,
  `/identity/auth/change-password`, `/identity/auth/handoff`, `/identity/auth/logout`, `/identity/auth/logout-everywhere`
  (and the public `/identity/auth/refresh`).
- The admin app's middleware sends a limited session to `/setup-two-step` (after `/reset-password` while the password is still
  temporary), and away from it once it is a full session. The screen reuses My profile's setup (QR code + key, a code, the
  recovery codes once); "I've saved them, continue" renews the session (`POST /auth/refresh`), which now comes back full
  because the person has 2-step, and opens the dashboard.
- Whether a session is limited is decided every time tokens are issued (sign-in, refresh, hand-over, password change), not
  stored. So turning the setting on reaches people already signed in within the hour (their next refresh); turning it off
  frees a limited session at its next refresh.
- Nobody can be locked out by it: the limited session always allows setting 2-step up. The super admin turning it on without
  2-step is asked first ("You'll be asked to set it up on your next sign-in", or "Set up now" in My profile).
- The bootstrap super admin's first sign-in still works with it on: temporary password → `/reset-password` (change-password is
  allowed and reissues a still-limited session) → `/setup-two-step` → dashboard.
- Someone who turns 2-step off in My profile while it is required is limited again at their next refresh and sets it up anew
  (how one moves to a new phone). `GET /users/me/two-step` reports `required: true` for staff while the setting is on.

**Session length** (`sessionHours`, 4, 12 or 24; default 12; platform refuses other values with 400).

- A staff session lasts `sessionHours` from its sign-in (`refresh_tokens.signed_in_at`, kept by every refresh): the refresh
  token expires then, the access token never outlives it (its lifetime is cut to what is left, `expiresIn` says so), and a
  refresh at or after it is refused with 401 "Your session has ended. Sign in again".
- A hand-over keeps the family's sign-in time (the earliest live session of the family it joins), so hopping between the apps
  never lengthens a session; a wrong-app sign-in's handed-over session starts at that sign-in. Sign out, sign out everywhere,
  ending a session and forced sign-outs work as above.
- Changing the setting applies to sessions already open at their next refresh: shortening it to 4 hours ends a 5-hour-old
  session within the hour.
- Hotel sessions are unchanged (`hospio.auth.refresh-days`, 30).

**Only allow admin sign-in from office IPs** (`adminIpAllowlist`): **not implemented.** There is no IP list and nothing reads it;
the switch was removed from the admin console on 21 Nov 2026 (the team is remote). The field stays in `SecuritySettings` so
saved rows and older clients keep working.

Tests: `TokenIssuerAdminSecurityTest`, `AdminSecurityPolicyTest` (identity), `JwtFilterTwoStepSetupTest` (common),
`SecuritySettingsTest` (platform). QA: hospio-frontend `docs/qa/89-admin-security-settings.md`.

## The first super admin on a fresh environment

A new environment has no one to sign in to the admin console. identity-service creates one platform super admin at start-up
for `BOOTSTRAP_ADMIN_EMAIL` (default in application.yml) when no platform SUPER_ADMIN exists, and emails a random temporary
password (TEMP_PASSWORD, through messaging-service) with a link to the admin console sign-in (`ADMIN_APP_URL`). The first
sign-in is the ordinary temporary-password flow: change the password, then set up 2-step. Where a super admin already exists
(staging) it does nothing and logs `Bootstrap super admin not needed`. Details, the re-issue rule and the internal endpoint:
docs/admin-portal.md, "Bootstrap super admin".
