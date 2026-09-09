package com.mianmiantong.config;

import com.mianmiantong.service.ai.gateway.*;
import com.mianmiantong.service.user.QuotaService;
import com.mianmiantong.service.user.UserAiConfigService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 提供者配置
 * 创建 AiGateway 所需的所有 Bean
 */
@Configuration
public class AiProviderConfig {

    @Bean
    public ProviderConfig deepseekProviderConfig(
            @Value("${ai.deepseek.endpoint}") String endpoint,
            @Value("${ai.deepseek.model}") String model,
            @Value("${ai.deepseek.stream-usage-enabled:true}") boolean streamUsageEnabled) {
        return new ProviderConfig("deepseek", endpoint, model, List.of("deepseek-v4-flash", "deepseek-v4-pro"), streamUsageEnabled);
    }

    @Bean
    public ProviderConfig qwenProviderConfig(
            @Value("${ai.qwen.endpoint}") String endpoint,
            @Value("${ai.qwen.model}") String model,
            @Value("${ai.qwen.stream-usage-enabled:false}") boolean streamUsageEnabled) {
        return new ProviderConfig("qwen", endpoint, model, List.of("qwen-turbo", "qwen-plus"), streamUsageEnabled);
    }

    @Bean
    public ProviderConfig doubaoProviderConfig(
            @Value("${ai.doubao.endpoint}") String endpoint,
            @Value("${ai.doubao.model}") String model,
            @Value("${ai.doubao.stream-usage-enabled:false}") boolean streamUsageEnabled) {
        return new ProviderConfig("doubao", endpoint, model, List.of("doubao-lite-4k", "doubao-pro-4k", "doubao-pro-32k"), streamUsageEnabled);
    }

    @Bean
    public ProviderConfig zhipuProviderConfig(
            @Value("${ai.zhipu.endpoint}") String endpoint,
            @Value("${ai.zhipu.model}") String model,
            @Value("${ai.zhipu.stream-usage-enabled:false}") boolean streamUsageEnabled) {
        return new ProviderConfig("zhipu", endpoint, model, List.of("glm-4-flash", "glm-4-plus", "glm-4-long"), streamUsageEnabled);
    }

    @Bean
    public OpenAiCompatibleAdapter deepseekAdapter(
            @Qualifier("deepseekProviderConfig") ProviderConfig config) {
        return new OpenAiCompatibleAdapter(config);
    }

    @Bean
    public OpenAiCompatibleAdapter qwenAdapter(
            @Qualifier("qwenProviderConfig") ProviderConfig config) {
        return new OpenAiCompatibleAdapter(config);
    }

    @Bean
    public OpenAiCompatibleAdapter doubaoAdapter(
            @Qualifier("doubaoProviderConfig") ProviderConfig config) {
        return new OpenAiCompatibleAdapter(config);
    }

    @Bean
    public OpenAiCompatibleAdapter zhipuAdapter(
            @Qualifier("zhipuProviderConfig") ProviderConfig config) {
        return new OpenAiCompatibleAdapter(config);
    }

    @Bean
    public ProviderRegistry providerRegistry(
            @Qualifier("deepseekAdapter") OpenAiCompatibleAdapter deepseekAdapter,
            @Qualifier("qwenAdapter") OpenAiCompatibleAdapter qwenAdapter,
            @Qualifier("doubaoAdapter") OpenAiCompatibleAdapter doubaoAdapter,
            @Qualifier("zhipuAdapter") OpenAiCompatibleAdapter zhipuAdapter,
            @Value("${ai.provider}") String defaultProvider) {
        Map<String, ProviderAdapter> adapters = new HashMap<>();
        adapters.put("deepseek", deepseekAdapter);
        adapters.put("qwen", qwenAdapter);
        adapters.put("doubao", doubaoAdapter);
        adapters.put("zhipu", zhipuAdapter);
        return new ProviderRegistry(adapters, defaultProvider);
    }

    @Bean
    public KeyResolver keyResolver(UserAiConfigService userAiConfigService,
                                   @Value("${ai.deepseek.api-key:}") String systemApiKey) {
        return new KeyResolver(userAiConfigService, systemApiKey);
    }

    @Bean
    public ModelResolver modelResolver(UserAiConfigService userAiConfigService) {
        return new ModelResolver(userAiConfigService);
    }

    @Bean
    public QuotaPolicy quotaPolicy(QuotaService quotaService) {
        return new QuotaPolicy(quotaService);
    }

    @Bean
    public EndpointValidator endpointValidator() {
        return new EndpointValidator();
    }
}
