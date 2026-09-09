package com.mianmiantong.service.ai.usage;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

class AiUsageRecorderTest {
    @Test
    void savesMetadataOnceAndKeepsUnknownTokensNull() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V16__ai_usage_record.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        var recorder = new AiUsageRecorder(jdbc);
        var record = new AiUsageRecord("unique", LocalDateTime.of(2026, 9, 9, 12, 0), 42L,
                "test", "model", "RESUME", "SYSTEM", true, "FAILED", 3_000_000_000L, null);
        recorder.record(record);
        recorder.record(record);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_record", Integer.class)).isEqualTo(1);
        var row = jdbc.queryForMap("SELECT * FROM ai_usage_record");
        assertThat(row.get("USER_ID")).isEqualTo(42L);
        assertThat(row.get("INPUT_TOKENS")).isEqualTo(3_000_000_000L);
        assertThat(row.get("OUTPUT_TOKENS")).isNull();
        assertThat(row.get("STATUS")).isEqualTo("FAILED");
    }

    @Test
    void databaseFailureDoesNotReplaceAiResult() {
        var recorder = new AiUsageRecorder(new JdbcTemplate(new DriverManagerDataSource("jdbc:h2:mem:missing", "sa", "")));
        assertThatCode(() -> recorder.record(new AiUsageRecord("request", LocalDateTime.now(), null,
                "test", "model", "OTHER", "SYSTEM", false, "SUCCESS", null, null))).doesNotThrowAnyException();
    }
}
