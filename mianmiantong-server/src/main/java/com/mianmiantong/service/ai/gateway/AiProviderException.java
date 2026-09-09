package com.mianmiantong.service.ai.gateway;

/** Preserves returned billing metadata even if a provider response cannot be consumed. */
public class AiProviderException extends RuntimeException {
    private final TokenUsage usage;
    private final String model;

    public AiProviderException(String message, Throwable cause, TokenUsage usage, String model) {
        super(message, cause);
        this.usage = usage;
        this.model = model;
    }

    public TokenUsage usage() { return usage; }
    public String model() { return model; }
}
