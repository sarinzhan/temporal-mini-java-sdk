Общая картина

Состояние живёт в 4 таблицах схемы wflow:
- workflows — экземпляр (статус, input/result/error, @Version для оптимистичной блокировки)
- tasks — очередь задач с арендой (lock_token, locked_until)
- events — источник правды для replay (append-only, по id)
- schedule — когда разбудить припаркованный workflow

Главная идея: весь turn выполняется без открытой транзакции, все записи копятся в памяти (EventLogImpl), а в конце сбрасываются одной атомарной транзакцией под защитой токена
аренды. Падение посреди turn = нет частичной истории, аренда протухает, turn переигрывается заново.

  ---
Шаг 1. Создание — WorkflowClientImpl.start → writeStartState

WorkflowClientImpl.java:78 в одной транзакции пишет 4 вещи:

workflowRepository.save(wf);              // workflows: PENDING, input
eventRepository.save(WORKFLOW_CREATED);   // events: input
taskRepository.save(task);                // tasks: PENDING, task_type=workflow.start
eventRepository.save(WORKFLOW_TASK_QUEUED);

Возвращается WorkflowHandle — обёртка над workflowId, которая потом будет опрашивать строку workflows в getResult(). Сам клиент ничего не выполняет — только кладёт задачу в
очередь.

  ---
Шаг 2. Поллинг и захват задачи — WorkerLoopImpl

pollAndProcess() (WorkerLoopImpl.java:66) крутится по таймеру (poll-interval-ms):

1. Проверяет свободные слоты семафора (= worker-pool-size).
2. claimBatch() (:123) в транзакции: taskRepository.pollPending(n) делает SELECT … FOR UPDATE SKIP LOCKED (вот так несколько реплик не дерутся за одну задачу), помечает строки
   PROCESSING, ставит locked_by, locked_until = now + lock-timeout и новый lock_token (UUID) — это фенсинг-токен.
3. На каждую задачу создаётся TaskLease(taskId, token), кладётся в running (чтобы продлевать аренду), и processTask запускается в пуле потоков.

Параллельно renewLeases() (:107) периодически двигает locked_until вперёд. Если продление вернуло 0 строк — значит задачу уже отобрал другой узел → lease.markLost() (прерывает
поток и запрещает дальнейшие записи).

  ---
Шаг 3. Один turn — WorkflowTurnRunner.run

WorkflowTurnRunner.java:73:

wf = workflowRepository.findById(...);          // нет → Outcome.UNKNOWN
bean = registry.createInstance(type);           // свежий экземпляр @WorkflowComponent
entryMethod = registry.getEntryMethod(type);
callArgs = decode(wf.getInput());

// История ДО записи TASK_STARTED — курсор видит только прошлые turn'ы
List<Event> history = eventRepository.findByWorkflowIdOrderByIdAsc(wf.getId());
HistoryCursor cursor = new HistoryCursor(...);   // раздаёт seq, ищет завершённые команды
ReplayState replayState = new ReplayStateImpl(cursor, codec);
EventLogImpl eventLog = factory.create(...);     // БУФЕР, ничего не пишет в БД

eventLog.workflowTaskStarted();                  // в буфер
ctx = new CommandContext(...);                   // seq, eventLog, lease, dispatcher
WorkflowContextHolder.set(ctx);                  // ThreadLocal — чтобы Workflow.activity нашёл контекст

Object value = entryMethod.invoke(bean, callArgs);   // ← ВЫЗОВ ВАШЕГО МЕТОДА inline
result = outcomeMapper.onCompleted(...);

Ключевой момент: метод workflow выполняется в этом же потоке, в обычном Java-стиле. Каждый Workflow.activity(...) внутри него уходит через dispatcher в ActivityCommandHandler.

  ---
Шаг 4. Активность — ActivityCommandHandler.handle

Workflow.activity(...) (Workflow.java) лишь строит ActivityCommand и шлёт через диспетчер. Вся логика — в ActivityCommandHandler.java:96:

int seq = state.nextSeq();                            // 1,2,3… — порядок вызова
state.assertCommandTypeMatches(seq, ACTIVITY);        // защита от недетерминизма

// ── REPLAY: эта активность уже завершалась в прошлом turn? ──
Optional<ActivityReplay> replay = state.findActivityResult(seq);
if (Completed) return codec.decode(payload);          // лямбда НЕ вызывается, результат из истории
if (Failed)    throw new ActivityFailureException(...);// терминальный провал тоже переигрывается

// ── НОВЫЙ запуск ──
int attempt = state.countUsedActivityAttempts(seq) + 1;  // считает прошлые ACTIVITY_STARTED

// слот пула резервируем ДО записи ACTIVITY_STARTED:
Future<Object> future;
try { future = invocationPool.submit(cmd.body()::get); }
catch (RejectedExecutionException) { return parkForBackpressure(...); }  // пул забит → park без сжигания попытки

eventLog.activityStarted(seq, name, attempt);         // в буфер
result = awaitWithTimeout(future, startToCloseTimeout);// future.get(timeout); таймаут → cancel(true)
eventLog.activityCompleted(seq, name, attempt, result);// в буфер
return result;

Как findActivityResult понимает, завершена ли активность (ReplayStateImpl.java:36): берёт последнее событие по этому seq:
- ACTIVITY_COMPLETED → Completed(payload) (кеш)
- ACTIVITY_FAILED → Failed (терминал)
- только ACTIVITY_STARTED / ACTIVITY_RETRY_SCHEDULED или ничего → не завершена, запускаем следующую попытку.

Если активность упала — failOrRetry (:167)

RetryDecision d = retryDecider.decide(cause, attempt, policy);
if (d instanceof Retry retry) {
Instant fireAt = computeFireAt(retry.delay());           // backoff
eventLog.activityRetryScheduled(seq, name, attempt, fireAt, reason); // событие + строка в schedule
throw new WorkflowParkedException(seq);                   // ← ПАРКОВКА: turn завершается
}
// иначе — терминал (исчерпаны попытки / noRetryOn / NonRetryable):
eventLog.activityFailed(seq, name, attempt, reason);
throw new ActivityFailureException(...);                      // → workflow упадёт

activityRetryScheduled (EventLogImpl.java:77) кладёт в буфер и событие ACTIVITY_RETRY_SCHEDULED, и строку Schedule(workflowId, seq, fireAt).

  ---
Шаг 5. Чем кончился метод — WorkflowOutcomeMapper

entryMethod.invoke либо вернул значение, либо бросил. WorkflowOutcomeMapper (:33/:45) переводит это в TurnResult (буферизуя финальные lifecycle-события):

┌──────────────────────────────────────────────┬─────────────────────────────────────────────┬────────────┬─────────────────────────┐
│                Что случилось                 │               События в буфер               │ TurnResult │     task / workflow     │
├──────────────────────────────────────────────┼─────────────────────────────────────────────┼────────────┼─────────────────────────┤
│ метод вернул значение                        │ WORKFLOW_COMPLETED, WORKFLOW_TASK_COMPLETED │ completed  │ task=DONE, wf=COMPLETED │
├──────────────────────────────────────────────┼─────────────────────────────────────────────┼────────────┼─────────────────────────┤
│ WorkflowParkedException                      │ WORKFLOW_TASK_COMPLETED                     │ parked     │ task=DONE, wf=RUNNING   │
├──────────────────────────────────────────────┼─────────────────────────────────────────────┼────────────┼─────────────────────────┤
│ ActivityFailure/Timeout/NonDeterminism/любое │ WORKFLOW_FAILED, WORKFLOW_TASK_FAILED       │ failed     │ task=DEAD, wf=FAILED    │
└──────────────────────────────────────────────┴─────────────────────────────────────────────┴────────────┴─────────────────────────┘

Парковка (TurnResult.java:24) — нормальный исход: задача закрывается DONE, но workflow остаётся RUNNING, а в schedule лежит строка на пробуждение.

  ---
Шаг 6. Атомарный коммит — TurnCommitter.commit

TurnCommitter.java:81, одна транзакция:

// 1. ФЕНС: SELECT … FOR UPDATE по строке task + проверка нашего lock_token
if (taskRepository.lockIfOwned(taskId, token) == 0) return false;  // аренду отобрали → ничего не пишем

// 2. весь буфер
eventRepository.saveAll(eventLog.bufferedEvents());
scheduleRepository.saveAll(eventLog.bufferedSchedules());

// 3. статус workflow (второй фенс — @Version optimistic lock)
applyMutation(wf, mutation); workflowRepository.save(wf);

// 4. финализация задачи (DONE/DEAD), снова под lock_token
taskRepository.finalizeIfOwned(taskId, token, taskFinal);

Два независимых фенса (lock_token на задаче + @Version на workflow) гарантируют, что «отставший» узел не испортит состояние. Если коммит не прошёл (аренда потеряна) →
Outcome.LOST, всё выкинуто.

  ---
Шаг 7. Пробуждение припаркованного — WakeupSchedulerImpl

pollAndFire() (:52) по таймеру (retry-poll-interval-ms):

due = scheduleRepository.pollDue(now, 100);     // строки, у которых fireAt наступил
for (s : due) { s.setProcessed(true); ... }
for (workflowId : уникальные) {
if (статус терминальный) continue;          // workflow уже завершился — пропустить
taskRepository.save(new Task(workflow.wakeup, PENDING));
eventRepository.save(WORKFLOW_TASK_QUEUED);  // reason=wakeup
}

Новая задача → снова Шаг 2 → новый turn.

  ---
Шаг 8. Повторный turn = replay

Второй (и каждый следующий) turn делает то же самое с начала, но:
- история теперь содержит прошлые ACTIVITY_COMPLETED → на этих seq лямбды не вызываются, результат берётся из истории (Шаг 4, ветка Completed);
- доходит до незавершённой активности (по ней в истории только ACTIVITY_RETRY_SCHEDULED) → attempt = прошлые STARTED + 1, делается следующая попытка;
- порядок и количество Workflow.activity(...) должны совпасть, иначе assertCommandTypeMatches бросит NonDeterminismException → workflow упадёт.

Так движок и обеспечивает «успешная активность ≤ 1 раза»: результат закеширован в истории.

  ---
Шаг 9. Падение узла — TimeoutWatcherImpl

Если узел умер посреди turn: коммита не было (Шаг 6 атомарен) → история чистая, задача висит PROCESSING с протухшим locked_until. TimeoutWatcherImpl.resetStaleTasks() (:22) по
таймеру (timeout-watcher-interval-ms) вызывает resetStaleLocks() → возвращает задачу в PENDING, и её подхватывает другая реплика, которая переигрывает с нуля (Шаг 8).

  ---
Шаг 10. Завершение

После completed/failed коммита строка workflows имеет статус COMPLETED(с result) или FAILED(с error). WorkflowHandle.getResult() опрашивает её: на COMPLETED отдаёт декодированный
результат, на FAILED — бросает.

  ---
Короткая цепочка событий (happy path с одним ретраем)

WORKFLOW_CREATED, WORKFLOW_TASK_QUEUED                      ← start
WORKFLOW_TASK_STARTED                                       ← turn 1
ACTIVITY_STARTED(seq1,att1), ACTIVITY_COMPLETED(seq1)
ACTIVITY_STARTED(seq2,att1), ACTIVITY_RETRY_SCHEDULED(seq2,att1)  ← упала, park
WORKFLOW_TASK_COMPLETED                                     ← turn 1 закрыт (wf=RUNNING)
WORKFLOW_TASK_QUEUED(wakeup)                                ← schedule сработал
WORKFLOW_TASK_STARTED                                       ← turn 2 (replay)
seq1: из кеша, лямбда не вызывается
ACTIVITY_STARTED(seq2,att2), ACTIVITY_COMPLETED(seq2)       ← вторая попытка успешна
WORKFLOW_COMPLETED, WORKFLOW_TASK_COMPLETED                 ← wf=COMPLETED