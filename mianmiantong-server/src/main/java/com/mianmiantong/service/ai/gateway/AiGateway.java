package com.mianmiantong.service.ai.gateway;

import com.mianmiantong.entity.user.UserAiConfig;
import com.mianmiantong.service.user.UserAiConfigService;
import com.mianmiantong.service.ai.usage.AiUsageRecord;
import com.mianmiantong.service.ai.usage.AiUsageRecorder;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 网关门面
 * 消费者使用此服务与 AI 交互
 */
@Slf4j
@Service
public class AiGateway {

    private final ProviderRegistry registry;
    private final KeyResolver keyResolver;
    private final ModelResolver modelResolver;
    private final QuotaPolicy quotaPolicy;
    private final UserAiConfigService userAiConfigService;
    private final AiUsageRecorder usageRecorder;

    public AiGateway(ProviderRegistry registry, KeyResolver keyResolver,
                     ModelResolver modelResolver, QuotaPolicy quotaPolicy,
                     UserAiConfigService userAiConfigService, AiUsageRecorder usageRecorder) {
        this.registry = registry;
        this.keyResolver = keyResolver;
        this.modelResolver = modelResolver;
        this.quotaPolicy = quotaPolicy;
        this.userAiConfigService = userAiConfigService;
        this.usageRecorder = usageRecorder;
    }

    /**
     * 同步聊天
     * @param request AI 请求
     * @param userId 用户 ID（null 表示匿名）
     * @return AI 响应
     */
    public AiResponse chat(AiRequest request, Long userId) {
        ProviderAdapter adapter = resolveAdapter(userId);
        KeyResolver.ResolvedKey credentials = keyResolver.resolveCredentials(userId);
        String apiKey = credentials.apiKey();
        String model = modelResolver.resolve(userId, request.model(), adapter.defaultModel());

        // 创建带有解析后模型的请求
        AiRequest resolvedRequest = new AiRequest(
                request.systemPrompt(),
                request.messages(),
                model,
                request.taskType()
        );

        // 检查配额
        quotaPolicy.consume(userId, request.taskType(), model);

        log.info("AI chat: provider={}, model={}, userId={}", adapter.name(), model, userId);
        String keySource = credentials.source();
        String requestId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        TokenUsage usage = TokenUsage.UNKNOWN;
        String actualModel = model;
        String status = "FAILED";
        try {
            AiResponse response = adapter.chat(resolvedRequest, apiKey);
            usage = new TokenUsage(response.promptTokens(), response.completionTokens());
            if (response.model() != null && !response.model().isBlank()) actualModel = response.model();
            status = "SUCCESS";
            return response;
        } catch (AiProviderException e) {
            usage = e.usage();
            if (e.model() != null && !e.model().isBlank()) actualModel = e.model();
            throw e;
        } finally {
            recordUsage(requestId, occurredAt, request, userId, adapter.name(), actualModel, keySource, false, status, usage);
        }
    }

    /**
     * 流式聊天
     * @param request AI 请求
     * @param userId 用户 ID（null 表示匿名）
     * @param handler 流式处理器
     */
    public void streamChat(AiRequest request, Long userId, AiStreamHandler handler) {
        ProviderAdapter adapter = resolveAdapter(userId);
        KeyResolver.ResolvedKey credentials = keyResolver.resolveCredentials(userId);
        String apiKey = credentials.apiKey();
        String model = modelResolver.resolve(userId, request.model(), adapter.defaultModel());

        // 创建带有解析后模型的请求
        AiRequest resolvedRequest = new AiRequest(
                request.systemPrompt(),
                request.messages(),
                model,
                request.taskType()
        );

        // 检查配额
        quotaPolicy.consume(userId, request.taskType(), model);

        log.info("AI stream: provider={}, model={}, userId={}", adapter.name(), model, userId);
        String keySource = credentials.source();
        String requestId = UUID.randomUUID().toString();
        LocalDateTime occurredAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        TokenUsage[] usage = {TokenUsage.UNKNOWN};
        String[] actualModel = {model};
        String status = "FAILED";
        try {
            adapter.streamChat(resolvedRequest, apiKey, new AiStreamHandler() {
                @Override public void onToken(String token) { handler.onToken(token); }
                @Override public void onModel(String reported) {
                    if (reported != null && !reported.isBlank()) actualModel[0] = reported;
                    handler.onModel(reported);
                }
                @Override public void onUsage(TokenUsage reported) {
                    // Providers report cumulative totals, never add repeated usage events together.
                    usage[0] = new TokenUsage(
                            reported.inputTokens() != null ? reported.inputTokens() : usage[0].inputTokens(),
                            reported.outputTokens() != null ? reported.outputTokens() : usage[0].outputTokens());
                    handler.onUsage(reported);
                }
            });
            status = "SUCCESS";
        } finally {
            recordUsage(requestId, occurredAt, request, userId, adapter.name(), actualModel[0], keySource, true, status, usage[0]);
        }
    }

    private void recordUsage(String requestId, LocalDateTime occurredAt, AiRequest request, Long routingUserId,
                             String provider, String model, String keySource, boolean streaming,
                             String status, TokenUsage usage) {
        try {
            usageRecorder.record(new AiUsageRecord(requestId, occurredAt,
                    request.usageUserId() != null ? request.usageUserId() : routingUserId,
                    provider, model == null ? "unknown" : model,
                    request.feature() == null ? "OTHER" : request.feature(), keySource, streaming,
                    status, usage.inputTokens(), usage.outputTokens()));
        } catch (RuntimeException e) {
            // Also covers transaction setup/commit failures outside the recorder method body.
            log.warn("AI usage unavailable: requestId={}, errorType={}", requestId, e.getClass().getSimpleName());
        }
    }

    private ProviderAdapter resolveAdapter(Long userId) {
        // 根据用户配置选择提供者
        if (userId != null) {
            UserAiConfig config = userAiConfigService.getByUserId(userId);
            if (config != null && config.getProvider() != null) {
                String provider = config.getProvider();
                // 自定义端点
                if ("custom".equals(provider) && config.getCustomEndpoint() != null
                        && !config.getCustomEndpoint().isBlank()) {
                    return registry.getOrCreateCustomAdapter(config.getCustomEndpoint());
                }
                // 预设提供者
                ProviderAdapter adapter = registry.getAdapter(provider);
                if (adapter != null) return adapter;
            }
        }
        // 回退到默认提供者
        ProviderAdapter adapter = registry.getDefault();
        if (adapter == null) {
            throw new RuntimeException("没有可用的 AI 提供者");
        }
        return adapter;
    }
}
