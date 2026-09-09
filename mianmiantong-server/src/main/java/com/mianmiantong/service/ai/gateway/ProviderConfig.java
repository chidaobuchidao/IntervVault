package com.mianmiantong.service.ai.gateway;

import java.util.List;

/**
 * 提供者配置
 */
public record ProviderConfig(
    String name,
    String endpoint,
    String defaultModel,
    List<String> supportedModels,
    boolean streamUsageEnabled
) {
    public ProviderConfig(String name, String endpoint, String defaultModel, List<String> supportedModels) {
        this(name, endpoint, defaultModel, supportedModels, false);
    }
}
