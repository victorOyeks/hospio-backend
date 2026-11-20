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
| POST | `/identity/auth/handoff/exchange` | public (the code is the credential); counted with sign-in by the per-address limit | `{code, target}` → `{accessToken, refreshToken, expiresIn, roles, mustChangePassword, next}`. 401 unknown, expired, already used, other app's code, source session signed out; 403 account paused or lost the role meanwhile |

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

## Sign-out

Sign out on either app ends every session of the person (as before: all refresh tokens revoked) and now also publishes
`access-changed` (`SESSIONS_ENDED`), so every service refuses the person's access tokens issued before that second. The other
app, still holding a live access token, gets 401 on its next page, its refresh is refused, and it shows sign-in. A shared
device signs out by locking, unchanged.

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
