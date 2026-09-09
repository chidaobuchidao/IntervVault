package com.mianmiantong.service.ai.gateway;

import com.mianmiantong.entity.user.UserAiConfig;
import com.mianmiantong.service.user.UserAiConfigService;

/**
 * API Key 解析器
 * 优先使用用户自己的 key，否则使用系统 key
 */
public class KeyResolver {

    private final UserAiConfigService userAiConfigService;
    private final String systemApiKey;

    public KeyResolver(UserAiConfigService userAiConfigService, String systemApiKey) {
        this.userAiConfigService = userAiConfigService;
        this.systemApiKey = systemApiKey;
    }

    /**
     * 解析 API Key
     * @param userId 用户 ID，null 表示使用系统 key
     * @return 解析后的 API Key
     */
    public String resolve(Long userId) {
        return resolveCredentials(userId).apiKey();
    }

    public ResolvedKey resolveCredentials(Long userId) {
        if (userId != null) {
            UserAiConfig config = userAiConfigService.getByUserId(userId);
            if (config != null && config.getApiKey() != null && !config.getApiKey().isBlank()) {
                return new ResolvedKey(config.getApiKey(), "PERSONAL");
            }
        }
        return new ResolvedKey(systemApiKey, "SYSTEM");
    }

    public record ResolvedKey(String apiKey, String source) {
        @Override public String toString() { return "ResolvedKey[source=" + source + "]"; }
    }
}
