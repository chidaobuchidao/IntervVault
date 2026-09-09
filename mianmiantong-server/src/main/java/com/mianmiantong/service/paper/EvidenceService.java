package com.mianmiantong.service.paper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mianmiantong.config.JwtAuthFilter;
import com.mianmiantong.dto.paper.EvidenceRequest;
import com.mianmiantong.dto.paper.EvidenceResponse;
import com.mianmiantong.entity.user.User;
import com.mianmiantong.entity.user.UserAiConfig;
import com.mianmiantong.mapper.user.UserMapper;
import com.mianmiantong.service.ai.gateway.AiGateway;
import com.mianmiantong.service.ai.gateway.AiRequest;
import com.mianmiantong.service.ai.gateway.AiResponse;
import com.mianmiantong.service.ai.gateway.AiTaskType;
import com.mianmiantong.service.ai.gateway.ChatMessage;
import com.mianmiantong.service.user.UserAiConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class EvidenceService {

    private static final int MAX_CHUNKS = 10;
    private static final int MAX_QUERY_CHARS = 900;
    private static final int MAX_CHUNK_CHARS = 900;

    private final AiGateway aiGateway;
    private final UserAiConfigService userAiConfigService;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EvidenceService(AiGateway aiGateway, UserAiConfigService userAiConfigService, UserMapper userMapper) {
        this.aiGateway = aiGateway;
        this.userAiConfigService = userAiConfigService;
        this.userMapper = userMapper;
    }

    public EvidenceResponse classify(EvidenceRequest request) {
        Long userId = JwtAuthFilter.getCurrentUserId();
        UserAiConfig config = userAiConfigService.getByUserId(userId);
        boolean hasOwnKey = config != null && config.getApiKey() != null && !config.getApiKey().isBlank();
        boolean granted = hasOwnKey || JwtAuthFilter.isAdmin() || isKnowledgeBaseGranted(userId);
        if (!granted) {
            throw new IllegalArgumentException("知识库需要配置自己的 AI API Key，或由管理员单独开放后才能使用");
        }

        List<EvidenceRequest.Chunk> chunks = sanitizeChunks(request.getChunks());
        EvidenceResponse response = new EvidenceResponse();
        if (chunks.isEmpty()) {
            return response;
        }

        String prompt = buildPrompt(request, chunks);
        try {
            List<ChatMessage> messages = List.of(
                    new ChatMessage("user", prompt)
            );
            AiRequest aiRequest = new AiRequest(
                "你是严谨的论文证据审查员，只判断文献片段是否能支撑当前论文观点，必须输出合法 JSON。",
                messages, request.getModel(), AiTaskType.FLASH
            );
            AiResponse aiResponse = aiGateway.chat(aiRequest.withUsage(userId, "EVIDENCE"), userId);
            response.setEvidences(mergeWithOriginal(chunks, parseEvidence(aiResponse.content())));
            return response;
        } catch (Exception e) {
            log.warn("Evidence classification failed, falling back to retrieval scores", e);
            response.setEvidences(fallbackByScore(chunks));
            return response;
        }
    }

    private boolean isKnowledgeBaseGranted(Long userId) {
        if (userId == null) return false;
        User user = userMapper.selectById(userId);
        return user != null && user.getKnowledgeBaseEnabled() != null && user.getKnowledgeBaseEnabled() == 1;
    }

    private List<EvidenceRequest.Chunk> sanitizeChunks(List<EvidenceRequest.Chunk> chunks) {
        if (chunks == null) return List.of();
        return chunks.stream()
            .filter(c -> c.getContent() != null && !c.getContent().isBlank())
            .limit(MAX_CHUNKS)
            .toList();
    }

    private String buildPrompt(EvidenceRequest request, List<EvidenceRequest.Chunk> chunks) {
        StringBuilder sb = new StringBuilder();
        sb.append("请判断候选文献片段是否能支撑当前论文观点。\n\n");
        sb.append("判定标准：\n");
        sb.append("- direct_support：片段能直接支撑当前论文中的具体观点、原因、方法、结论、数据或背景判断，可以作为正文引用。\n");
        sb.append("- background_only：片段只提供主题背景或术语参考，不能紧跟具体结论作为引用。\n");
        sb.append("- irrelevant：片段与当前观点关系弱或仅关键词相似，不应使用。\n\n");
        sb.append("只输出 JSON 数组，不要 Markdown，不要解释。每项字段：index, supportLevel, confidence, supportedClaim, reason。\n");
        sb.append("supportLevel 只能是 direct_support/background_only/irrelevant；confidence 只能是 high/medium/low。\n\n");
        sb.append("当前论文/任务重点：").append(trim(request.getFocusText(), 300)).append("\n");
        sb.append("当前待处理文本：").append(trim(request.getQueryText(), MAX_QUERY_CHARS)).append("\n\n");
        sb.append("候选文献片段：\n");
        for (int i = 0; i < chunks.size(); i++) {
            EvidenceRequest.Chunk c = chunks.get(i);
            int index = c.getIndex() != null ? c.getIndex() : i + 1;
            sb.append("[").append(index).append("] ")
                .append(c.getPaperTitle() == null ? "未知论文" : c.getPaperTitle());
            if (c.getSection() != null && !c.getSection().isBlank()) {
                sb.append(" / ").append(c.getSection());
            }
            sb.append("\n").append(trim(c.getContent(), MAX_CHUNK_CHARS)).append("\n\n");
        }
        return sb.toString();
    }

    private List<EvidenceResponse.Evidence> parseEvidence(String raw) throws Exception {
        String json = extractJsonArray(raw);
        JsonNode root = objectMapper.readTree(json);
        List<EvidenceResponse.Evidence> result = new ArrayList<>();
        if (!root.isArray()) return result;
        for (JsonNode node : root) {
            EvidenceResponse.Evidence evidence = new EvidenceResponse.Evidence();
            evidence.setIndex(node.path("index").isInt() ? node.path("index").asInt() : null);
            evidence.setSupportLevel(normalizeSupportLevel(node.path("supportLevel").asText("background_only")));
            evidence.setConfidence(normalizeConfidence(node.path("confidence").asText("low")));
            evidence.setSupportedClaim(node.path("supportedClaim").asText(""));
            evidence.setReason(node.path("reason").asText(""));
            result.add(evidence);
        }
        return result;
    }

    private List<EvidenceResponse.Evidence> mergeWithOriginal(
        List<EvidenceRequest.Chunk> chunks,
        List<EvidenceResponse.Evidence> classified
    ) {
        Map<Integer, EvidenceResponse.Evidence> byIndex = classified.stream()
            .filter(e -> e.getIndex() != null)
            .collect(java.util.stream.Collectors.toMap(EvidenceResponse.Evidence::getIndex, e -> e, (a, b) -> a));

        List<EvidenceResponse.Evidence> merged = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            EvidenceRequest.Chunk chunk = chunks.get(i);
            int index = chunk.getIndex() != null ? chunk.getIndex() : i + 1;
            EvidenceResponse.Evidence evidence = byIndex.getOrDefault(index, fallbackEvidence(chunk, index));
            copyChunkFields(chunk, evidence, index);
            merged.add(evidence);
        }
        return merged;
    }

    private List<EvidenceResponse.Evidence> fallbackByScore(List<EvidenceRequest.Chunk> chunks) {
        List<EvidenceResponse.Evidence> result = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            EvidenceRequest.Chunk chunk = chunks.get(i);
            int index = chunk.getIndex() != null ? chunk.getIndex() : i + 1;
            EvidenceResponse.Evidence evidence = fallbackEvidence(chunk, index);
            copyChunkFields(chunk, evidence, index);
            result.add(evidence);
        }
        return result;
    }

    private EvidenceResponse.Evidence fallbackEvidence(EvidenceRequest.Chunk chunk, int index) {
        double score = chunk.getScore() == null ? 0 : chunk.getScore();
        EvidenceResponse.Evidence evidence = new EvidenceResponse.Evidence();
        evidence.setIndex(index);
        if (score >= 0.72) {
            evidence.setSupportLevel("direct_support");
            evidence.setConfidence("medium");
            evidence.setReason("模型证据筛选不可用，按检索相关度暂定为可直接引用候选。");
        } else if (score >= 0.42) {
            evidence.setSupportLevel("background_only");
            evidence.setConfidence("medium");
            evidence.setReason("模型证据筛选不可用，按检索相关度暂定为背景参考。");
        } else {
            evidence.setSupportLevel("irrelevant");
            evidence.setConfidence("low");
            evidence.setReason("模型证据筛选不可用，检索相关度较低，不建议引用。");
        }
        return evidence;
    }

    private void copyChunkFields(EvidenceRequest.Chunk chunk, EvidenceResponse.Evidence evidence, int index) {
        evidence.setIndex(index);
        evidence.setChunkId(chunk.getChunkId());
        evidence.setPaperId(chunk.getPaperId());
        evidence.setChunkIndex(chunk.getChunkIndex());
        evidence.setPaperTitle(chunk.getPaperTitle());
        evidence.setSection(chunk.getSection());
        evidence.setContent(chunk.getContent());
        evidence.setScore(chunk.getScore());
        evidence.setKeywords(chunk.getKeywords());
        evidence.setSupportLevel(normalizeSupportLevel(evidence.getSupportLevel()));
        evidence.setConfidence(normalizeConfidence(evidence.getConfidence()));
        if (evidence.getReason() == null || evidence.getReason().isBlank()) {
            evidence.setReason("未给出理由，请人工核对原文后再作为正式引用。");
        }
    }

    private String extractJsonArray(String raw) {
        if (raw == null) return "[]";
        String cleaned = raw.replace("```json", "").replace("```", "").trim();
        int start = cleaned.indexOf('[');
        int end = cleaned.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }
        return "[]";
    }

    private String normalizeSupportLevel(String value) {
        if ("direct_support".equals(value) || "background_only".equals(value) || "irrelevant".equals(value)) {
            return value;
        }
        return "background_only";
    }

    private String normalizeConfidence(String value) {
        if ("high".equals(value) || "medium".equals(value) || "low".equals(value)) {
            return value;
        }
        return "low";
    }

    private String trim(String value, int max) {
        if (value == null) return "";
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= max ? normalized : normalized.substring(0, max);
    }
}
