# temporal-lite

Лёгкий движок **долговечного (durable) выполнения** для Spring Boot. Идея заимствована у
[Temporal](https://docs.temporal.io), но в минимальном объёме: только **durable execution + ретраи**.
Нужны лишь ваше Spring Boot-приложение и PostgreSQL.

Вы пишете бизнес-логику обычным Java-методом, вызываете из него активности (внешние эффекты) через
inline-лямбды, а движок гарантирует, что:

- каждая успешная активность выполнится **не более одного раза** — её результат сохраняется в историю
  и переиспользуется при повторном проигрывании (replay);
- упавшие активности **повторяются** по настраиваемой `RetryPolicy` (экспоненциальный backoff,
  списки `retryOn` / `noRetryOn`);
- workflow **переживают рестарт процесса** — всё состояние лежит в Postgres, а не в памяти;
- можно запустить **несколько реплик** приложения — они разбирают задачи из общей таблицы через
  `SELECT … FOR UPDATE SKIP LOCKED`, а аренда (lease) с фенсингом не даёт двум узлам выполнять один
  workflow одновременно.

> Чего здесь **нет** (сознательно убрано ради простоты): signals, queries, updates, таймеры
> (`sleep`/`await`), типизированные стабы активностей, отдельный worker-поток, UI. Если нужны эти
> возможности — берите полноценный Temporal.

---

## Модель выполнения

Одно выполнение workflow идёт **в одном потоке**:

1. Старт workflow создаёт одну задачу `workflow`.
2. Реплика забирает задачу под арендой и прогоняет метод workflow целиком. Тело каждой активности
   (лямбда) выполняется **в том же потоке**.
3. Активность идентифицируется **порядковым счётчиком (seq)** — порядком вызова. Имя необязательно и
   нужно лишь для читаемости истории.
   - если в истории уже есть `ACTIVITY_COMPLETED` для этого seq → результат берётся из истории, лямбда
     **не вызывается** (replay);
   - иначе лямбда выполняется; успех пишет `ACTIVITY_COMPLETED`.
4. Если активность упала и ретрай положен по политике — пишется `ACTIVITY_RETRY_SCHEDULED` + строка в
   таблицу `wflow.schedule` (с временем следующей попытки), и workflow **паркуется** (поток
   освобождается, слот пула не занят на время backoff). Планировщик в срок снова ставит задачу
   `workflow`, workflow переигрывается и делает следующую попытку.
5. Если ретрай не положен (исчерпаны попытки / `noRetryOn` / `NonRetryableException`) — пишется
   терминальный `ACTIVITY_FAILED`, и workflow падает (`FAILED`).
6. Если узел умер посреди выполнения — аренда протухает, `TimeoutWatcher` переотдаёт задачу другой
   реплике, та переигрывает с начала, пропуская уже завершённые активности.

Источник правды — таблица событий `wflow.events`. Таблица `wflow.schedule` управляет только **временем**
пробуждения припаркованных workflow.

---

## Установка

### 1. Зависимость
```xml
<dependency>
    <groupId>com.beeline</groupId>
    <artifactId>workflow</artifactId>
    <version>0.1.0</version>
</dependency>
```
Также: `spring-boot-starter-data-jpa`, JDBC-драйвер PostgreSQL, и `spring-boot-starter-web`
(если нужен REST). Требуется Spring Boot 4.x, сериализация — Jackson 3 (`tools.jackson`).

### 2. Схема БД
Движок хранит всё в схеме `wflow`. Примените `schema.sql` один раз перед стартом:
```bash
psql "$DB_URL" -f schema.sql
```

### 3. Подключение
```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/myapp
spring.datasource.username=app
spring.datasource.password=secret
spring.jpa.hibernate.ddl-auto=none
```
Автоконфигурация Spring Boot поднимет воркер, реестр и REST-слой сама.

Для локальной разработки есть `docker-compose.yml` с Postgres.

---

## Пишем workflow

Активности — это обычные Spring-бины:
```java
@Service
public class OrderActivities {
    public String reserve(String orderId, double amount) { /* внешний эффект */ }
    public String charge(String reservationId, double amount) { ... }
    public void notifyCustomer(String orderId, String txnId) { ... }
}
```

Интерфейс workflow — только один `@WorkflowMethod`:
```java
@WorkflowInterface
public interface OrderWorkflow {
    @WorkflowMethod
    String process(OrderRequest request);
}
```

Реализация — активности вызываются inline-лямбдами; замыкание держит ссылку на бин:
```java
@WorkflowComponent
public class OrderWorkflowImpl implements OrderWorkflow {

    private final OrderActivities activities;
    public OrderWorkflowImpl(OrderActivities activities) { this.activities = activities; }

    @Override
    public String process(OrderRequest req) {
        ActivityOptions opts = ActivityOptions.newBuilder()
            .setStartToCloseTimeout(Duration.ofSeconds(30))
            .setRetryPolicy(RetryPolicy.newBuilder()
                .setMaxAttempts(5)
                .setInitialInterval(Duration.ofSeconds(1))
                .setBackoffCoefficient(2.0)
                .setMaxInterval(Duration.ofSeconds(20))
                .addNoRetry(IllegalArgumentException.class)   // эти ошибки НЕ ретраить
                .build())
            .build();

        // Supplier: значение, без входа
        String reservationId = Workflow.activity("reserve", opts,
                () -> activities.reserve(req.getOrderId(), req.getAmount()));

        String txnId = Workflow.activity("charge", opts,
                () -> activities.charge(reservationId, req.getAmount()));

        // Runnable: void, дефолтные опции
        Workflow.activity("notify", () -> activities.notifyCustomer(req.getOrderId(), txnId));

        return "order " + req.getOrderId() + " charged: " + txnId;
    }
}
```

### Перегрузки `Workflow.activity(...)`
Имя и `ActivityOptions` необязательны. Поддерживаются:

| Тип | Сигнатура (упрощённо) | Вход | Результат |
|---|---|---|---|
| `Supplier<T>` | `T activity([name,] [opts,] () -> ...)` | нет | да |
| `Runnable`    | `void activity([name,] [opts,] () -> ...)` | нет | нет |
| `Function<I,O>` | `O activity([name, opts,] in, x -> ...)` | да | да |
| `Consumer<I>`   | `void activity([name, opts,] in, x -> ...)` | да | нет |

> Замечание про Java: пару с входом (`Function`/`Consumer`) компилятор не всегда может развести для
> лямбды, возвращающей значение, — при неоднозначности приведите лямбду
> (`(Function<I,O>) x -> ...`) либо захватите вход в `Supplier`/`Runnable` (без аргумента).

### Детерминизм
Для недетерминированных чтений (текущее время, случайные id) используйте `Workflow.sideEffect(...)`:
её результат тоже пишется в историю и переигрывается. Для версионирования кода —
`Workflow.getVersion(...)`. Всё остальное в методе workflow должно быть детерминированным; внешний
мир — только через активности.

### Идемпотентность активностей (effectively-once)
Активности выполняются **at-least-once**: тело может запуститься больше одного раза (ретрай; повтор
при крахе в окне «эффект сделан, но `ACTIVITY_COMPLETED` ещё не записан»; редкий конкурентный дубль
от двух задач одного workflow в кластере). Истинный exactly-once через границу процесса невозможен —
нельзя в одной транзакции и сделать внешний вызов, и зафиксировать факт у себя.

Практическое решение — **effectively-once через идемпотентность**: движок даёт каждой активности
стабильный ключ `Workflow.currentActivityKey()` вида `wf:<workflowId>:<seq>`, **одинаковый на всех
ретраях и реплеях** одной и той же активности. Передайте его во внешнюю систему как ключ дедупликации:

```java
String txnId = Workflow.activity("charge", opts, String.class,
        () -> payments.charge(orderId, Workflow.currentActivityKey())); // Idempotency-Key
```

Гарантия «ровно один эффект» держится только если **принимающая сторона дедуплицирует** по этому
ключу (например `Idempotency-Key` у платёжного провайдера или `INSERT ... ON CONFLICT DO NOTHING`).
`Workflow.currentActivityKey()`/`currentActivity()` доступны только **изнутри тела активности**.

---

## Запуск и чтение

Программно:
```java
@Autowired WorkflowClient client;

WorkflowHandle<String> h = client.start(OrderWorkflow.class, request);
Long id = h.getInstanceId();
String result = h.getResult();   // ждёт COMPLETED, бросает при FAILED
```

REST:
- `POST /workflow/api/workflows/{type}` (тело = input JSON) → `{ "workflowId": 123, "type": "OrderWorkflow" }`
- `GET  /workflow/api/workflows/{id}` → `{ status, result, error, completedAt }`
- `GET  /workflow/api/workflows/{id}/events` → история событий (source of truth)

---

## RetryPolicy

```java
RetryPolicy.newBuilder()
    .setMaxAttempts(5)
    .setInitialInterval(Duration.ofSeconds(1))
    .setBackoffCoefficient(2.0)
    .setMaxInterval(Duration.ofSeconds(20))
    .addRetryOn(IOException.class)               // если список задан — ретраить ТОЛЬКО эти
    .addNoRetry(IllegalArgumentException.class)  // и никогда эти
    .build();
```
Активность ретраится, если: попытки не исчерпаны, исключение не в `noRetryOn`, оно не
`NonRetryableException`, и (список `retryOn` пуст **или** исключение в нём). Backoff между попытками
проходит через парковку — рабочий поток на это время свободен.

> Совет: держите `maxInterval`/`maxAttempts` разумными. Долгих durable-ожиданий (`sleep` на дни) здесь
> нет — это вне области движка.

---

## Несколько реплик

Запустите несколько экземпляров приложения над одной БД. Каждая реплика опрашивает `wflow.tasks`
(`FOR UPDATE SKIP LOCKED`), берёт задачу под арендой с фенсинг-токеном и периодически продлевает её.
Если узел умирает, `TimeoutWatcher` сбрасывает протухшую аренду и задача достаётся другой реплике.
Каждая запись в историю проверяет владение арендой, поэтому «отставший» узел не может испортить
состояние.

Для многоузлового режима задайте уникальные `workflow.instance.id` и `workflow.instance.external-url`
на каждом узле.

---

## Тесты

- `mvn test` — unit-тесты (`*Test`), без внешних зависимостей.
- `mvn verify` — то же плюс интеграционные тесты (`*IT`) на Testcontainers с настоящим PostgreSQL.
  **Нужен запущенный Docker.**

---

## Конфигурация

| Свойство | По умолчанию | Назначение |
|---|---|---|
| `workflow.worker-pool-size` | 4 | число потоков воркера; им же ограничен пул исполнения тел активностей |
| `workflow.poll-interval-ms` | 1000 | период опроса задач |
| `workflow.lock-timeout-seconds` | 60 | срок аренды задачи |
| `workflow.lease-renew-interval-ms` | 20000 | период продления аренды |
| `workflow.retry-poll-interval-ms` | 2000 | период опроса `wflow.schedule` |
| `workflow.timeout-watcher-interval-ms` | 5000 | период сброса протухших аренд |
| `workflow.instance.id` | `default` | id узла (уникальный при мульти-реплике) |
| `workflow.instance.external-url` | — | включает реестр инстансов |

---

## Правила

- Метод workflow должен быть **детерминированным**: одинаковая история → одинаковая последовательность
  вызовов активностей (по seq). Не делайте в нём прямых обращений к БД/сети/времени — только через
  активности или `sideEffect`.
- Порядок и количество `Workflow.activity(...)` не должны зависеть от недетерминированных данных —
  иначе при replay seq разойдётся (движок выбросит ошибку недетерминизма).
- Активность может выполниться повторно при крахе между её завершением и записью `ACTIVITY_COMPLETED`
  (семантика at-least-once) — делайте внешние эффекты идемпотентными, где это важно.

---

## Как это устроено внутри

### Таблицы (схема `wflow`)

| Таблица | Назначение |
|---|---|
| `workflows` | Экземпляры workflow: тип, статус, input/result/error |
| `tasks` | Очередь задач. Реплики тянут `PENDING` через `FOR UPDATE SKIP LOCKED`, берут под арендой (`lock_token`, `locked_until`) |
| `events` | **Источник правды** для replay: история событий по каждому workflow (append-only, упорядочена по `id`) |
| `schedule` | Когда разбудить припаркованный workflow (backoff ретраев). Управляет только временем, не состоянием |
| `instance_registry` | Реестр узлов в мульти-реплике (только если задан `workflow.instance.external-url`) |

### События (`events.event_type`)

`WORKFLOW_CREATED`, `WORKFLOW_TASK_QUEUED`, `WORKFLOW_TASK_STARTED`, `WORKFLOW_TASK_COMPLETED`,
`WORKFLOW_COMPLETED`, `WORKFLOW_FAILED`; `ACTIVITY_STARTED`, `ACTIVITY_COMPLETED`, `ACTIVITY_FAILED`,
`ACTIVITY_RETRY_SCHEDULED`; `SIDE_EFFECT_RECORDED`, `VERSION_MARKER`. Командные события
(`ACTIVITY_*`, `SIDE_EFFECT`, `VERSION`) несут `seq` — монотонный счётчик команд внутри workflow.

### Компоненты

| Компонент | Роль |
|---|---|
| `WorkerLoopImpl` | Поллер задач; claim под арендой, продление аренды, запуск decision-turn в пуле потоков |
| `WorkflowExecutor` | Один decision-turn: грузит историю, прогоняет метод workflow inline, пишет лайфсайкл-события |
| `HistoryCursor` | Раздаёт `seq`, отвечает «эта команда уже завершена в истории?», ловит недетерминизм |
| `ActivityCommandHandler` | Inline-исполнение активити: replay-кеш по seq, ретрай через парковку + строку в `schedule`. Тело активности выполняется в ограниченном пуле (размер = `worker-pool-size`); при насыщении пула submit отклоняется (`AbortPolicy`) и турн паркуется с короткой backpressure-перепланировкой, не сжигая попытку; по `startToCloseTimeout` поток активности прерывается |
| `WakeupSchedulerImpl` | Поллер `schedule`: в срок ставит задачу `workflow`, чтобы припаркованный workflow проснулся |
| `TimeoutWatcherImpl` | Сбрасывает протухшие аренды → задачу подхватывает другая реплика |
| `WorkflowClientImpl` | Старт workflow (запись начального состояния + задачи) |

### Поток выполнения

```
start → tasks(workflow) ──claim+lease──> WorkflowExecutor.execute
   │                                          │ replay events по seq
   │                                          │ метод workflow (inline)
   │                                          ▼
   │                            Workflow.activity(...) ── ActivityCommandHandler
   │                                          │   seq есть COMPLETED? → кеш
   │                                          │   иначе запустить лямбду
   │                                ┌─────────┴─────────┐
   │                          успех │                   │ ошибка (ретрай положен)
   │                                ▼                   ▼
   │                       ACTIVITY_COMPLETED   ACTIVITY_RETRY_SCHEDULED + schedule(fireAt)
   │                                │                   │ + park (turn done)
   │                                ▼                   ▼
   │                       workflow продолжает   WakeupScheduler в срок → tasks(workflow) → replay
   ▼
WORKFLOW_COMPLETED / WORKFLOW_FAILED
```

---

## FAQ

**Почему активность — лямбда, а не интерфейс?** В однопоточной inline-модели тело активити выполняется
в потоке workflow, сериализуется только результат. Замыкание захватывает нужные бины напрямую — нет
интерфейса, impl и реестра. (В Temporal стабы нужны, потому что активити уходит на отдельный worker.)

**Что если лямбда вернёт значение, а я его проигнорирую и компилятор ругается на неоднозначность?**
Используйте форму без аргумента (`Supplier`/`Runnable`, захватив вход в замыкание) или приведите лямбду
к нужному типу. См. таблицу перегрузок выше.

**Долгий backoff заблокирует поток?** Нет — между попытками workflow паркуется, поток и слот пула
свободны; пробуждение приходит из таблицы `schedule`.

**Можно ли ждать внешнего события (как signal)?** Нет, signals/queries/updates и `sleep`/`await`
убраны. Это движок durable-выполнения с ретраями, не оркестратор долгих ожиданий.

**Активность выполнится ровно один раз?** Успешная — да, при нормальной работе (результат кешируется).
Но при крахе между выполнением и записью `ACTIVITY_COMPLETED` возможен повтор (at-least-once) — делайте
эффекты идемпотентными.
