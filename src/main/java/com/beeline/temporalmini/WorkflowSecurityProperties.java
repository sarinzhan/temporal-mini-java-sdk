package com.beeline.temporalmini;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings for the Temporal-mini admin UI authentication.
 *
 * <p>Auth is opt-in: set {@code workflow.ui.security.enabled=true} (and ensure
 * {@code spring-boot-starter-security} is on the classpath) to require login for the
 * {@code /temporal-mini/**} endpoints. A single in-memory user is provisioned from
 * {@link #username} / {@link #password}.
 *
 * <p>The password value is parsed by Spring Security's
 * {@code DelegatingPasswordEncoder}, so prefix it with the encoder id, e.g.
 * {@code {bcrypt}$2a$10$...} or {@code {noop}admin} for local dev.
 */
@ConfigurationProperties(prefix = "workflow.ui")
public class WorkflowSecurityProperties {

    private final Security security = new Security();
    private String username = "admin";
    private String password = "{noop}admin";

    public Security getSecurity() { return security; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public static class Security {
        /** Enable HTTP session-based auth in front of /temporal-mini/**. Defaults to off. */
        private boolean enabled = false;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
    }
}
