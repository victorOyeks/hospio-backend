# Daily Hospio fee, debited at source · build tracker

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

## Phases

| Phase | What | Status |
|---|---|---|
| 0 | Folio correctness: an in-house date change adds or removes room nights on the folio; room lines carry the nights they cover; voids record when; kept deposits recorded on the booking | Planned |
| 1 | Daily sales: each service reports a hotel's sales for a day (folio, outlets, laundry, kept deposits); platform-service stores them | Planned |
| 2 | Daily fee and statement: percent-of-sales fee from the business's rule, 00:05 job, owner email (WhatsApp once its template is approved), owner **Hospio fees** screen, admin view | Planned |
| 3 | POS per hotel: Moniepoint and OPay push-to-terminal ported from StationMag, keys per hotel (encrypted, masked), terminal serials, webhooks, status checks | Planned |
| 4 | Collection: debit providers behind a switch per hotel, the 06:00 debit, carry-forward, retries, pause POS push after 3 days | Planned |
| 5 | End-to-end test on staging, QA scripts, help screens, docs; the owner's POS device test | Planned |
