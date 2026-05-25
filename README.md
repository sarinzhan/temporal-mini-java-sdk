# temporal-lite

Лёгкий движок **долговечных (durable) workflow** для Spring Boot. Идеи и API заимствованы у [Temporal](https://docs.temporal.io), но без отдельного кластера, без message-broker'а и без кодогенерации — нужны только ваше Spring Boot-приложение и PostgreSQL, который у вас наверняка уже есть.

Вы пишете бизнес-логику обычными Java-методами, вызываете из них активности, а движок гарантирует, что:

- каждая успешная активность выполнится **не более одного раза** — её результат сохраняется в историю и переиспользуется при повторном проигрывании;
- упавшие активности **повторяются** по настраиваемой `RetryPolicy` (экспоненциальный backoff);
- workflow **переживают рестарт процесса** — всё состояние лежит в Postgres, а не в памяти;
- можно запустить **сколько угодно реплик** приложения — они разбирают задачи из одной очереди через `SELECT … FOR UPDATE SKIP LOCKED`;
- workflow можно **усыплять на дни** (`sleep`), слать им **сигналы**, спрашивать **состояние** (query) и **менять** его на лету (update).

> Если вы знакомы с Temporal — здесь те же понятия: Workflow, Activity, Signal, Query, Update, Timer, Side Effect, Retry Policy, версионирование. Просто меньше инфраструктуры. По ходу текста я ссылаюсь на оригинальную документацию Temporal — там те же концепции описаны подробнее.

---

## Содержание

- [Чем temporal-lite отличается от Temporal](#чем-temporal-lite-отличается-от-temporal)
- [Установка и запуск](#установка-и-запуск)
- [Основные понятия](#основные-понятия)
- [Пишем первый workflow](#пишем-первый-workflow)
- [Активности](#активности)
- [Политика повторов (RetryPolicy)](#политика-повторов-retrypolicy)
- [Таймеры и ожидание: `sleep` и `await`](#таймеры-и-ожидание-sleep-и-await)
- [Сигналы](#сигналы)
- [Queries — чтение состояния](#queries--чтение-состояния)
- [Updates — изменение состояния](#updates--изменение-состояния)
- [Детерминизм: `sideEffect` и версионирование](#детерминизм-sideeffect-и-версионирование)
- [Запуск и управление workflow](#запуск-и-управление-workflow)
- [Правила, которым нужно следовать](#правила-которым-нужно-следовать)
- [Конфигурация](#конфигурация)
- [Несколько реплик (multi-instance)](#несколько-реплик-multi-instance)
- [REST API и UI](#rest-api-и-ui)
- [Как это устроено внутри (архитектура)](#как-это-устроено-внутри-архитектура)
- [FAQ](#faq)

---

## Чем temporal-lite отличается от Temporal

| | Temporal | temporal-lite |
|---|---|---|
| Инфраструктура | отдельный кластер + БД | только Postgres |
| Worker | отдельный процесс | те же бины внутри вашего Spring-приложения |
| Регистрация | через `WorkflowClient`/`Worker` API | через аннотации Spring (`@WorkflowComponent`, `@Activity`) |
| Активности | выполняются на отдельном worker'е, workflow-поток свободен | выполняются **в том же потоке** воркера (синхронно, с таймаутом) |
| Стабы активностей | кодогенерация / динамические прокси | только JDK Proxy, без CGLIB и байткода |
| Хранилище истории | внутреннее | таблицы в схеме `wflow` вашего Postgres |
| Масштабирование | через task queue кластера | реплики приложения тянут задачи из общей таблицы |

Это не замена Temporal для нагрузок «миллион workflow в секунду». Это способ получить **долговечное выполнение** там, где разворачивать целый кластер — оверкилл.

---

## Установка и запуск

### 1. Подключите зависимость

```xml
<dependency>
    <groupId>com.beeline</groupId>
    <artifactId>workflow</artifactId>
    <version>0.1.0</version>
</dependency>
```

Также понадобятся (если их ещё нет в вашем приложении):

- `spring-boot-starter-data-jpa`
- JDBC-драйвер PostgreSQL
- `spring-boot-starter-web` — если нужны REST-эндпоинты и UI

> Требуется Spring Boot 4.x. Сериализация — Jackson 3 (`tools.jackson`).

### 2. Создайте схему в базе

Движок хранит всё в схеме `wflow`. Схема **не создаётся автоматически** — примените `schema.sql` (лежит в ресурсах) к вашей базе один раз перед стартом:

```bash
psql "$DB_URL" -f schema.sql
```

### 3. Настройте подключение к базе

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/myapp
spring.datasource.username=app
spring.datasource.password=secret
spring.jpa.hibernate.ddl-auto=none
```

Автоконфигурация Spring Boot поднимет воркер, реестры и REST-слой сама — отдельной аннотации `@Enable…` не нужно.

Для локальной разработки в репозитории есть `docker-compose.yml` с Postgres.

---

## Основные понятия

| Понятие | В temporal-lite | Аналог в Temporal |
|---|---|---|
| **Workflow** | класс с `@WorkflowComponent` и одним методом-входом | [Workflow](https://docs.temporal.io/workflows) |
| **Activity** | интерфейс с `@Activity` (или просто лямбда) | [Activity](https://docs.temporal.io/activities) |
| **Timer** | `Workflow.sleep(Duration)` | [Durable Timer](https://docs.temporal.io/workflows#timer) |
| **Signal** | `Workflow.waitForSignal(...)` + REST | [Signal](https://docs.temporal.io/sending-messages#signals) |
| **Query** | метод с `@QueryMethod` | [Query](https://docs.temporal.io/sending-messages#queries) |
| **Update** | метод с `@UpdateMethod` | [Update](https://docs.temporal.io/sending-messages#updates) |
| **Side Effect** | `Workflow.sideEffect(...)` | [Side Effect](https://docs.temporal.io/workflows#side-effects) |
| **Versioning** | `Workflow.getVersion(...)` | [Patching/Versioning](https://docs.temporal.io/patching) |
| **Retry Policy** | `RetryPolicy` | [Retry Policy](https://docs.temporal.io/encyclopedia/retry-policies) |

Главная идея, общая с Temporal: **код workflow проигрывается заново** (replay) после рестартов и пауз. Поэтому он должен быть детерминированным, а все обращения к внешнему миру — спрятаны в активности. Подробнее — в разделе [Правила, которым нужно следовать](#правила-которым-нужно-следовать).

---

## Пишем первый workflow

### Активность

Активность — это всё «грязное»: вызовы по сети, запись в БД, обращения к внешним API. Объявите интерфейс с `@Activity`, а реализацию сделайте обычным Spring-бином.

```java
@Activity
public interface PaymentActivity {
    PaymentResult charge(String orderId, BigDecimal amount);
    void refund(String orderId);
}

@Service
public class PaymentActivityImpl implements PaymentActivity {
    @Override
    public PaymentResult charge(String orderId, BigDecimal amount) {
        // реальный вызов платёжного шлюза
        return new PaymentResult("tx-" + orderId, "OK");
    }
    @Override
    public void refund(String orderId) { /* ... */ }
}

public record PaymentResult(String transactionId, String status) {}
```

Реализация подхватывается автоматически: любой бин, реализующий `@Activity`-интерфейс, попадает в реестр активностей.

### Workflow

Workflow — это класс с `@WorkflowComponent` и **одним методом-входом**. Внутри метода вы оркеструете активности.

```java
@WorkflowComponent              // тип workflow = "OrderWorkflow" (имя класса)
public class OrderWorkflow {

    private final PaymentActivity payment = Workflow.newActivityStub(
            PaymentActivity.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(30))
                    .setRetryPolicy(RetryPolicy.newBuilder()
                            .setMaxAttempts(3)
                            .addNoRetry(IllegalArgumentException.class)
                            .build())
                    .build());

    // метод-вход: один параметр десериализуется из JSON-входа workflow
    public String processOrder(OrderInput input) {
        PaymentResult result = payment.charge(input.orderId(), input.amount());
        return result.transactionId();
    }
}

public record OrderInput(String orderId, BigDecimal amount) {}
```

Правила определения метода-входа:

- метод должен быть `public`, не `static`, объявлен в самом классе;
- если такой публичный метод **ровно один** — он и есть вход;
- если их несколько — движок ищет метод с именем `run`;
- параметров — **0 или 1**. Если один, вход workflow (JSON) десериализуется в его тип. Возвращаемое значение становится результатом workflow.

Имя типа workflow по умолчанию — имя класса. Можно переопределить: `@WorkflowComponent("order-flow-v2")`.

---

## Активности

Активности можно вызывать **двумя способами** — выбирайте по вкусу, семантика (кэш, повторы, таймаут) одинаковая.

### A. Типизированный стаб (рекомендуется)

```java
private final PaymentActivity payment =
        Workflow.newActivityStub(PaymentActivity.class, options);

// вызываем как обычный метод
PaymentResult r = payment.charge(orderId, amount);
```

- типобезопасно, удобно в IDE;
- имя активности выводится как `Интерфейс.метод` (например, `PaymentActivity.charge`);
- стаб — это JDK Proxy, его **безопасно создавать прямо в поле** класса: до первого вызова он ничего не делает;
- `Workflow.newActivityStub(PaymentActivity.class)` без опций использует значения по умолчанию.

### B. Функциональный API

Когда заводить интерфейс лень или нужен разовый вызов:

```java
// с опциями
PaymentResult r = Workflow.activity("charge",
        ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build(),
        () -> gateway.charge(orderId, amount));

// с явным входом
String ack = Workflow.activity("confirm", r, x -> notifier.send(x.transactionId()));

// только побочный эффект (Runnable), без результата
Workflow.activity("audit", () -> auditLog.record(orderId));

// короткая форма — опции по умолчанию
String s = Workflow.activity("simple", () -> svc.doSomething());
```

> **Имя активности должно быть уникальным внутри одного workflow.** Это ключ кэша результата. Два вызова с одинаковым именем в одном workflow столкнутся. У типизированных стабов имя выводится автоматически, у функционального API — на вашей ответственности.

Оба стиля можно смешивать в одном workflow.

---

## Политика повторов (RetryPolicy)

Аналог [Retry Policy в Temporal](https://docs.temporal.io/encyclopedia/retry-policies).

```java
RetryPolicy.defaultPolicy();   // maxAttempts=3, initialInterval=1s, backoff=2.0, maxInterval=10m

RetryPolicy.newBuilder()
    .setMaxAttempts(5)                          // всего попыток (первая + повторы); 1 = без повторов
    .setInitialInterval(Duration.ofSeconds(2))
    .setBackoffCoefficient(2.0)                 // задержка = initial * (backoff ^ номер_попытки)
    .setMaxInterval(Duration.ofMinutes(10))     // потолок задержки
    .addNoRetry(IllegalArgumentException.class) // эти исключения не повторяем
    .build();
```

Что происходит при падении активности:

- бросили `NonRetryableException` или исключение из `addNoRetry(...)` → повтора **нет**, workflow → `FAILED`;
- попытки ещё остались → активность переедет в таблицу повторов и выполнится снова через `initial × backoff^попытка`;
- попытки исчерпаны → workflow → `FAILED`.

`ActivityOptions` также задаёт `startToCloseTimeout` (по умолчанию 1 минута) — максимальное время одной попытки.

---

## Таймеры и ожидание: `sleep` и `await`

### `Workflow.sleep(Duration)` — долговечный таймер

```java
public void run() {
    sendWelcomeEmail();
    Workflow.sleep(Duration.ofDays(3));   // воркер освобождается на 3 дня
    sendFollowUpEmail();
}
```

Это **не** `Thread.sleep`. Workflow «паркуется»: поток воркера освобождается, состояние остаётся в базе, а через указанное время задача снова попадёт в очередь и код продолжится с этого места. Можно спать минуты, часы, дни. Аналог [Durable Timer](https://docs.temporal.io/workflows#timer).

### `Workflow.await(timeout, condition)` — ждать условие

Блокирует workflow, пока `condition` не станет `true` (обычно условие меняется из update-метода или сигнала).

```java
boolean approved = Workflow.await(Duration.ofHours(24), () -> this.approved);
if (!approved) {
    // вышли по таймауту
}
```

Возвращает `true`, если условие выполнилось, и `false`, если истёк таймаут.

---

## Сигналы

Сигнал — это асинхронное сообщение снаружи внутрь работающего workflow. Аналог [Signal](https://docs.temporal.io/sending-messages#signals).

Внутри workflow ждём сигнал:

```java
Object payload = Workflow.waitForSignal("approval", Duration.ofMinutes(10));
if (payload == null) {
    throw new NonRetryableException("Approval timeout");
}
```

`waitForSignal(name)` без таймаута ждёт по умолчанию 5 минут.

Послать сигнал снаружи — через REST (см. ниже) или программно через `SignalBus`:

```java
@Autowired SignalBus signalBus;
signalBus.send(workflowId, "approval", Map.of("by", "manager-42"));
```

---

## Queries — чтение состояния

Query — это **read-only** запрос текущего состояния работающего (или уже завершённого) workflow, без его изменения. Аналог [Query](https://docs.temporal.io/sending-messages#queries).

Пометьте метод `@QueryMethod`:

```java
@WorkflowComponent
public class OrderWorkflow {

    private String stage = "created";

    public String run(OrderInput in) {
        stage = "charging";
        payment.charge(in.orderId(), in.amount());
        stage = "done";
        return "ok";
    }

    @QueryMethod                       // имя query = имя метода ("currentStage")
    public String currentStage() {
        return stage;
    }
}
```

Имя query по умолчанию — имя метода; можно задать явно: `@QueryMethod(name = "stage")`.

Вызвать query:

```http
POST /workflow/api/workflows/{id}/query/currentStage
Content-Type: application/json

{ "args": [] }
```

> Query-метод выполняется на **свежем экземпляре** workflow, прогнанном по истории, поэтому он не должен ничего менять и не должен вызывать активности, `sleep` и т.п. Только чтение полей.

---

## Updates — изменение состояния

Update — это запрос снаружи, который **меняет** состояние workflow и возвращает результат. Аналог [Update](https://docs.temporal.io/sending-messages#updates).

```java
@WorkflowComponent
public class OrderWorkflow {

    private boolean approved = false;

    public String run(OrderInput in) {
        boolean ok = Workflow.await(Duration.ofHours(24), () -> approved);
        return ok ? "approved" : "rejected";
    }

    @UpdateMethod                      // имя update = имя метода ("approve")
    public String approve(String who) {
        this.approved = true;
        return "approved by " + who;
    }
}
```

Вызвать update (синхронно дождётся результата):

```http
POST /workflow/api/workflows/{id}/update/approve?timeoutMs=30000
Content-Type: application/json

{ "args": ["manager-42"] }
```

Update-методы исполняются движком после каждого «хода» workflow, а результат сохраняется в `update_requests`.

---

## Детерминизм: `sideEffect` и версионирование

Поскольку код workflow проигрывается заново, любая недетерминированность (случайные числа, текущее время, UUID) сломает воспроизведение. Для таких случаев есть два инструмента — как в Temporal.

### `Workflow.sideEffect` — записать недетерминированное значение один раз

Выполняет функцию **один раз** и сохраняет результат в историю; при повторном проигрывании возвращает сохранённое значение, не вызывая функцию заново. Аналог [Side Effect](https://docs.temporal.io/workflows#side-effects).

```java
String requestId = Workflow.sideEffect(String.class, () -> UUID.randomUUID().toString());
```

### `Workflow.getVersion` — безопасно менять логику работающих workflow

Когда нужно изменить код workflow, для которого уже есть запущенные экземпляры, используйте версионирование вместо «просто поправить и задеплоить». Аналог [Patching/Versioning](https://docs.temporal.io/patching).

```java
int v = Workflow.getVersion("use-new-payment", Workflow.DEFAULT_VERSION, 1);
if (v == Workflow.DEFAULT_VERSION) {
    legacyCharge();      // старые workflow идут по старому пути
} else {
    newCharge();         // новые — по новому
}
```

---

## Запуск и управление workflow

### Запуск из кода

Внедрите `WorkflowClient` и стартуйте workflow по его типу:

```java
@RestController
public class OrderController {

    private final WorkflowClient workflowClient;

    public OrderController(WorkflowClient workflowClient) {
        this.workflowClient = workflowClient;
    }

    @PostMapping("/orders")
    public Map<String, Long> start(@RequestBody OrderInput input) {
        Long workflowId = workflowClient.startWorkflow("OrderWorkflow", input);
        return Map.of("workflowId", workflowId);
    }
}
```

`startWorkflow(type, input)` создаёт workflow, ставит задачу в очередь и сразу возвращает его `id` (`Long`). Само выполнение подхватит воркер асинхронно.

### Управление снаружи (REST)

| Действие | Запрос |
|---|---|
| Послать сигнал | `POST /workflow/api/workflows/{id}/signal` — тело `{ "signalName": "...", "payload": {...} }` |
| Query | `POST /workflow/api/workflows/{id}/query/{name}` — тело `{ "args": [...] }` |
| Update | `POST /workflow/api/workflows/{id}/update/{name}?timeoutMs=30000` — тело `{ "args": [...] }` |
| Отменить | `POST /workflow/api/workflows/{id}/cancel` |
| Возобновить | `POST /workflow/api/workflows/{id}/resume` |
| Повторить «мёртвую» активность | `POST /workflow/api/workflows/{id}/activities/{activityName}/retry` |

---

## Правила, которым нужно следовать

Это самое важное при написании workflow. Они повторяют [deterministic constraints](https://docs.temporal.io/workflows#deterministic-constraints) Temporal.

**Код метода workflow должен быть детерминированным.** При проигрывании он выполнится снова, и должен пойти ровно по тому же пути.

✅ Можно прямо в workflow:
- разбирать вход, ветвиться, готовить аргументы активностей;
- вызывать активности, `sleep`, `await`, ждать сигналы;
- читать/писать **свои поля** (для query/update/await).

❌ Нельзя прямо в workflow (только внутри активностей):
- ходить в БД, по сети, в файлы, во внешние API;
- `new Random()`, `Instant.now()`, `UUID.randomUUID()` в логике ветвления — вместо этого `Workflow.sideEffect(...)`;
- `Thread.sleep(...)` — вместо этого `Workflow.sleep(...)`;
- запускать свои потоки, использовать глобальное изменяемое состояние.

И ещё несколько практических правил:

- **Активности должны быть идемпотентны.** Если процесс умрёт прямо во время активности, при следующем заходе она выполнится снова. Используйте ключи запроса, `INSERT … ON CONFLICT` и т.п.
- **Имена активностей уникальны в пределах workflow** — это ключ кэша результата.
- **Не меняйте порядок/имена активностей** в уже запущенных workflow без `getVersion` — иначе сломаете воспроизведение.
- **Делайте входы и результаты сериализуемыми в JSON** — они проходят через Jackson и колонки JSONB.

---

## Конфигурация

Все настройки — под префиксом `workflow.*`.

| Свойство | По умолчанию | Назначение |
|---|---|---|
| `workflow.worker-pool-size` | `4` | сколько потоков обрабатывают задачи |
| `workflow.poll-interval-ms` | `1000` | как часто воркер опрашивает очередь задач |
| `workflow.lock-timeout-seconds` | `60` | на сколько задача блокируется за воркером; после — считается «зависшей» (если аренда не продлевается) |
| `workflow.lease-renew-interval-ms` | `20000` | как часто воркер продлевает аренду своих задач; должно быть заметно меньше `lock-timeout-seconds`×1000 |
| `workflow.retry-poll-interval-ms` | `2000` | как часто проверяются назревшие повторы |
| `workflow.timeout-watcher-interval-ms` | `5000` | как часто ищутся зависшие задачи |
| `workflow.instance.id` | `default` | уникальный ID реплики (см. multi-instance) |
| `workflow.instance.internal-url` | _(пусто)_ | внутренний URL реплики |
| `workflow.instance.external-url` | _(пусто)_ | публичный URL; **его наличие включает multi-instance-режим** |

---

## Несколько реплик (multi-instance)

Можно запустить любое число копий приложения против одной базы — они делят общую очередь задач. Двойное выполнение исключено на уровне БД: задачи разбираются через `SELECT … FOR UPDATE SKIP LOCKED`, так что одну задачу заберёт ровно одна реплика.

В конфиге каждой реплики задайте уникальный ID и адреса:

```properties
workflow.instance.id=node-1
workflow.instance.internal-url=http://app1:8080
workflow.instance.external-url=https://api.example.com/node-1
```

- `id` должен быть уникален на каждую реплику;
- `external-url` — публичный адрес, по которому UI обращается к ноде; **его наличие включает multi-instance-режим**;
- если `external-url` пуст (по умолчанию) — нода работает в одиночном режиме, без регистрации.

### Регистрация нод

В multi-instance-режиме каждая нода регистрирует себя в таблице `wflow.instance_registry`:

- при старте — пишет туда строку со своим `id`, `internal_url`, `external_url`;
- каждые 10 секунд — обновляет `last_heartbeat` (heartbeat), чтобы остальные знали, что она жива;
- нода считается живой, если её `last_heartbeat` обновлялся в последние 30 секунд.

Именно из этой таблицы берётся список нод для эндпоинта `/workflow/api/cluster/nodes` и для топологии в UI. В одиночном режиме (без `external-url`) строки не пишутся и список нод пустой.

### Аренда задач, продление и фенсинг

Чтобы две ноды не выполняли одну задачу одновременно, каждая задача захватывается **в аренду**:

- при захвате нода ставит `locked_until = now + lock-timeout-seconds` и уникальный `lock_token`;
- пока нода жива и обрабатывает задачу, она **продлевает аренду** каждые `lease-renew-interval-ms` (двигает `locked_until` вперёд). Поэтому медленная, но живая обработка **никогда** не считается зависшей;
- если нода реально умерла (или надолго зависла) и перестала продлевать, `locked_until` уходит в прошлое — `TimeoutWatcher` возвращает задачу в очередь, и её забирает другая нода с новым токеном.

Если протухшая нода всё-таки «оживёт» и попробует дописать состояние по уже отобранной задаче, сработает **фенсинг**: перед каждой записью (события, статус workflow, результаты update) проверяется, что токен ещё наш. Если задачу уже забрали — запись отклоняется (`LockLostException`), текущий «ход» отбрасывается целиком, а владельцем остаётся новая нода. Финализация задачи тоже условная: статус проставляется только если токен совпадает.

Дополнительно, когда нода обнаруживает потерю аренды, она **прерывает свой рабочий поток** (`interrupt`), чтобы пораньше освободить слот — но это лишь оптимизация: корректность держится на фенсинге, а не на прерывании.

> Практический вывод тот же: **активности должны быть идемпотентны.** Фенсинг защищает записи движка, но если две копии хода успели вызвать одну и ту же ещё-не-записанную активность, тело активности выполнится дважды.

---

## REST API и UI

Все эндпоинты — под `/workflow/api/...`, открыты для CORS (`*`), чтобы UI с одной ноды мог ходить на другие.

**Просмотр workflow:**

| Метод | Путь | Что делает |
|---|---|---|
| `GET` | `/workflow/api/workflows` | список с фильтрами (`status`, `type`, `id`, `from`, `to`, `page`, `size`, `sort`) |
| `GET` | `/workflow/api/workflows/types` | список зарегистрированных типов |
| `GET` | `/workflow/api/workflows/{id}` | детали одного workflow |
| `GET` | `/workflow/api/workflows/{id}/events` | история событий (таймлайн) |
| `GET` | `/workflow/api/workflows/{id}/pending-activities` | активности в ожидании/повторе |

**Управление** (signal/query/update/cancel/resume/retry) — см. таблицу в разделе [Запуск и управление](#запуск-и-управление-workflow).

**Кластер:**

| Метод | Путь | Что делает |
|---|---|---|
| `GET` | `/workflow/api/cluster/nodes` | живые ноды из реестра (heartbeat за последние 30 с) |
| `GET` | `/workflow/api/cluster/local` | состояние пула этой ноды и её текущие задачи |

**Динамическая настройка активностей** (переопределить таймаут/повторы без передеплоя):

| Метод | Путь |
|---|---|
| `GET` / `PUT` / `DELETE` | `/workflow/api/activity-overrides/{activityName}` |

**Веб-интерфейс** доступен по адресу `/workflow/ui/` (нужен `spring-boot-starter-web`).

---

## Как это устроено внутри (архитектура)

Этот блок — «для интереса». Чтобы пользоваться temporal-lite, его читать необязательно.

Идея простая: **очередь задач в Postgres + проигрывание истории.**

**1. Очередь задач.**
Когда вы зовёте `startWorkflow(...)`, в таблицу `wflow.workflows` пишется сам workflow, а в `wflow.tasks` — задача «выполнить его». Воркер в фоне (`@Scheduled`) забирает пачку задач запросом `SELECT … FOR UPDATE SKIP LOCKED`, помечает их за собой и раздаёт по потокам пула. `SKIP LOCKED` — это то, что позволяет нескольким репликам безопасно тянуть из одной таблицы, не мешая друг другу.

**2. История событий.**
Всё, что делает workflow — вызвал активность, заснул, получил сигнал, записал side effect — пишется в таблицу `wflow.events` как событие. Эта таблица — **единственный источник правды**. Состояние самого workflow в памяти не хранится.

**3. Проигрывание (replay).**
Когда воркер берёт workflow, он не помнит, что было раньше — он просто **запускает метод workflow с начала** и проигрывает его поверх истории. Дошли до активности, которая уже выполнялась? Движок не зовёт её снова — возвращает сохранённый результат из истории. Дошли до новой активности — выполняет и дописывает событие. Так каждая успешная активность выполняется не больше одного раза, даже после рестартов.

**4. Паузы.**
Когда workflow зовёт `sleep` или `await`, он не блокирует поток — он бросает специальное «парковочное» исключение. Воркер ловит его, освобождает поток и записывает, когда workflow надо разбудить (таблицы `pending_timers` / `pending_awaits`). Отдельный планировщик в нужный момент снова кладёт задачу в очередь — и проигрывание продолжается с того же места.

**5. Повторы и зависшие задачи.**
Упавшие активности планируются на повтор в `wflow.retries`. Отдельный шедулер вытаскивает назревшие повторы обратно в очередь. Пока воркер жив, он продлевает аренду своих задач (`lock_token` + `locked_until`); если перестал — сторож (`TimeoutWatcher`) возвращает задачу в очередь, а опоздавшие записи старого воркера отсекаются фенсингом по токену. Подробнее — в разделе [Аренда задач, продление и фенсинг](#аренда-задач-продление-и-фенсинг).

Схематично:

```
startWorkflow ──► wflow.workflows + wflow.tasks (одна транзакция)

       Воркер (@Scheduled)
          │  SELECT … FOR UPDATE SKIP LOCKED  ← так реплики делят очередь
          ▼
   запускает метод workflow с начала
          │
          ├─ активность уже в истории?  → вернуть сохранённый результат
          ├─ новая активность?          → выполнить, записать событие
          ├─ ошибка?                     → запланировать повтор (retries)
          └─ sleep / await?             → припарковать, освободить поток
          │
          ▼
   обновить статус workflow, дописать события
                                   │
                                   ▼
                            ┌──────────────┐
                            │  PostgreSQL  │  схема wflow:
                            │              │  workflows, tasks, events,
                            │              │  retries, signals,
                            │              │  pending_timers/awaits,
                            │              │  update_requests,
                            │              │  instance_registry
                            └──────────────┘
```

Таблицы схемы `wflow`:

| Таблица | Зачем |
|---|---|
| `workflows` | по одной строке на workflow: вход, результат, статус |
| `tasks` | очередь работы; сердце распределения — `FOR UPDATE SKIP LOCKED` |
| `events` | история — источник правды для проигрывания |
| `retries` | запланированные повторы активностей |
| `signals` | входящие сигналы |
| `pending_timers` / `pending_awaits` | когда разбудить припаркованный workflow |
| `update_requests` | запросы update и их результаты |
| `instance_registry` | реестр живых реплик (heartbeat) |

---

## FAQ

**Что будет, если JVM упадёт посреди активности?**
Результат активности пишется в историю только **после** того, как её тело вернуло значение. Если процесс умер в середине — записи нет, и при следующем заходе активность выполнится заново. Поэтому делайте тела активностей идемпотентными.

**Могут ли две реплики выполнять один и тот же workflow одновременно?**
Нет. `FOR UPDATE SKIP LOCKED` гарантирует, что задачу заберёт ровно одна реплика; остальные её пропустят.

**Почему активность выполняется в том же потоке, что и workflow?**
Ради простоты. В «большом» Temporal worker workflow освобождается, пока активность крутится на другом worker'е. Здесь активность выполняется синхронно (с таймаутом через `CompletableFuture`). Для коротких и средних активностей это проще и быстрее; для очень долгих — учитывайте, что слот в пуле занят всё это время, и подбирайте `worker-pool-size`.

**Нужно ли обязательно делать `@Activity`-интерфейсы?**
Нет. Либо типизированные стабы (`@Activity` + `Workflow.newActivityStub`), либо функциональный API `Workflow.activity(name, options, () -> ...)`. Семантика одна, отличается только удобство.

**Поддерживается ли версионирование?**
Да, через `Workflow.getVersion(...)` — для безопасного изменения логики уже запущенных workflow. Для несовместимых изменений проще завести новый тип workflow (`@WorkflowComponent("...-v2")`).