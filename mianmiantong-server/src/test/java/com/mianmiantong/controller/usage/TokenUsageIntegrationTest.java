package com.mianmiantong.controller.usage;

import com.mianmiantong.common.JwtUtil;
import com.mianmiantong.service.ai.usage.AiUsageRecord;
import com.mianmiantong.service.ai.usage.AiUsageRecorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TokenUsageIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JwtUtil jwt;
    @Autowired private AiUsageRecorder recorder;
    @Autowired private PlatformTransactionManager transactionManager;

    @MockBean private StringRedisTemplate stringRedisTemplate;

    private final List<String> requestIds = new ArrayList<>();
    private long ownerId;
    private long otherId;
    private String model;
    private LocalDate today;

    @BeforeEach
    void insertIndependentFixtures() {
        // Unique IDs and model scope keep this fixture independent of other Spring context tests.
        ownerId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
        otherId = ownerId + 1;
        model = "usage-integration-" + UUID.randomUUID();
        today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        insert(ownerId, "SYSTEM", "SUCCESS", 10L, 20L);
        insert(ownerId, "PERSONAL", "FAILED", 40L, null);
        insert(otherId, "SYSTEM", "SUCCESS", 100L, 200L);
        insert(null, "SYSTEM", "FAILED", null, null);
    }

    @AfterEach
    void removeOnlyOwnFixtures() {
        for (String requestId : requestIds) {
            jdbc.update("DELETE FROM ai_usage_record WHERE request_id = ?", requestId);
        }
        SecurityContextHolder.clearContext();
    }

    @Test
    void signedUserTokenReturnsOnlyOwnedUsageThroughRealDatabaseAndController() throws Exception {
        mvc.perform(get("/api/user/token-usage").header("Authorization", bearer(ownerId, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.timezone").value("Asia/Shanghai"))
                .andExpect(jsonPath("$.data.startDate").value(today.minusDays(6).toString()))
                .andExpect(jsonPath("$.data.endDate").value(today.plusDays(1).toString()))
                .andExpect(jsonPath("$.data.summary.calls").value(2))
                .andExpect(jsonPath("$.data.summary.inputTokens").value(50))
                .andExpect(jsonPath("$.data.summary.outputTokens").value(20))
                .andExpect(jsonPath("$.data.summary.totalTokens").value(70))
                .andExpect(jsonPath("$.data.summary.unknownCalls").value(1))
                .andExpect(jsonPath("$.data.summary.failedCalls").value(1))
                .andExpect(jsonPath("$.data.daily.length()").value(7))
                .andExpect(jsonPath("$.data.daily[6].totalTokens").value(70))
                .andExpect(jsonPath("$.data.models[0].key").value(model))
                .andExpect(jsonPath("$.data.users").isEmpty());

        mvc.perform(get("/api/user/token-usage").header("Authorization", bearer(ownerId, 0))
                        .param("userId", String.valueOf(otherId)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void signedAdminTokenAggregatesAllOwnersAndAppliesSystemKeyAndUserFilters() throws Exception {
        mvc.perform(get("/api/admin/token-usage").header("Authorization", bearer(ownerId, 1))
                        .param("model", model))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.calls").value(4))
                .andExpect(jsonPath("$.data.summary.totalTokens").value(370))
                .andExpect(jsonPath("$.data.summary.unknownCalls").value(2))
                .andExpect(jsonPath("$.data.users.length()").value(3))
                .andExpect(jsonPath("$.data.users[0].key").value(String.valueOf(otherId)))
                .andExpect(jsonPath("$.data.users[2].key").value("unassigned"));

        mvc.perform(get("/api/admin/token-usage").header("Authorization", bearer(ownerId, 1))
                        .param("model", model).param("keySource", "SYSTEM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.calls").value(3))
                .andExpect(jsonPath("$.data.summary.totalTokens").value(330))
                .andExpect(jsonPath("$.data.daily[6].totalTokens").value(330));

        mvc.perform(get("/api/admin/token-usage").header("Authorization", bearer(ownerId, 1))
                        .param("model", model).param("keySource", "SYSTEM").param("userId", String.valueOf(ownerId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.calls").value(1))
                .andExpect(jsonPath("$.data.summary.totalTokens").value(30))
                .andExpect(jsonPath("$.data.users.length()").value(1));
    }

    @Test
    void realSecurityChainRejectsUnsignedDevTokenAndAdminAccessForRegularUser() throws Exception {
        mvc.perform(get("/api/user/token-usage").header("Authorization", "Bearer dev-token-" + ownerId))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/token-usage").header("Authorization", "Bearer dev-token-" + ownerId))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/token-usage").header("Authorization", bearer(ownerId, 0)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403));
    }

    @Test
    void proxiedRecorderCommitsUsageWhenSurroundingBusinessTransactionRollsBack() {
        String recordedRequestId = newRequestId();
        String businessRequestId = newRequestId();
        LocalDateTime occurredAt = today.atTime(12, 0);
        var transactions = new TransactionTemplate(transactionManager);

        transactions.executeWithoutResult(transaction -> {
            insert(businessRequestId, ownerId, "SYSTEM", "SUCCESS", 999L, 999L);
            recorder.record(new AiUsageRecord(recordedRequestId, occurredAt, ownerId,
                    "test", model, "OTHER", "SYSTEM", false, "SUCCESS", 12L, 34L));
            transaction.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_record WHERE request_id = ?",
                Long.class, businessRequestId)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_usage_record WHERE request_id = ?",
                Long.class, recordedRequestId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT input_tokens + output_tokens FROM ai_usage_record WHERE request_id = ?",
                Long.class, recordedRequestId)).isEqualTo(46L);
    }

    private String bearer(long userId, int role) {
        return "Bearer " + jwt.generateToken(userId, "usage-integration", role);
    }

    private String newRequestId() {
        String requestId = UUID.randomUUID().toString();
        requestIds.add(requestId);
        return requestId;
    }

    private void insert(Long userId, String source, String status, Long input, Long output) {
        insert(newRequestId(), userId, source, status, input, output);
    }

    private void insert(String requestId, Long userId, String source, String status, Long input, Long output) {
        jdbc.update("INSERT INTO ai_usage_record (request_id, occurred_at, user_id, provider, model, feature, "
                        + "key_source, streaming, status, input_tokens, output_tokens) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                requestId, today.atTime(12, 0), userId, "test", model, "OTHER", source, false, status, input, output);
    }
}
