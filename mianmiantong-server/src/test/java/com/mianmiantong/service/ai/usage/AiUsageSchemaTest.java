package com.mianmiantong.service.ai.usage;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class AiUsageSchemaTest {
    @Test
    void migrationPreservesUnknownUsageAndLargeCountsAndRejectsDuplicateRequests() {
        var migration = new ClassPathResource("db/migration/V16__ai_usage_record.sql");
        assertThat(migration.exists()).as("Token usage migration exists").isTrue();
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(migration).execute(source);
        var jdbc = new JdbcTemplate(source);
        String insert = "INSERT INTO ai_usage_record (request_id, occurred_at, provider, model, feature, key_source, streaming, status, input_tokens) VALUES (?, CURRENT_TIMESTAMP, 'test', 'model', 'RESUME', 'SYSTEM', false, 'SUCCESS', ?)";
        jdbc.update(insert, "one", 3_000_000_000L);
        assertThat(jdbc.queryForObject("SELECT input_tokens FROM ai_usage_record", Long.class)).isEqualTo(3_000_000_000L);
        assertThat(jdbc.queryForObject("SELECT output_tokens FROM ai_usage_record", Long.class)).isNull();
        assertThatThrownBy(() -> jdbc.update(insert, "one", 10)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }
}
