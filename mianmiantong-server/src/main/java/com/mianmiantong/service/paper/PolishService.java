package com.mianmiantong.service.paper;

import com.mianmiantong.config.JwtAuthFilter;

import com.mianmiantong.dto.paper.PolishRequest;
import com.mianmiantong.service.ai.gateway.AiGateway;
import com.mianmiantong.service.ai.gateway.AiRequest;
import com.mianmiantong.service.ai.gateway.AiTaskType;
import com.mianmiantong.service.ai.gateway.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

@Slf4j
@Service
public class PolishService {

    private final AiGateway aiGateway;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PolishService(AiGateway aiGateway) {
        this.aiGateway = aiGateway;
    }

    /** SSE 流式润色 */
    public SseEmitter runPolish(PolishRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L);

        String systemPrompt = getSystemPrompt("prompts/polish_run_task.txt");
        String userPrompt = renderPrompt("prompts/polish_run_task.txt", Map.of(
            "text", req.getText() != null ? req.getText() : "",
            "task_type", req.getTaskType() != null ? req.getTaskType() : "章节正文",
            "polish_type", req.getPolishType() != null ? req.getPolishType() : "full",
            "topic", req.getTopic() != null ? req.getTopic() : "",
            "notes", req.getNotes() != null ? req.getNotes() : "",
            "output_language", outputLanguageLabel(req.getLanguage()),
            "language_policy", outputLanguagePolicy(req.getLanguage())
        ));

        List<ChatMessage> messages = List.of(
            new ChatMessage("user", userPrompt)
        );

        emitter.onTimeout(() -> {
            safeSend(emitter, "error", "润色超时");
            emitter.complete();
        });

        Long usageUserId = JwtAuthFilter.getCurrentUserId();
        CompletableFuture.runAsync(() -> {
            try {
                AiRequest aiRequest = new AiRequest(systemPrompt, messages, req.getModel(), AiTaskType.FLASH).withUsage(usageUserId, "PAPER_POLISH");
                aiGateway.streamChat(aiRequest, null, token -> {
                    safeSendJson(emitter, "token", token);
                });

                emitter.send(SseEmitter.event().name("finish")
                    .data("{\"phase\":\"polish\"}"));
                emitter.complete();
            } catch (Exception e) {
                log.error("Polish SSE stream failed", e);
                safeSend(emitter, "error", e.getMessage() != null ? e.getMessage() : "润色失败");
                emitter.complete();
            }
        });

        return emitter;
    }

    /** 本地格式规范检查（不调 AI） */
    public FormatCheckResult scanFormat(String text) {
        FormatCheckResult result = new FormatCheckResult();
        if (text == null || text.isBlank()) {
            return result;
        }

        // 中英文标点混用
        if (text.contains(",") && !text.contains("，")) {
            result.addIssue("建议使用中文逗号（，）替代英文逗号（,）");
        }
        String noEllipsis = text.replace("...", "");
        if (noEllipsis.contains(".") && !noEllipsis.contains("。")) {
            result.addIssue("建议使用中文句号（。）替代英文句号（.）");
        }

        // 中文数字
        Pattern cnNum = Pattern.compile("[一二三四五六七八九十百千万]+");
        long cnCount = cnNum.matcher(text).results().count();
        if (cnCount > 0) {
            result.addIssue("发现 " + cnCount + " 处中文数字，学术论文建议优先使用阿拉伯数字");
        }

        // 过短段落
        String[] paragraphs = text.split("\n");
        int shortParas = 0;
        for (String p : paragraphs) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty() && trimmed.length() < 50) {
                shortParas++;
            }
        }
        if (shortParas > 0) {
            result.addIssue("发现 " + shortParas + " 个过短段落（<50字），建议合并或扩充");
        }

        // 引用标记
        if (!text.contains("[") && text.length() > 500) {
            result.addIssue("未发现参考文献引用标记，建议补充文献引用");
        }

        return result;
    }

    /** 读取 prompt 文件的 system 部分（---SYSTEM--- 之前） */
    private String getSystemPrompt(String path) {
        try {
            String content = new String(
                new ClassPathResource(path).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
            );
            String[] parts = content.split("---SYSTEM---", 2);
            return parts[0].trim();
        } catch (IOException e) {
            log.error("Failed to load prompt: {}", path, e);
            return "你是一位专业的学术写作助手。";
        }
    }

    /** 读取 prompt 文件并替换模板变量 */
    private String renderPrompt(String path, Map<String, String> vars) {
        try {
            String content = new String(
                new ClassPathResource(path).getInputStream().readAllBytes(),
                StandardCharsets.UTF_8
            );
            String[] parts = content.split("---SYSTEM---", 2);
            String template = parts.length > 1 ? parts[1].trim() : content;
            for (Map.Entry<String, String> e : vars.entrySet()) {
                template = template.replace("{" + e.getKey() + "}", e.getValue());
            }
            return template;
        } catch (IOException e) {
            log.error("Failed to load prompt: {}", path, e);
            return vars.getOrDefault("text", "");
        }
    }

    private String outputLanguageLabel(String language) {
        return isEnglishOutput(language) ? "英文" : "中文";
    }

    private String outputLanguagePolicy(String language) {
        if (isEnglishOutput(language)) {
            return "输出语言=英文：可将中文学术表达润色为英文，但不得改写事实、数字、引用、图表编号、公式和专业术语含义；原文已有英文专业术语、缩写、URL、机构名、期刊名等片段必须保持原样，仅修正明显拼写或空格错误。";
        }
        return "输出语言=中文：整体保持中文学术表达，不得把中文正文翻译成英文；原文已有英文标题、英文摘要、英文关键词、专业术语、缩写、URL、机构名、期刊名等英文片段必须保持原样，仅修正明显拼写或空格错误，不翻译、不扩写。";
    }

    private boolean isEnglishOutput(String language) {
        if (language == null) {
            return false;
        }
        String normalized = language.trim().toLowerCase();
        return normalized.equals("en") || normalized.equals("english") || normalized.equals("英文");
    }

    private void safeSend(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException ignored) {
            // SSE connection already closed by client
        }
    }

    private void safeSendJson(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(objectMapper.writeValueAsString(data)));
        } catch (Exception ignored) {
            // SSE connection already closed by client
        }
    }

    /** 格式检查结果 */
    public static class FormatCheckResult {
        private final List<String> issues = new ArrayList<>();

        public void addIssue(String issue) {
            issues.add(issue);
        }

        public List<String> getIssues() {
            return issues;
        }

        public int getIssueCount() {
            return issues.size();
        }
    }
}
