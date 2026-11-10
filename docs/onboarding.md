# Hotel business onboarding and setup

How a hotel business gets into Hospio360 and sets itself up, on both paths, and the API contract the web apps build against. Figma: Hotel Owner page, section "Onboarding" (3163:16113, 12 screens); Platform admin page, "Phase 2 · Back-office onboarding & handover" (3328:661, J1–J18 and Owner K1–K3) and "Phase 1 · Businesses & hotels" (3321:327).

## The two paths

**Self-serve (the hotel signs up).** The owner creates an account on the hotel app (`/sign-up`), confirms their phone, and walks a 4-step wizard: business, first hotel, rooms and prices, first manager. Each step writes to the real services as it is saved; the wizard's progress is kept in the business's onboarding draft (`source = SELF_SERVE`). Finishing the wizard sets the business `LIVE`. The manager gets a temporary password and an invite link the owner can send; the manager sets a password on `/accept-invite` and lands on their first day.

**Back office (H.M.S onboards the hotel).** A platform admin creates the business (`source = BACK_OFFICE`, stage `ONBOARDING`) and walks the 10-step wizard: business and legal, owner and contacts, fee and contract, hotels, rooms, setup template, people, connections, go-live checklist, logins and handover. Steps that create things call the real services; the draft keeps progress and the list of logins created. Handover creates the owner login (temporary password, shown once, plus an invite link), sets the stage `HANDED_OVER`, and the owner's first sign-in is: choose a password → review and accept the handover (`/handover`) → `LIVE`.

Both paths end in the same place: an owner with a business, one or more hotels with room types and rooms, default hotel settings, at least one manager, and the owner's dashboard.

## Rules

- Every user belongs to a business except platform staff (`business_id` null) and marketers (business null, but they are **not** platform scope; see below).
- Owner grants are business-wide (`hotel_id` null). The owner's token carries **every hotel of the business** in `hotels` so the owner can pick a branch (`X-Hotel-Id`). After a hotel is added, the web app refreshes its token (`POST /identity/auth/refresh`) so the new hotel is selectable at once.
- Every other role is granted per hotel. Creating a user with a hotel role needs a `hotelId`.
- Platform callers act on a business by naming it: `businessId` in the body or query, or `X-Hotel-Id` for hotel-scoped calls. Inside the service that work runs as `Tenant.systemFor(businessId, hotelId, …)` so the row-level policies pass.
- A new hotel gets defaults from hotel-config (via the `HotelCreated` event): sections, lists and the catalogue below. Nothing else is created until the wizard says so.
- Temporary passwords are returned once in the API response and shown once on screen. Every created user also gets an **invite token** (7 days) that opens `/accept-invite?token=…` on the right app; accepting sets the password and signs the user in. No email or WhatsApp is sent yet (messaging-service does not consume these events).
- Phone verification uses a 6-digit code. The code is logged on identity-service and, when `hospio.auth.otp-dev-code` is set (staging: `123456`), that code is always accepted. Sign-in is not blocked by an unverified phone; the wizard just shows the step.

## identity-service (`/identity`)

### New and changed

| Method | Path | Who | Body → response |
|---|---|---|---|
| POST | `/auth/sign-up` | public | `{fullName, email, phone, password (8..72), businessName, city?}` → `AuthResponse` (the owner is signed in). Creates the business through tenant internal (`source SELF_SERVE`, stage `ONBOARDING`, owner fields set) with a preset id, then the owner account (`mustChangePassword=false`, `phoneVerified=false`) and an `OWNER` grant. 409 if the email exists. |
| POST | `/auth/phone/send-code` | authenticated | `{}` → `{expiresAt}`; stores the code hash on the user and logs the code. |
| POST | `/auth/phone/verify` | authenticated | `{code}` → `MeResponse`; sets `phoneVerified=true`. 400 on a wrong or expired code. |
| GET | `/auth/invites/{token}` | public | → `{fullName, email, role, businessName, hotelName?, expiresAt, accepted}` for the accept screen. 404 if unknown, 410 if expired or already used. |
| POST | `/auth/accept-invite` | public | `{token, password}` → `AuthResponse`; sets the password, clears `mustChangePassword`, marks the invite accepted, signs in. |
| POST | `/auth/refresh` | public | unchanged; the token it issues carries the current hotel list. |
| POST | `/users` | manager, owner, platform | `CreateUserRequest` gains `businessId` (platform callers must send it; others are ignored) → `UserCreatedResponse(userId, temporaryPassword, temporaryPasswordExpiresAt, inviteToken, inviteExpiresAt)`. Owner callers may create `MANAGER`, `FINANCE`, `INVENTORY` and any staff role; managers may create staff roles and `MANAGER` for their own hotels only; platform may create anything including `OWNER` when `businessId` is given. The `hotelId` must belong to the business (checked through tenant internal). 409 for a duplicate email anywhere (checked as the system, not through RLS). |
| GET | `/users?businessId=&hotelId=&role=&q=` | manager, owner, platform | platform callers must send `businessId`; owners and managers list their business (managers are limited to users with a grant on one of their hotels). Returns `UserSummary[]` with `grants`, `status`, `lastSignInAt`, `mustChangePassword`, `inviteStatus` (`PENDING`/`ACCEPTED`/`EXPIRED`/`NONE`). |
| POST | `/users/{id}/resend-invite` | manager, owner, platform | → `UserCreatedResponse` with a fresh temporary password and invite token. |
| POST | `/users/{id}/reset-password` | unchanged, but owners may call it too |  |
| POST | `/internal/users` | internal | same as `/users`, with `businessId` honoured. |

### Fixes

- `TokenIssuer`: for a user holding `OWNER`, `hotels` = every hotel of the business (tenant internal `GET /internal/businesses/{id}/hotels`), scope `business`.
- Marketers (`MARKETER`, business null) get scope `marketer`, not `platform`; `HospioUserDetails.isPlatform()` stays false for them and `@access.platform()` rejects them.
- `UserService.create` honours `businessId` from platform and internal callers; the handed-over owner is therefore a business account.
- Duplicate email is reported as 409, checked outside the tenant scope.
- New entity `invites` (`user_id, token_hash, expires_at, accepted_at, created_by`), protected by business like `role_grants` but readable by the public accept endpoints as the system.
- `user_accounts` gains `phone_verified boolean default false`, `phone_code_hash`, `phone_code_expires_at`.
- `MeResponse.verified` reflects `phoneVerified`.

## tenant-service (`/tenant`)

### Business

Gains `source` (`SELF_SERVE | BACK_OFFICE`), `city`, `address`, `email`, `phone`, `logoUrl`, `brandColour`, `contractStartsOn`, `contractNotes`, and `hotel_count` kept current.

| Method | Path | Who | Body → response |
|---|---|---|---|
| GET | `/businesses/mine` | owner, manager | `BusinessResponse` (now with `source, city, address, email, phone, logoUrl, brandColour, stage, feeModel, feeValue, onboarding: {status, step, completedSteps[]}`), hotels included. |
| PUT | `/businesses/mine` | owner | `{name, legalName, registrationNumber, businessType, email, phone, address, city, logoUrl, brandColour, timezone}` → `BusinessResponse`. Owner profile edits; currency is not here (taxes live in hotel setup). |
| GET | `/businesses?stage=&q=&page=&size=` | platform | paged `BusinessSummary` (`id, name, stage, source, city, hotels, rooms, ownerName, ownerEmail, accountManagerId, accountManagerName?, marketerId, feeModel, feeValue, createdAt, lastActiveAt?`). |
| GET | `/businesses/{id}` | platform | `BusinessResponse` with hotels and onboarding. |
| POST | `/businesses` | platform | unchanged request plus `city`, `source` defaults `BACK_OFFICE`; creates the onboarding draft at step 1. |
| PUT | `/businesses/{id}` | platform | every business field incl. owner contacts, fee model and value, contract fields → `BusinessResponse`. |
| PUT | `/businesses/{id}/stage` | platform | unchanged; publishes `BusinessStageChanged` (as does every other stage change now). |
| GET | `/businesses/{id}/hotels` | platform | `HotelResponse[]` |
| POST | `/internal/businesses` | internal | `{id, name, city, ownerName, ownerEmail, ownerPhone, source}` → `BusinessResponse` (sign-up). |

### Hotels

Hotel gains `status` values `ACTIVE | PAUSED | CLOSED`, `checkInTime`, `checkOutTime` (defaults 14:00 / 12:00), `starRating?`, `managerUserId?`.

| Method | Path | Who | Body → response |
|---|---|---|---|
| GET | `/hotels` | owner, manager, staff | the caller's hotels (owner: all in the business). |
| GET | `/hotels/{id}` | same, platform | `HotelResponse` |
| POST | `/hotels` | owner, platform (with `businessId`) | `{name, city, address, phone, email, timezone, checkInTime?, checkOutTime?, businessId?}` → `HotelResponse`. Publishes `HotelCreated`. |
| PUT | `/hotels/{id}` | owner, manager of that hotel, platform | same fields → `HotelResponse` |
| PUT | `/hotels/{id}/status` | owner, platform | `{status, reason?}` |

### Onboarding

| Method | Path | Who | Body → response |
|---|---|---|---|
| GET | `/onboarding?status=` | platform | `OnboardingResponse[]` (`businessId, businessName, source, step, totalSteps, completedSteps[], assignedTo, assignedToName?, status, nextAction?, startedAt, updatedAt, data`) |
| GET | `/onboarding/{businessId}` | platform | `OnboardingResponse` |
| PUT | `/onboarding/{businessId}/step` | platform | `{step, data, nextAction?}`; `data` is the step's saved form JSON, merged into the draft's `data` under the step key; marks the step completed. Step 10 → `READY_FOR_HANDOVER`. |
| PUT | `/onboarding/{businessId}/assign?adminId=` | platform | unchanged |
| POST | `/onboarding/{businessId}/handover` | platform | creates the owner login (fixed: with the business), stores the handover, stage `HANDED_OVER` → `HandoverResponse(owner login with temporary password + inviteToken, hotels, logins created during onboarding)` |
| GET | `/onboarding/mine` | owner | the owner's own draft (self-serve wizard progress) |
| PUT | `/onboarding/mine/step` | owner | `{step (1..4), data}` |
| POST | `/onboarding/mine/complete` | owner | stage `LIVE`, draft `DONE` → `BusinessResponse` |
| POST | `/handover/accept` | owner | unchanged → `BusinessResponse` (stage `LIVE`) |
| GET | `/setup-templates` | authenticated | unchanged; the default template is seeded (see hotel-config catalogue) |

Events: `BusinessCreated` (with `ownerUserId` when known and `source`), `HotelCreated`, `BusinessStageChanged` on every change, `HandoverSent`, new `HandoverAccepted`.

## hotel-config-service (`/hotel-config`)

The setup is `section → key → value`. The catalogue below is the contract with the web app's Hotel setup screen; unknown keys are kept as-is.

| Section | Keys (defaults) | Who may edit |
|---|---|---|
| `BASICS` | `checkInTime` 14:00, `checkOutTime` 12:00, `timezone` Africa/Lagos, `receiptFooter` "" | manager, owner |
| `BRAND` | `primaryColour` #4c091e, `shortName` "", `darkSidebar` true, `logoUrl` "" | manager, owner |
| `STAY_RULES` | `allowOverbooking` false, `lateCheckoutFee` 0, `maxStayNights` 30 | manager, owner |
| `PAYMENTS` | `allowCash` true, `allowCard` true, `allowTransfer` true, `receipts` "WHATSAPP", `depositPct` 0, `tillFloat` 0, `bankName` "", `bankAccount` "" | manager, owner |
| `TAXES_CURRENCY` | `currency` NGN, `vatPct` 7.5, `serviceChargePct` 0, `consumptionTaxPct` 0, `pricesIncludeTax` true | owner only |
| `APPROVAL_LIMITS` | `managerExpenseLimit` 200000, `ownerExpenseLimit` 0 (none), `discountPct` 10, `refundsNeedOwner` false, `rateChangesNeedOwner` true | owner only |
| `HOUSEKEEPING` | `inspectDepartures` true, `minibarToFolio` true | manager, owner |
| `OUTLETS` | `roomCharge` true, `tabs` true, `tabLimit` 50000 | manager, owner |
| `MESSAGING` | `whatsappNumber` "", `sendConfirmation` true, `arrivalMessage` true, `sendReceipts` true | manager, owner |
| `STAFF` | `lateMinutes` 15 | manager, owner |
| `NIGHT_AUDIT` | `closeTime` 23:00, `tillsClosedFirst` true, `arrivalsResolvedFirst` true | manager, owner |
| `POS` | `provider` "logging", `offlineCash` true, `receiptChannel` "PRINT" | manager, owner |

Lists (`/setup/lists/{listName}`): `CANCEL_REASONS`, `DISCOUNT_REASONS`, `VOID_REASONS`, `WRITE_OFF_REASONS`, `SHIFTS` (Morning 07–15, Afternoon 15–23, Night 23–07), `DEPARTMENTS` (one per hotel role), `PUBLIC_AREAS`, `HOUSEKEEPING_CHECKLIST`, `PAYMENT_METHODS` (Cash, Card, Transfer).

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/setup` | authenticated (+`X-Hotel-Id`) | every section merged over the defaults, so a brand-new hotel already answers fully |
| PUT | `/setup/{section}` | manager/owner; owner-only sections owner; platform allowed | `@PreAuthorize` added; body `Map<String, JsonNode>` |
| POST | `/setup/apply-template` | manager, owner, platform (+`X-Hotel-Id`) | `{templateId}` or `{settings}`; applies sections and lists |
| GET/POST/PUT | `/setup/lists/...` | unchanged, plus `DELETE /setup/lists/{listName}/{id}` (manager; deactivates) |

`HotelCreated` listener: creates the default sections and lists for the new hotel (idempotent), running as `Tenant.systemFor(businessId, hotelId, …)`. `/internal/apply-template` binds the tenant the same way.

## property-service (`/property`)

| Method | Path | Who | Body → response |
|---|---|---|---|
| POST | `/rooms/bulk` | manager, owner, platform (+`X-Hotel-Id`) | `{rooms: [{number, roomTypeId, floor?, areaId?}]}` → `{created, skipped: [numbers already present]}`. Up to 500 per call. |
| DELETE | `/room-types/{id}` | manager, owner | 409 if rooms use it; otherwise `active=false` |
| everything else | unchanged |  |

Room count on the hotel is synced to tenant after bulk creation (existing `TenantClient`).

## payment-service (`/payment`)

Unchanged; the back-office "Connections" step and Hotel setup use the existing `/terminals` routes.

## Web apps

### apps/hotel

- `/sign-up` (Figma 3163:16114) → `signUp` → session → `/verify-phone` (3163:16180; "Skip for now" allowed) → `/onboarding`.
- `/onboarding` (own layout: logo, step bar, "Save and finish later"): Welcome checklist (3163:16235), Step 1 Your business (3164:16113; `PUT /tenant/businesses/mine`), Step 2 Your first hotel (3164:16239; `POST /tenant/hotels` then `refreshSession`), Step 3 Rooms & prices (3164:16373; room types `POST /property/room-types`, rooms `POST /property/rooms/bulk`, with `X-Hotel-Id` of the hotel just created), Step 4 Your first manager (3165:16113; `POST /identity/users` role MANAGER for that hotel; shows the temporary password and the invite link with copy and WhatsApp buttons), All set (3165:16555) → `POST /tenant/onboarding/mine/complete` → `/dashboard`. Progress is saved per step (`PUT /tenant/onboarding/mine/step`) so the owner can leave and return; the dashboard shell sends an owner whose business is `ONBOARDING` + `SELF_SERVE` back to `/onboarding`.
- `/handover` (K1–K3): an owner whose business is `HANDED_OVER` is sent here after the password change; it shows the hotels, logins and settings H.M.S set up, and "Accept and continue" calls `POST /tenant/handover/accept`.
- `/accept-invite?token=` (3167:16201): `GET /identity/auth/invites/{token}` → form (name read-only, email read-only, password, confirm) → `POST /identity/auth/accept-invite` → session → `/dashboard` (manager's first day, 3165:17210, is the normal manager dashboard).
- Owner: Hotels (list, add wizard, edit, status) live on tenant; Hotel detail live; Users (list, invite, change role/hotels, pause, resend invite, reset password) live on identity; Settings: Company → `PUT /tenant/businesses/mine`; Taxes & currency and Approval limits → hotel-config owner-only sections for the active hotel; Hotel setup link added to the owner nav (route under `/hotel-owner/hotel-setup/[section]`, same screen as the manager's).
- Manager: Staff (list, add, reset password, pause, remove) live on identity for the active hotel; Hotel setup loads `GET /hotel-config/setup` and saves `PUT /hotel-config/setup/{section}`; reasons and shifts use the lists routes.

### apps/admin

- Businesses: list live (`GET /tenant/businesses`), New business → `POST /tenant/businesses` → onboarding step 1.
- Onboarding board live (`GET /tenant/onboarding`); each card opens `/dashboard/onboarding/[businessId]`, the 10-step wizard (J2–J14): business & legal and owner & contacts (`PUT /tenant/businesses/{id}`), fee & contract (same), hotels (`POST /tenant/hotels` with `businessId`, list), rooms (room types + `rooms/bulk` per hotel with `X-Hotel-Id`), setup template (`GET /tenant/setup-templates`, `POST /hotel-config/setup/apply-template` per hotel), people (`POST /identity/users` with `businessId` + `hotelId`; the created logins with temporary passwords and invite links are kept in the draft data for the handover step), connections (POS terminals `POST /payment/terminals` per hotel, WhatsApp number in `MESSAGING`), go-live checklist (computed from the draft and live counts), logins & handover (`POST /tenant/onboarding/{id}/handover`; the owner's temporary password and invite link shown once with copy buttons; the handover pack lists every login).
- Business detail: overview, Hotels and People tabs live; Change stage, Change account manager, Edit business live; Reset password and Resend invite on people rows live.

### packages/api

Live domains `tenant`, `identity`, `hotel-config` join the staging default (`NEXT_PUBLIC_LIVE_DOMAINS=property,reservation,folio,payment,tenant,identity,hotel-config`). Server actions (all return `ActionResult<T>`): `signUp`, `sendPhoneCode`, `verifyPhone`, `acceptInvite`, `getInvite`, `refreshSession`; `tenant.ts`: `updateMyBusiness`, `createHotel`, `updateHotel`, `setHotelStatus`, `saveMyOnboardingStep`, `completeMyOnboarding`, `acceptHandover`, `createBusiness`, `updateBusiness`, `changeBusinessStage`, `assignAccountManager`, `saveOnboardingStep`, `sendHandover`; `identity.ts`: `createUser`, `grantRole`, `changeAccess`, `pauseAll`, `resetPassword`, `resendInvite`; `hotel-config.ts`: `saveSetupSection`, `applyTemplate`, `saveListItem`, `deleteListItem`; `property.ts`: `createRoomsBulk`, `deleteRoomType`.

## Staging accounts

Unchanged seed accounts (password `Hospio123!`): owner `godwin@royalsuites.ng`, manager `tunde@royalsuites.ng`, super admin `amoria@hospio360.com`, support `kola@hospio360.com`. Onboarding is tested by creating new businesses; the QA script `docs/qa/01-onboarding.md` in hospio-frontend lists the steps and the accounts it creates. Phone code on staging: `123456`.
