package com.beeline.temporalmini;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "workflow.metrics")
public class MetricsProperties {

    /** Disable to skip the sampler bean entirely (no writes, no retention job). */
    private boolean enabled = true;

    /** How often a sample row is appended. */
    private long sampleIntervalMs = 10_000;

    /** Rows older than this are deleted by the cleanup job. */
    private int retentionDays = 14;

    /** Cron for the retention sweep. Default: every day at 03:00 server time. */
    private String cleanupCron = "0 0 3 * * *";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public long getSampleIntervalMs() { return sampleIntervalMs; }
    public void setSampleIntervalMs(long sampleIntervalMs) { this.sampleIntervalMs = sampleIntervalMs; }

    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }

    public String getCleanupCron() { return cleanupCron; }
    public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
}
