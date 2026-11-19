# Marketer (referral partner) commission

Tracker and contract for how marketers earn and are paid. Built 18 Nov 2026 (backend `ce72e9b`, frontend `d2ad4e2`..`8ff400b`,
QA script `hotel-internal-system-admin-FE/docs/qa/55-marketer-commission.md`). The Referral Partner Agreement template is
"10 - Referral Partner (Marketer) Agreement and Schedule (fillable).docx" (version **RPA-2026-11**); product and agreement say the same.

## Monthly invoice (18 Nov 2026)

Debits are gone (`docs/plan-monthly-invoices.md`). Commission now accrues on the **platform-fee part of invoice payments received**: when a
monthly invoice is paid (Paystack or recorded by finance, part payments pro rata) each of its days' `collected` becomes fee × the share of
the invoice received (after credit notes), and `CommissionService.syncReceived` adds a `DAILY_FEE` line for what more was received (same
agreement, 12-month window anchored on the first paid fee day, 20 %, 5 % WHT) or a `CLAWBACK` line when a credit note on a paid invoice
lowers it. Device shares, guest messages, data plans and VAT never earn. `FeeDebitRunner` / `onCollected` (removed 19 Nov 2026) and the QA
simulated debit below are history; QA uses `POST /admin/qa/billing/issue` and a Paystack test payment (or a recorded transfer) instead.

## Decisions (owner, 18 Nov 2026)

| Topic | Decision |
|---|---|
| Base | 20 % of the **platform fee actually collected** from a referred business (the daily % of sales debit, `docs/plan-daily-fees.md`) |
| Excluded | Device share (paid after the platform fee from the same debit), data plans, message charges, VAT. No activation bonus |
| Term | 12 months per business, from the business's **first collected fee** (window = [first collected fee day, +12 months)). A marketer change keeps the business's window (never extended) |
| WHT | 5 %, deducted per line; net = gross − WHT |
| Clawback | A refunded/reversed collected fee gives a negative line on the next statement |
| Statement | 07:00 Lagos on the 5th, for the month before; 7 days to query |
| Payout | Manual bank transfer by finance by the 15th after approval; reference/date/amount recorded for the whole statement; marketer emailed |
| Minimum | ₦5,000 (`fees.marketerMinPayout`); a smaller payable is CARRIED_OVER into the next statement, except an ENDED marketer's final statement (paid whatever the amount) |
| Defaults | `fees.marketerSharePct 20`, `fees.marketerMonths 12` (was 24), `fees.whtPct 5`, `fees.marketerMinPayout 5000` |

Decisions taken while building (tell the owner):
- **Window anchor** = the business's first collected fee, not go-live: shadow-mode months (debit off) don't use up the marketer's year.
- **Which agreement earns** = the one covering the **fee day** (`startedOn ≤ day < endedOn`), whatever its status now; the window is checked against the fee day too. A carried-over day collected later earns on the collection, for the agreement that covered the day it was for. A fee day inside the window collected after the window ended still earns (see "Collection after the window" below).
- **Line month** = the Lagos day the money was collected (`earnedOn`); a line goes on the first statement made for a month ≥ its month.
- **Unknown/inactive referral code at sign-up** = refused with a readable message (the owner fixes it or clears it), not silently ignored. Only an ACTIVE marketer who accepted the agreement can refer.
- **Reactivating an ENDED marketer**: their ended agreements stay ended; only new referrals open new ones. An ENDED marketer can't be set as Referred by.
- **Bank details** must be checked by finance (Mark checked) after every save before a statement can be approved; the bank details are copied onto the statement at approval.
- **Carried-over balance of an ENDED marketer** (decided 18 Nov 2026): paid out with a **final statement regardless of the ₦5,000 minimum**. Setting a marketer to ENDED (`PUT /platform/admin/marketers/{id} {status:"ENDED"}`) immediately makes a final statement (`finalStatement: true`, DRAFT) with every line not on a statement yet (any month) and every CARRIED_OVER statement not yet carried; it goes through the usual query → approve → pay. It is dated the current month, or the month after the marketer's latest statement when that month's is already made. Nothing open → no statement. A later line (a fee day covered by the ended agreement collected after the end) goes on the 5th's statement, which for an ENDED marketer is never CARRIED_OVER while something is payable (also `finalStatement: true`); a zero or negative total is still carried (nothing to pay). Migration platform 017 (`marketer_statements.final_statement`).
- **Collection after the window** (rule, tested in `CommissionServiceTest.aFeeDayInsideTheWindowCollectedAfterTheWindowEndedStillEarns`): what decides is the **fee day**, never the collection date. A fee day inside the 12-month window (and covered by an agreement) that is collected after the window ended, or after the agreement ended, still earns, on the month it was collected in. A fee day on or after the window end never earns, whenever it is collected.

## How it works

1. **Attribution.** Self-serve sign-up `?ref=HM-XXXXXX` (or the "Referral code" field) → identity `POST /auth/sign-up {referralCode}` → tenant `POST /internal/businesses {referralCode}` → platform `GET /internal/marketers/referral-codes/{code}` → `businesses.marketer_id`. Back office: "Referred by" at creation (`marketerId`), on the business overview or the onboarding fee step → platform `PUT /admin/businesses/{id}/marketer {profileId|null, reason}` (tenant's own `PUT /businesses/{id}` refuses a different `marketerId`).
2. **Agreement.** `platform.marketers` row per business and marketer. Opens when the business goes LIVE (`BusinessStageChanged`), or today when the marketer is set/changed on a LIVE/AT_RISK business. A change ends the previous ACTIVE agreement today (`endedReason`) and is recorded in Changes by Hospio360 (`platform_changes`, area "Marketer"). Going LIVE ends any ACTIVE agreement of another marketer, so two never earn at once; AT_RISK → LIVE keeps the same agreement.
3. **Accrual.** `FeeDebitRunner.apply` → `CommissionService.onCollected` whenever a debit raises the day's `platformPaid()`. Line: `source DAILY_FEE`, `sourceId` = daily fee, `sourceRef` = debit reference + running platform total (unique), base = what the platform part grew by, gross = base × share, WHT = gross × whtPct. Never fails the debit.
4. **Clawback.** `POST /admin/fees/daily/{id}/refund {amount, reason}` (money) → `daily_fees.refunded` (≤ platform collected − refunded) and a `CLAWBACK` line (negative) up to what earned.
5. **Statements.** `MarketerStatementJob` (5th, 07:00) → `makeStatements(previous month)`: per marketer, lines not on a statement with month ≤ M plus CARRIED_OVER statements → `marketer_statements` row: DRAFT (or CARRIED_OVER when payable < minimum), `queryUntil` = +7 days; "statement ready" email. Unique (profile, month); reruns make nothing new.
6. **Query → approve → pay.** Marketer `POST /marketer/statements/{id}/query {note}` (DRAFT, within 7 days) → QUERIED + alert `MARKETER_QUERY`. Finance `POST /admin/marketer-statements/{id}/approve {answer}` (answer required if queried; bank details must be checked) → APPROVED. Finance `POST /admin/marketer-statements/{id}/payment {reference, paidOn, amount = payable}` → PAID, lines PAID (also the statements carried into it), "you've been paid" email (Kind NOTICE).

## API

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/platform/public/referral-codes/{code}` | anyone | `{code, valid, referrerFirstName, message}` |
| POST | `/platform/marketer/agreement` | MARKETER | `{version}` (the RPA version in force); recorded by the one acceptance system (`docs/legal-acceptance.md`, method PORTAL, with IP and browser), which also keeps the version on the profile |
| PUT | `/platform/marketer/me/bank` | MARKETER | `{bankName, bankAccount (10 digits), bankAccountName, note}`; note required for a change; clears the check |
| GET | `/platform/marketer/me` | MARKETER | `{profile, totals{earnedTotal, due, paid, businesses, referrals, nextStatement, carried, byStage}, currentAgreementVersion, agreementAccepted, minimumPayout, whtPct}` |
| GET | `/platform/marketer/statements`, `/statements/{id}` | MARKETER | own statements + next-statement lines; detail with lines per business |
| POST | `/platform/marketer/statements/{id}/query` | MARKETER | `{note}` |
| GET | `/platform/admin/marketer-statements?month=&status=&profileId=` | money | `{statements, counts, totals}` |
| GET | `/platform/admin/marketer-statements/{id}` · `/export?month=&status=` | money | detail · CSV |
| POST | `/platform/admin/marketer-statements/run?month=` | SUPER_ADMIN | previous month by default; the current month only when `QA_FEE_SIMULATION=true` |
| POST | `/platform/admin/marketer-statements/{id}/approve` · `/payment` | money | see above |
| POST | `/platform/admin/marketers/{id}/bank-checked` | money | |
| GET/PUT | `/platform/admin/businesses/{id}/marketer` | platform | who referred it + agreements · change with reason |
| POST | `/platform/admin/fees/daily/{id}/refund` | money | clawback |
| POST | `/platform/admin/commissions/{id}/pay` | money | legacy monthly-statement rows only; new lines are paid per statement |
| GET | `/platform/internal/marketers/referral-codes/{code}` | internal | for tenant |
| POST | `/platform/public/partner-applications` | anyone | the landing form: see "Apply to be a partner" below |
| GET | `/platform/admin/partner-applications?status=&q=&page=&size=` · `/{id}` · `/defaults` | platform | paged applications with `counts` (`all`, `PENDING`, `APPROVED`, `DECLINED`, `pendingTotal`) · one · `{sharePct, months, canDecide, canChangeTerms}` |
| POST | `/platform/admin/partner-applications/{id}/approve` · `/decline` | SUPER_ADMIN, SUPPORT | `{fullName?, email?, phone?, sharePct?, months?}` → `{application, invited}` · `{reason, shareReason}` |

Admin marketers list: `businesses` counts **every** business naming the marketer (all stages, `byStage`), not only live agreements.

## Apply to be a partner (18 Nov 2026)

Marketers were only ever invited by an admin. Now anyone can **apply** on the landing site (`/partners/apply`, linked from the
partner card and the footer); Hospio360 reviews it in the admin portal under Marketers · Applications, and an approval **is** the
usual invite (`MarketerService.invite`), so nothing about the marketer, the agreement or the commission changes. QA script:
`hotel-internal-system-admin-FE/docs/qa/64-partner-application-form.md`.

**The form** (`PartnerApplicationService.submit`, table `platform.partner_applications`, migration platform 020): full name,
email, Nigerian mobile (`0803…`, `803…`, `234803…`, `+234803…` → stored `+234XXXXXXXXXX`), city, state (the 36 states and FCT),
kinds of businesses (`HOTEL`, `LOUNGE`, `BAR`, `RESTAURANT`, one or more), how many (`1-2`, `3-5`, `6-10`, `10+`), optional
note (≤ 500) and company (≤ 150), and the tick "I have read the Referral partner terms and the Privacy Policy" (required). Kept
with it: status (`PENDING` → `APPROVED` | `DECLINED`), who decided and when, the decline reason and whether it was shared, the
marketer profile an approval made, the visitor's address and browser, and the versions of the two documents the page showed
(checked against `legal_documents`; an unknown one is replaced by the version in force). The tick is **not** the partner's
acceptance of the agreement: that still happens in the portal (`POST /marketer/agreement`).

| Answer | When |
|---|---|
| 200 `{received: true}` "Thanks — we'll review and email you within 2 working days." | a new application; **also** a repeat (an application already `PENDING` for that email, or the email already belongs to a marketer) and a filled hidden field: none of those stores or sends anything, and the form can't be used to find out whose email is known |
| 400 with a message per field (`data: {phone: "Enter a Nigerian phone number, …"}`) | something is missing or wrong |
| 429 "You've sent a few applications in a short time. Please try again in an hour, or email us at …" | more than 3 in an hour from one address, or for one email |

**Against abuse.**
- *Plain text only.* Every value is trimmed, cut to its length and stored without `<` `>` or control characters; nothing the
  form sends is ever rendered as HTML (the admin screens and the emails show it as text).
- *Hidden field* (`website`): people never see it; a script that fills it gets the normal 200 and **nothing is stored** (decided:
  a spam table would give scripts a way to write to the database; the drop is logged with the address).
- *Hourly limit* (`PartnerApplicationLimiter`): 3 per address and 3 per email (`PARTNER_APPLICATIONS_PER_ADDRESS`,
  `PARTNER_APPLICATIONS_PER_EMAIL`), counted in Redis (`partner-apply:ip:…`, `partner-apply:email:<sha-256>`, 1 hour); if Redis
  can't be reached the count is kept in the instance's memory, so the form still works and is still limited. Only submissions
  that pass the checks are counted.
- *Whose address* (`SiteCallers`): the landing site posts from its own server, so it names the visitor in `X-Client-Address`
  (Cloudflare's `CF-Connecting-IP`, else the first forwarded hop). platform-service believes that header **only** when the
  request came from one of our own servers (this machine's addresses, or `SITE_ADDRESSES`); from anyone else it is ignored and
  the caller's own address (nginx's `X-Real-IP`) counts. A production landing site on another machine must be listed in
  `SITE_ADDRESSES`, otherwise every visitor shares that server's 3 an hour.
- *One open application per email*: checked first, and a unique index (`status = 'PENDING'`) settles two requests arriving
  together.
- The applicant's "we received it" email goes out once per stored application, so the form can't be used to mail someone
  repeatedly.

**Review** (admin portal, Marketers · Applications; the tab's count is `pendingTotal`). Everyone on the platform team reads;
**SUPER_ADMIN and SUPPORT decide** (the people who invite marketers in the admin portal today).
- **Approve** opens the invite form prefilled (name, email, phone) at the settings' share and months (`fees.marketerSharePct`
  20, `fees.marketerMonths` 12) and calls `POST …/approve`, which runs `MarketerService.invite` in the same transaction (row
  locked: decided once), marks the application `APPROVED` and stores the new profile's id. A share or term other than the
  settings' needs a money role (SUPER_ADMIN, FINANCE_ADMIN): support approving with other terms is a 403. An email that is
  already a marketer or already has a login is refused as the invite refuses it (409); the admin corrects the email in the form.
- **Decline** needs a reason (5–500 characters). The applicant is emailed; the reason is in the email only with
  `shareReason: true`. A declined applicant may apply again.
- Both are recorded in the activity log as Hospio360 (`PartnerApplicationApproved` / `PartnerApplicationDeclined`, actor
  "Hospio360 · <admin>"), which is the **Admin activity** tab of Changes by Hospio360. They are not `platform_changes` rows:
  those belong to a business and can be undone; an application belongs to nobody yet.

**Messages** (email only; test domains are skipped as everywhere):
- to the applicant on receipt, "We received your Hospio360 partner application" (what they entered, 2 working days);
- on decline, "Your Hospio360 partner application" (polite; "A note from our team: …" only when the reason is shared);
- on approval, the existing invite email from identity (link to `/accept-invite` on the admin portal);
- to Hospio360: a bell notice `PARTNER_APPLICATION` ("New partner application") to every SUPER_ADMIN and SUPPORT user
  (identity `GET /internal/users/platform?role=`), linking to `/dashboard/marketers?tab=applications&app=<id>`. It is a
  `NoticeEvent` with no business, so messaging migration 011 makes `notifications.business_id` nullable (as on
  `platform.alerts`; the row policy still lets only the platform see a row with no business).

**Run on staging (18 Nov 2026).** API (36 checks) and browser (landing at 1440, 1024, 820 and 390; admin as super admin, support
and finance): valid application stored cleaned with versions, address and browser; field messages; hidden field not stored;
repeat and existing marketer answered the same with one record; 3 an hour per address and per email then 429; an outside caller
naming another address is counted by its own; support approves at 20%/12 (marketer PENDING, application linked, invite link
opens the portal with the terms), support at another share 403, finance 403, phone on another account 409 that says so, approve
without a phone; decline with and without a reason; both decisions in Admin activity; bell for super admin. Screenshots:
`~/Desktop/Hospio test screenshots/Partner application form (18 Nov 2026)/`.

## Testing on staging (QA only)

`QA_FEE_SIMULATION=true` is set only in `/opt/hospio-staging/.env.staging` (never production). It enables `QaFeeController`
(SUPER_ADMIN): `PUT /admin/qa/hotels/{hotelId}/simulated-debit {businessId, on}` (provider `SIMULATED`; `FeeDebitRunner` never
sends it to payment-service; refused for a hotel with a real debit on), `POST /admin/qa/fees/daily/work-out {businessId, hotelId,
date}` (that hotel only), `POST /admin/qa/fees/daily/{id}/debit {result: APPROVED|FAILED, amount?}` (same bookkeeping as a real
answer: carry-over, device share, commission).

## Migration (platform 014)

New `marketer_statements`; columns on `marketer_profiles` (agreement, bank check), `marketers` (window, ended reason),
`commissions` (source, sourceId, sourceRef unique, feeDate, earnedOn, share/WHT %, statementId, note), `daily_fees`
(refunded, refundNote). Data: `months` 24 → 12 on profiles and open agreements (only where 24, the old default; a custom
share or term stays), `fees.marketerMonths` 24 → 12 where stored as 24. Staging had no agreements or commissions before.

## End-to-end run on staging (18 Nov 2026)

QA marketers "QA Mkt Ada/Bayo/Chidi uuxcq", business "QA Referred Hotel uuxcq" (signed up via Ada's `?ref=`), simulated
debits. Agreement start backdated to 14 Nov and three charges moved to 15–17 Nov by SQL (test only) to have several fee days.

| Day | Sales | Fee 1.5 % | Debit | Commission line (Ada, 20 %, WHT 5 %) |
|---|---|---|---|---|
| 15 Nov | 1,000,000 | 15,000 | collected | 15,000 → 3,000 − 150 = 2,850 |
| 16 Nov | 600,000 | 9,000 | failed → carried | none |
| 17 Nov | 500,000 | 7,500 + 9,000 carried | 10,000 then 6,500 | 2,000 − 100 = 1,900; 1,300 − 65 = 1,235 |
| refund of 15 Nov | | −4,000 | | −800 + 40 = −760 |
| **Ada, November** | | **27,500** net collected | | **gross 6,300, clawback −800, WHT 275, net 5,225** → queried, approved, paid (QA-TRF-UUXCQ) |
| 18 Nov (after change to Bayo) | 350,000 | 5,250 | collected | Bayo: 1,050 − 52.50 = **997.50 → CARRIED_OVER** |

Screenshots: `~/Desktop/Hospio test screenshots/Marketer commission end to end (18 Nov 2026)/`.
