# Workflow Engine — API & Entity Schema

Справочник для генерации фронтенда (Claude design). Описывает доменные сущности движка и
**реальные** схемы ответов REST API в его текущем виде.

> Base path: `/workflow/api/workflows`
> UI (static): `/workflow/ui/index.html`
> Сериализация: Jackson, camelCase. Все временные поля — ISO-8601 строки в UTC
> (`"2026-05-31T10:15:30.123Z"`). Enum'ы сериализуются как строки (имя константы).
> `input` / `result` / `payload` — это **сырой JSON в виде строки** (jsonb-колонки),
> а не вложенные объекты. Фронт должен парсить их сам (`JSON.parse`).

---

## 1. Перечисления (enums)

```ts
type WorkflowStatus =
  | 'PENDING'    // создан, ещё не подхвачен воркером
  | 'RUNNING'    // исполняется / припаркован между шагами
  | 'COMPLETED'  // успешно завершён, есть result
  | 'FAILED'     // завершён с ошибкой, есть error
  | 'CANCELLED'; // отменён

type EventType =
  // Жизненный цикл
  | 'WORKFLOW_CREATED'
  | 'WORKFLOW_TASK_QUEUED'
  | 'WORKFLOW_TASK_STARTED'
  | 'WORKFLOW_TASK_COMPLETED'
  | 'WORKFLOW_TASK_FAILED'
  | 'WORKFLOW_COMPLETED'
  | 'WORKFLOW_FAILED'
  // Активности (выполняются inline; ретрай ждёт через припаркованный turn + строку schedule)
  | 'ACTIVITY_STARTED'
  | 'ACTIVITY_COMPLETED'
  | 'ACTIVITY_FAILED'
  | 'ACTIVITY_TIMEOUT'
  | 'ACTIVITY_RETRY_SCHEDULED'
  // Помощники детерминизма
  | 'SIDE_EFFECT_RECORDED'
  | 'VERSION_MARKER';

type TaskStatus =
  | 'PENDING'     // ждёт воркера
  | 'PROCESSING'  // захвачена воркером (lease)
  | 'DONE'
  | 'DEAD';       // исчерпаны попытки
```

> ⚠️ В `frontend/src/types/index.ts` сейчас перечислен расширенный набор `EventType`
> (`TIMER_STARTED`, `AWAIT_BLOCKED`, `SIGNAL_RECEIVED`, `UPDATE_REQUESTED`, …) и статус
> `CANCELLED` без `WORKFLOW_TASK_FAILED`/`ACTIVITY_TIMEOUT`. **Источник истины — бэкенд
> (`EventType.java`, `WorkflowStatus.java`), список выше.** Старые TS-типы опережают бэкенд.

---

## 2. Доменные сущности (модель БД, схема `wflow`)

Это то, чем оперирует движок. REST наружу отдаёт их подмножество (см. §3).

### WorkflowInstance — `wflow.workflows`
| поле          | тип              | примечание                                        |
|---------------|------------------|---------------------------------------------------|
| `id`          | number (bigint)  | PK                                                |
| `workflowType`| string           | зарегистрированный тип воркфлоу                    |
| `status`      | WorkflowStatus   |                                                   |
| `input`       | string \| null   | сырой JSON — вход воркфлоу                         |
| `result`      | string \| null   | сырой JSON — результат (только при COMPLETED)      |
| `error`       | string \| null   | текст ошибки (только при FAILED)                  |
| `createdAt`   | string (instant) |                                                   |
| `updatedAt`   | string (instant) |                                                   |
| `completedAt` | string \| null   | момент терминального состояния                    |
| `version`     | number           | optimistic-lock fence (внутреннее)                |

### Event — `wflow.events`
Журнал событий — **источник истины для replay**. Упорядочен по `id` возрастанию.
| поле          | тип              | примечание                                        |
|---------------|------------------|---------------------------------------------------|
| `id`          | number           | PK, порядок = хронология                          |
| `workflowId`  | number           | FK → workflows.id                                 |
| `eventType`   | EventType        |                                                   |
| `commandType` | string \| null   | тип команды, породившей событие                   |
| `seq`         | number \| null   | порядковый номер команды внутри воркфлоу          |
| `activityName`| string \| null   | имя активности (для ACTIVITY_* событий)           |
| `payload`     | string \| null   | сырой JSON — аргументы/результат/ошибка события   |
| `createdAt`   | string (instant) |                                                   |

### Task — `wflow.tasks`
Очередь работ для воркеров (lease-based). Наружу через REST **пока не отдаётся**.
| поле          | тип              | примечание                                        |
|---------------|------------------|---------------------------------------------------|
| `id`          | number           | PK                                                |
| `workflowId`  | number           | FK → workflows.id                                 |
| `taskType`    | string           | напр. `workflow`                                  |
| `status`      | TaskStatus       |                                                   |
| `payload`     | string \| null   | сырой JSON                                        |
| `scheduledAt` | string (instant) | когда задача готова к исполнению                  |
| `lockedBy`    | string \| null   | id инстанса-владельца lease                       |
| `lockedAt`    | string \| null   |                                                   |
| `lockedUntil` | string \| null   | срок lease                                        |
| `lockToken`   | string \| null   | SQL-fence токен                                   |
| `createdAt`   | string (instant) |                                                   |
| `version`     | number           | optimistic-lock fence                             |

### Schedule — `wflow.schedule`
Будущие пробуждения припаркованных воркфлоу (ретраи активностей, таймеры).
| поле          | тип              | примечание                                        |
|---------------|------------------|---------------------------------------------------|
| `id`          | number           | PK                                                |
| `workflowId`  | number           | FK → workflows.id                                 |
| `seq`         | number \| null   | команда, к которой относится пробуждение          |
| `fireAt`      | string (instant) | когда разбудить                                   |
| `reason`      | string \| null   |                                                   |
| `processed`   | boolean          |                                                   |
| `createdAt`   | string (instant) |                                                   |

### InstanceRegistryEntity — `wflow.instance_registry`
Реестр узлов в multi-instance режиме (heartbeat). Наружу через REST **пока не отдаётся**.
| поле           | тип              | примечание                                       |
|----------------|------------------|--------------------------------------------------|
| `id`           | string           | PK — id инстанса                                 |
| `internalUrl`  | string \| null   |                                                  |
| `externalUrl`  | string           |                                                  |
| `lastHeartbeat`| string (instant) |                                                  |

---

## 3. REST API — текущие эндпоинты и схемы ответов

Контроллер: `WorkflowController` (`/workflow/api/workflows`). Сейчас реализованы **три**
эндпоинта.

### 3.1 POST `/workflow/api/workflows/{type}` — запустить воркфлоу
- **Path**: `type` — зарегистрированный тип воркфлоу.
- **Body** (optional): сырой JSON-вход воркфлоу (`text/plain`/`application/json`, строка).
- **200 → StartResponse**:
```ts
interface StartResponse {
  workflowId: number;
  type: string;
}
```
```json
{ "workflowId": 42, "type": "OrderWorkflow" }
```

### 3.2 GET `/workflow/api/workflows/{id}` — статус/результат
- **200 → StatusResponse**, **404** если воркфлоу не найден.
```ts
interface StatusResponse {
  id: number;
  type: string;                 // = workflowType
  status: WorkflowStatus;
  result: string | null;        // сырой JSON
  error: string | null;
  completedAt: string | null;   // instant
}
```
```json
{
  "id": 42,
  "type": "OrderWorkflow",
  "status": "COMPLETED",
  "result": "{\"orderId\":42,\"charged\":true}",
  "error": null,
  "completedAt": "2026-05-31T10:15:30.123Z"
}
```

### 3.3 GET `/workflow/api/workflows/{id}/events` — история событий
- **200 → EventView[]** (упорядочено по `id` возрастанию).
```ts
interface EventView {
  id: number;
  eventType: EventType;
  commandType: string | null;
  seq: number | null;
  activityName: string | null;
  payload: string | null;       // сырой JSON
  createdAt: string;            // instant
}
```
```json
[
  {
    "id": 100,
    "eventType": "WORKFLOW_CREATED",
    "commandType": null,
    "seq": null,
    "activityName": null,
    "payload": "{\"orderId\":42}",
    "createdAt": "2026-05-31T10:15:29.000Z"
  },
  {
    "id": 101,
    "eventType": "ACTIVITY_COMPLETED",
    "commandType": "ACTIVITY",
    "seq": 1,
    "activityName": "ChargeCard",
    "payload": "{\"charged\":true}",
    "createdAt": "2026-05-31T10:15:30.000Z"
  }
]
```

---

## 4. Чего в API ещё нет (но фронт-типы это предполагают)

`frontend/src/types/index.ts` уже описывает функционал, которого на бэкенде **нет**.
Для дизайна это «целевое» состояние — но если делать фронт под текущий бэкенд, этих
ответов сейчас не существует:

- **Список воркфлоу с пагинацией/фильтрами** — `PageResponse<WorkflowSummary>`,
  `WorkflowSearchParams` (status[], type, from/to, quick-фильтры, sort). Нет эндпоинта
  `GET /workflow/api/workflows` (листинг).
- **WorkflowSummary / WorkflowDetail** с полями `startTime`/`endTime`/`durationMs` — у
  бэкенда это `createdAt`/`completedAt`, длительность не считается.
- **PendingActivity** (attempt/maxAttempts/nextFireAt/lastError) — отдельного эндпоинта нет;
  данные пришлось бы выводить из `events` + `schedule`.
- **ActivityOverride** (правка ActivityOptions на лету) — нет эндпоинта.
- **Admin actions** (отмена/ретрай воркфлоу) — нет эндпоинтов.
- **Metrics** (страница `MetricsPage`) — нет эндпоинта; Micrometer подключён опционально.

> Рекомендация дизайн-агенту: строй UI вокруг трёх реальных эндпоинтов (§3) как MVP,
> а разделы списка/метрик/admin помечай как «требует новых эндпоинтов» — их надо
> добавить в `WorkflowController` отдельно.
