package com.mianmiantong.service.ai.gateway;

/**
 * AI 响应包装
 */
public record AiResponse(
    String content,
    String model,
    Long promptTokens,
    Long completionTokens
) {
    public AiResponse(String content, String model, int promptTokens, int completionTokens) {
        this(content, model, Long.valueOf(promptTokens), Long.valueOf(completionTokens));
    }
}
