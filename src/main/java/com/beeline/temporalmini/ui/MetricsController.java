package com.beeline.temporalmini.ui;

import com.beeline.temporalmini.MetricSample;
import com.beeline.temporalmini.MetricSampleRepository;
import com.beeline.temporalmini.metrics.LocalMeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Time-series queries for the metrics page in the UI. Two modes:
 * <ul>
 *     <li>{@code bucket=raw} — return individual rows (suitable for narrow windows).</li>
 *     <li>{@code bucket=second|minute|hour|...} — server-side {@code date_trunc}
 *     aggregation so wide windows still fit in a few hundred points.</li>
 * </ul>
 * The picker on the client (see {@code chartUtils.pickBucket}) chooses the
 * bucket so we always return ≤ ~300 rows regardless of how wide the window is.
 */
@RestController
@RequestMapping("/temporal-mini/api/metrics")
public class MetricsController {

    /** Buckets we accept — anything else falls back to {@code raw}. The values
     *  match Postgres {@code date_trunc} units. */
    private static final Set<String> ALLOWED_BUCKETS = Set.of(
            "second", "minute", "hour", "day"
    );

    private final MetricSampleRepository repository;
    private final LocalMeterRegistry localMeterRegistry;

    public MetricsController(MetricSampleRepository repository,
                             ObjectProvider<LocalMeterRegistry> localRegistry) {
        this.repository = repository;
        this.localMeterRegistry = localRegistry.getIfAvailable();
    }

    public record SampleDto(
            LocalDateTime ts,
            int poolActive, int poolFree, int poolQueue, int runtimeCount,
            long cntNew, long cntRetry, long cntBlocked, long cntFinished, long cntFailed) {}

    public record HistoryResponse(String bucket, int count, List<SampleDto> samples) {}

    /**
     * @param from   inclusive lower bound (defaults to now − 1h)
     * @param to     inclusive upper bound (defaults to now)
     * @param bucket "raw" / "second" / "minute" / "hour" / "day"; default "raw"
     */
    @GetMapping("/history")
    public HistoryResponse history(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(required = false, defaultValue = "raw") String bucket) {

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime fromTs = from != null ? from : now.minusHours(1);
        LocalDateTime toTs   = to   != null ? to   : now;
        String unit = ALLOWED_BUCKETS.contains(bucket) ? bucket : "raw";

        List<SampleDto> samples = "raw".equals(unit)
                ? repository.findByTsBetweenOrderByTsAsc(fromTs, toTs).stream().map(MetricsController::toDto).toList()
                : repository.findBucketedRaw(unit, fromTs, toTs).stream().map(MetricsController::toDtoFromRow).toList();

        return new HistoryResponse(unit, samples.size(), samples);
    }

    private static SampleDto toDto(MetricSample s) {
        return new SampleDto(
                s.getTs(),
                s.getPoolActive(), s.getPoolFree(), s.getPoolQueue(), s.getRuntimeCount(),
                s.getCntNew(), s.getCntRetry(), s.getCntBlocked(), s.getCntFinished(), s.getCntFailed());
    }

    /** Maps positional native-query rows to {@link SampleDto}. Order matches {@code findBucketedRaw}. */
    private static SampleDto toDtoFromRow(Object[] r) {
        return new SampleDto(
                toLocalDateTime(r[0]),
                ((Number) r[1]).intValue(),
                ((Number) r[2]).intValue(),
                ((Number) r[3]).intValue(),
                ((Number) r[4]).intValue(),
                ((Number) r[5]).longValue(),
                ((Number) r[6]).longValue(),
                ((Number) r[7]).longValue(),
                ((Number) r[8]).longValue(),
                ((Number) r[9]).longValue());
    }

    /**
     * Returns in-memory timer snapshots recorded by {@link com.beeline.temporalmini.metrics.LocalMeterRegistry}.
     * Only available when no external Micrometer backend (actuator + exporter) is configured.
     * Returns {@code 204 No Content} when an external registry is in use instead.
     */
    @GetMapping("/timers")
    public org.springframework.http.ResponseEntity<List<LocalMeterRegistry.TimerSnapshot>> timers() {
        if (localMeterRegistry == null) {
            return org.springframework.http.ResponseEntity.noContent().build();
        }
        return org.springframework.http.ResponseEntity.ok(localMeterRegistry.snapshot());
    }

    private static LocalDateTime toLocalDateTime(Object v) {
        if (v instanceof LocalDateTime ldt) return ldt;
        if (v instanceof Timestamp ts) return ts.toLocalDateTime();
        if (v instanceof Map<?, ?> ignored) return null;
        return LocalDateTime.parse(v.toString());
    }
}
