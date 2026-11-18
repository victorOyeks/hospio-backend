# Legal documents and online acceptance

Built 18 Nov 2026. Standard customers accept the Hospio360 terms online by ticking boxes; signed documents are only for exceptions
(a negotiated addendum, an NDA on request, a signed device schedule, a partner who wants a signed copy). One acceptance system for
owners, staff and referral partners (marketers), in platform-service. Templates: "Oyeks Global - Hospio360 Contract Templates
(Nov 2026)" (document 00 explains the approach). Public text: hospio360.com/legal (apps/landing in hospio-frontend).

Company: Oyeks Global Limited, RC 10001123, Lagos, Nigeria · hello@oyeksglobal.com · oyeksglobal.com. Hospio360 is a product of
Oyeks Global Limited.

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
   day at 06:00." (DEBIT)
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

## New versions and notice

A Super admin publishes a version (admin → Platform → Legal documents, or `POST /platform/admin/legal/documents`). The newest version
whose effective date has passed is in force; from then, everyone who accepts that document sees the modal at their next page load.
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
| GET | `/platform/public/legal/documents` | anyone | `{current: [DocView], upcoming: [DocView]}`; DocView `{key, version, title, slug, url, effectiveFrom, material, summary}` |
| GET | `/platform/legal/me` | signed in | `{required, pending, upcoming, records, canAccept}` |
| POST | `/platform/legal/accept` | signed in | `{documents: [{key, version}], method?: SELF_SIGN_UP\|HANDOVER\|PORTAL}`; 409 when a newer version is in force; 400 for a document that isn't theirs; already accepted → nothing new |
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
