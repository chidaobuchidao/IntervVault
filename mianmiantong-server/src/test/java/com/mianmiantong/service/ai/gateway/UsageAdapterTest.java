package com.mianmiantong.service.ai.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import java.util.List;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class UsageAdapterTest {
    private OpenAiCompatibleAdapter adapter(String response) {
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("https://fixture.invalid/chat"))
                .andRespond(withSuccess(response, response.startsWith("data:") ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON));
        return new OpenAiCompatibleAdapter(new ProviderConfig("custom", "https://fixture.invalid/chat", "test-model", List.of()), rest);
    }

    private AiRequest request() { return new AiRequest("", List.of(new ChatMessage("user", "test")), "test-model", AiTaskType.FLASH); }

    @Test void missingUsageRemainsUnknown() throws Exception {
        var result = adapter("{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}").chat(request(), "fixture");
        assertThat(result.promptTokens()).isNull();
        assertThat(result.completionTokens()).isNull();
    }

    @Test void preservesLargeCountsAndRejectsMalformedUsage() throws Exception {
        var result = adapter("{\"choices\":[{\"message\":{\"content\":\"ok\"}}],\"usage\":{\"prompt_tokens\":3000000000,\"completion_tokens\":-5}}").chat(request(), "fixture");
        assertThat(result.promptTokens()).isEqualTo(3_000_000_000L);
        assertThat(result.completionTokens()).isNull();
    }

    @Test void truncatedStreamFailsInsteadOfReportingSuccess() throws Exception {
        var a = adapter("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n");
        assertThatThrownBy(() -> a.streamChat(request(), "fixture", text -> {})).isInstanceOf(RuntimeException.class);
    }

    @Test void readsUsageOnlyChunkAndDoesNotForceOptionsOnCustomProvider() {
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("https://fixture.invalid/chat"))
                .andExpect(jsonPath("$.stream_options").doesNotExist())
                .andRespond(withSuccess("""
                        data:{"choices":[{"delta":{"content":"hello"}}]}

                        data: {"choices":[],"usage":{"prompt_tokens":12,"completion_tokens":4}}

                        data: [DONE]

                        """, MediaType.TEXT_EVENT_STREAM));
        var adapter = new OpenAiCompatibleAdapter(new ProviderConfig("custom", "https://fixture.invalid/chat", "m", List.of()), rest);
        var usages = new ArrayList<TokenUsage>();
        var text = new StringBuilder();
        adapter.streamChat(request(), "fixture", new AiStreamHandler() {
            @Override public void onToken(String token) { text.append(token); }
            @Override public void onUsage(TokenUsage usage) { usages.add(usage); }
        });
        assertThat(text.toString()).isEqualTo("hello");
        assertThat(usages).containsExactly(new TokenUsage(12L, 4L));
        server.verify();
    }

    @Test void optedInProviderRequestsStreamUsage() {
        var rest = new RestTemplate();
        var server = MockRestServiceServer.bindTo(rest).build();
        server.expect(requestTo("https://fixture.invalid/chat"))
                .andExpect(jsonPath("$.stream_options.include_usage").value(true))
                .andRespond(withSuccess("data: [DONE]\n\n", MediaType.TEXT_EVENT_STREAM));
        var adapter = new OpenAiCompatibleAdapter(new ProviderConfig("test", "https://fixture.invalid/chat", "m", List.of(), true), rest);
        adapter.streamChat(request(), "fixture", text -> {});
        server.verify();
    }

    @Test void rejectsFractionalStringAndOverflowCountsWithoutLosingValidZero() {
        assertThat(TokenUsage.parse(java.util.Map.of("prompt_tokens", 1.5, "completion_tokens", 0)))
                .isEqualTo(new TokenUsage(null, 0L));
        assertThat(TokenUsage.parse(java.util.Map.of("prompt_tokens", "12", "completion_tokens", new java.math.BigInteger("9223372036854775808"))))
                .isEqualTo(TokenUsage.UNKNOWN);
    }

    @Test void malformedSyncResponsePreservesAlreadyReportedUsage() {
        var adapter = adapter("{\"model\":\"actual\",\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}");
        var failure = catchThrowable(() -> adapter.chat(request(), "fixture"));
        assertThat(failure).isInstanceOf(AiProviderException.class);
        assertThat(((AiProviderException) failure).usage()).isEqualTo(new TokenUsage(7L, 3L));
        assertThat(((AiProviderException) failure).model()).isEqualTo("actual");
    }

    @Test void errorEventRetainsUsageAndActualModelBeforeThrowing() {
        var adapter = adapter("data: {\"error\":{\"message\":\"failed\"},\"model\":\"actual\",\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}\n\n");
        var usages = new ArrayList<TokenUsage>();
        var models = new ArrayList<String>();
        assertThatThrownBy(() -> adapter.streamChat(request(), "fixture", new AiStreamHandler() {
            @Override public void onToken(String text) { }
            @Override public void onUsage(TokenUsage usage) { usages.add(usage); }
            @Override public void onModel(String model) { models.add(model); }
        })).isInstanceOf(RuntimeException.class);
        assertThat(usages).containsExactly(new TokenUsage(7L, 3L));
        assertThat(models).containsExactly("actual");
    }
}
