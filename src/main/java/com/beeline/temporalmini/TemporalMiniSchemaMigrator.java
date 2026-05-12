package com.beeline.temporalmini;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tiny forward-only SQL migrator. Replaces Flyway for this module — the SDK is
 * meant to ship "two tables, no extra infrastructure", and a 200-LOC migrator
 * is cheaper than a transitive Flyway dependency tree.
 *
 * <p>Tracks applied versions in {@code wflow.sql_migrations} (created on first run).
 * Picks up scripts named {@code db/migration/temporal-mini/V<version>__<name>.sql}
 * from the classpath, applies pending ones in version order, each in its own
 * transaction. Re-running an already-applied migration is a no-op. Migration
 * scripts are not checksummed — change a script in place at your own risk.
 */
@Slf4j
public class TemporalMiniSchemaMigrator {

    private static final String LOCATION = "classpath*:db/migration/temporal-mini/V*__*.sql";
    private static final Pattern FILE = Pattern.compile("V(\\d+(?:[._]\\d+)*)__.+\\.sql");

    private final DataSource dataSource;

    public TemporalMiniSchemaMigrator(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Run any pending migrations. Throws {@link IllegalStateException} on failure
     * so Spring fails the bean factory and surfaces the error at startup.
     */
    public void migrate() {
        List<Migration> all;
        try {
            all = scan();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot scan migration scripts", e);
        }
        try (Connection conn = dataSource.getConnection()) {
            boolean prevAutoCommit = conn.getAutoCommit();
            try {
                conn.setAutoCommit(true);
                ensureBootstrap(conn);
                Set<String> applied = loadApplied(conn);

                conn.setAutoCommit(false);
                for (Migration m : all) {
                    if (applied.contains(m.version)) continue;
                    apply(conn, m);
                }
            } finally {
                conn.setAutoCommit(prevAutoCommit);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Schema migration failed", e);
        }
    }

    private void ensureBootstrap(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE SCHEMA IF NOT EXISTS wflow");
            s.execute("""
                    CREATE TABLE IF NOT EXISTS wflow.sql_migrations (
                        version    VARCHAR(64) PRIMARY KEY,
                        name       VARCHAR(255) NOT NULL,
                        applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )""");
        }
    }

    private Set<String> loadApplied(Connection conn) throws SQLException {
        Set<String> applied = new HashSet<>();
        try (Statement s = conn.createStatement();
             ResultSet rs = s.executeQuery("SELECT version FROM wflow.sql_migrations")) {
            while (rs.next()) applied.add(rs.getString(1));
        }
        return applied;
    }

    private void apply(Connection conn, Migration m) throws SQLException {
        log.info("Applying migration V{} — {}", m.version, m.name);
        try (Statement s = conn.createStatement()) {
            s.execute(m.sql);
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO wflow.sql_migrations(version, name) VALUES (?, ?)")) {
            ps.setString(1, m.version);
            ps.setString(2, m.name);
            ps.executeUpdate();
        }
        conn.commit();
    }

    private List<Migration> scan() throws IOException {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources = resolver.getResources(LOCATION);
        List<Migration> result = new ArrayList<>();
        for (Resource r : resources) {
            String filename = r.getFilename();
            if (filename == null) continue;
            Matcher mt = FILE.matcher(filename);
            if (!mt.matches()) continue;
            String version = mt.group(1).replace('_', '.');
            String name = filename.substring(filename.indexOf("__") + 2, filename.length() - 4);
            String sql = new String(r.getContentAsByteArray(), StandardCharsets.UTF_8);
            result.add(new Migration(version, name, sql));
        }
        result.sort((a, b) -> compareVersions(a.version, b.version));
        return result;
    }

    /** Element-wise integer comparison of dot-separated versions ({@code "1.2.3"}). */
    static int compareVersions(String a, String b) {
        String[] la = a.split("\\.");
        String[] lb = b.split("\\.");
        int n = Math.max(la.length, lb.length);
        for (int i = 0; i < n; i++) {
            int x = i < la.length ? parseIntSafe(la[i]) : 0;
            int y = i < lb.length ? parseIntSafe(lb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s); }
        catch (NumberFormatException e) { return 0; }
    }

    record Migration(String version, String name, String sql) {}
}
