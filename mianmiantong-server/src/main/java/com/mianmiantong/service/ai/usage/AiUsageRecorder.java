package com.mianmiantong.service.ai.usage;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class AiUsageRecorder {
    private final JdbcTemplate jdbc;

    public AiUsageRecorder(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // AI usage survives a surrounding business transaction rollback.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AiUsageRecord r) {
        try {
            jdbc.update("""
                    INSERT INTO ai_usage_record
                    (request_id, occurred_at, user_id, provider, model, feature, key_source,
                     streaming, status, input_tokens, output_tokens)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, r.requestId(), r.occurredAt(), r.userId(), r.provider(), r.model(), r.feature(),
                    r.keySource(), r.streaming(), r.status(), r.inputTokens(), r.outputTokens());
        } catch (DuplicateKeyException ignored) {
            log.debug("AI usage already recorded: requestId={}", r.requestId());
        } catch (DataAccessException e) {
            log.warn("AI usage recording failed: requestId={}, errorType={}", r.requestId(), e.getClass().getSimpleName());
        }
    }
}
