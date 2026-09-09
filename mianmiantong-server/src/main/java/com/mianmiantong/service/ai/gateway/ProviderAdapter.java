package com.mianmiantong.service.ai.gateway;

/**
 * AI 提供者适配器接口
 * 每个 AI 提供者（DeepSeek、Qwen、OpenAI 等）实现此接口
 */
public interface ProviderAdapter {

    /** 提供者名称 */
    String name();

    default String defaultModel() { return ""; }

    /** 同步聊天 */
    AiResponse chat(AiRequest request, String apiKey);

    /** 流式聊天 */
    void streamChat(AiRequest request, String apiKey, AiStreamHandler handler);
}
