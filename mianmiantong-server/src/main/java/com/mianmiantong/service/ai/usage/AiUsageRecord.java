package com.mianmiantong.service.ai.usage;

import java.time.LocalDateTime;

/** Statistical metadata only: never include credentials, prompts or generated content. */
public record AiUsageRecord(String requestId, LocalDateTime occurredAt, Long userId,
        String provider, String model, String feature, String keySource, boolean streaming,
        String status, Long inputTokens, Long outputTokens) {
}
