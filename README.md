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
- [Lifecycle & states](#lifecycle--states)
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
| `WorkflowEngine` | Starts, runs, stops/resumes/restarts workflows (single + bulk). Resolves `Workflow` beans into a registry. |
| `TemporalMiniSchemaMigrator` | Tiny forward-only SQL migrator. Reads `db/migration/temporal-mini/V*__*.sql` from the classpath and tracks applied versions in `wflow.sql_migrations`. Replaces Flyway. |
| `MetricsSampler` | `@Scheduled` snapshot writer for the metrics chart — appends a row to `wflow.metric_sample` every 10s and trims older rows on a daily cron. |
| `WorkflowScheduler` | `@Scheduled` poller that picks up `NEW` and `RETRY` (when `nextRetryAt <= now`) workflows and submits them to the executor. |
| `workflowExecutor` | Bounded `ThreadPoolTaskExecutor`. Runs workflows in parallel. |
| `WorkflowRuntimeRegistry` | In-memory tracker for ids the scheduler has handed to the executor. Distinguishes `SUBMITTED` (in the executor's queue) from `RUNNING` (worker thread actually executing); the latter powers the UI's `RUNNING` state and the `runtimeCount` metric. Also acts as a deduplication guard across overlapping polls. |
| `WorkflowRepository` / `ActivityRepository` | Spring Data JPA repos for the live tables: `wflow.workflow`, `wflow.activity`. |
| `WorkflowHistoryRepository` / `ActivityHistoryRepository` | Spring Data JPA repos for the append-only audit tables: `wflow.workflow_history` (one row per `engine.run()` pickup), `wflow.activity_history` (mirror of every activity attempt). See [Database schema](#database-schema). |
| `ActivityMetrics` / `WorkflowMetrics` | Optional Micrometer instrumentation. Auto-registered when a `MeterRegistry` is present on the classpath. |
| `WorkflowUiController` | REST API at `/temporal-mini/api/**`: workflows, stats, pool, control actions. |
| `AuthController` (optional) | JSON login/logout/me at `/temporal-mini/api/auth/**` — wired only when auth is enabled. |
| `SpaController` | Redirects `/temporal-mini` → `/temporal-mini/ui/` and serves the SPA's `index.html` for deep links. |
| React SPA | Dashboard at `/temporal-mini/ui/` (Vite + TypeScript + TanStack Query/Table + Material UI + React Router). |

**Replay model** — at the start of every run, the engine re-executes `Workflow.run()` from scratch. Each `ctx.activity(name, ...)` call first checks `wflow.activity` for the row matching this workflow + name; if `success = true`, it returns the cached output without invoking your function. Otherwise the engine runs the function and **upserts** the row (the same `(workflow_id, name)` row is updated in place across retries, so the live table stays slim — one row per activity per workflow). Failed and successful attempts alike are also appended to `wflow.activity_history` for the full audit trail.

> The replay-cache only reads `wflow.activity` (the live table). The `*_history` tables are written in parallel but never consulted by the engine — they exist purely as an immutable audit trail for the UI / operators.

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
- `spring-boot-starter-data-jpa` + a JDBC driver (PostgreSQL is what the bundled migrations target).

### 2. Configure your DataSource

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/myapp
spring.datasource.username=app
spring.datasource.password=secret
```

`temporal-mini` ships its own SQL migrator (no Flyway dependency). On startup it reads
`db/migration/temporal-mini/V*__*.sql` from the classpath, applies any version it
hasn't seen before, and tracks applied versions in `wflow.sql_migrations`. Your
application's own migrations (Flyway, Liquibase, anything else) are untouched.

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
        Invoice invoice = ctx.activity("fetch-invoice",
                RetryPolicy.exponential(5, 1_000),
                () -> billing.fetch(/* ... */));

        ctx.activity("email-customer", invoice,
                RetryPolicy.fixed(3, 30_000),
                inv -> { email.send(inv); return null; });
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

There are three `activity(...)` overloads:

```java
// Run with a typed input. Input is JSON-serialized into wflow.activity.input_payload
// and the same instance is passed to the function. Output type is captured at runtime
// (via result.getClass()) and stored alongside the JSON in output_type so replay can
// deserialize back without you having to pass a Class<O> token.
<I, O> O activity(String name, I input, RetryPolicy policy, Function<I, O> fn);

// No input. Output handling identical to the above.
<T> T activity(String name, RetryPolicy policy, Supplier<T> fn);

// Side-effecting only; nothing stored in output_payload/output_type.
void activity(String name, RetryPolicy policy, Runnable fn);
```

> The replay-cache uses {@code output_type} (a fully-qualified class name persisted on first success) to deserialize the cached JSON back to a typed Java object. If the class is later renamed/removed from the classpath, replay throws `IllegalStateException` — the operator should `restart` the workflow.

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

The persisted state machine has **four** states. There is intentionally no "currently running" state in the database — in-flight execution is tracked in memory by `WorkflowRuntimeRegistry`. Persisting it would create restart races (a JVM crash mid-execution would leave a stale row that no longer corresponds to anything).

> **`STOPPED` vs. legacy `BLOCKED`.** The user-facing name (Java enum, REST API, UI) is `STOPPED`. The database column still stores the literal string `"BLOCKED"` so existing data needs no migration — see `WorkflowStateConverter`.

> **Legacy `RUNNABLE` rows.** If you're upgrading from a previous version that used `RUNNABLE`, `WorkflowStateConverter` maps the string `"RUNNABLE"` on read to the new `RETRY` state. No data migration needed.

```
            start()
   (none) ─────────►  NEW  ───────────────────────────────────────┐
                                                                   │
                                         engine picks up          │
                       NEW / RETRY ◄──────────────────────────────┘
                              │
                              │  run()
                              ▼
                         (executing)
                              │
                  ┌───────────┼───────────┐
                  │           │           │
                  ▼           ▼           ▼
               FINISHED     FAILED      RETRY
                                     (nextRetryAt set)
                                          │
                                          ├── nextRetryAt in future → WAITING
                                          └── nextRetryAt <= now    → IN QUEUE

   stop()         → NEW / RETRY → STOPPED
   resume()       → STOPPED / FAILED → RETRY (queued immediately)
   restart()      → wipe activities, reset to RETRY (any state)
   restartFromActivity() → wipe activities ≥ pivot, reset to RETRY
```

| State | Persisted? | Picked by scheduler? | Meaning |
|---|---|---|---|
| `NEW` | yes | yes | created, never run |
| `RETRY` | yes | yes (when `nextRetryAt <= now`) | a previous attempt failed; waiting for the next run window |
| `STOPPED` | yes (as `"BLOCKED"`) | **no** | manually paused — won't be picked up |
| `FINISHED` | yes | no | completed successfully (terminal) |
| `FAILED` | yes | no | exhausted retries (terminal) |

The UI shows three additional **derived** views, computed server-side:

| UI label | Wire state | Source | Meaning |
|---|---|---|---|
| **Ready to run** | `IN_QUEUE` | DB | `NEW` + `RETRY` rows where `nextRetryAt <= now` — ready to be picked up on the next poll |
| **Waiting retry** | `WAITING` | DB | `RETRY` rows where `nextRetryAt > now` — sleeping until their retry window opens |
| **Running** | `RUNNING` | `WorkflowRuntimeRegistry` | ids the executor has actually started running on a worker thread (excludes those still sitting in the executor queue) |

**Manual controls** (also exposed in the UI):

- `engine.runNow(id)` — sets `nextRetryAt = now`. If the workflow is `FAILED` or `STOPPED`, also flips it to `RETRY`. Forbidden on `FINISHED`.
- `engine.stop(id)` — `NEW` or `RETRY` → `STOPPED`. Forbidden on terminal states.
- `engine.resume(id)` — `STOPPED` or `FAILED` → `RETRY` and queues for immediate pickup.
- `engine.restart(id)` — wipes every activity row for this workflow and resets state to `RETRY`. Allowed in any state. **Destructive** for the live replay cache, but `wflow.activity_history` rows are preserved (with their `activity_id` set to `NULL` by the FK).
- `engine.restartFromActivity(id, activityId)` — deletes activity rows whose `startedAt >= chosen.startedAt` (the pivot and everything after it), resets state to `RETRY`. Earlier successful activities stay cached. History rows for deleted activities are preserved in `wflow.activity_history`.
- Bulk variants — `stopAll`, `resumeAll`, `restartAll`, `runNowAll` — accept a `Collection<Long>` and return the number of workflows successfully transitioned. Illegal transitions are skipped silently.
- Payload editing — `engine.setPayload(id, ...)` (allowed in `NEW`/`RETRY`/`STOPPED`/`FAILED`); `engine.setActivityPayload(id, activityId, payload, output)` for an activity's input or output (no state check — operator's responsibility).

---

## REST API

All endpoints are mounted under `/temporal-mini/api`:

| Method | Path | Description |
|---|---|---|
| `GET` | `/stats` | Counts per logical state: `NEW`, `IN_QUEUE`, `WAITING`, `RUNNING`, `STOPPED`, `FINISHED`, `FAILED`. `IN_QUEUE` and `WAITING` are derived from `RETRY` rows split by `nextRetryAt`; `RUNNING` is the size of the in-memory runtime registry (workers currently executing). |
| `GET` | `/pool` | Live snapshot of the workflow executor: `{active, free, poolSize, corePoolSize, maxPoolSize, queue, queueCapacity}`. |
| `GET` | `/metrics/history?from=&to=&bucket=` | Time-series of pool/state/runtime counters. `bucket` ∈ `raw / second / minute / hour / day` — wide windows aggregate via Postgres `date_trunc`. |
| `GET` | `/workflows?state=&page=&size=&sort=` | Paged list. `state` repeats for multi-select: `?state=NEW&state=RETRY`. Accepts virtual values `IN_QUEUE` / `WAITING` / `RUNNING` (single-state filters only — the UI never mixes them with others). `sort=field,dir` where field ∈ `id / createdAt / state` and dir ∈ `asc / desc`; default `id,desc`. |
| `GET` | `/workflows/{id}` | Single workflow record. |
| `GET` | `/workflows/{id}/activities` | All activity attempts for a workflow, ordered by `startedAt`. |
| `GET` | `/last-activities?ids=1,2,3` | Latest activity per workflow id: `{name, attempt, lastAttemptAt}`. Used by the dashboard. |
| `POST` | `/workflows/{id}/run-now` | Force-run on next poll. |
| `POST` | `/workflows/{id}/stop` | `NEW`/`RETRY` → `STOPPED`. |
| `POST` | `/workflows/{id}/resume` | `STOPPED`/`FAILED` → `RETRY`, queue for immediate pickup. |
| `POST` | `/workflows/{id}/restart` | Wipe all activities and reset to `RETRY`. Destructive. |
| `POST` | `/workflows/{id}/restart-from-activity` | Body `{activityId}`. Wipe activities at/after the pivot, reset to `RETRY`. |
| `POST` | `/workflows/bulk/{stop,resume,restart,run-now}` | Body either `{ids: [...]}` or `{from, to, states?: []}`. Returns `{affected: N}`. |
| `PUT`  | `/workflows/{id}/payload` | Body `{payload}`. Replace the workflow input. Allowed in `NEW`/`RETRY`/`STOPPED`/`FAILED`. |
| `PUT`  | `/workflows/{id}/activities/{activityId}/input` | Body `{payload}`. Edit a stored activity input. |
| `PUT`  | `/workflows/{id}/activities/{activityId}/output` | Body `{payload}`. Edit a stored activity output (use with care — this is the cached "successful" result). |
| `POST` | `/auth/login` | JSON `{username, password}` — sets a session cookie. Only present when [auth](#authentication) is enabled. |
| `POST` | `/auth/logout` | Invalidates the session. |
| `GET` | `/auth/me` | Returns `{username}` for the current session, `401` if not authenticated, `404` if auth is disabled. |

Control endpoints return `200 {"status":"ok"}` on success and `400 {"error": "..."}` on illegal state transitions. Bulk endpoints always return `200 {"affected": N}` — workflows whose state forbids the action are silently skipped.

---

## Web UI

Mounted at **`/temporal-mini/ui/`** (set `temporal-mini.ui.enabled=false` to disable). `/temporal-mini` and `/temporal-mini/` redirect there for old bookmarks.

The dashboard is a React SPA (`frontend/`) — Vite + TypeScript (strict) + TanStack Query for server state and polling, TanStack Table for the workflow list, `@mui/x-charts` for the metrics page, Material UI for components, React Router for `/login`, `/workflows`, `/workflows/:id`, `/metrics`. Layout:

| Folder | What lives there |
|---|---|
| `frontend/src/api/` | REST client (`client.ts`, base URL is dynamic) + per-resource modules (`workflows.ts`, `auth.ts`, `pool.ts`, `controls.ts`, `metrics.ts`, `edits.ts`). |
| `frontend/src/hooks/` | One TanStack Query hook per query (`useWorkflows`, `useStats`, `usePool`, `useWorkflow`, `useActivities`, `useLastActivities`, `useMetricsHistory`); mutations live in `useWorkflowControls` (run/stop/resume/restart + bulk) and `useEditPayload`. |
| `frontend/src/contexts/` | `AuthContext` (login/logout/me; auto-rechecks on backend switch), `RefreshIntervalContext` (manual polling cadence in `localStorage`), `BackendContext` (list of API base URLs the operator can switch between). |
| `frontend/src/pages/` | `LoginPage`, `WorkflowsPage`, `WorkflowDetailsPage`, `MetricsPage`. |
| `frontend/src/components/` | Presentational pieces: `Header/` (tabs + backend select + refresh select), `StatsCards/`, `PoolGauge/`, `WorkflowTable/`, `WorkflowControls/`, `BulkActionBar/`, `ActivityList/`, `JsonViewer/`, `StatusBadge/`, `RelativeTime/`, `PayloadEditDialog/`, `MetricsCharts/`, `Toast/`, `ProtectedRoute.tsx`. |
| `frontend/src/types/` | Shared TS interfaces for `Workflow`, `Activity`, `PoolStats`, `AuthUser`, `MetricSample`. |
| `frontend/src/utils/` | `format.ts` (date helpers), `baseUrl.ts` (dynamic API base URL store), `toastBus.ts` (global error toast emitter). |

### Features

#### Workflows page (`/workflows`)
- **Executor (Thread Pool)** panel — workers (active vs. free / max) and executor queue depth (current vs. slot capacity). Note: the executor queue capacity (default 100) is the number of tasks the thread pool can buffer internally — it is separate from the number of workflows waiting in the database. Backed by `GET /pool`.
- **Stats cards** — `ALL`, `NEW`, `Ready to run`, `Waiting retry`, `Running`, `STOPPED`, `FINISHED`, `FAILED`. `Ready to run` and `Waiting retry` filter the list by `RETRY` in the DB (split by `nextRetryAt`); `Running` filters by the in-memory runtime registry — only ids the executor has actually started on a worker thread (not those still queued inside the executor). Cmd/Ctrl-click to multi-select; plain click replaces.
- **Workflow list** — TanStack Table with id, type, state, **relative-time** Created / Last run / Next run (auto-updating, hover for absolute timestamp), current activity name, attempts, error. Click any header on `id`/`createdAt`/`state` to sort (server-side).
- **Pagination** — page-size picker (10 / 20 / 50 / 100), persisted in `localStorage`.
- **Multi-select + bulk actions** — checkbox column on each row. When ≥1 workflow is selected, a sticky `BulkActionBar` appears: `Run now`, `Stop`, `Resume`, `Restart`, plus "By time range…" which builds a `{from, to, states}` filter and dispatches against `/workflows/bulk/*`. Destructive actions (Restart) confirm before firing.
- **"Next run"** ticks every second with an adaptive format. When the deadline has passed it counts upward (`+5s overdue`).

#### Workflow details (`/workflows/:id`)
- Meta block (created / started / next-run / activity count), single-workflow controls (`Run now` / `Stop` / `Resume` / `Restart` with confirm).
- **Edit input** button on the workflow's initial payload (only in `NEW`/`RETRY`/`STOPPED`/`FAILED`) — opens `PayloadEditDialog` with optional JSON validation.
- **Activities grouped by name** with `Restart from here` button on each group header (re-execute that activity and everything after it, earlier ones stay cached).
- Each attempt expands to show input/output JSON; both fields have inline `Edit` buttons.
- **JSON viewer** — inline preview with click-to-expand fullscreen dialog.

#### Metrics (`/metrics`)
- Window picker `5m / 30m / 1h / 6h / 24h / 7d / 14d` (persisted). Bucket size is chosen client-side so wide windows still fit ≤300 points.
- **Pool & queue** chart (`pool_free` + executor queue depth over time).
- **Workflows by state** chart with multi-select Autocomplete — operator adds/removes states; defaults to `RETRY / STOPPED / FAILED`.
- **Throughput** chart computed client-side as the delta of cumulative `cnt_finished` / `cnt_failed` between adjacent buckets.

#### Header (everywhere)
- **Tabs** — `Workflows` / `Metrics`, synced with the URL.
- **Backend switcher** — dropdown lists configured environments and a "Manage…" entry to add/remove. Switching clears React Query cache and re-runs `/auth/me`. State stored in `localStorage`. Cross-origin URLs require CORS on the backend.
- **Refresh interval picker** — `Off / 2s / 5s / 10s / 30s`. Selection persists in `localStorage` and drives `refetchInterval` on every TanStack Query hook.
- **Login page** at `/login` (only when [auth](#authentication) is enabled). When auth is disabled the UI runs as `anonymous` without a login screen.
- **Global error toast** — any failed query/mutation (network, 5xx) surfaces a `Snackbar`; 401s are routed through the auth flow instead.

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
- CSRF is disabled (same-origin SPA with `SameSite=Lax` cookies). HTTP Basic and form login are intentionally turned off.

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
| `workflow.scheduler.queue-capacity` | `100` | Executor task queue capacity before back-pressure (`CallerRunsPolicy`) kicks in. |
| `workflow.scheduler.thread-name-prefix` | `wflow-` | Prefix for worker thread names. |
| `temporal-mini.ui.enabled` | `true` | Mount the REST API + dashboard. |
| `workflow.ui.security.enabled` | `false` | Require login for `/temporal-mini/**`. Needs Spring Security on the classpath. |
| `workflow.ui.username` | `admin` | Username of the single in-memory user provisioned when security is enabled. |
| `workflow.ui.password` | `{noop}admin` | Password (with `{encoder}` prefix). Override in production. |
| `workflow.metrics.enabled` | `true` | Append a row to `wflow.metric_sample` on the configured cadence; powers the metrics chart. |
| `workflow.metrics.sample-interval-ms` | `10000` | Period between samples. |
| `workflow.metrics.retention-days` | `14` | Rows older than this are deleted by the cleanup job. |
| `workflow.metrics.cleanup-cron` | `0 0 3 * * *` | Cron for the retention sweep (default: 03:00 daily, server time). |

The bean name is `workflowExecutor`. Define your own bean of the same name to override the executor entirely:

```java
@Bean(name = WorkflowCoreAutoConfiguration.EXECUTOR_BEAN, destroyMethod = "shutdown")
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

The plugin runs every `mvn` invocation from `generate-resources` onward. To skip the frontend build pass `-Dfrontend.skip=true`:

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

`temporal-mini` owns the `wflow` schema with six tables — two live, two history mirrors, plus metrics and migrator bookkeeping:

Live (mutated by the engine, drive replay-cache):

- `wflow.workflow` — one row per workflow. `started_at` is set on the first pickup; `finished_at` is set **only** when the workflow reaches `FINISHED` (it stays `NULL` for `FAILED`/`STOPPED`/in-flight). The `state` column stores `"BLOCKED"` for `STOPPED` and will transparently read `"RUNNABLE"` as `RETRY` for backward compatibility with older rows.
- `wflow.activity` — one row per **(workflow id, activity name)**: the engine upserts this row on each attempt so it always reflects the latest state (`attempt` = latest attempt number, `success` = latest outcome, `started_at` = first-attempt start, `input_payload`/`output_payload`/`output_type`/`error_message` = latest values). `output_type` stores the FQN of the cached output's runtime class so replay can deserialize without a `Class<O>` token from the caller. `finished_at` is set **only** when the activity has successfully completed; while it is still retrying it stays `NULL`. Wiped by `engine.restart()` and `engine.restartFromActivity()`. The per-attempt audit lives in `wflow.activity_history`.

History (append-only audit, never mutated, never deleted by `restart*`):

- `wflow.workflow_history` — one row per call to `WorkflowEngine.run(Long)` (one scheduler pickup). Inserted at the start of the run, updated at the end with `finished_at`, `outcome` (`FINISHED`/`RETRY`/`FAILED`), `next_retry_at`, and `error_message`. `initial_state` records what state the workflow was in when this pickup began. `pickup_delay_ms` records how long the workflow was waiting between becoming eligible to run (`nextRetryAt` for retries, `createdAt` for first runs) and this pickup actually starting on a worker — captures scheduler poll latency plus executor-queue wait.
- `wflow.activity_history` — one row per individual activity attempt. The live `wflow.activity` row is upserted in place across retries, but this table always **appends** a fresh row per attempt (so you can see "attempt 1 failed at T1, attempt 2 failed at T2, attempt 3 succeeded at T3" even though the live table only carries the latest snapshot). Has FKs to `wflow.workflow_history` (the wrapping pickup), `wflow.workflow` (the parent workflow), and `wflow.activity` (the live row). The `activity_id` FK uses `ON DELETE SET NULL` so `engine.restart()` can still wipe live activity rows — the history row survives with a null `activity_id` and intact `workflow_id` / `workflow_history_id`.

Bookkeeping:

- `wflow.metric_sample` — one row per metrics snapshot (timestamp PK, pool/queue counters and per-state counts including `cnt_retry`).
- `wflow.sql_migrations` — applied version, name, applied-at timestamp.

The migrator runs on startup, scans the classpath for `db/migration/temporal-mini/V*__*.sql`, applies any pending versions in order (each in its own transaction), and inserts a row into `wflow.sql_migrations`. Indexes — `idx_workflow_state_retry`, `idx_activity_workflow_name`, `idx_metric_sample_ts`, `idx_workflow_history_workflow_id_started`, `idx_activity_history_workflow_history_id`, `idx_activity_history_workflow_id_name`, `idx_activity_history_activity_id` — are created by those migrations. `V6` adds the nullable `wflow.workflow.finished_at` and `wflow.workflow_history.pickup_delay_ms` columns. `V7` adds `output_type VARCHAR(512)` to `wflow.activity` and `wflow.activity_history` so the engine can deserialize cached outputs on replay without the caller passing a `Class<O>` token.

> **Upgrading from a Flyway-era deployment.** Old installations have a `flyway_temporal_mini_history` table that the new migrator ignores. Once the new migrator has run successfully (check `SELECT * FROM wflow.sql_migrations`) you can drop the legacy table by hand: `DROP TABLE wflow.flyway_temporal_mini_history;`.

### Concurrency

The scheduler tracks in-flight workflow ids in a `ConcurrentHashMap` (`WorkflowRuntimeRegistry`) so the same workflow is never queued twice across overlapping polls. Each entry carries a status: `SUBMITTED` is set when the scheduler calls `executor.execute(...)` (and is enough to block re-submission), and is flipped to `RUNNING` from inside the worker right before `engine.run(id)` — so the "RUNNING" view reflects what is actually executing, not what is still buffered in the executor's task queue. Within a single workflow, activities are sequential — there is no parallelism inside `run()`.

If you run multiple application instances against the same database, all of them will poll. The current implementation does **not** have row-level locking, so a workflow could in theory be picked up by two instances at once. For multi-instance deployments, either run a single scheduler (`workflow.scheduler.enabled=false` on the others) or wrap the polling query in a `SELECT ... FOR UPDATE SKIP LOCKED` (extension point, not provided out of the box).

### Observability

Every activity and state transition is logged at INFO. Increase to DEBUG on `com.beeline.temporalmini` to see replay-skip messages and the per-poll heartbeat.

#### Micrometer metrics

`temporal-mini` instruments workflow and activity execution through the Micrometer facade — the SDK doesn't bind to a specific backend, so the client picks whichever registry they want (Prometheus, JMX, OTLP, CloudWatch, ...). Wiring is opt-in: the metric beans are guarded by `@ConditionalOnBean(MeterRegistry.class)`, so without Micrometer on the classpath nothing is registered and there is zero overhead.

To enable it in a Spring Boot app, add the registry of your choice plus Actuator:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```properties
management.endpoints.web.exposure.include=prometheus
```

Prometheus then scrapes `/actuator/prometheus`.

**Exported gauges and counters:**

| Metric | Type | What it measures |
|---|---|---|
| `temporalmini.workflows.new` | Counter | Total workflows ever created (increments on `engine.start()`). |
| `temporalmini.workflows.running` | Gauge | Workflows actively executing in the thread pool right now (from `executor.getActiveCount()`). |
| `temporalmini.workflows.queued` | Gauge | `NEW` + `RETRY` rows where `nextRetryAt <= now` — ready for the next poll. |
| `temporalmini.workflows.waiting` | Gauge | `RETRY` rows where `nextRetryAt > now` — sleeping until their retry window opens. |
| `temporalmini.workflows.stopped` | Gauge | Workflows manually paused. |
| `temporalmini.workflows.finished` | Gauge | Workflows completed successfully. |
| `temporalmini.workflows.failed` | Gauge | Workflows that exhausted all retries. |

**Exported timers** (both publish percentile histograms — `_bucket` series for p50/p95/p99):

| Metric | Tags | What it measures |
|---|---|---|
| `temporalmini.workflow.duration` | `workflow`, `status` ∈ `success` / `failure` | One attempt of `Workflow.run(ctx)`. |
| `temporalmini.activity.duration` | `workflow`, `activity`, `status` ∈ `success` / `failure` | The user's activity function only — surrounding cache lookup and persistence not counted. |

Each timer exports `_count` (invocations), `_sum` (total time) and `_max`. Average over a window is `rate(..._sum[5m]) / rate(..._count[5m])`.

---

## FAQ

**Does this scale to thousands of workflows?**
The pulling model is fine for hundreds-to-low-thousands of concurrently in-flight workflows. Beyond that, look at Temporal proper.

**What happens on JVM crash mid-activity?**
The activity row is only written **after** the function returns or throws. A crash during the function leaves no row, so the next replay re-attempts the activity. Side-effects inside the function should therefore be idempotent.

**Can I pass complex objects through `nextPayload`?**
Yes — `String` is just JSON in practice. The framework doesn't interpret it; deserialize it yourself in `run()`.

**How do I share results between activities?**
Use the return value: `Order order = ctx.activity("fetch", policy, () -> backend.load(id));`. The result is serialized once on success and replayed on retry — the engine remembers the runtime class so you don't pass a `Class<O>` token. Pass the result as the input of the next activity (`ctx.activity("ship", order, policy, o -> shipper.send(o))`) — both the input and output get persisted for audit.

**Can I run code outside an activity in `run()`?**
You can, but remember it runs on every replay. Keep it deterministic and side-effect-free (e.g. routing logic, parsing the input payload).

**Can I point the UI at a remote `temporal-mini` deployment?**
Yes — the header has a backend switcher. Add an entry with the absolute base URL (e.g. `https://prod.example.com/temporal-mini/api`) and select it. The remote backend must allow CORS from the SPA host with `Access-Control-Allow-Credentials: true` so the session cookie travels. Same-origin paths like `/temporal-mini/api` always work.

**My UI was working before this upgrade — why did the `/block` and `/unblock` endpoints disappear?**
They were renamed to `/stop` and `/resume`. Same semantics; the database string for the `STOPPED` state is still `"BLOCKED"` so existing workflows keep working without a data migration.

**I'm upgrading from a version that had `RUNNABLE` state — do I need to migrate data?**
No. `WorkflowStateConverter` transparently maps the string `"RUNNABLE"` on read to the new `RETRY` enum value. The migration `V4__rename_runnable_to_retry.sql` renames the `cnt_runnable` column in `wflow.metric_sample` to `cnt_retry` — this runs automatically on startup.
