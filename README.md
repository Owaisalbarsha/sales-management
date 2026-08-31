# Sales Management System

A backend for field sales and distribution ("van sales"): a company loads products onto vans in the morning, sales representatives drive planned routes to visit customers, sell from the van, issue invoices on the spot, and return unsold stock at the end of the day. Managers watch it happen live and approve invoices afterwards.

Built as my graduation project at Damascus University. This repository is the **backend** — a Spring Boot modular monolith serving a Flutter mobile app (sales reps in the field) and a Vue.js dashboard (managers in the office).

---

## Contents

- [What it does](#what-it-does)
- [Tech stack](#tech-stack)
- [Architecture](#architecture)
- [Modules](#modules)
- [Running it](#running-it)
- [API documentation](#api-documentation)
- [Testing](#testing)
- [Database](#database)
- [Project status](#project-status)

---

## What it does

| Role | What they can do |
|---|---|
| **Sales representative** | Load the van, follow a daily route, check in and out of customer visits, sell and issue invoices, return unsold stock, work offline |
| **Sales manager** | Plan and assign routes, watch reps on a live map, review and approve or reject invoices, run reports |
| **Warehouse manager** | Manage products and warehouse stock, fulfil van loads, run stock counts and see variance |
| **Admin** | Manage users and territories, change system configuration, broadcast announcements |

Core flows: daily van load → route execution → customer visit → invoice → manager approval → end-of-day return sheet → reporting.

---

## Tech stack

| Concern | Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 4, Spring Modulith 2 |
| Persistence | Spring Data JPA / Hibernate, PostgreSQL |
| Migrations | Flyway |
| Security | Spring Security, JWT (JJWT), BCrypt |
| Async messaging | Spring Modulith event publication log |
| Documents | Apache POI (Excel), openhtmltopdf (PDF, Arabic RTL) |
| Push | Firebase Cloud Messaging |
| API docs | SpringDoc OpenAPI |
| Build | Maven |
| Testing | JUnit 5, Mockito, `@ApplicationModuleTest`, `@DataJpaTest` |

No Kafka, no Redis, no microservices. Every dependency here earns its place — see below.

---

## Architecture

### Why a modular monolith, not microservices

Microservices solve problems this system does not have: independent team deployment cadence, independent scaling of hot paths, and language heterogeneity. What it *does* have is a domain with strong transactional coupling — an invoice must not commit unless van stock was deducted in the same transaction. Splitting that across services would mean sagas and compensating transactions to solve a problem a single database transaction already solves for free.

So: one deployable, one database, but **hard internal boundaries**. Each module owns its tables and exposes only a narrow public API. If the system ever genuinely needs to split, the seams are already cut.

### How the boundaries are enforced

Not by discipline — by the build. Spring Modulith verifies the module graph statically:

```java
@Test
void verifyModularStructure() {
    ApplicationModules.of(SalesManagementApplication.class).verify();
}
```

If any module imports a type from another module's `internal` package, this test fails. It runs in about two seconds and is the single most valuable test in the repository.

### The rules every module follows

```
com.salesmanagement.<module>/
├── api/                    ← the ONLY package other modules may import
│   ├── package-info.java   ← @NamedInterface("api")
│   ├── XxxFacade.java      ← the public entry point
│   ├── XxxInfo.java        ← public DTOs (records)
│   └── XxxEvent.java       ← published domain events
└── internal/               ← invisible to every other module
    ├── entity/
    ├── repository/
    ├── service/
    ├── dto/
    └── XxxController.java
```

1. **Cross-module calls go through a facade.** Never a repository, never an entity.
2. **Only plain `Long` IDs and DTOs cross a boundary.** No `@ManyToOne` between modules — foreign keys live in the Flyway SQL, not in the object graph. This is what keeps modules genuinely separable.
3. **Anything that can be asynchronous, is.** Notifications, tracking updates and cross-module side effects are published as events and handled by `@ApplicationModuleListener` — async, after commit, retried automatically through the Modulith event log, which gives at-least-once delivery without a broker.
4. **Authorisation in two layers.** `@PreAuthorize` on controllers for role checks, plus ownership guards in the service layer so a rep cannot reach another rep's data by guessing an ID.

### Handling a dependency cycle

`visit` needed to tell `routing` that a route's visits were finished, but `routing` already depended on `visit`. Rather than break the module graph, `visit` publishes an event whose type is *declared in `routing.api`* — the dependency arrow stays pointing one way, and `verify()` stays green.

---

## Modules

| Module | Owns | Publishes |
|---|---|---|
| `identity` | users, roles, JWT issuance | `UserCreatedEvent`, `UserLoggedOutEvent` |
| `territory` | geographic zones | — |
| `customer` | customers, GPS locations, categories | — |
| `inventory` | products, warehouse stock, van stock, stock counts | `LowStockDetectedEvent` |
| `vanops` | van loading, demand orders, return sheets | — |
| `routing` | routes, customer assignments, route optimisation | `RouteAssignedEvent` |
| `visit` | check-in / check-out, visit lifecycle | `RouteVisitsFinalized` |
| `invoicing` | invoices, line items, approval workflow | `InvoiceSubmitted/Approved/Rejected` |
| `tracking` | GPS logs, live location feed | — |
| `systemconfig` | runtime configuration values | — |
| `notification` | in-app feed, FCM push, device tokens | — |
| `reporting` | reports and Excel / PDF export (no tables of its own) | — |
| `sync` | offline sync queue and idempotency ledger | — |

The `shared` package (`ApiResponse`, `BaseEntity`, `BusinessException`, `PageRequest`) is a shared kernel, not a module.

---

## Running it

### With Docker (recommended)

```bash
git clone https://github.com/Owaisalbarsha/sales-management.git
cd sales-management
cp .env.example .env        # then edit the values
docker compose up
```

The API comes up on `http://localhost:8080`, Postgres on `5432`. Flyway runs migrations on startup; the `local` profile also seeds demo data.

### Without Docker

Requires Java 21, Maven and PostgreSQL 16.

```bash
createdb sales_management
cp src/main/resources/application-local.properties.example \
   src/main/resources/application-local.properties   # fill in DB credentials
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

### Demo accounts

Seeded by the `local` profile:

| Role | Phone number | Password |
|---|---|---|
| Admin | `0900000001` | `Demo@1234` |
| Sales manager | `0900000002` | `Demo@1234` |
| Sales rep | `0900000003` | `Demo@1234` |
| Warehouse manager | `0900000004` | `Demo@1234` |

> These are sample seed credentials for local testing only. Do not reuse a real password here.

---

## API documentation

Swagger UI: `http://localhost:8080/swagger-ui.html`
OpenAPI spec: `http://localhost:8080/v3/api-docs`

All endpoints except `/api/auth/**` require a bearer token. Get one from `POST /api/auth/login`, then use `Authorize` in Swagger UI.

---

## Testing

```bash
./mvnw test
```

Four layers:

- **Static architecture verification** — `ApplicationModules.verify()`, fails the build on any boundary violation.
- **Unit tests** — pure domain logic with no Spring context: invoice totals, state transitions, business rules.
- **Module tests** — `@ApplicationModuleTest` boots one module with its cross-module facades mocked, which is several times faster than a full context and forces honest decoupling.
- **Repository slices** — `@DataJpaTest` for query correctness.

---

## Database

Schema evolves only through Flyway migrations in `src/main/resources/db/migration/<module>/`. Versions are globally unique across modules, applied in numeric order:

```
V0  shared kernel        V8  visit
V1  identity             V9  event publication patch
V2  identity             V10 invoicing
V3  territory            V11 tracking
V4  customer             V12 systemconfig
V5  inventory            V13 notification
V6  van operations       V14 sync
V7  routing              V15 visit client_uuid
```

Conventions: money is `NUMERIC(12,2)` and always recomputed server-side; all timestamps are `Instant` in UTC, with clients sending ISO 8601 and explicit offsets; business dates are `LocalDate` set by the server.

---

## Project status

Built over roughly three months. Honest state of things:

**Done and working**
All thirteen modules, the full REST surface, JWT security, reporting with Excel and Arabic PDF export, push notifications, live GPS tracking, and offline sync.

**Known limitations, deliberately accepted at this scope**
- Route optimisation uses straight-line (Haversine) distance rather than real road routing, and anchors to the first stop rather than the rep's live position.
- VAT is not configurable at runtime; it would touch the invoice integrity hash and the frozen-total contract.
- A rare race on concurrent demand orders is guarded at load time rather than eliminated.

**What I would do next**
Real road-network routing, and moving reports to materialised views if data volume ever justified it.
