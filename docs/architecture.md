# Architecture

Source of truth: the "Developer handoff" page of the Hotel Internal System Figma file, cards 11 and 12.

## Modules → services

The handoff names 14 modules and says to build them as separate services. Each is one Maven module and one Spring Boot process; see the table in the README for ports and paths.

## Tenancy and row-level security

Every table in every schema has `business_id` and, for hotel-scoped data, `hotel_id`. `common`'s `TenantEntity` fills them from the request's tenant on insert, and Postgres row-level security enforces them on every statement:

- `JwtFilter` reads the token and the `X-Hotel-Id` header into `TenantContext` (scope `hotel`, `business` for an owner looking across hotels, or `platform` for H.M.S staff).
- `RowLevelSecurityDataSource` runs `set_config('app.business_id' …)`, `app.hotel_id` and `app.scope` on every pooled connection, and resets them when the connection is returned.
- Each service's first changelog calls `app.protect(schema, table)` (from `common`'s `000-app-functions.sql`), which enables and forces RLS with the policy `app.can_see(business_id, hotel_id)`.
- Kafka listeners run before any tenant is known, so they call `TenantBinder.bind(event)` first; it sets the thread tenant and transaction-local settings from the event's business and hotel.

Liquibase runs with no tenant bound, which is fine for DDL; dev seeding runs as the platform (`Tenant.system(...)`).

## Events

| Event | Published by | Consumed by |
|---|---|---|
| GuestCheckedIn | reservation | folio (open + room charge), housekeeping (stay-over clean) |
| GuestCheckedOut | reservation | folio (close), housekeeping (departure clean), messaging (stay summary) |
| ChargePosted | fnb, housekeeping (minibar, laundry) | folio |
| PaymentApproved | payment | folio (payment line), finance (ledger, till), messaging (receipt) |
| RefundApproved | payment | folio |
| ApprovalRequested | folio, payment, fnb, housekeeping, maintenance, inventory, finance | platform (opens the approval), messaging (tells the approver) |
| ApprovalDecided | platform | the requesting service applies it; messaging tells the requester |
| StockLow, RoomOutOfOrder, PosOffline, … | inventory, property, payment, … | messaging (notifications), platform (live board) |
| every topic | everyone | platform (activity log, Departments live) |

Events are JSON on Kafka, keyed by hotel id so one hotel's events stay ordered. `EventPublisher` sends after the surrounding transaction commits and stamps business, hotel, actor and time. Consumers are idempotent (unique `event_id`, unique `(source_module, source_ref)` on folio lines, unique `payment_id` in the ledger) so a replay is harmless.

## Approvals

Staff → manager (up to their limit) → owner. The requesting service decides who must approve from the configured limits (`hospio.approvals.*`, overridden per hotel in Hotel setup), stores a pending row with an `approval_id`, and publishes `ApprovalRequested`. platform-service keeps the queue; a decision publishes `ApprovalDecided` and the requesting service applies it (a folio discount, a refund, a price change, a purchase order, an expense, a write-off, a parts request).

## The POS model

Hotel setup → Payments says which methods a hotel takes and the receipt rule; Hotel setup → POS terminals maps one terminal per role. A card or transfer request goes to that role's terminal through `PosProvider`; the provider's webhook (`POST /payment/webhooks/pos/{provider}`) settles it; Verify asks the provider again. Cash is approved on the spot and feeds the role's open till in finance-service. A role with no terminal, or an offline terminal, is cash only.

## Splitting a service out later

Each service already owns its schema and talks only through Kafka and `/internal/**`, so moving one to its own database or host is configuration: point its datasource elsewhere, keep the topics.
