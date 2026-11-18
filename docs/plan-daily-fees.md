# Daily Hospio fee, debited at source · build tracker

> **18 Nov 2026: no more debits.** The daily fee is still worked out every day, but nothing is debited: each day is billed on the business's **monthly invoice** (`docs/plan-monthly-invoices.md`). The debit, carry-over, 3-day POS pause, `FeeDebitRunner` and the QA simulated debit described below are removed; the rest of this page is history.

Started 16 Nov 2026. Hospio takes **1.5 % of everything a hotel sells** (rate set per business in the admin portal, no cap).
The fee is worked out each day and debited from the hotel's own Moniepoint or OPay account at 6am. There are no invoices
(messaging keeps its invoice).

## Decisions (owner, 16 Nov 2026)

| # | Decision |
|---|---|
| Rate | 1.5 %, no cap, set per business in the admin portal |
| Basis | What was sold, excluding VAT: folio charges less discounts and voids; restaurant, bar and kitchen amounts paid at the counter; laundry paid directly; deposits kept on a cancellation or no-show. Anything charged to a room counts once, on the folio |
| Day | Lagos calendar day, 00:00 to 24:00. Worked out at 00:05, statement sent then |
| Room nights | Counted one night per day stayed, not all at check-in |
| Debit | 06:00 Lagos, from the hotel's provider account (the business/settlement account, not a terminal's static account) |
| Provider | Hotel chooses Moniepoint or OPay; both are built. Hospio admins enter the hotel's keys; the hotel sees them masked, read-only |
| Debit API | Not available yet. Each provider's debit is a switch per hotel on the admin portal, which can only be turned on when that provider's debit API is configured on the server |
| Unpaid | Carried to the next day. After 3 days unpaid, POS push pauses for that hotel; taking payments by hand never stops |
| Rollout | Shadow mode first: statements every day, nothing debited, until the switch is on |
| Care | No regressions: every phase ends with tests and a staging check |

## Marketer commission (18 Nov 2026)

Every collection of the platform part of a daily fee (never the device share) accrues the referring marketer's commission (`CommissionService.onCollected`, called from `FeeDebitRunner.apply`); a refund of a collected day (`POST /platform/admin/fees/daily/{id}/refund`, `daily_fees.refunded`) claws it back. On staging only, `QA_FEE_SIMULATION=true` lets a Super admin switch a test hotel to a simulated debit and answer its debits by hand. See `docs/plan-marketers.md`.

## Fee lock (18 Nov 2026)

Owner decision: the platform fee is **1.5 % of daily sales for everyone** (model `PERCENT_OF_SALES`; the rate is the platform default `fees.revenuePct` in platform settings, `FeeSettings.DEFAULTS` 1.5). Anywhere a business's % can be set it is fixed at that default; a different rate needs the reference of a **signed addendum** (free text, at most 120 characters, e.g. "ADD-2026-014 signed 18 Nov"). The rule is `common/.../billing/FeeLock.java`, used by both services.

| Where | Rule |
|---|---|
| tenant `POST /tenant/businesses`, `PUT /tenant/businesses/{id}` (also the back-office onboarding "Fee & contract" step, which saves through the PUT) | `feeAddendumRef` required when `feeValue` is not the default (or the model is another). Otherwise **400** "The platform fee is 1.5% for everyone. A different rate needs a signed addendum: enter its reference" (the number is the current default). At the default the reference is dropped. A PUT that resends the business's current model and value with no new reference leaves the fee alone (older rates are not re-checked until they change), so other edits are never blocked |
| platform `POST /platform/admin/fees/rules` | the same rule on `value` with `feeAddendumRef` (also read as `addendumRef`); the rule stores `addendumRef` and passes it to tenant (`PUT /internal/businesses/{id}/fee` now takes `feeAddendumRef`, stored as sent: platform already checked and recorded it) |
| tenant's default | `GET /platform/internal/fees/default` → `{model, revenuePct}` (`PlatformClient.feeDefaultPct`); **1.5** when platform-service can't be reached |
| stored | `tenant.businesses.fee_addendum_ref` (tenant 004-fee-addendum), `platform.fee_rules.addendum_ref` (platform 016-fee-addendum); `feeAddendumRef` in `BusinessResponse`, `BusinessSummary` and the onboarding fee step data; `addendumRef` on a fee rule |
| audit | `platform.platform_changes`, area **Fee**: "Fee set to 2% of daily sales (addendum ADD-…)", reason = the reference, before/after JSON; or "Fee back to the platform default 1.5% of daily sales" when a business on another rate returns to the default. Nothing is recorded for a fee set at the default from the default. Platform-side changes go through `ChangeService.recordFee` with the signed-in admin; tenant-side changes call `POST /platform/internal/changes/fee` (`{businessId, feeValue, feeAddendumRef, previousValue, defaultPct, actorId, actorName}`) inside the save, and if that call fails the save is rolled back (502 "Could not record the fee change in the audit trail right now…"), so a rate off the default is never saved without its record. Like any change by Hospio360 it reaches the owner's activity log (`CHANGE_BY_PLATFORM`) |
| admin portal | create business, onboarding fee step, Edit business, Fee rate modal: the fee shows locked "1.5% of daily sales · platform default" with a "Different rate (signed addendum)" toggle that opens the rate and a required Addendum reference; Overview shows the addendum (hospio-frontend `docs/qa/56-fee-lock.md`) |

## Phases

| Phase | What | Status |
|---|---|---|
| 0 | Folio correctness: an in-house date change adds or removes room nights on the folio; room lines carry the nights they cover; voids record when; kept deposits recorded on the booking | Done (16 Nov) |
| 1 | Daily sales: each service reports a hotel's sales for a day (folio, outlets, laundry, kept deposits); platform-service stores them | Done (16 Nov) |
| 2 | Daily fee and statement: percent-of-sales fee from the business's rule, 00:05 job, owner email (WhatsApp once its template is approved), owner **Hospio fees** screen, admin view | Done (16 Nov) |
| 3 | POS per hotel: Moniepoint and OPay push-to-terminal ported from StationMag, keys per hotel (encrypted, masked), terminal serials, webhooks, status checks | Done (16 Nov) |
| 4 | Collection: debit providers behind a switch per hotel, the 06:00 debit, carry-forward, retries, pause POS push after 3 days | Done (16 Nov) |
| 5 | End-to-end test on staging, QA scripts, help screens, docs; the owner's POS device test | E2E done 16 Nov; POS device test waits on the hotel's keys |

## Staging state (16 Nov 2026)

- Royal Suites Ltd: fee rule PERCENT_OF_SALES 1.5 from 1 Nov. 15 Nov worked out by hand (shadow): sales NGN 6,976.75 (counter sales
  ex VAT), fee NGN 104.65, statement emailed to the owner. The 00:05 job takes it from here.
- POS_SECRETS_KEY is set on the server. Not set yet: MONIEPOINT_BASE_URL, FEE_DEBIT_OPAY_* (Hospio's receiving OPay account),
  FEE_DEBIT_MONIEPOINT_URL.
- No hotel has POS keys yet (the E2E saved and removed test keys).
- The backend has no Jackson tree types (JsonNode etc.): every payload is a typed record (16 Nov, four parallel tracks merged).
