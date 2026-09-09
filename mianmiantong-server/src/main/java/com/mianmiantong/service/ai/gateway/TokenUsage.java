package com.mianmiantong.service.ai.gateway;

import java.math.BigDecimal;
import java.util.Map;

/** Null denotes usage the provider did not report, not zero consumption. */
public record TokenUsage(Long inputTokens, Long outputTokens) {
    public static final TokenUsage UNKNOWN = new TokenUsage(null, null);

    public TokenUsage {
        if (inputTokens != null && inputTokens < 0) inputTokens = null;
        if (outputTokens != null && outputTokens < 0) outputTokens = null;
    }

    public static TokenUsage parse(Object value) {
        if (!(value instanceof Map<?, ?> map)) return UNKNOWN;
        return new TokenUsage(count(map.get("prompt_tokens")), count(map.get("completion_tokens")));
    }

    private static Long count(Object value) {
        if (!(value instanceof Number number)) return null;
        try {
            long count = new BigDecimal(number.toString()).longValueExact();
            return count >= 0 ? count : null;
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }
}
