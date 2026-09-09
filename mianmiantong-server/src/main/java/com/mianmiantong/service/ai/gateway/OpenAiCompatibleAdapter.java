package com.mianmiantong.service.ai.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestTemplate;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 通用 OpenAI 兼容适配器
 * 适用于 DeepSeek、Qwen、OpenAI 等所有 OpenAI 兼容 API
 */
@Slf4j
public class OpenAiCompatibleAdapter implements ProviderAdapter {

    private final ProviderConfig config;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleAdapter(ProviderConfig config) {
        this(config, new RestTemplate());
    }

    OpenAiCompatibleAdapter(ProviderConfig config, RestTemplate restTemplate) {
        this.config = config;
        this.restTemplate = restTemplate;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String name() {
        return config.name();
    }

    @Override
    public String defaultModel() {
        return config.defaultModel();
    }

    @Override
    public AiResponse chat(AiRequest request, String apiKey) {
        TokenUsage usage = TokenUsage.UNKNOWN;
        String model = resolveModel(request);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            List<Map<String, String>> allMessages = buildMessages(request);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", resolveModel(request));
            body.put("messages", allMessages);

            String json = objectMapper.writeValueAsString(body);
            log.debug("[{}] chat request: {}", config.name(), json);

            HttpEntity<String> entity = new HttpEntity<>(json, headers);
            String response = restTemplate.postForObject(config.endpoint(), entity, String.class);

            @SuppressWarnings("unchecked")
            Map<String, Object> respMap = objectMapper.readValue(response, Map.class);
            usage = TokenUsage.parse(respMap.get("usage"));
            if (respMap.get("model") instanceof String actual && !actual.isBlank()) model = actual;

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) respMap.get("choices");
            if (choices != null && !choices.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                String content = (String) message.get("content");

                return new AiResponse(content, model, usage.inputTokens(), usage.outputTokens());
            }

            throw new RuntimeException(config.name() + " 返回格式异常: " + response);

        } catch (Exception e) {
            log.error("[{}] API调用失败", config.name(), e);
            throw new AiProviderException("AI服务调用失败: " + e.getMessage(), e, usage, model);
        }
    }

    @Override
    public void streamChat(AiRequest request, String apiKey, AiStreamHandler handler) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            List<Map<String, String>> allMessages = buildMessages(request);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", resolveModel(request));
            body.put("messages", allMessages);
            body.put("stream", true);
            if (config.streamUsageEnabled()) {
                body.put("stream_options", Map.of("include_usage", true));
            }

            String json = objectMapper.writeValueAsString(body);
            log.debug("[{}] stream request: {}", config.name(), json);

            restTemplate.execute(config.endpoint(), HttpMethod.POST, req -> {
                req.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                req.getHeaders().set("Authorization", "Bearer " + apiKey);
                req.getBody().write(json.getBytes(StandardCharsets.UTF_8));
            }, response -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                    String line;
                    boolean finished = false;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data:")) continue;
                        String data = line.substring(5).strip();
                        if (data.isEmpty()) continue;
                        if ("[DONE]".equals(data)) {
                            finished = true;
                            break;
                        }

                        @SuppressWarnings("unchecked")
                        Map<String, Object> chunk = objectMapper.readValue(data, Map.class);
                        // Usage-only chunks can have an empty choices array.
                        if (chunk.get("usage") != null) handler.onUsage(TokenUsage.parse(chunk.get("usage")));
                        if (chunk.get("model") instanceof String actual && !actual.isBlank()) handler.onModel(actual);
                        if (chunk.containsKey("error")) throw new IllegalStateException("AI stream returned an error");
                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> choices = (List<Map<String, Object>>) chunk.get("choices");
                        if (choices == null || choices.isEmpty()) continue;

                        if (choices.get(0).get("finish_reason") != null) finished = true;

                        @SuppressWarnings("unchecked")
                        Map<String, Object> delta = (Map<String, Object>) choices.get(0).get("delta");
                        if (delta == null) continue;

                        String content = (String) delta.get("content");
                        if (content != null && !content.isEmpty()) {
                            handler.onToken(content);
                        }
                    }
                    if (!finished) throw new IllegalStateException("AI stream ended before completion");
                }
                return null;
            });
        } catch (Exception e) {
            log.error("[{}] 流式调用失败", config.name(), e);
            throw new RuntimeException("AI流式调用失败: " + e.getMessage());
        }
    }

    private List<Map<String, String>> buildMessages(AiRequest request) {
        List<Map<String, String>> allMessages = new ArrayList<>();
        if (request.systemPrompt() != null && !request.systemPrompt().isBlank()) {
            allMessages.add(Map.of("role", "system", "content", request.systemPrompt()));
        }
        for (ChatMessage msg : request.messages()) {
            allMessages.add(Map.of("role", msg.role(), "content", msg.content()));
        }
        return allMessages;
    }

    private String resolveModel(AiRequest request) {
        if (request.model() != null && !request.model().isBlank()) {
            return request.model();
        }
        return config.defaultModel();
    }
}
