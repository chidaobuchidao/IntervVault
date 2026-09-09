package com.mianmiantong.service.ai.usage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TokenUsageService {
    public static final ZoneId REPORTING_ZONE = ZoneId.of("Asia/Shanghai");
    private static final String AGGREGATES = "COUNT(*) AS calls, "
            + "COALESCE(SUM(input_tokens), 0) AS input_tokens, "
            + "COALESCE(SUM(output_tokens), 0) AS output_tokens, "
            + "COALESCE(SUM(input_tokens), 0) + COALESCE(SUM(output_tokens), 0) AS total_tokens, "
            + "COALESCE(SUM(CASE WHEN input_tokens IS NULL OR output_tokens IS NULL THEN 1 ELSE 0 END), 0) AS unknown_calls, "
            + "COALESCE(SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END), 0) AS failed_calls";

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    public TokenUsageService(JdbcTemplate jdbc) {
        this(jdbc, Clock.system(REPORTING_ZONE));
    }

    public TokenUsageService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public TokenUsageResponse personal(Long currentUserId, int days, String model, String feature) {
        requirePositiveUser(currentUserId);
        return aggregate(days, model, feature, currentUserId, null, false);
    }

    @Transactional(readOnly = true)
    public TokenUsageResponse admin(int days, String model, String feature, Long userId, String keySource) {
        if (userId != null) requirePositiveUser(userId);
        if (keySource != null && !keySource.equals("SYSTEM") && !keySource.equals("PERSONAL")) {
            throw new IllegalArgumentException("keySource 只支持 SYSTEM 或 PERSONAL");
        }
        return aggregate(days, model, feature, userId, keySource, true);
    }

    private TokenUsageResponse aggregate(int days, String model, String feature, Long userId,
                                         String keySource, boolean includeUsers) {
        if (days != 7 && days != 30 && days != 90) {
            throw new IllegalArgumentException("days 只支持 7、30 或 90");
        }
        validateLength(model, 255, "model");
        validateLength(feature, 64, "feature");

        LocalDate end = LocalDate.now(clock.withZone(REPORTING_ZONE)).plusDays(1);
        LocalDate start = end.minusDays(days);
        StringBuilder where = new StringBuilder(" FROM ai_usage_record WHERE occurred_at >= ? AND occurred_at < ?");
        List<Object> params = new ArrayList<>();
        // DATETIME stores Shanghai wall-clock values; LocalDateTime avoids JDBC instant conversion.
        params.add(start.atStartOfDay());
        params.add(end.atStartOfDay());
        if (userId != null) {
            where.append(" AND user_id = ?");
            params.add(userId);
        }
        if (model != null && !model.isEmpty()) {
            where.append(" AND model = ?");
            params.add(model);
        }
        if (feature != null && !feature.isEmpty()) {
            where.append(" AND feature = ?");
            params.add(feature);
        }
        if (keySource != null) {
            where.append(" AND key_source = ?");
            params.add(keySource);
        }
        String filter = where.toString();
        Object[] args = params.toArray();
        TokenUsageResponse.Metrics summary = jdbc.queryForObject("SELECT " + AGGREGATES + filter,
                (rs, rowNum) -> metrics(rs), args);
        Map<LocalDate, TokenUsageResponse.Metrics> dailyValues = new HashMap<>();
        jdbc.query("SELECT CAST(occurred_at AS DATE) AS usage_date, " + AGGREGATES + filter
                        + " GROUP BY CAST(occurred_at AS DATE)",
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        dailyValues.put(rs.getDate("usage_date").toLocalDate(), metrics(rs)), args);
        List<TokenUsageResponse.Daily> daily = start.datesUntil(end)
                .map(date -> TokenUsageResponse.Daily.of(date, dailyValues.getOrDefault(date, TokenUsageResponse.Metrics.ZERO)))
                .toList();

        return new TokenUsageResponse(REPORTING_ZONE.getId(), start, end, summary, daily,
                groups(Grouping.MODEL, filter, args), groups(Grouping.FEATURE, filter, args),
                includeUsers ? groups(Grouping.USER, filter, args) : List.of());
    }

    private List<TokenUsageResponse.Group> groups(Grouping grouping, String filter, Object[] args) {
        String column = grouping.column;
        // Column names come only from this enum. All caller-controlled filters are bound parameters.
        return jdbc.query("SELECT " + column + " AS group_key, " + AGGREGATES + filter
                        + " GROUP BY " + column + " ORDER BY total_tokens DESC, calls DESC, " + column + " ASC LIMIT 20",
                (rs, rowNum) -> TokenUsageResponse.Group.of(
                        rs.getString("group_key") == null ? "unassigned" : rs.getString("group_key"), metrics(rs)), args);
    }

    private static TokenUsageResponse.Metrics metrics(ResultSet rs) throws SQLException {
        // SUM(BIGINT) returns decimal on MySQL. Exact conversion prevents silent long overflow.
        return new TokenUsageResponse.Metrics(number(rs, "calls"), number(rs, "input_tokens"),
                number(rs, "output_tokens"), number(rs, "total_tokens"),
                number(rs, "unknown_calls"), number(rs, "failed_calls"));
    }

    private static long number(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column).longValueExact();
    }

    private static void requirePositiveUser(Long userId) {
        if (userId == null || userId <= 0) throw new IllegalArgumentException("userId 必须为正整数");
    }

    private static void validateLength(String value, int maximum, String name) {
        if (value != null && value.length() > maximum) {
            throw new IllegalArgumentException(name + " 长度不能超过 " + maximum);
        }
    }

    private enum Grouping {
        MODEL("model"), FEATURE("feature"), USER("user_id");
        private final String column;

        Grouping(String column) {
            this.column = column;
        }
    }
}
