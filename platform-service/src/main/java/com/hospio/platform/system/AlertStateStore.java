package com.hospio.platform.system;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** platform.system_alert_states: one row per system health condition that has been alerted and not yet cleared. */
@Component
public class AlertStateStore {

    /** A raised condition: its level, the words last sent, when it was raised and its dashboard alert. */
    public record State(String key, String level, String title, String detail, Instant raisedAt, String alertId) {
    }

    private final JdbcTemplate jdbc;

    public AlertStateStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, State> load() {
        Map<String, State> out = new LinkedHashMap<>();
        jdbc.query("SELECT condition_key, level, title, detail, raised_at, alert_id FROM platform.system_alert_states", rs -> {
            out.put(rs.getString(1), new State(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getTimestamp(5).toInstant(),
                    rs.getString(6)));
        });
        return out;
    }

    public void insert(State s) {
        jdbc.update("INSERT INTO platform.system_alert_states (condition_key, level, title, detail, raised_at, alert_id, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?) ON CONFLICT (condition_key) DO NOTHING", s.key(), s.level(), cut(s.title(), 200), cut(s.detail(), 1000),
                Timestamp.from(s.raisedAt()), s.alertId(), Timestamp.from(Instant.now()));
    }

    public void update(String key, String level, String title, String detail) {
        jdbc.update("UPDATE platform.system_alert_states SET level = ?, title = ?, detail = ?, updated_at = ? WHERE condition_key = ?", level,
                cut(title, 200), cut(detail, 1000), Timestamp.from(Instant.now()), key);
    }

    public void delete(String key) {
        jdbc.update("DELETE FROM platform.system_alert_states WHERE condition_key = ?", key);
    }

    static String cut(String s, int max) {
        return s == null ? null : s.length() <= max ? s : s.substring(0, max);
    }
}
