package com.mianmiantong.service.ai.gateway;

import java.util.List;

/**
 * 不可变的 AI 请求对象
 */
public record AiRequest(
    String systemPrompt,
    List<ChatMessage> messages,
    String model,
    AiTaskType taskType,
    Long usageUserId,
    String feature
) {
    public AiRequest(String systemPrompt, List<ChatMessage> messages, String model, AiTaskType taskType) {
        this(systemPrompt, messages, model, taskType, null, "OTHER");
    }

    /** Attribution does not change the user ID used for key, model or quota resolution. */
    public AiRequest withUsage(Long userId, String feature) {
        return new AiRequest(systemPrompt, messages, model, taskType, userId, feature);
    }
}
