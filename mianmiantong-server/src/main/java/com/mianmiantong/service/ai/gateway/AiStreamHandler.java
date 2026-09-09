package com.mianmiantong.service.ai.gateway;

/**
 * 流式响应处理器，替代 Consumer<String>
 */
@FunctionalInterface
public interface AiStreamHandler {
    void onToken(String token);

    default void onUsage(TokenUsage usage) { }

    default void onModel(String model) { }
}
