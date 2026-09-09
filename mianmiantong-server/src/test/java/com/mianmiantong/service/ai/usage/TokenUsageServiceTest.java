package com.mianmiantong.service.ai.usage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class TokenUsageServiceTest {
    private JdbcTemplate jdbc;
    private TokenUsageService service;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:usage_query_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V16__ai_usage_record.sql"))
                .execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        // Shanghai has crossed midnight while UTC is still September 8.
        service = new TokenUsageService(jdbc,
                Clock.fixed(Instant.parse("2026-09-08T16:30:00Z"), ZoneOffset.UTC));
    }

    @Test
    void bindsShanghaiWallClockBoundariesAsLocalDateTimeForDatetimeColumns() {
        JdbcTemplate observedJdbc = spy(jdbc);
        var observedService = new TokenUsageService(observedJdbc,
                Clock.fixed(Instant.parse("2026-09-08T16:30:00Z"), ZoneOffset.UTC));

        observedService.personal(7L, 7, null, null);

        // Timestamp would let Connector/J shift these boundaries when JVM and connection zones differ.
        verify(observedJdbc).queryForObject(anyString(), any(RowMapper.class),
                eq(LocalDateTime.of(2026, 9, 3, 0, 0)),
                eq(LocalDateTime.of(2026, 9, 10, 0, 0)), eq(7L));
    }

    @Test
    void isolatesPersonalUsageIncludesBothKeySourcesAndUsesExclusiveShanghaiBoundary() {
        insert("2026-09-02 23:59:59", 7L, "m", "INTERVIEW", "SYSTEM", "SUCCESS", 999L, 999L);
        insert("2026-09-03 00:00:00", 7L, "m", "INTERVIEW", "SYSTEM", "SUCCESS", 10L, 20L);
        insert("2026-09-09 23:59:59", 7L, "m", "INTERVIEW", "PERSONAL", "SUCCESS", 30L, 40L);
        insert("2026-09-10 00:00:00", 7L, "m", "INTERVIEW", "SYSTEM", "SUCCESS", 999L, 999L);
        insert("2026-09-08 12:00:00", 8L, "secret", "RESUME", "SYSTEM", "SUCCESS", 999L, 999L);
        insert("2026-09-08 12:00:00", null, "orphan", "OTHER", "SYSTEM", "SUCCESS", 999L, 999L);

        var response = service.personal(7L, 7, null, null);
        assertThat(response.timezone()).isEqualTo("Asia/Shanghai");
        assertThat(response.startDate()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(response.endDate()).isEqualTo(LocalDate.of(2026, 9, 10));
        assertThat(response.summary()).isEqualTo(new TokenUsageResponse.Metrics(2, 40, 60, 100, 0, 0));
        assertThat(response.daily()).hasSize(7);
        assertThat(response.daily().get(1).calls()).isZero();
        assertThat(response.daily().get(6).totalTokens()).isEqualTo(70);
        assertThat(response.models()).extracting(TokenUsageResponse.Group::key).containsExactly("m");
        assertThat(response.features()).extracting(TokenUsageResponse.Group::key).containsExactly("INTERVIEW");
        assertThat(response.users()).isEmpty();
    }

    @Test
    void aggregatesKnownComponentsAndUnknownFailedCallsWithoutLosingLongValues() {
        insert("2026-09-09 01:00:00", 7L, "m", "INTERVIEW", "SYSTEM", "SUCCESS", 4_000_000_000L, 3L);
        insert("2026-09-09 02:00:00", 7L, "m", "INTERVIEW", "SYSTEM", "FAILED", 8L, null);
        insert("2026-09-09 03:00:00", 7L, "m", "INTERVIEW", "SYSTEM", "FAILED", null, 9L);
        insert("2026-09-09 04:00:00", 7L, "m", "INTERVIEW", "SYSTEM", "SUCCESS", null, null);
        assertThat(service.personal(7L, 7, null, null).summary())
                .isEqualTo(new TokenUsageResponse.Metrics(4, 4_000_000_008L, 12, 4_000_000_020L, 3, 2));
    }

    @Test
    void adminFiltersApplyToEveryAggregationAndModelFilterIsBoundAsData() {
        String model = "model' OR 1=1 --";
        insert("2026-09-09 01:00:00", 7L, model, "RESUME", "PERSONAL", "SUCCESS", 10L, 20L);
        insert("2026-09-09 01:00:00", 7L, model, "RESUME", "SYSTEM", "SUCCESS", 900L, 900L);
        insert("2026-09-09 01:00:00", 8L, model, "RESUME", "PERSONAL", "SUCCESS", 900L, 900L);
        insert("2026-09-09 01:00:00", 7L, "other", "RESUME", "PERSONAL", "SUCCESS", 900L, 900L);
        insert("2026-09-09 01:00:00", 7L, model, "INTERVIEW", "PERSONAL", "SUCCESS", 900L, 900L);
        var response = service.admin(7, model, "RESUME", 7L, "PERSONAL");
        assertThat(response.summary().calls()).isEqualTo(1);
        assertThat(response.daily().get(6).totalTokens()).isEqualTo(30);
        assertThat(response.models()).singleElement().satisfies(g -> {
            assertThat(g.key()).isEqualTo(model);
            assertThat(g.totalTokens()).isEqualTo(30);
        });
        assertThat(response.features()).singleElement().satisfies(g -> assertThat(g.totalTokens()).isEqualTo(30));
        assertThat(response.users()).singleElement().satisfies(g -> assertThat(g.key()).isEqualTo("7"));
    }

    @Test
    void adminIncludesUnassignedAndCapsOrderedRankingsAtTwenty() {
        for (int i = 1; i <= 25; i++) {
            insert("2026-09-09 01:00:00", (long) i, "m" + i, "f" + i, "SYSTEM", "SUCCESS", (long) i, 0L);
        }
        insert("2026-09-09 01:00:00", null, "top", "top", "SYSTEM", "SUCCESS", 100L, 0L);
        var response = service.admin(7, null, null, null, null);
        assertThat(response.summary().calls()).isEqualTo(26);
        assertThat(response.models()).hasSize(20);
        assertThat(response.features()).hasSize(20);
        assertThat(response.users()).hasSize(20);
        assertThat(response.users().get(0).key()).isEqualTo("unassigned");
        assertThat(response.models().get(0).key()).isEqualTo("top");
    }

    @Test
    void emptyWindowsAreZeroFilledAndValidationRejectsInvalidFilters() {
        for (int days : new int[]{7, 30, 90}) {
            var response = service.personal(7L, days, "", "");
            assertThat(response.daily()).hasSize(days).allSatisfy(d -> assertThat(d.totalTokens()).isZero());
            assertThat(response.summary()).isEqualTo(new TokenUsageResponse.Metrics(0, 0, 0, 0, 0, 0));
            assertThat(response.models()).isEmpty();
        }
        assertThatIllegalArgumentException().isThrownBy(() -> service.personal(7L, 8, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.personal(0L, 7, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.admin(7, "x".repeat(256), null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.admin(7, null, "x".repeat(65), null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.admin(7, null, null, -1L, null));
        assertThatIllegalArgumentException().isThrownBy(() -> service.admin(7, null, null, null, "all"));
    }

    private void insert(String time, Long userId, String model, String feature, String source,
                        String status, Long input, Long output) {
        jdbc.update("INSERT INTO ai_usage_record (request_id, occurred_at, user_id, provider, model, feature, "
                        + "key_source, streaming, status, input_tokens, output_tokens) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID().toString(), time, userId, "test", model, feature, source, false, status, input, output);
    }
}
