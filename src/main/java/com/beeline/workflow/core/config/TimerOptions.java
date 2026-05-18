package com.beeline.workflow.core.config;

import java.time.Duration;

public final class TimerOptions {

    private final Duration duration;
    private final String name;

    private TimerOptions(Builder b) {
        this.duration = b.duration;
        this.name = b.name;
    }

    public Duration getDuration() { return duration; }
    public String getName() { return name; }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static final class Builder {
        private Duration duration = Duration.ofSeconds(1);
        private String name;

        public Builder setDuration(Duration v) { this.duration = v; return this; }
        public Builder setName(String v) { this.name = v; return this; }

        public TimerOptions build() { return new TimerOptions(this); }
    }
}
