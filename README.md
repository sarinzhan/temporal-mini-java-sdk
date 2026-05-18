# beeline-workflow

Lightweight durable workflow engine for Spring Boot, backed by PostgreSQL. No external infrastructure — no Temporal cluster, no Conductor, no message broker. Just your Spring app and the database you already have.

You write workflows as plain Java methods, call activities (either through typed interfaces or via a functional API), and the engine guarantees:

- each successful activity runs **at most once** per workflow (replay-safe cache by `(workflow_id, activity_name)`);
- failed activities are **retried** according to a configurable `RetryPolicy` (exponential backoff, non-retryable classes);
- per-activity **timeout** through `CompletableFuture`;
- workflows **survive process restarts** — state lives in Postgres;
- **multi-instance**: any number of replicas pull from the same task queue using `SELECT … FOR UPDATE SKIP LOCKED`;
- **JDK Proxy only** — no CGLIB, no bytecode rewriting.

---

## Table of contents

- [Architecture](#architecture)
- [Quick start](#quick-start)
- [Writing a workflow](#writing-a-workflow)
- [Two styles of activities](#two-styles-of-activities)
- [Retry policies](#retry-policies)
- [Workflow lifecycle & states](#workflow-lifecycle--states)
- [Signals](#signals)
- [Multi-instance deployment](#multi-instance-deployment)
- [Cluster REST API](#cluster-rest-api)
- [Configuration reference](#configuration-reference)
- [Database schema](#database-schema)
- [FAQ](#faq)

---

## Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                          your Spring Boot app                          │
│                                                                        │
│   @WorkflowComponent class OrderWorkflow { ... }                       │
│   @Activity         interface PaymentActivity { ... }                  │
│   @Service          class PaymentActivityImpl implements ... { ... }   │
│                                                                        │
│                              │                                         │
│                              ▼                                         │
│    WorkflowClient ──► workflows + tasks (one tx, INSERT both)          │
│                                                                        │
│    WorkerLoop @Scheduled ──► SELECT FOR UPDATE SKIP LOCKED             │
│         │                                                              │
│         ├─► claim batch (PROCESSING, locked_by, locked_at)             │
│         ├─► thread pool (worker-pool-size)                             │
│         │     │                                                        │
│         │     ▼                                                        │
│         │   WorkflowExecutor                                           │
│         │     ├─► find @WorkflowComponent bean by type                 │
│         │     ├─► set WorkflowContextHolder (ThreadLocal)              │
│         │     ├─► invoke entry method                                  │
│         │     │     │                                                  │
│         │     │     ▼                                                  │
│         │     │   activity stub (JDK Proxy) OR Workflow.activity(...)  │
│         │     │     │                                                  │
│         │     │     ▼                                                  │
│         │     │   ActivityExecutor                                     │
│         │     │     ├─ SELECT activity_results — if COMPLETED, return  │
│         │     │     │     cached + skip execution                      │
│         │     │     ├─ CompletableFuture.get(startToCloseTimeout)      │
│         │     │     ├─ success: UPSERT activity_results,               │
│         │     │     │           INSERT events(ACTIVITY_COMPLETED)      │
│         │     │     └─ failure: → RetryPolicy →                        │
│         │     │           INSERT retries(fire_at=now+backoff)          │
│         │     │           OR mark DEAD                                 │
│         │     └─ finalize: workflow status, event log                  │
│         └─► task → DONE / DEAD                                         │
│                                                                        │
│    RetryScheduler @Scheduled ──► retries where fire_at<=now →          │
│                                  INSERT tasks(PENDING)                 │
│                                                                        │
│    TimeoutWatcher @Scheduled ──► reset stale PROCESSING tasks          │
│                                                                        │
│    InstanceRegistryService @Scheduled(10s) ──► UPSERT instance_registry│
│                                                                        │
└────────────────────────────────────────────────────────────────────────┘
                                  │
                                  ▼
                          ┌────────────────┐
                          │   PostgreSQL   │
                          │                │
                          │  workflows     │
                          │  tasks         │
                          │  activity_*    │
                          │  retries       │
                          │  events        │
                          │  signals       │
                          │  instance_*    │
                          └────────────────┘
```

**Key idea — replay cache.** When an activity is called, the engine first checks `activity_results` for a row matching `(workflow_id, activity_name)`. If `status = 'COMPLETED'`, the cached JSON is deserialized and returned without invoking the activity body. So even when the workflow is re-entered (for example, after a worker crash mid-flight), already-successful steps are skipped.

The runtime class of the result is captured at the first success (`activity_results.result_type`) and used on replay to deserialize back to the right Java type — you don't need to pass a `Class<T>` token.

---

## Quick start

### 1. Add the dependency

```xml
<dependency>
    <groupId>com.beeline</groupId>
    <artifactId>workflow</artifactId>
    <version>0.1.0</version>
</dependency>
```

You also need:
- `spring-boot-starter-data-jpa` (compile-time dep)
- a JDBC driver (PostgreSQL is what the bundled migrations target)
- `spring-boot-starter-web` if you want the cluster REST endpoints

### 2. Configure your DataSource

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/myapp
spring.datasource.username=app
spring.datasource.password=secret
```

Flyway runs `db/migration/V1__*.sql` … `V9__*.sql` from inside the jar on first startup.

### 3. Write an activity

```java
// Interface — must be annotated with @Activity
@Activity
public interface PaymentActivity {
    PaymentResult charge(String orderId, BigDecimal amount);
    void refund(String orderId);
}

// Implementation — plain Spring bean
@Service
public class PaymentActivityImpl implements PaymentActivity {
    public PaymentResult charge(String orderId, BigDecimal amount) {
        // your real payment-gateway call
        return new PaymentResult("tx-" + orderId, "OK");
    }
    public void refund(String orderId) { /* ... */ }
}

public record PaymentResult(String transactionId, String status) {}
```

### 4. Write a workflow

```java
@WorkflowComponent              // value() not set → type = "OrderWorkflow"
public class OrderWorkflow {

    private final PaymentActivity payment = Workflow.newActivityStub(
            PaymentActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryPolicy(RetryPolicy.newBuilder()
                            .setMaxAttempts(3)
                            .setInitialInterval(Duration.ofSeconds(1))
                            .setBackoffCoefficient(2.0)
                            .addNoRetry(IllegalArgumentException.class)
                            .build())
                    .build()
    );

    // single-param entry method — input is deserialized from JSON
    public String processOrder(OrderInput input) {
        PaymentResult result = payment.charge(input.orderId(), input.amount());
        return result.transactionId();
    }
}

public record OrderInput(String orderId, BigDecimal amount) {}
```

### 5. Start a workflow

```java
@RestController
public class OrderController {

    private final WorkflowClient workflowClient;

    public OrderController(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    @PostMapping("/orders")
    public Map<String, UUID> startOrder(@RequestBody OrderInput input) {
        UUID workflowId = workflowClient.startWorkflow("OrderWorkflow", input);
        return Map.of("workflowId", workflowId);
    }
}
```

---

## Writing a workflow

A workflow class is a regular Spring bean annotated with `@WorkflowComponent`. It must expose a single entry method:

- public, non-static, non-synthetic, declared on the class itself;
- if there's exactly **one** such method, it's used automatically;
- otherwise the engine looks for a method named `run`;
- the method may take **0 or 1 parameters**. With 1 parameter, the input JSON from `workflows.input` is deserialized into that type.

**`@WorkflowComponent` is a meta-`@Component`**, so Spring picks the class up via component scan. You can pass a value to override the workflow type:

```java
@WorkflowComponent("order-flow-v2")
public class OrderWorkflow { ... }
```

Default type when value is omitted is the simple class name.

### Determinism

Code in a workflow method **between** activity calls runs on every replay (in this lightweight engine, currently a replay = a fresh worker pickup after a retry-eligible failure). Keep that code deterministic and side-effect-free:

- ✅ parsing input, branching, building activity arguments
- ✅ calling other activities
- ❌ direct DB writes, HTTP calls, file I/O — wrap those in activities
- ❌ `Math.random()`, `Instant.now()` in branching logic — feed timestamps in via activity results if you need them deterministic

---

## Two styles of activities

### A. Typed interface stub (recommended)

```java
private final PaymentActivity payment =
        Workflow.newActivityStub(PaymentActivity.class, options);

// call as a normal method
PaymentResult r = payment.charge(orderId, amount);
```

- Type-safe, IDE-friendly
- Activity name auto-derived as `InterfaceName.methodName` (e.g. `PaymentActivity.charge`)
- Implementation must be a `@Service` (or any Spring bean) implementing the `@Activity` interface — auto-wired into `ActivityRegistry`
- The stub is a JDK Proxy created lazily. **Creating it in a field initializer is safe** — the proxy never accesses any registry at construction time, only when a method is actually invoked inside a workflow execution.

### B. Functional API (familiar to users of the old temporal-mini)

```java
PaymentResult r = Workflow.activity(
        "charge",
        ActivityOptions.newBuilder()
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .setRetryPolicy(RetryPolicy.newBuilder().setMaxAttempts(3).build())
                .build(),
        () -> gateway.charge(orderId, amount)
);

// With explicit input — useful when you want input audit in events later
String ack = Workflow.activity("confirm", r, x -> notifier.send(x.transactionId()));

// Side-effect only (Runnable)
Workflow.activity("audit", () -> auditLog.record(orderId));

// Short forms use ActivityOptions.defaultOptions()
String s = Workflow.activity("simple", () -> svc.doSomething());
```

- No interface required — call any Spring bean (or lambda) directly
- Activity name is **whatever string you pass** — uniqueness within a workflow is **your responsibility**
- Return type is captured at runtime via `result.getClass()`. On replay the engine uses `activity_results.result_type` to deserialize back without a `Class<T>` token

Both styles can be mixed in the same workflow. They share the same cache, retry, timeout, and event semantics — they both delegate to `ActivityExecutor`.

> **Activity names must be unique within a workflow.** Two calls with the same name (and same workflow) collide on the cache key.

---

## Retry policies

```java
RetryPolicy.defaultPolicy();                  // maxAttempts=3, initialInterval=1s, backoff=2.0

RetryPolicy.newBuilder()
    .setMaxAttempts(5)
    .setInitialInterval(Duration.ofSeconds(2))
    .setBackoffCoefficient(2.0)              // delay = initial * (backoff ^ attempt)
    .addNoRetry(IllegalArgumentException.class)
    .addNoRetry(SomeBusinessException.class)
    .build();
```

`maxAttempts` is the **total** number of attempts (initial + retries). Setting `1` = fail-fast.

On failure:
- If the exception is a `NonRetryableException` or matches any `addNoRetry(...)` class → activity is marked `DEAD`, workflow transitions to `FAILED`. No retry.
- Else if `attempt < maxAttempts` → row inserted into `retries(fire_at = now + initialInterval × backoff^attempt)`. `RetryScheduler` will pick it up later and create a fresh `tasks` row.
- Else (attempts exhausted) → activity `DEAD`, workflow `FAILED`.

---

## Workflow lifecycle & states

```
              startWorkflow()
       ┌────────────────────┐
       ▼                    │
   PENDING ──── worker picks up ────┐
                                    ▼
                                 RUNNING ────────┐
                                    │            │
                                    │ success    │ unexpected exception /
                                    ▼            ▼ non-retryable / attempts exhausted
                                COMPLETED      FAILED
```

| State | Persisted | Meaning |
|---|---|---|
| `PENDING` | yes | created, in queue, never executed (transient, usually <2s) |
| `RUNNING` | yes | currently executing on some node, **or** sitting between retry attempts (the in-flight detail is in `retries.fire_at`) |
| `COMPLETED` | yes | terminal — workflow returned normally |
| `FAILED` | yes | terminal — non-retryable error or retries exhausted |

There is **no separate "RETRYING" state** in the database. Between attempts, the workflow stays in `RUNNING` and the `retries` row carries the schedule. UI can derive "waiting for retry" from `tasks` having no `PROCESSING` row + an open `retries` row.

**Currently-executing right now (per node):**

```sql
SELECT t.workflow_id, t.locked_by AS node_id, t.locked_at, t.locked_until
FROM tasks t
WHERE t.status = 'PROCESSING' AND t.locked_until > now();
```

Each instance writes its `instance.id` to `tasks.locked_by` when claiming work. This is the source of truth for "what's running where" across the cluster.

---

## Signals

External code can send named signals to a running workflow; workflows can block waiting for them.

```java
// Inside a workflow
Object payload = Workflow.waitForSignal("approval", Duration.ofMinutes(10));
if (payload == null) {
    throw new NonRetryableException("Approval timeout");
}

// From elsewhere (e.g. a REST controller)
@Autowired SignalBus signalBus;
signalBus.send(workflowId, "approval", Map.of("by", "manager-42"));
```

Signals are persisted in the `signals` table. Delivery is at-least-once-then-consumed: `await` claims the first unconsumed row via `FOR UPDATE SKIP LOCKED` and marks it `consumed = true`.

> **Note:** the current `await` implementation polls every 500 ms while a worker thread is blocked. For long waits (hours, days), this is fine — Postgres load is negligible — but it does keep a worker slot occupied. Consider sizing `worker-pool-size` accordingly if many workflows wait simultaneously.

---

## Multi-instance deployment

Run any number of replicas against the same Postgres. They share the same task queue. Duplicate execution is prevented at the database level via `SELECT … FOR UPDATE SKIP LOCKED`.

### How it works

```
┌────────────┐   poll every Nms      ┌─────────────────────────────────┐
│ Instance 1│ ────────────────────► │            tasks                │
│            │   FOR UPDATE SKIP    │  id  status  locked_by ...      │
│            │   LOCKED LIMIT N     └─────────────────────────────────┘
└────────────┘                                ▲
                                              │ locked_by = node-2
┌────────────┐   poll every Nms              │ (other instance won this row)
│ Instance 2│ ──────────────────────────────┘
└────────────┘
```

Each worker loop does this in one transaction:

1. `SELECT * FROM tasks WHERE status='PENDING' AND scheduled_at<=now() ORDER BY scheduled_at LIMIT N FOR UPDATE SKIP LOCKED`
2. For each returned row: set `status='PROCESSING'`, `locked_by=<this instance id>`, `locked_at=now()`, `locked_until=now()+lockTimeout`
3. Commit

Rows locked by another instance's transaction are silently skipped (that's what `SKIP LOCKED` does). After commit, the worker hands the task to its thread pool and processes it without holding the row lock.

If a worker dies mid-processing, the row stays in `PROCESSING` with a stale `locked_until`. The `TimeoutWatcher` (`@Scheduled`, default 5 s) resets such rows back to `PENDING` so any node can pick them up again.

### Setup

In each replica's config:

```properties
workflow.instance.id=node-1
workflow.instance.internal-url=http://app1:8080
workflow.instance.external-url=https://api.example.com/node-1
```

- `id` must be unique per replica. PK in `instance_registry`, value of `tasks.locked_by`.
- `internal-url` — address other nodes can reach this one in the private network (e.g. docker service name, k8s service). Currently informational; stored in the registry and exposed to the UI for topology display.
- `external-url` — public address the browser-side UI calls. **Required to enable multi-instance mode.**

Validation on startup (fail-fast):
- `external-url` set + `id == "default"` → exception
- `external-url` set + `internal-url` missing → exception

If `external-url` is empty (default), the node runs in **single-instance mode**: no registry rows, no heartbeats, and `/workflow/api/cluster/nodes` returns `{ "nodes": [] }`.

### Heartbeat

When multi-instance is enabled:

- `@PostConstruct`: UPSERT into `instance_registry` (id, internal_url, external_url, last_heartbeat=now)
- `@Scheduled(fixedDelay=10s)`: UPDATE `last_heartbeat=now()`
- `@PreDestroy`: DELETE the row
- A node is considered live when `last_heartbeat > now() - 30s`

### Browser-side fan-out (no server-side aggregator)

The engine does **not** fan out HTTP between nodes to build a global cluster view. Instead, the UI gets the list of nodes from any single instance and queries each node's `external-url` directly.

```
Browser → GET https://nodeA/workflow/api/cluster/nodes
        ← { self: "node-A", nodes:[
              {id:"node-A", externalUrl:"https://nodeA/..."},
              {id:"node-B", externalUrl:"https://nodeB/..."},
              {id:"node-C", externalUrl:"https://nodeC/..."} ]}

Browser → GET https://nodeA/workflow/api/cluster/local
Browser → GET https://nodeB/workflow/api/cluster/local
Browser → GET https://nodeC/workflow/api/cluster/local
        ← per-node { pool, running:[ ... ] }
```

This avoids node-to-node HTTP, timeouts, and one-bad-node-poisons-everyone. If a node is unreachable from the browser, the UI marks it offline; the list of nodes itself still comes from the registry.

`/workflow/api/cluster/*` endpoints are annotated `@CrossOrigin(origins = "*")` to allow the UI loaded from one node to fetch state from peers.

---

## Cluster REST API

| Method | Path | Description |
|---|---|---|
| `GET` | `/workflow/api/cluster/nodes` | List of live nodes from `instance_registry` (`last_heartbeat > now − 30 s`). Always present; returns empty list in single-instance mode. |
| `GET` | `/workflow/api/cluster/local` | This node's pool snapshot (active / queue / max) and its currently-running tasks (`SELECT * FROM tasks WHERE locked_by=<self> AND status='PROCESSING'`). |

### Sample responses

```http
GET /workflow/api/cluster/nodes
```
```json
{
  "self": "node-1",
  "nodes": [
    {
      "id": "node-1",
      "internalUrl": "http://app1:8080",
      "externalUrl": "https://api.example.com/node-1",
      "lastHeartbeat": "2026-05-17T10:42:01Z",
      "self": true
    },
    {
      "id": "node-2",
      "internalUrl": "http://app2:8080",
      "externalUrl": "https://api.example.com/node-2",
      "lastHeartbeat": "2026-05-17T10:41:55Z",
      "self": false
    }
  ]
}
```

```http
GET /workflow/api/cluster/local
```
```json
{
  "nodeId": "node-1",
  "pool": { "active": 3, "queue": 7, "max": 8 },
  "running": [
    {
      "taskId": "2a3b4c...",
      "workflowId": "9f8e7d...",
      "lockedAt": "2026-05-17T10:41:50Z",
      "lockedUntil": "2026-05-17T10:42:50Z"
    }
  ]
}
```

---

## Configuration reference

| Property | Default | Purpose |
|---|---|---|
| `workflow.worker-pool-size` | `4` | Number of worker threads processing tasks |
| `workflow.poll-interval-ms` | `1000` | Worker loop poll interval |
| `workflow.lock-timeout-seconds` | `60` | `locked_until = locked_at + this`. After this expires, TimeoutWatcher resets the task |
| `workflow.retry-poll-interval-ms` | `2000` | RetryScheduler poll interval — how often `retries` is scanned for due retries |
| `workflow.timeout-watcher-interval-ms` | `5000` | TimeoutWatcher poll interval |
| `workflow.instance.id` | `default` | Unique node ID. **Must be set when multi-instance.** |
| `workflow.instance.internal-url` | _(unset)_ | Internal network URL (private DNS, docker service name). Required when `external-url` is set. |
| `workflow.instance.external-url` | _(unset)_ | Public URL for browser-side UI access. Enables multi-instance mode when set. |

---

## Database schema

The engine owns 7 tables in the **`public` schema** (no dedicated schema). Flyway runs them on startup.

| Table | Purpose |
|---|---|
| `workflows` | One row per workflow run. `input`, `result` are JSONB. Holds final outcome. |
| `tasks` | The work queue. One row per scheduled execution attempt. `FOR UPDATE SKIP LOCKED` is the heart of distribution. |
| `activity_results` | Replay cache, one row per `(workflow_id, activity_name)`. Updated in place across retries (so live table stays slim). `result_type` stores the runtime class for type-safe replay. |
| `retries` | Pending retries with `fire_at` schedule. `RetryScheduler` reads, inserts `tasks` rows, marks `processed=true`. |
| `events` | Append-only audit log — `WORKFLOW_STARTED`/`COMPLETED`/`FAILED`, `ACTIVITY_STARTED`/`COMPLETED`/`FAILED`/`RETRYING`. **Not** the source of truth for replay; `activity_results` is. Used by UI / debugging / metrics. |
| `signals` | Inbox for `Workflow.waitForSignal(...)`. Polled by waiting workflows; consumed signals are marked `consumed=true`. |
| `instance_registry` | Heartbeat table for multi-instance discovery. Only populated when `workflow.instance.external-url` is set. |

Flyway migrations:

```
V1__workflows.sql
V2__tasks.sql
V3__events.sql
V4__activity_results.sql
V5__retries.sql
V6__signals.sql
V7__activity_result_type.sql      ← adds activity_results.result_type
V8__tasks_locked_at.sql            ← adds tasks.locked_at
V9__instance_registry.sql          ← instance registry table
```

Notable indexes (all created by the migrations):

- `idx_tasks_poll (status, scheduled_at) WHERE status='PENDING'` — drives the worker poll
- `idx_events_workflow (workflow_id, created_at)` — for UI timeline queries
- `idx_retries_fire (fire_at) WHERE processed=false` — drives RetryScheduler
- `idx_signals_lookup (workflow_id, signal_name, consumed)`
- `idx_instance_registry_heartbeat (last_heartbeat)`

---

## FAQ

**What happens on JVM crash mid-activity?**
The `activity_results` row is only written **after** the activity body returns or throws — so if the JVM dies inside `payment.charge(...)`, no row exists. The next time a worker picks up the workflow, that activity gets re-executed. Make sure activity bodies are idempotent (request-id headers, INSERT … ON CONFLICT, etc.).

**Can two replicas execute the same workflow at the same time?**
No. `FOR UPDATE SKIP LOCKED` ensures only one replica wins the lock on a given `tasks` row. Other replicas see the row as locked and skip it. The lock is released only when the worker commits the claim transaction; after that the worker processes the task without holding the row.

**Do I have to use `@Activity` interfaces? Can I just call methods?**
You have two options. Typed interfaces (`@Activity` + `Workflow.newActivityStub`) give you type safety and auto-naming. Or use `Workflow.activity(name, options, () -> ...)` and pass any lambda. Same engine semantics, different ergonomics.

**Why is the workflow execution synchronous? In real Temporal, the workflow worker is freed up while the activity runs.**
This engine is designed to be lightweight. Activities run in the same worker thread as the workflow (with a `CompletableFuture` wrapper for timeout enforcement). For short-to-medium activities (seconds, minutes) this is simpler and faster — no replay round-trip per step. For very long activities (hours) the worker slot is held for that whole time; consider sizing the pool accordingly or splitting the long step.

**Do events drive replay?**
No. **`activity_results` is the source of truth for replay.** `events` is an append-only audit log for UI and debugging.

**Does the engine support workflow versioning?**
Not built-in. If you change the activity order in a workflow that's already running, the cache lookup by activity_name may return stale results. For breaking changes: rename the workflow type, or restart-from-activity (purge cached rows and re-execute).

**Where is the UI?**
The Java backend is rebuilt. The previous React SPA is being adapted to the new schema and will be wired in via `web/controller/*` REST endpoints — currently only the cluster endpoints are in place. See `web/dto/*` for the shape of data the UI will consume.
