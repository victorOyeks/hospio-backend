# Admin portal · API contract (phases 2 and 3)

Everything the H.M.S admin portal (apps/admin) and the marketer portal show, beyond onboarding (`docs/onboarding.md`). Figma: Platform admin page (3304:3841): Dashboard and Hotel businesses (3304:3908), Fees & billing and Marketers (3305:3841), Marketer portal, Support, Admin team, Settings (3306:3841), Phase 1 Businesses & hotels (3321:327), Phase 3 Edit as H.M.S & governance (3334:1454), Payments (3345:7509).

Conventions: every route needs the gateway prefix (`/platform`, `/identity`, `/payment`, `/tenant`). "platform" means `@access.platform()` (SUPER_ADMIN, SUPPORT, FINANCE_ADMIN); money routes are SUPER_ADMIN or FINANCE_ADMIN (`@access.has('SUPER_ADMIN') or @access.has('FINANCE_ADMIN')`); team and settings are SUPER_ADMIN only. Lists are paged (`page`, `size`, `PagedResponse`) unless noted. Names of people (admins, owners) are resolved server-side where the row needs them, so the web app never joins fixtures.

## Deliveries (cross-cutting, phase 3)

A `Delivery` is a message the platform would send: `{channel: "EMAIL" | "SMS" | "WHATSAPP", to, subject?, text, kind: "TEMP_PASSWORD" | "INVITE" | "PHONE_CODE" | "HANDOVER_PACK" | "PASSWORD_RESET" | "FEE_STATEMENT" | "TICKET_UPDATE", ref?}`. Services build deliveries through `common` `DeliveryComposer` and publish a `DeliveryRequested` event (messaging-service will send them once a provider exists). When `hospio.deliveries.echo=true` (staging: `DELIVERIES_ECHO=true`), the same deliveries are also returned in the response under `deliveries: Delivery[]`. Responses that carry it: identity `UserCreatedResponse` (temp password + invite), `AuthResponse` from sign-up (phone code), `send-code`, reset-password, resend-invite; tenant `HandoverResponse` (owner login + handover pack text); platform fee statement creation and ticket updates. The web apps show every `deliveries` entry in the DeliveryNotice (top-centre, stays until closed, copy buttons) with the line "Email and SMS are not connected on staging; this is what we would have sent."

## Dashboard

| Method | Path | Who | Response |
|---|---|---|---|
| GET | `/platform/admin/overview` | platform | `{businesses: {total, byStage: {LEAD, ONBOARDING, HANDED_OVER, LIVE, AT_RISK, SUSPENDED, CHURNED}}, hotels, rooms, mrr, dueFees, overdueFees, openTickets, onboardingInProgress, readyForHandover, alerts: Alert[] (open, newest 10), recentChanges: PlatformChange[] (10), recentActivity: ActivityEntry[] (10, platform actors), health: {good, watch, atRisk}}` — counts from tenant internal (`GET /tenant/internal/businesses/summary`, new), fees from statements of the current month, tickets and alerts local. |

## Admin team (identity)

| Method | Path | Who | Body → response |
|---|---|---|---|
| GET | `/identity/users?scope=platform&q=` | SUPER_ADMIN | platform accounts (business null, roles SUPER_ADMIN/SUPPORT/FINANCE_ADMIN) as `UserSummary[]` (+ `twoStepEnabled`, `lastSignInAt`, `inviteStatus`) |
| POST | `/identity/users` | SUPER_ADMIN | role SUPER_ADMIN/SUPPORT/FINANCE_ADMIN, no business → `UserCreatedResponse` (+deliveries) |
| POST | `/identity/users/{id}/pause-all?paused=` · `/reset-password` · `/resend-invite` | SUPER_ADMIN for platform accounts | unchanged shapes |
| PUT | `/identity/users/{id}/roles/{grantId}` | SUPER_ADMIN | change a platform role (CHANGE_LEVEL/REMOVE) and `POST /users/{id}/roles` to add one |
| GET | `/identity/users/{id}/activity?page=` | platform | that person's recent activity (from platform activity log via internal call) — optional, phase 3 |

Names for `accountManagerId`, `assignedTo`, `madeBy` are resolved through `GET /identity/internal/users/contacts?userIds=` (exists).

## Fees and billing (platform)

Entities: `FeeRule` (exists), `FeeStatement` (exists; gains `dueOn`, `businessName` snapshot, `lines` JSON `[{hotelId, hotelName, base, fee}]`, `sentAt`), `FeePayment` (new: statementId, amount, paidOn, reference, recordedBy). Status: `DUE`, `PART_PAID`, `PAID`, `OVERDUE` (computed: due and `dueOn` + grace days passed; a nightly job flips the stored status).

| Method | Path | Who | Body → response |
|---|---|---|---|
| GET | `/platform/admin/fees/statements?businessId=&status=&month=&page=` | money | paged `FeeStatement` (+ `businessName`, `payments: FeePayment[]`) |
| GET | `/platform/admin/fees/statements/{id}` | money | `FeeStatement` with lines and payments |
| POST | `/platform/admin/fees/statements/generate` | money | `{month: "2026-11", businessId?}` → `{created, skipped}`: for every LIVE/AT_RISK business (or the one named) without a statement for the month, computes base and fee from its fee model: `PERCENT_OF_PAYMENTS` → approved payments in the month (payment internal `GET /payment/internal/admin/summary?businessId=&from=&to=`, new: `{gross, byHotel: [{hotelId, gross, count}]}`), `PER_ROOM_NIGHT` → rooms × nights sold (reservation internal `GET /reservation/internal/admin/nights?businessId=&from=&to=`, new), `PERCENT_OF_BOOKINGS` → booking value (same reservation call, `value`). `dueOn` = first of next month + `fees.dueDay`. Creates marketer commissions as today. Publishes a `FEE_STATEMENT` delivery to the owner. |
| POST | `/platform/admin/fees/statements/{id}/payments` | money | `{amount, paidOn, reference}` → `FeeStatement`; records the `FeePayment`, recomputes status |
| POST | `/platform/admin/fees/statements/{id}/send` | money | re-issues the `FEE_STATEMENT` delivery → `{deliveries}` |
| GET | `/platform/admin/fees/rules?businessId=` · POST `/platform/admin/fees/rules` | money | unchanged; creating a rule also updates `Business.feeModel/feeValue` through tenant internal `PUT /tenant/internal/businesses/{id}/fee` (new) so the two stay in step |

## Payments (payment-service, `/payment/admin/**`, money roles)

Entities (new, in payment-service): `Settlement` (businessId, businessName, hotelId, provider, periodDate, gross, providerFees, hmsShare, net, status PENDING|SETTLED|FAILED, settledAt, payoutBatchId), `PayoutBatch` (ref `PB-YYYY-NNN`, createdBy, createdByName, status PROCESSING|PAID|PART_FAILED, total, businessCount, paidAt), `FailedPayout` (settlementId, businessId, businessName, amount, bankLabel, reason, attempts, lastTriedAt, resolvedAt). A nightly job (and `POST /payment/admin/settlements/run?date=`) builds one PENDING settlement per business × provider × day from approved card and transfer payments (provider fee % and H.M.S share % from platform settings via platform internal `GET /platform/internal/settings/fees`, new); cash never settles.

| Method | Path | Who | Body → response |
|---|---|---|---|
| GET | `/payment/admin/settlements?businessId=&status=&from=&to=&page=` | money | paged `Settlement` |
| POST | `/payment/admin/settlements/run` | money | `{date?}` → `{created}` |
| GET | `/payment/admin/payouts/batches?page=` · `/payout/batches/{id}` | money | `PayoutBatch` (+ settlements) |
| POST | `/payment/admin/payouts/batches` | money | `{settlementIds? (default: all PENDING)}` → `PayoutBatch` PROCESSING; settlements → SETTLED with `settledAt` null until marked |
| POST | `/payment/admin/payouts/batches/{id}/mark-paid` | money | `{reference, paidOn}` → batch PAID, settlements settledAt set |
| POST | `/payment/admin/payouts/batches/{id}/fail-settlement` | money | `{settlementId, reason, bankLabel}` → settlement FAILED + `FailedPayout`, batch PART_FAILED |
| GET | `/payment/admin/payouts/failed?page=` | money | `FailedPayout[]` open |
| POST | `/payment/admin/payouts/failed/{id}/retry` | money | attempts+1, lastTriedAt; `{resolved: boolean}` (resolved when `?resolve=true`) |
| GET | `/payment/admin/payments?businessId=&hotelId=&status=&from=&to=&page=` | platform | cross-business payment list (view only) |
| GET | `/payment/admin/summary?businessId=&from=&to=` | platform | `{gross, count, byProvider, byHotel}` (the business Money tab) |

## Support (platform)

`SupportTicket` gains `businessName` snapshot, `hotelId`, `openedByName`, `assignedToName`, `lastActivityAt`; new `SupportTicketComment` (ticketId, authorId, authorName, body, internal boolean, createdAt).

| Method | Path | Who | Body → response |
|---|---|---|---|
| GET | `/platform/admin/support/tickets?status=&priority=&businessId=&assignedTo=&q=&page=` | platform | paged `SupportTicket` |
| GET | `/platform/admin/support/tickets/{id}` | platform | ticket + comments |
| POST | `/platform/admin/support/tickets` | platform | `{businessId, hotelId?, subject, body, priority}` → ticket (opened on behalf of a business) |
| PUT | `/platform/admin/support/tickets/{id}/assign` | platform | `{adminId}` → status IN_PROGRESS |
| PUT | `/platform/admin/support/tickets/{id}/priority` | platform | `{priority}` |
| POST | `/platform/admin/support/tickets/{id}/comments` | platform | `{body, internal}` → comment; non-internal comments create a `TICKET_UPDATE` delivery to the opener |
| POST | `/platform/admin/support/tickets/{id}/resolve` | platform | `{resolution}` (exists) → RESOLVED + delivery |
| POST | `/platform/admin/support/tickets/{id}/reopen` | platform | → OPEN |
| GET/POST | `/platform/support/tickets` | hotel users | unchanged (owner/manager open and list their own) |

## Marketers (platform) and the marketer portal

Today `Marketer` is one row per referred business. Rename it conceptually to an agreement and add the person:

- `MarketerProfile` (new): userId (identity, role MARKETER), fullName, email, phone, status PENDING|ACTIVE|ENDED, sharePct (default from settings), months, bankName, bankAccount, bankAccountName, joinedAt, endedAt, referralCode.
- `Marketer` (existing, per business) gains `profileId`; it is created automatically when a business with `marketerId` (= profileId) goes LIVE (listener on `BusinessStageChanged`), with the profile's share and months.
- `Referral` (new): profileId, hotelName, ownerName, phone, email?, city, stage LEAD|CONTACTED|ONBOARDING|LIVE|LOST, note, businessId?, sentAt, updatedAt. When a business is created with `referralId`, the referral gets `businessId` and stage ONBOARDING; LIVE follows the business.
- `Commission` (existing) gains `profileId`, `paidReference`.

| Method | Path | Who | Body → response |
|---|---|---|---|
| GET | `/platform/admin/marketers?status=&q=` | platform | `MarketerProfile[]` (+ `businesses`, `earnedTotal`, `due`) |
| POST | `/platform/admin/marketers/invite` | platform | `{fullName, email, phone, sharePct?, months?}` → creates the identity user (MARKETER) through identity internal, the profile (PENDING → ACTIVE on first sign-in, via `UserSignedIn` listener) → `{profile, login: UserCreatedResponse}` (+deliveries) |
| GET | `/platform/admin/marketers/{id}` | platform | profile + agreements + referrals + commissions |
| PUT | `/platform/admin/marketers/{id}` | platform | `{sharePct, months, status}` |
| GET | `/platform/admin/referrals?profileId=&stage=&page=` | platform | paged `Referral` |
| PUT | `/platform/admin/referrals/{id}/stage` | platform | `{stage, note?}` |
| GET | `/platform/admin/commissions?profileId=&status=&month=&page=` | money | paged `Commission` (+ `marketerName`, `businessName`) |
| POST | `/platform/admin/commissions/{id}/pay` | money | `{reference, paidOn}` (exists; now records the reference) |
| GET | `/platform/marketer/me` | MARKETER | profile + totals `{earnedTotal, due, paid, businesses, referrals}` |
| PUT | `/platform/marketer/me/bank` | MARKETER | `{bankName, bankAccount, bankAccountName}` |
| GET | `/platform/marketer/referrals` · POST `/platform/marketer/referrals` | MARKETER | own referrals; create `{hotelName, ownerName, phone, email?, city, note?}` |
| GET | `/platform/marketer/businesses` | MARKETER | the businesses referred (name, city, stage, hotels, since, monthly share) via tenant internal `GET /tenant/internal/businesses?marketerId=` (new) |
| GET | `/platform/marketer/commissions?month=` | MARKETER | own commissions; `GET /platform/marketer/commissions/statement?month=` → text statement |

Security: a MARKETER token has scope `marketer` (see onboarding contract); `@access.marketer()` added to `AccessChecker`. `tenant POST /businesses` accepts `marketerId` (profile id) and `referralId`.

## Changes by H.M.S (platform)

| Method | Path | Who | Body → response |
|---|---|---|---|
| GET | `/platform/admin/changes?businessId=&madeBy=&from=&to=&page=` | platform | paged `PlatformChange` (+ `businessName`, `madeByName`, `undone`) |
| POST | `/platform/admin/changes` | platform | exists: `{businessId, hotelId, area, description, reason (≥8 chars), beforeJson, afterJson}`; the web app records a change **after** each edit it makes as H.M.S (business/hotel/setup/users), with before and after snapshots |
| POST | `/platform/changes/{id}/undo` | owner **or platform** within `undoUntil` | exists; now also allowed to platform |

## Settings and integrations (platform, SUPER_ADMIN)

`PlatformSetting` (new): group (`general`, `fees`, `security`), key, value JSON. Defaults: general `{company, supportEmail, supportWhatsApp, website, timezone}`; fees `{model, perRoom 1500, revenuePct 2, trialDays 30, dueDay 5, graceDays 10, marketerSharePct 20, marketerMonths 24, whtPct 5, providerFeePct 0.5, hmsSharePct 1.5}`; security `{require2Step false, sessionHours 12, adminIpAllowlist false, auditRetentionDays 365}`.

| Method | Path | Who | Response |
|---|---|---|---|
| GET | `/platform/admin/settings` | platform (read) | `{general, fees, security}` merged over defaults |
| PUT | `/platform/admin/settings/{group}` | SUPER_ADMIN | body = the group's map → merged group |
| GET | `/platform/admin/integrations` | platform | `Integration[]` `{key, name, kind, status CONNECTED|DISCONNECTED|ERROR|NOT_CONFIGURED, detail, lastCheck}` for: database, kafka, payment provider (logging on staging), whatsapp (NOT_CONFIGURED), email (NOT_CONFIGURED), sms (NOT_CONFIGURED), smile-id/kyc (NOT_CONFIGURED) — from actuator health and configuration |
| GET | `/platform/internal/settings/fees` | internal | the fees group (payment-service settlements) |

## Health and alerts (platform)

`HealthScore` exists (`score`, `factors`); a nightly job computes every LIVE business: factors adoption (active users last 7 days / users), bookings last 7 days, payments last 7 days, open tickets, overdue fees, night audits missed. `Alert` (new): businessId, businessName, kind (`FEES_OVERDUE`, `NIGHT_AUDIT_MISSED`, `PAYMENTS_FAILING`, `NO_ACTIVITY`, `TICKET_URGENT`, `ONBOARDING_STALLED`), title, detail, tone danger|warning|grey, openedAt, dismissedAt, dismissedBy. Raised by listeners (payment failed events, urgent tickets) and the nightly job; cleared automatically when the cause ends.

| Method | Path | Who | Response |
|---|---|---|---|
| GET | `/platform/admin/health?businessId=` | platform | `HealthScore[]` latest per business |
| POST | `/platform/admin/health/{businessId}` | platform | exists: recompute |
| GET | `/platform/admin/alerts?businessId=&open=true` | platform | `Alert[]` |
| POST | `/platform/admin/alerts/{id}/dismiss` | platform | → alert |

## Business detail tabs (platform)

| Tab | Source |
|---|---|
| Overview | tenant `GET /tenant/businesses/{id}` + `GET /platform/admin/health?businessId=` + open tickets count + due fees |
| Hotels | tenant `GET /tenant/businesses/{id}/hotels` + per hotel `GET /platform/admin/businesses/{id}/hotels/{hotelId}/overview` (new: rooms, occupancy today, arrivals/departures today, open folios, payments today, manager — gathered from property, reservation, folio, payment internal endpoints `…/internal/admin/hotel-overview?hotelId=` added to each) |
| Usage | `GET /platform/admin/businesses/{id}/usage` (new): active users 7/30 days, bookings, payments, messages per week for 8 weeks (activity log aggregates), feature adoption flags (POS used, WhatsApp set, night audit closed) |
| Money | fee statements + settlements + payment summary for the business |
| People | identity `GET /identity/users?businessId=` (exists) + row actions (exist) |
| Support | tickets for the business |
| Contract & activity | business fields (fee, contract, account manager, marketer) + `GET /platform/activity?businessId=` (exists) + changes |
| Guests & bookings (view only, C8/C9) | reservation `GET /reservation/bookings` and folio `GET /folio/folios/by-booking/{id}` as platform with `X-Hotel-Id`; before opening, the web app records the reason (C7) with `POST /platform/admin/changes` area `VIEW` |

## Support access · "View as owner" (identity, phase 3)

| Method | Path | Who | Body → response |
|---|---|---|---|
| POST | `/identity/support-access` | platform | exists: `{businessId, reason, minutes}` → grant |
| POST | `/identity/support-access/{grantId}/token` | platform | → `{accessToken, expiresIn, hotels}`: a token for the admin with `bid` = business, scope `business`, roles `OWNER:VIEW`, `act: adminId`, `sub` = admin, expiring with the grant (max 60 min). Every write made with such a token is refused by services (`AccessLevel.VIEW`), and `TenantContext` records `actedBy` so the activity log shows "H.M.S · Name". |
| GET | `/identity/support-access?businessId=` | platform | grants incl. expired, for the Contract & activity tab |

The hotel app accepts `/auth/support?token=…` (opened from the admin portal in a new tab): sets the session cookie from the token with a visible "Viewing as H.M.S support · read only · ends at HH:MM" bar.

## Seed (staging)

With `SEED_DEMO_DATA=true`: platform settings defaults; two more businesses in different stages (Eko Garden Hotels LIVE with 2 hotels, Savannah Lodge Abuja ONBOARDING at step 4) with owners and managers (password `Hospio123!`); one marketer profile for `ese@hospio360.com` with two referrals; fee statements for the current and previous month for Royal Suites; three open tickets; a settlement run for yesterday. Everything created only when missing.
