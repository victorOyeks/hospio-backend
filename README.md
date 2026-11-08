# Hospio360 Backend

**hospio-backend** is the API behind Hospio360, a hotel management platform. It serves four apps from one API: the owner/manager web app (hospio-frontend), the staff tablet app, the platform admin console and the marketer portal.

The design handoff's rule is **build it as if it were fourteen services** — one per module, each with its own folder, its own tables and a public interface, talking through events. This repo is laid out exactly that way, with one Spring Boot service per module behind a gateway, the way coryd-backend is.

## Services

| Service | Port | Path | Module (design handoff card 12) |
|---|---|---|---|
| api-gateway | 9200 | / | Single public entry point, aggregated Swagger UI |
| identity-service | 9201 | /identity | 1 · Identity & Access: logins, PINs, roles & levels, temporary passwords, support access |
| tenant-service | 9202 | /tenant | 2 · Tenant & Onboarding: businesses, hotels, stages, onboarding wizard, handover, templates |
| hotel-config-service | 9203 | /hotel-config | 3 · Hotel configuration: every Hotel setup setting, lists, price lists |
| property-service | 9204 | /property | 4 · Property & Rooms: room types, rooms, areas, out of order |
| reservation-service | 9205 | /reservation | 5 · Reservations & Guests: bookings, availability, check-in/out, guests |
| folio-service | 9206 | /folio | 6 · Folio & Billing: folios, charges, discounts/voids, split/move, organizations, invoices |
| payment-service | 9207 | /payment | 7 · Payments: POS terminals, payment requests, webhooks, Verify, refunds, disputes |
| fnb-service | 9208 | /fnb | 8 · Food & beverage: outlets, tables, menus, orders, tabs, happy hours |
| housekeeping-service | 9209 | /housekeeping | 9 · Housekeeping & Laundry: cleaning, inspections, minibar, linen, guest laundry, lost & found |
| maintenance-service | 9210 | /maintenance | 10 · Maintenance & Tasks: repairs, scheduled jobs, spare parts |
| inventory-service | 9211 | /inventory | 11 · Inventory & Procurement: stores, stock, requests, orders, deliveries, counts |
| finance-service | 9212 | /finance | 12 · Finance & Workforce: tills, expenses, night audit, banking · rota, leave, clock-in |
| messaging-service | 9213 | /messaging | 13 · Messaging & Notifications: WhatsApp/SMS/email queue, guest inbox, in-app notifications |
| platform-service | 9214 | /platform | 14 · Platform & Insights: approvals, activity log, Departments live, reports, fees, marketers, support |

`common` is the shared library every service depends on: the response envelope, exceptions, JWT security and service-to-service auth, the tenant context and row-level-security binding, the Kafka event contracts, HTTP client defaults, request logging and the integration ports (POS, WhatsApp, SMS, email, object storage).

## How the pieces fit

- **One Postgres, a schema per service.** Every row carries `business_id` and `hotel_id`. Row-level security policies (`app.can_see`, in `common/src/main/resources/db/changelog/common/000-app-functions.sql`) mean a hotel can never read another hotel's rows, whatever the query. `common`'s `RowLevelSecurityDataSource` binds the caller's business, hotel and scope to every connection.
- **Events, not calls, between modules.** Each write publishes an event to Kafka (topics in `common/.../kafka/KafkaTopics.java`, payloads in `kafka/events`). `GuestCheckedIn` opens the folio and schedules housekeeping; `ChargePosted` from the bar, kitchen, laundry or minibar lands on the folio; `PaymentApproved` settles the folio, feeds the till and sends the receipt; `ApprovalRequested`/`ApprovalDecided` run the staff → manager → owner chain. Every event is written to the activity log by platform-service.
- **Service-to-service reads** go through `/internal/**` endpoints, gated by the internal API key on top of the JWT, using `ServiceClients.internal(...)`.
- **One worker in each service.** Night audit, scheduled jobs, message retries and the like are `@Scheduled` methods; set `WORKER_ENABLED=false` on extra API instances.
- **Integrations are ports.** `PosProvider`, `MessageChannel` and `ObjectStorage` in `common` have logging/local implementations for development; a Moniepoint or OPay adapter, a WhatsApp Business adapter or an S3 adapter is a new bean, not a change to business logic.
- **Nothing is hard-coded.** Rules, lists, prices and limits come from hotel-config-service (Hotel setup); taxes, currency and approval limits are owner-only and H.M.S never changes them.

## Running locally

Prerequisites: Java 21, Maven, Docker, a local Postgres (or `docker compose --profile db up -d`).

```bash
cp .env.example .env            # edit if your Postgres differs
docker compose up -d            # Kafka, Zookeeper, Redis
mvn -q -DskipTests package
```

Start the services you need (identity first, then whichever you are working on, then the gateway):

```bash
java -jar identity-service/target/identity-service-1.0.0-SNAPSHOT.jar
java -jar tenant-service/target/tenant-service-1.0.0-SNAPSHOT.jar
java -jar api-gateway/target/api-gateway-1.0.0-SNAPSHOT.jar
```

With `SPRING_PROFILES_ACTIVE=dev`, identity-service and tenant-service seed the example business from the design handoff: Royal Suites Ltd, Simpas Ikeja, owner Godwin Kwin, GM Tunde Bakare and the named staff. Password `Hospio123!`, PIN `1234` (see `common/.../devseed/SeedFixtures.java`).

Swagger UI for every service: http://localhost:9200/swagger-ui.html. Sign in at `POST /identity/auth/sign-in`, then send `Authorization: Bearer <token>` and `X-Hotel-Id: <hotel id>` on every call.

## Tests

```bash
mvn test
```

Unit tests cover the rules that matter: PIN lock-out and temporary passwords, double-booking and special prices, folio balances and discount limits, the POS model. `*IT` tests boot a service against a real Postgres through Testcontainers and are skipped where Docker is not running.

## Deployment

See `docs/deployment-staging.md`. In short: a push to `staging` builds whichever services changed, ships the jars to the staging VPS under `/opt/hospio-staging` and restarts them in dependency order with a health-checked rollback. nginx (`nginx/api-staging.hospio360.com`) fronts the gateway on `api-staging.hospio360.com`; `systemd/` holds one unit per service; `scripts/vps/` has the one-time bootstrap, the cutover from the old `his-be-staging` jar, and its rollback.

## Layout of a service

```
<name>-service/
  src/main/java/com/hospio/<pkg>/
    <Name>ServiceApplication.java
    client/       RestClients to other services (through /internal/**)
    config/       SecurityConfig (public paths for this service)
    controller/   public endpoints; controller/internal for service-to-service
    devseed/      dev/test data
    dto/          request and response records
    entity/       JPA entities (TenantEntity: business_id + hotel_id)
    kafka/        @KafkaListener consumers
    repository/   Spring Data repositories
    service/      the rules
  src/main/resources/
    application.yml, application-dev.yml
    db/changelog/db.changelog-master.xml + 001-initial-schema.sql (Liquibase formatted SQL)
```
