package com.beeline.workflow.spring.autoconfigure;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("workflow")
public class WorkflowProperties implements InitializingBean {

    private int workerPoolSize = 4;
    private long pollIntervalMs = 1000;
    private long lockTimeoutSeconds = 60;
    private long leaseRenewIntervalMs = 20000;
    private long retryPollIntervalMs = 2000;
    private long timeoutWatcherIntervalMs = 5000;

    private final Instance instance = new Instance();
    private final Metrics metrics = new Metrics();

    public int getWorkerPoolSize() { return workerPoolSize; }
    public void setWorkerPoolSize(int v) { this.workerPoolSize = v; }

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long v) { this.pollIntervalMs = v; }

    public long getLockTimeoutSeconds() { return lockTimeoutSeconds; }
    public void setLockTimeoutSeconds(long v) { this.lockTimeoutSeconds = v; }

    public long getLeaseRenewIntervalMs() { return leaseRenewIntervalMs; }
    public void setLeaseRenewIntervalMs(long v) { this.leaseRenewIntervalMs = v; }

    public long getRetryPollIntervalMs() { return retryPollIntervalMs; }
    public void setRetryPollIntervalMs(long v) { this.retryPollIntervalMs = v; }

    public long getTimeoutWatcherIntervalMs() { return timeoutWatcherIntervalMs; }
    public void setTimeoutWatcherIntervalMs(long v) { this.timeoutWatcherIntervalMs = v; }

    public Instance getInstance() { return instance; }

    public Metrics getMetrics() { return metrics; }

    /** Convenience: instance id used as tasks.locked_by. */
    public String getInstanceId() { return instance.getId(); }

    @Override
    public void afterPropertiesSet() {
        if (instance.getExternalUrl() != null && !instance.getExternalUrl().isBlank()) {
            if ("default".equals(instance.getId())) {
                throw new IllegalStateException(
                        "workflow.instance.external-url is set but workflow.instance.id is still the default. " +
                        "Multi-instance deployment requires a unique workflow.instance.id per node.");
            }
            if (instance.getInternalUrl() == null || instance.getInternalUrl().isBlank()) {
                throw new IllegalStateException(
                        "workflow.instance.external-url is set, but workflow.instance.internal-url is missing. " +
                        "Both are required when running multi-instance.");
            }
        }
    }

    /** Configuration for the periodic metrics rollup (see MetricsCollector). */
    public static class Metrics {
        private boolean enabled = true;
        private long intervalMs = 15000;
        /** Snapshots older than this are pruned on each run; 0 disables pruning. */
        private long retentionHours = 168; // 7 days

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean v) { this.enabled = v; }

        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long v) { this.intervalMs = v; }

        public long getRetentionHours() { return retentionHours; }
        public void setRetentionHours(long v) { this.retentionHours = v; }
    }

    public static class Instance {
        private String id = "default";
        private String internalUrl;
        private String externalUrl;

        public String getId() { return id; }
        public void setId(String v) { this.id = v; }

        public String getInternalUrl() { return internalUrl; }
        public void setInternalUrl(String v) { this.internalUrl = v; }

        public String getExternalUrl() { return externalUrl; }
        public void setExternalUrl(String v) { this.externalUrl = v; }

        public boolean isMultiInstance() {
            return externalUrl != null && !externalUrl.isBlank();
        }
    }
}
