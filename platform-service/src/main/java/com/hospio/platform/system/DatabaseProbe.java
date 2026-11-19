package com.hospio.platform.system;

import com.hospio.platform.system.SystemHealthDtos.Backups;
import com.hospio.platform.system.SystemHealthDtos.Database;
import com.hospio.platform.system.SystemHealthDtos.NamedSize;
import com.hospio.platform.system.SystemHealthDtos.TableSize;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Postgres, read-only, on one pooled connection with a 3-second statement limit: version, sizes (every database on the server, each
 * Hospio schema, the five largest tables), connections in use against max_connections, the longest running query, the cache hit ratio
 * and rows waiting for autovacuum. Catalog and statistics views only; no table data is read. Without pg_read_all_stats Postgres
 * shows query times only for Hospio's own connections, which is what matters here.
 */
@Slf4j
@Component
public class DatabaseProbe {

    private static final String EXCLUDED = "('pg_catalog','information_schema')";

    private final JdbcTemplate jdbc;

    public DatabaseProbe(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Database probe(Backups backups) {
        try {
            return jdbc.execute((ConnectionCallback<Database>) con -> read(con, backups));
        } catch (Exception e) {
            log.warn("System health: database not readable: {}", e.toString());
            return new Database(false, null, "Not reachable: " + e.getClass().getSimpleName(), null, null, List.of(), List.of(), null, null,
                    null, List.of(), null, null, null, backups, SystemHealthDtos.PROBLEM);
        }
    }

    private Database read(Connection con, Backups backups) throws SQLException {
        try (Statement st = con.createStatement()) {
            st.execute("SET statement_timeout = '3s'");
            try {
                String version = one(st, "SHOW server_version");
                Integer max = intOf(one(st, "SHOW max_connections"));
                Integer connections = intOf(one(st, "SELECT count(*) FROM pg_stat_activity WHERE backend_type = 'client backend'"));
                Double longest = dbl(one(st, "SELECT EXTRACT(EPOCH FROM max(now() - query_start)) FROM pg_stat_activity "
                        + "WHERE state = 'active' AND backend_type = 'client backend' AND pid <> pg_backend_pid()"));
                List<NamedSize> databases = new ArrayList<>();
                rows(st, "SELECT datname, pg_database_size(datname) FROM pg_database WHERE NOT datistemplate "
                        + "AND has_database_privilege(datname, 'CONNECT') ORDER BY 2 DESC", rs -> databases.add(new NamedSize(rs.getString(1), rs.getLong(2))));
                Long hospio = longOf(one(st, "SELECT pg_database_size(current_database())"));
                List<NamedSize> schemas = new ArrayList<>();
                rows(st, "SELECT n.nspname, sum(pg_total_relation_size(c.oid))::bigint FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "WHERE c.relkind IN ('r','m') AND n.nspname NOT IN " + EXCLUDED + " AND n.nspname NOT LIKE 'pg_toast%' "
                        + "GROUP BY 1 ORDER BY 2 DESC", rs -> schemas.add(new NamedSize(rs.getString(1), rs.getLong(2))));
                List<TableSize> tables = new ArrayList<>();
                rows(st, "SELECT n.nspname, c.relname, pg_total_relation_size(c.oid), coalesce(s.n_live_tup, 0), coalesce(s.n_dead_tup, 0), "
                        + "greatest(s.last_autovacuum, s.last_vacuum) FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace "
                        + "LEFT JOIN pg_stat_all_tables s ON s.relid = c.oid WHERE c.relkind = 'r' AND n.nspname NOT IN " + EXCLUDED
                        + " AND n.nspname NOT LIKE 'pg_toast%' ORDER BY 3 DESC LIMIT 5", rs -> {
                    Timestamp t = rs.getTimestamp(6);
                    tables.add(new TableSize(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), t == null ? null : t.toInstant()));
                });
                Double hit = dbl(one(st, "SELECT round(sum(blks_hit) * 100.0 / nullif(sum(blks_hit + blks_read), 0), 2) FROM pg_stat_database "
                        + "WHERE datname = current_database()"));
                Long dead = null;
                Integer needVacuum = null;
                try (ResultSet rs = st.executeQuery("SELECT coalesce(sum(n_dead_tup), 0), count(*) FILTER (WHERE n_dead_tup > 1000 "
                        + "AND n_dead_tup > n_live_tup * 0.2) FROM pg_stat_all_tables WHERE schemaname NOT IN " + EXCLUDED
                        + " AND schemaname NOT LIKE 'pg_toast%'")) {
                    if (rs.next()) {
                        dead = rs.getLong(1);
                        needVacuum = rs.getInt(2);
                    }
                }
                long total = databases.stream().mapToLong(NamedSize::bytes).sum();
                return new Database(true, version, null, total, hospio, databases, schemas, connections, max, longest, tables, hit, dead,
                        needVacuum, backups, SystemHealthDtos.OK);
            } finally {
                st.execute("RESET statement_timeout");
            }
        }
    }

    @FunctionalInterface
    private interface Row {
        void accept(ResultSet rs) throws SQLException;
    }

    private static void rows(Statement st, String sql, Row row) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                row.accept(rs);
            }
        }
    }

    private static String one(Statement st, String sql) throws SQLException {
        try (ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static Integer intOf(String s) {
        try {
            return s == null ? null : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long longOf(String s) {
        try {
            return s == null ? null : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double dbl(String s) {
        try {
            return s == null ? null : Math.round(Double.parseDouble(s.trim()) * 100.0) / 100.0;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
