# Legal documents and online acceptance

Built 18 Nov 2026. Standard customers accept the Hospio360 terms online by ticking boxes; signed documents are only for exceptions
(a negotiated addendum, an NDA on request, a signed device schedule, a partner who wants a signed copy). One acceptance system for
owners, staff and referral partners (marketers), in platform-service. Templates: "Oyeks Global - Hospio360 Contract Templates
(Nov 2026)" (document 00 explains the approach). Public text: hospio360.com/legal (apps/landing in hospio-frontend).

Company: Oyeks Global Ltd, RC 10001123, Lagos, Nigeria (published documents give no street address) ·
hello@oyeksglobal.com · oyeksglobal.com. Hospio360 is a product of Oyeks Global Limited.

## Documents

| Key | Title | First version | Page | Who accepts |
|---|---|---|---|---|
| TERMS | Terms of Service | TOS-2026-11 | /legal/terms | owner (box 1) |
| PRIVACY | Privacy Policy | PRIV-2026-11 | /legal/privacy | owner (box 1) |
| DPA | Data Processing Terms | DPA-2026-11 | /legal/data-processing | owner (box 1) |
| DEBIT | Fees and daily debit authorisation | FEES-2026-11 | /legal/fees | owner (box 2) |
| DEVICES | Devices & equipment terms | DEV-2026-11 | /legal/devices | owner, per device package |
| SUPPORT | Support & services | SUP-2026-11 | /legal/support | part of the Terms (no box) |
| RPA | Referral partner terms | RPA-2026-11 | /legal/referral-partners | marketer |
| STAFF | Staff terms of use | STAFF-2026-11 | /legal/staff-terms | every staff user (manager and below) |

All effective 18 Nov 2026 (`platform.legal_documents`, seeded by migration 015). Hospio360's own staff (SUPER_ADMIN, SUPPORT,
FINANCE_ADMIN) accept nothing here; a support "view as owner" session is never asked and can't accept.

The boxes:

1. "I agree to the [Terms of Service], [Privacy Policy] and [Data Processing Terms] on behalf of my business." (TERMS, PRIVACY, DPA)
2. "I authorise Hospio360 (a product of Oyeks Global Limited) to debit 1.5% of my business's daily sales from my business account each
   day at 06:00." (DEBIT) The figure is **that business's rate**: the rate its daily fee uses (newest fee rule, else tenant's
   `feeValue`; `FeeService.dailySalesPct`), so a business on a signed addendum rate reads e.g. "debit 1.2% …". Self sign-up shows
   the platform default (`fees.revenuePct`). See "The debit rate" below.
3. Staff: "I agree to the [Staff terms of use]." · Marketer: "I agree to the [Referral partner terms]." · Owner, device package: "I agree
   to the [Devices & equipment terms] for this package…".

## Where people tick

| Who | Where | Method recorded |
|---|---|---|
| Owner, self-serve | `/sign-up`: both boxes required (identity `POST /auth/sign-up` refuses without `acceptTerms` and `acceptDebit`); the web app records them right after sign-up | `SELF_SIGN_UP` |
| Owner onboarded by Hospio360 | `/handover` (first sign-in, after choosing a password): both boxes required before "Accept and go to my dashboard". The admin never ticks for the owner; in the handover step the admin records how the links were shared (channel + date) | `HANDOVER` (owner) · `LINKS_SHARED` note (admin, `BACK_OFFICE`) |
| Existing owners, staff, marketers | A modal over every screen at the next sign-in (dashboard shell, both apps) until accepted; the only other button is Sign out | `FIRST_SIGN_IN` |
| Anyone, new version | The same modal ("We've updated our terms. Please accept to continue.") | `RE_ACCEPTANCE` |
| Marketer | The portal's agreement step (`POST /platform/marketer/agreement`) and the modal both record through the same system | `PORTAL` |
| Owner, devices | Hospio fees → Devices & internet: "Accept device terms" on a package not yet installed | `DEVICE_PACKAGE` (reference = package id) |

Device packages: the admin can mark a package installed only after the owner accepted the device terms for it, or by entering the
reference of a signed device schedule (`signedScheduleRef`, the exception path). Changing a draft package clears the owner's acceptance.

## The debit rate (18 Nov 2026)

- `/legal/me` (owner, DEBIT pending) and `/public/legal/documents` give the DEBIT document a `ratePct`: the business's rate, or the
  platform default on the public list. Other documents have `ratePct: null`. The rate is only worked out while the box is to be shown.
- `POST /legal/accept` takes `ratePct` on the DEBIT item: the rate the box showed. The server compares it with the business's rate now;
  a different one (or none from an older client, which always said 1.5%, for a business not on 1.5%) is **409** "Your business's daily
  fee is 1.2% of daily sales, not 1.5%. Reload the page and read it", and nothing is recorded. If the rate can't be told (no fee rule
  and tenant-service didn't answer) it is 409 "…could not be checked right now. Try again in a minute".
- The record keeps it: `legal_acceptances.rate_pct` (migration 018); the record's title is "Daily debit authority (1.2%)" (also in
  the CSV). Records from before 18 Nov have no rate: their box said 1.5%, and the title says so.
- A later rate change (a new addendum) does not by itself ask the owner to accept again; the addendum is the signed authority for it.

## Monthly invoice versions (18 Nov 2026)

Debits are gone (`docs/plan-monthly-invoices.md`). New versions, effective 18 Nov 2026 on staging: **TOS-2026-11B**, **FEES-2026-11B**
(title "Fees and monthly invoice", same `DEBIT` key and `/legal/fees` page), **DEV-2026-11B**, **SUP-2026-11B**, **RPA-2026-11B**. Box 2 is
now "I agree to pay Hospio360's monthly invoice — 1.5% of my business's daily sales — within 7 days of issue." (the rate rules above are
unchanged: the business's own rate). A record of FEES-2026-11B or later is titled "Monthly invoice agreement (x%)"; FEES-2026-11 records
keep "Daily debit authority". Existing owners and marketers accept the new versions in the modal at their next page load. They were
published as material on staging with `LEGAL_MATERIAL_NOTICE_DAYS=0` (platform property `hospio.legal.material-notice-days`); production
keeps 30 days' notice. Word templates: 03 Direct Debit Mandate is replaced by "03 - Billing & invoice terms (summary)".

## New versions and notice

A Super admin publishes a version (admin → Platform → Legal documents, or `POST /platform/admin/legal/documents`). The newest version
whose effective date has passed is in force; from then, everyone who accepts that document sees the modal at their next page load.
Text changes that aren't material (an address, a typo) are made on hospio360.com/legal without a new version, so nobody is
asked again: the platform holds only versions, the text lives in the landing site (`apps/landing/src/legal/documents.json`). The
Terms 17.1 (notices: "our registered office in Lagos, Nigeria") and the entity line on every legal page were reworded this way
on 18 Nov 2026. On 19 Nov 2026, the same way (wording only, no new version, nobody asked again): guest message charges are emails and
WhatsApp messages only, SMS being only for sign-in codes (Terms 5.4(b) and 5.9, Fees 3.2(b), Referral partner terms 4.3); Terms 7.3 and
Fees 5.5 say what a full suspension is; and Terms 7.3 names the grounds for suspending for another reason (a serious breach of the
Terms such as fraud, misuse of the Platform or of guests' data, or attempts to avoid fees; protecting the Platform, other customers
or data; or at the customer's request), with "where it is safe and lawful to do so, we will tell you why and how to have the
suspension lifted". The owner approved the last one as a wording change because there are no live customers yet and production
gives 30 days' notice at go-live. The Word templates (01 Terms, 02 Order Form, 03 Billing summary, 10 Referral partner, 00 guide)
were rebuilt with the same wording. Also on 19 Nov 2026, wording only (owner decision, `docs/data-retention.md`): audit logs are kept
7 days, not six months: Privacy Policy 7.1, Data Processing Terms 12.2 and the Schedule's "Audit logs" bullet, and Staff terms 4.1
("The activity log is kept for 7 days, then deleted automatically"). The Word templates were not touched for this (none of them
states an activity-log period).
A **material** change needs 30 days' notice (`hospio.legal.material-notice-days`): its effective date must be at least 30 days ahead,
and until then it shows as "Coming" in My profile → Terms & acceptances. Put the new text on hospio360.com/legal first.

## Records

`platform.legal_acceptances` is append-only (a trigger refuses UPDATE and DELETE). Each row: event (`ACCEPTED` or `LINKS_SHARED`),
method, document key and version, user id, name, email, role, business id and name, hotel (staff), reference (device package), channel
and date (links shared), note, IP, browser, who recorded it, when. The web apps forward the browser's address (`X-Client-IP`, from
the proxy's `X-Forwarded-For`) and user agent (`X-Client-Agent`). Marketers who accepted RPA-2026-11 before this were copied in
(method `PORTAL`, IP and browser unknown). The marketer profile keeps the accepted version too (`agreement_version`), which the
referral-code check reads.

Who sees them: admins on the business page → **Acceptance** tab (with Export CSV and "Record links shared"); the owner in My profile →
**Terms & acceptances** (their business's records); everyone else their own.

## API (platform-service)

| Method | Path | Who | Notes |
|---|---|---|---|
| GET | `/platform/public/legal/documents` | anyone | `{current: [DocView], upcoming: [DocView]}`; DocView `{key, version, title, slug, url, effectiveFrom, material, summary, ratePct}` (ratePct on DEBIT only) |
| GET | `/platform/legal/me` | signed in | `{required, pending, upcoming, records, canAccept}` |
| POST | `/platform/legal/accept` | signed in | `{documents: [{key, version, ratePct?}], method?: SELF_SIGN_UP\|HANDOVER\|PORTAL}`; 409 when a newer version is in force or the DEBIT `ratePct` isn't the business's rate; 400 for a document that isn't theirs; already accepted → nothing new |
| GET | `/platform/owner/legal/acceptances` | owner | the business's records |
| GET | `/platform/admin/legal/acceptances?businessId=` · `/export?businessId=` | platform | records · CSV |
| POST | `/platform/admin/legal/businesses/{id}/links-shared` | platform | `{channel: WHATSAPP\|EMAIL\|SMS\|IN_PERSON\|PHONE_CALL\|OTHER, sharedOn, note?}` (note required for OTHER) |
| GET/POST | `/platform/admin/legal/documents` | platform / SUPER_ADMIN | versions · publish `{key, version, title?, effectiveFrom?, material?, summary?}` |
| POST | `/platform/owner/devices/{packageId}/accept-terms` | owner | `{version?}`; package must be a draft of the owner's business |
| POST | `/platform/admin/device-packages/{id}/install` | platform | now `{installedOn, signedScheduleRef?}`; 400 without the owner's acceptance or a reference |

identity `POST /auth/sign-up` gains `acceptTerms` and `acceptDebit` (both must be true).

## Related decisions (18 Nov 2026)

- Platform fee: 1.5% of daily sales for everyone; a different rate only with a signed addendum reference (`docs/plan-daily-fees.md`,
  Fee lock).
- Referral partners: a fee day inside the 12 months collected after the window still earns; an ended partner's carried balance is paid
  with the final statement regardless of the ₦5,000 minimum (`docs/plan-marketers.md`).
- Existing owners, staff and marketers accept once at their next sign-in (expected).

## Free until charges start (19 Nov 2026, wording only)

Terms 5.1 and Fees 1.3 (hospio360.com/legal) now say the platform fee applies from the **charges start date** in the Order Form or set
by Hospio360 and shown under Billing & invoices; days before it (or with no date) are free of the platform fee and guest message charges;
devices are not part of the free period. Added to the web text of TOS-2026-11B / FEES-2026-11B **without a new version** (nobody is asked
again), like the laundry lines. Word templates: **02 Order Form** row "Start date (Effective Date)" is now **"Charges start on"**
(`[Start date]`, blank = free until Hospio360 sets it) and **00** describes the field; only 02 and 00 were rebuilt (backup in the
scratchpad `legal/backup-charges-start-*`). 01 Terms and 03 Billing summary get the same sentence at their next rebuild.

