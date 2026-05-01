# temporal-mini

A lightweight, persistent workflow engine for Spring Boot — Temporal-style replayable activities, **two database tables**, no extra infrastructure required.

`temporal-mini` is intended for teams that need durable, retry-aware background workflows but don't want to operate a full Temporal cluster. You write a `Workflow`, call `activity(...)` for each step, and the framework guarantees:

- each successful activity runs **at most once** per workflow (replay-safe);
- failed activities are **retried** according to a `RetryPolicy` (fixed / exponential / no-retry);
- workflows survive process restarts (state is in your relational DB);
- a built-in **scheduler** picks up pending work; a built-in **web UI** lets you observe and control workflows.

---

## Table of contents

- [Architecture](#architecture)
- [Quick start](#quick-start)
- [Writing a workflow](#writing-a-workflow)
- [Retry policies](#retry-policies)
- [Lifecycle &amp; states](#lifecycle--states)
- [REST API](#rest-api)
- [Web UI](#web-ui)
- [Authentication](#authentication)
- [Configuration reference](#configuration-reference)
- [Building the UI](#building-the-ui)
- [Operations](#operations)
- [FAQ](#faq)

---

## Architecture

```
┌──────────────────────────────────────────────────────────────────────────┐
│                          your Spring Boot app                            │
│                                                                          │
│   @Component class MyWorkflow implements Workflow { ... }                │
│                              │                                           │
│                              ▼                                           │
│                       WorkflowEngine ──────────► Workflow.run(ctx)       │
│                              ▲                                           │
│                              │ submit(id)                                │
│                       WorkflowScheduler                                  │
│                              │ poll every Nms                            │
│                              ▼                                           │
│   ┌──────────────┐    ┌─────────────────┐    ┌─────────────────┐         │
│   │ workflowExec │    │  WorkflowRepo   │    │  ActivityRepo   │         │
│   │ (thread pool)│    └────────┬────────┘    └────────┬────────┘         │
│   └──────────────┘             │                      │                  │
└────────────────────────────────┼──────────────────────┼──────────────────┘
                                 ▼                      ▼
                   ┌────────────────────┐    ┌────────────────────┐
                   │    wflow.workflow  │    │    wflow.activity  │
                   └────────────────────┘    └────────────────────┘
```

**Components**

| Component | Role |
|---|---|
| `Workflow` | Interface you implement. `type()` is the registry key, `run(ctx)` is the body. |
| `WorkflowContext` | Passed to your workflow. Use `ctx.activity(name, retryPolicy, fn)` to run a step. |
| `WorkflowEngine` | Starts, runs, blocks/unblocks workflows. Resolves `Workflow` beans into a registry. |
| `WorkflowScheduler` | `@Scheduled` poller that picks up pending workflows and submits them to the executor. |
| `workflowExecutor` | Bounded `ThreadPoolTaskExecutor`. Runs workflows in parallel. |
| `WorkflowRuntimeRegistry` | In-memory set of workflow ids the engine is currently executing. Source of truth for the runtime "RUNNING" view. |
| `WorkflowRepository` / `ActivityRepository` | Spring Data JPA repos. Two tables: `wflow.workflow`, `wflow.activity`. |
| `WorkflowUiController` | REST API at `/temporal-mini/api/**`: workflows, stats, runtime, pool, control actions. |
| `AuthController` (optional) | JSON login/logout/me at `/temporal-mini/api/auth/**` — wired only when auth is enabled. |
| `SpaController` | Redirects `/temporal-mini` → `/temporal-mini/ui/` and serves the SPA's `index.html` for deep links. |
| React SPA | Dashboard at `/temporal-mini/ui/` (Vite + TypeScript + TanStack Query/Table + Material UI + React Router). |

**Replay model** — at the start of every run, the engine re-executes `Workflow.run()` from scratch. Each `ctx.activity(name, ...)` call first checks `wflow.activity` for a row with `success = true` and the same `name` for this workflow. If found, it returns the cached result without invoking your function. Only failed/missing activities actually execute. This is how durability is achieved without storing intermediate state.

> **Activity names must be unique within a workflow.** Two activities with the same name in the same workflow will collide on replay.

---

## Quick start

### 1. Add the dependency

```xml
<dependency>
    <groupId>com.beeline</groupId>
    <artifactId>temporal-mini</artifactId>
    <version>0.0.1</version>
</dependency>
```

You also need:
- `spring-boot-starter-web` (for the UI/API),
- `spring-boot-starter-data-jpa` + a JDBC driver (PostgreSQL is what the bundled migration targets),
- optionally `flyway-core` + `flyway-database-postgresql` if you want the bundled DDL applied for you.

### 2. Configure your DataSource

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/myapp
spring.datasource.username=app
spring.datasource.password=secret
```

If Flyway is on the classpath, `temporal-mini` runs its own Flyway instance against the `wflow` schema (history table: `flyway_temporal_mini_history`). It does not interfere with your application's own Flyway migrations.

### 3. Write a workflow

```java
@Component
public class SendInvoiceWorkflow implements Workflow {

    private final BillingClient billing;
    private final EmailClient email;

    public SendInvoiceWorkflow(BillingClient billing, EmailClient email) {
        this.billing = billing;
        this.email = email;
    }

    @Override
    public String type() { return "send-invoice"; }

    @Override
    public void run(WorkflowContext ctx) {
        Invoice invoice = ctx.activity("fetch-invoice", Invoice.class,
                RetryPolicy.exponential(5, 1_000),
                () -> billing.fetch(/* ... */));

        ctx.activity("email-customer",
                RetryPolicy.fixed(3, 30_000),
                () -> email.send(invoice));
    }
}
```

### 4. Start a workflow

```java
@RestController
public class InvoicesController {
    private final WorkflowEngine engine;
    InvoicesController(WorkflowEngine engine) { this.engine = engine; }

    @PostMapping("/invoices/{id}/send")
    public Map<String, Long> send(@PathVariable Long id) {
        Long workflowId = engine.start("send-invoice", String.valueOf(id));
        return Map.of("workflowId", workflowId);
    }
}
```

### 5. Open the UI

Visit **`http://localhost:8080/temporal-mini/ui/`** (or just `http://localhost:8080/temporal-mini`, which redirects). The SPA bundle is built from `frontend/` into the jar's classpath resources during `mvn package` — see [Building the UI](#building-the-ui).

---

## Writing a workflow

A workflow is a deterministic sequence of `activity(...)` calls. The engine guarantees that:

1. Activities that have already succeeded for this workflow are **skipped on replay** (the cached result is returned).
2. Activities that fail are **retried** according to their `RetryPolicy`. Between retries, the workflow record's `nextRetryAt` is set to a future timestamp; the scheduler picks it up later.
3. When all activities have succeeded, the workflow transitions to `FINISHED`.
4. When an activity exhausts its retries, the workflow transitions to `FAILED`.

There are two `activity(...)` overloads:

```java
// Returns a result (serialized to JSON in the activity table)
T activity(String name, Class<T> resultType, RetryPolicy policy, Supplier<T> fn);

// No return value
void activity(String name, RetryPolicy policy, Runnable fn);
```

### Best practices

- **Make activity names stable.** They are the cache key on replay. Renaming a name re-executes the step.
- **Make activity bodies side-effect-safe to retry.** Network calls should be idempotent (e.g. include a request-id header) so a retry doesn't double-charge.
- **Don't put logic outside `activity(...)` calls.** Anything in `run()` outside an `activity` block runs every replay.
- **Don't share mutable state between activities through your workflow class fields** — use the activity result as the contract.

---

## Retry policies

```java
RetryPolicy.noRetry();                            // 1 attempt, fail-fast
RetryPolicy.fixed(maxAttempts, intervalMs);       // constant delay between attempts
RetryPolicy.exponential(maxAttempts, baseMs);     // delay = 2^attempt * base
RetryPolicy.DEFAULT                               // exponential(3, 1_000)
```

`maxAttempts` is the **total** number of attempts (so `fixed(3, ...)` means up to 3 tries: the initial call plus 2 retries).

Delays are scheduled by setting `workflowEntity.nextRetryAt` and throwing — the scheduler picks the workflow up again after that time.

---

## Lifecycle & states

The persisted state machine has **five** states. There is intentionally no `RUNNING` state in the database — "currently executing" is a transient runtime view kept in memory by `WorkflowRuntimeRegistry`. Persisting it would create restart races (a JVM crash mid-execution would leave a stale `RUNNING` row that no longer corresponds to anything). Keeping it in-memory makes the truth follow the actual runtime.

```
            start()                                  run()
   (none) ─────────►  NEW  ───────────────────────► RUNNABLE
                       │                              │
                       │                              ├── all activities OK ──► FINISHED
                       │                              ├── retries exhausted ──► FAILED
                       │                              └── awaiting retry ──────┐
                       │                                                       │
                       ├──── block() ───►  BLOCKED  ◄──── block() ─────────────┘
                       │                     │
                       │                     └─── unblock() ──► RUNNABLE
                       │
                       └──── (scheduler picks up when nextRetryAt <= now)

   While inside engine.run(...): id is also recorded in WorkflowRuntimeRegistry.
   The UI overlays a "RUNNING" badge on rows whose id is in the registry.
```

| State | Persisted? | Picked by scheduler? | Meaning |
|---|---|---|---|
| `NEW` | yes | yes | created, never run |
| `RUNNABLE` | yes | yes | queued for the next poll, or awaiting retry |
| `RUNNING` | **no** (runtime only) | n/a | engine is actively executing it right now (overlay over `RUNNABLE`) |
| `BLOCKED` | yes | **no** | manually paused — won't be picked up |
| `FINISHED` | yes | no | completed successfully (terminal) |
| `FAILED` | yes | no | exhausted retries (terminal) |

**Manual controls** (also exposed in the UI):

- `engine.runNow(id)` — sets `nextRetryAt = now`. If the workflow is `FAILED` or `BLOCKED`, also flips it back to `RUNNABLE`. Forbidden on `FINISHED`.
- `engine.block(id)` — `NEW` or `RUNNABLE` → `BLOCKED`. Forbidden on terminal states.
- `engine.unblock(id)` — `BLOCKED` → `RUNNABLE`.

---

## REST API

All endpoints are mounted under `/temporal-mini/api`:

| Method | Path | Description |
|---|---|---|
| `GET` | `/stats` | Counts of workflows per persisted state, plus a transient `RUNNING` count from the runtime registry. |
| `GET` | `/runtime` | Map of `{workflowId: epochMillisStartedRunning}` — workflows the engine is processing right now. |
| `GET` | `/pool` | Live snapshot of the workflow executor: `{active, free, poolSize, corePoolSize, maxPoolSize, queue, queueCapacity}`. |
| `GET` | `/workflows?state=&page=&size=` | Paged list. `state` repeats for multi-select: `?state=NEW&state=RUNNABLE`. Comma-separated also works (`?state=NEW,RUNNABLE`). |
| `GET` | `/workflows/{id}` | Single workflow record. |
| `GET` | `/workflows/{id}/activities` | All activity attempts for a workflow, ordered by `startedAt`. |
| `GET` | `/last-activities?ids=1,2,3` | Latest activity per workflow id (used by the dashboard). |
| `POST` | `/workflows/{id}/run-now` | Force-run on next poll. |
| `POST` | `/workflows/{id}/block` | Stop the scheduler from picking it up. |
| `POST` | `/workflows/{id}/unblock` | Resume a blocked workflow. |
| `POST` | `/auth/login` | JSON `{username, password}` — sets a session cookie. Only present when [auth](#authentication) is enabled. |
| `POST` | `/auth/logout` | Invalidates the session. |
| `GET` | `/auth/me` | Returns `{username}` for the current session, `401` if not authenticated, `404` if auth is disabled. |

Control endpoints return `200 {"status":"ok"}` on success and `400 {"error": "..."}` on illegal state transitions.

---

## Web UI

Mounted at **`/temporal-mini/ui/`** (set `temporal-mini.ui.enabled=false` to disable). `/temporal-mini` and `/temporal-mini/` redirect there for old bookmarks.

The dashboard is a React SPA (`frontend/`) — Vite + TypeScript (strict) + TanStack Query for server state and polling, TanStack Table for the workflow list, Material UI for components, React Router for `/login`, `/workflows`, `/workflows/:id`. Layout:

| Folder | What lives there |
|---|---|
| `frontend/src/api/` | REST client (`client.ts`) + per-resource modules (`workflows.ts`, `auth.ts`, `pool.ts`, `controls.ts`). |
| `frontend/src/hooks/` | One TanStack Query hook per query (`useWorkflows`, `useStats`, `useRuntime`, `usePool`, `useWorkflow`, `useActivities`, `useLastActivities`); mutations live in `useWorkflowControls`. |
| `frontend/src/contexts/` | `AuthContext` (login/logout/me), `RefreshIntervalContext` (manual polling cadence in `localStorage`). |
| `frontend/src/pages/` | `LoginPage`, `WorkflowsPage`, `WorkflowDetailsPage`. |
| `frontend/src/components/` | Presentational pieces: `Header/`, `StatsCards/`, `PoolGauge/`, `WorkflowTable/`, `WorkflowControls/`, `ActivityList/`, `JsonViewer/`, `StatusBadge/`, `ProtectedRoute.tsx`. |
| `frontend/src/types/` | Shared TS interfaces for `Workflow`, `Activity`, `PoolStats`, `AuthUser`. |
| `frontend/src/utils/format.ts` | `fmtDate`, `fmtElapsed`, `fmtDuration` — JSON/date helpers. |

### Features

- **Pool gauge** — workers (active vs. free / max) and queue depth (current vs. capacity), backed by `GET /pool`.
- **Stats cards** — `ALL`, `NEW`, `RUNNABLE`, `RUNNING`, `BLOCKED`, `FINISHED`, `FAILED`. Cmd/Ctrl-click to multi-select; plain click replaces. The `RUNNING` card filters to workflows currently in the runtime registry (resolved by id, not by DB state).
- **Workflow list** — TanStack Table with id, type, state, created/started timestamps, next-run countdown, current activity, error. Per-row badge swaps to a pulsing `RUNNING` whenever the id is in the runtime registry.
- **"Next run"** ticks every second with an adaptive format. When the workflow is executing it switches to a stopwatch (`running 12s`); when the deadline has passed it counts upward (`+5s overdue`).
- **Workflow details** at `/workflows/:id` (deep-link, browser back works) — meta block (created/started/next-run), `Run now` / `Stop` / `Resume` buttons (`useMutation` + `invalidateQueries`), initial payload viewer, activities grouped by name, each attempt expandable to show input/output JSON.
- **JSON viewer** — inline preview with click-to-expand fullscreen dialog.
- **Refresh interval picker** in the header — `Off / 2s / 5s / 10s / 30s`. Selection persists in `localStorage` and drives `refetchInterval` on every TanStack Query hook (no globals; the `RefreshIntervalContext` is the single source of truth).
- **Login page** at `/login` (only when [auth](#authentication) is enabled). When auth is disabled the UI runs as `anonymous` without a login screen.

### Dev mode

```sh
cd frontend
npm install
npm run dev    # Vite on :5173, proxies /temporal-mini/api → :8080
```

Open `http://localhost:5173/temporal-mini/ui/` while your Spring app runs on `:8080`.

---

## Authentication

Authentication is **opt-in**. To enable session-based login in front of `/temporal-mini/**`:

1. Add Spring Security to your app (the SDK declares `spring-boot-starter-security` as `optional`):

   ```xml
   <dependency>
       <groupId>org.springframework.boot</groupId>
       <artifactId>spring-boot-starter-security</artifactId>
   </dependency>
   ```

2. Set the credentials in `application.properties` and flip the toggle:

   ```properties
   workflow.ui.security.enabled=true
   workflow.ui.username=admin
   workflow.ui.password={bcrypt}$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy
   ```

   Passwords are parsed by Spring Security's `DelegatingPasswordEncoder`, so prefix with the encoder id: `{bcrypt}…` for production, `{noop}…` for local dev.

When enabled:

- A `SecurityFilterChain` matches `/temporal-mini/**` and rejects anonymous requests.
- `POST /temporal-mini/api/auth/login` accepts `{"username","password"}`, sets a session cookie, returns `{"username":"…"}`.
- `POST /temporal-mini/api/auth/logout` invalidates the session.
- `GET /temporal-mini/api/auth/me` returns `200 {username}` or `401`.
- CSRF is disabled (same-origin SPA with `SameSite=Lax` cookies). HTTP Basic and form login are intentionally turned off — there's no popup, no redirect.

When disabled (`workflow.ui.security.enabled=false`, the default), all `/temporal-mini/**` endpoints are open and the SPA runs as `anonymous` with no login screen.

### Override the user store

To use multiple users, an external user store, or LDAP, define your own `UserDetailsService` (or `AuthenticationManager`) bean — the SDK's defaults are guarded by `@ConditionalOnMissingBean`.

---

## Configuration reference

| Property | Default | Purpose |
|---|---|---|
| `workflow.scheduler.enabled` | `true` | Turn the polling scheduler on/off. |
| `workflow.scheduler.interval-ms` | `5000` | Poll interval (`@Scheduled` fixed delay). |
| `workflow.scheduler.pool-size` | CPU count | Worker thread pool size. |
| `workflow.scheduler.queue-capacity` | `100` | Task queue capacity before back-pressure (`CallerRunsPolicy`) kicks in. |
| `workflow.scheduler.thread-name-prefix` | `wflow-` | Prefix for worker thread names. |
| `temporal-mini.ui.enabled` | `true` | Mount the REST API + dashboard. |
| `workflow.ui.security.enabled` | `false` | Require login for `/temporal-mini/**`. Needs Spring Security on the classpath. |
| `workflow.ui.username` | `admin` | Username of the single in-memory user provisioned when security is enabled. |
| `workflow.ui.password` | `{noop}admin` | Password (with `{encoder}` prefix). Override in production. |

The bean name is `workflowExecutor`. Define your own bean of the same name to override the executor entirely:

```java
@Bean(name = TemporalMiniAutoConfiguration.EXECUTOR_BEAN, destroyMethod = "shutdown")
public Executor workflowExecutor() { /* your custom executor */ }
```

### Why a bounded `ThreadPoolTaskExecutor`?

The default thread pool is intentionally **not** `Executors.newCachedThreadPool()`. Cached pools are unbounded — under sustained load they will create unlimited threads and OOM the JVM. The default here is:

- **fixed pool of `cpu count` threads** — predictable concurrency, fair to other code on the box;
- **bounded queue (100)** — limits memory growth if the engine falls behind;
- **`CallerRunsPolicy`** — when the queue is full, the scheduler thread runs the task itself, which both completes the work and naturally throttles the next poll (back-pressure);
- **graceful shutdown** — `waitForTasksToCompleteOnShutdown=true`, `awaitTerminationSeconds=30`.

Tune `pool-size` based on what your activities do: if they're CPU-heavy, stick close to `cpu count`; if they're mostly I/O (HTTP, DB), `2 × cpu count` to `4 × cpu count` is reasonable.

---

## Building the UI

The frontend lives in `frontend/`. It builds straight into `src/main/resources/META-INF/resources/temporal-mini/ui/` so the SPA is bundled inside the SDK jar — no separate deployment, no static-host plumbing.

### Standard `mvn package`

`frontend-maven-plugin` (in the parent `pom.xml`) does this automatically during `generate-resources`:

1. Downloads Node `${node.version}` into `frontend/.node/` (cached across builds).
2. Runs `npm install` in `frontend/`.
3. Runs `npm run build` (`tsc` + Vite) which writes the bundle to the resources path above.

The plugin runs every `mvn` invocation from `generate-resources` onward (so `mvn compile` triggers it too — the Java code references the bundled resources). To skip the frontend build pass `-Dfrontend.skip=true`:

```sh
./mvnw -Dfrontend.skip=true package
```

This is useful when iterating on Java only or in CI stages where the bundle was built upstream.

### Manual build

```sh
cd frontend
npm install
npm run build       # tsc strict typecheck + Vite production build
npm run typecheck   # fast: types only, no bundle
npm run dev         # dev server on :5173 with API proxy to :8080
```

`npm run build` writes into the Spring resources directory directly (configured in `vite.config.ts`), so a subsequent `mvn package` (with frontend skipped) just packages the existing bundle into the jar.

---

## Operations

### Database schema

`temporal-mini` owns the `wflow` schema with two tables:

- `wflow.workflow` — one row per workflow (state, payload, retry timestamp, error).
- `wflow.activity` — one row per **attempt** (workflow id, name, attempt #, success, payloads, error).

The bundled Flyway migration creates them with appropriate indexes (`idx_workflow_state_retry`, `idx_activity_workflow_name`).

### Concurrency

The scheduler tracks in-flight workflow ids in a `ConcurrentHashMap` so the same workflow is never queued twice across overlapping polls. Within a single workflow, activities are sequential — there is no parallelism inside `run()`.

If you run multiple application instances against the same database, all of them will poll. The current implementation does **not** have row-level locking, so a workflow could in theory be picked up by two instances at once. For multi-instance deployments, either run a single scheduler (`workflow.scheduler.enabled=false` on the others) or wrap the polling query in a `SELECT ... FOR UPDATE SKIP LOCKED` (extension point, not provided out of the box).

### Observability

Every activity and state transition is logged at INFO. Increase to DEBUG on `com.beeline.temporalmini` to see replay-skip messages and the per-poll heartbeat.

---

## FAQ

**Does this scale to thousands of workflows?**
The pulling model is fine for hundreds-to-low-thousands of concurrently in-flight workflows. Beyond that, look at Temporal proper.

**What happens on JVM crash mid-activity?**
The activity row is only written **after** the function returns or throws. A crash during the function leaves no row, so the next replay re-attempts the activity. Side-effects inside the function should therefore be idempotent.

**Can I pass complex objects through `nextPayload`?**
Yes — `String` is just JSON in practice. The framework doesn't interpret it; deserialize it yourself in `run()`.

**How do I share results between activities?**
Use the return value: `Order order = ctx.activity("fetch", Order.class, ...);`. The result is serialized once on success and replayed on retry.

**Can I run code outside an activity in `run()`?**
You can, but remember it runs on every replay. Keep it deterministic and side-effect-free (e.g. routing logic, parsing the input payload).
