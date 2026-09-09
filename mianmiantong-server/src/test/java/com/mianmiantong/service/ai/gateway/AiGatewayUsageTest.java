package com.mianmiantong.service.ai.gateway;

import com.mianmiantong.service.ai.usage.AiUsageRecord;
import com.mianmiantong.service.ai.usage.AiUsageRecorder;
import com.mianmiantong.service.user.UserAiConfigService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AiGatewayUsageTest {
    private final ProviderAdapter adapter = mock(ProviderAdapter.class);
    private final KeyResolver keys = mock(KeyResolver.class);
    private final ModelResolver models = mock(ModelResolver.class);
    private final QuotaPolicy quota = mock(QuotaPolicy.class);
    private final AiUsageRecorder recorder = mock(AiUsageRecorder.class);
    private final UserAiConfigService configs = mock(UserAiConfigService.class);

    private AiGateway gateway() {
        when(adapter.name()).thenReturn("test");
        when(adapter.defaultModel()).thenReturn("model");
        when(keys.resolve(null)).thenReturn("fixture");
        when(keys.source(null)).thenReturn("SYSTEM");
        when(models.resolve(null, "model", "model")).thenReturn("model");
        return new AiGateway(new ProviderRegistry(Map.of("test", adapter), "test"), keys, models, quota, configs, recorder);
    }

    private AiRequest request() {
        return new AiRequest("", List.of(), "model", AiTaskType.FLASH).withUsage(42L, "RESUME");
    }

    @Test void recordsOwnerWithoutChangingSystemKeyOrQuotaRouting() {
        var gateway = gateway();
        when(adapter.chat(any(), any())).thenReturn(new AiResponse("ok", "actual-model", 12, 5));
        assertThat(gateway.chat(request(), null).content()).isEqualTo("ok");
        var captor = ArgumentCaptor.forClass(AiUsageRecord.class);
        verify(recorder).record(captor.capture());
        var r = captor.getValue();
        assertThat(r.userId()).isEqualTo(42L);
        assertThat(r.model()).isEqualTo("actual-model");
        assertThat(r.keySource()).isEqualTo("SYSTEM");
        assertThat(r.inputTokens()).isEqualTo(12L);
        verify(quota).consume(null, AiTaskType.FLASH, "model");
        verify(keys).resolve(null);
    }

    @Test void recordsFinalStreamingTotalsOnceIncludingFailure() {
        var gateway = gateway();
        doAnswer(invocation -> {
            AiStreamHandler handler = invocation.getArgument(2);
            handler.onModel("actual-model");
            handler.onUsage(new TokenUsage(8L, 2L));
            handler.onUsage(new TokenUsage(8L, 2L));
            throw new IllegalStateException("disconnected");
        }).when(adapter).streamChat(any(), any(), any());
        assertThatThrownBy(() -> gateway.streamChat(request(), null, text -> {})).hasMessage("disconnected");
        var captor = ArgumentCaptor.forClass(AiUsageRecord.class);
        verify(recorder, times(1)).record(captor.capture());
        assertThat(captor.getValue().inputTokens()).isEqualTo(8L);
        assertThat(captor.getValue().status()).isEqualTo("FAILED");
        assertThat(captor.getValue().model()).isEqualTo("actual-model");
    }

    @Test void persistenceFailureCannotBreakSuccessfulGeneration() {
        var gateway = gateway();
        when(adapter.chat(any(), any())).thenReturn(new AiResponse("ok", "model", 1, 2));
        doThrow(new IllegalStateException("db unavailable")).when(recorder).record(any());
        assertThat(gateway.chat(request(), null).content()).isEqualTo("ok");
    }

    @Test void quotaRejectionCreatesNoProviderCallOrUsageRecord() {
        var gateway = gateway();
        doThrow(new IllegalArgumentException("quota")).when(quota).consume(any(), any(), any());
        assertThatThrownBy(() -> gateway.chat(request(), null)).hasMessage("quota");
        verify(adapter, never()).chat(any(), any());
        verifyNoInteractions(recorder);
    }

    @Test void failedSyncResponseRetainsProviderUsage() {
        var gateway = gateway();
        when(adapter.chat(any(), any())).thenThrow(new AiProviderException("invalid response", null, new TokenUsage(7L, 3L), "actual"));
        assertThatThrownBy(() -> gateway.chat(request(), null)).isInstanceOf(AiProviderException.class);
        var captor = ArgumentCaptor.forClass(AiUsageRecord.class);
        verify(recorder).record(captor.capture());
        assertThat(captor.getValue().inputTokens()).isEqualTo(7L);
        assertThat(captor.getValue().model()).isEqualTo("actual");
        assertThat(captor.getValue().status()).isEqualTo("FAILED");
    }
}
