package com.mianmiantong.service.paper;

import com.mianmiantong.config.JwtAuthFilter;

import com.mianmiantong.dto.paper.PlagiarismReduceRequest;
import com.mianmiantong.service.ai.gateway.AiGateway;
import com.mianmiantong.service.ai.gateway.AiRequest;
import com.mianmiantong.service.ai.gateway.AiTaskType;
import com.mianmiantong.service.ai.gateway.ChatMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PlagiarismReduceService {

    private final AiGateway aiGateway;

    public PlagiarismReduceService(AiGateway aiGateway) {
        this.aiGateway = aiGateway;
    }

    /** 本地检测文本内部重复风险 */
    public RepetitionResult detectRepetitive(String text) {
        RepetitionResult r = new RepetitionResult();
        if (text == null || text.isBlank()) return r;

        Pattern wordPattern = Pattern.compile("[\\u4e00-\\u9fff]{4,}");
        Map<String, Integer> seen = new LinkedHashMap<>();
        java.util.regex.Matcher m = wordPattern.matcher(text);
        while (m.find()) {
            String word = m.group();
            seen.put(word, seen.getOrDefault(word, 0) + 1);
        }
        List<Map.Entry<String, Integer>> repeated = seen.entrySet().stream()
            .filter(e -> e.getValue() >= 3)
            .sorted((a, b) -> {
                int cmp = b.getValue().compareTo(a.getValue());
                return cmp != 0 ? cmp : Integer.compare(b.getKey().length(), a.getKey().length());
            })
            .limit(20)
            .collect(Collectors.toList());
        r.setRepeatedPhrases(repeated);

        String[] sentences = text.split("[。！？?!]");
        r.setLongSentences(Arrays.stream(sentences)
            .map(String::trim)
            .filter(s -> s.length() > 100)
            .map(s -> s.length() > 80 ? s.substring(0, 80) + "..." : s)
            .collect(Collectors.toList()));

        List<String> topWords = repeated.stream().map(Map.Entry::getKey).limit(5).collect(Collectors.toList());
        String[] paragraphs = text.split("\n");
        r.setRiskParagraphs(Arrays.stream(paragraphs)
            .map(String::trim)
            .filter(p -> p.length() > 20)
            .filter(p -> topWords.stream().mapToLong(w -> countIn(p, w)).sum() > 3)
            .map(p -> p.length() > 100 ? p.substring(0, 100) + "..." : p)
            .collect(Collectors.toList()));

        return r;
    }

    /** 词汇相似度 */
    public SimilarityResult compareSimilarity(String text1, String text2) {
        Pattern tokenPattern = Pattern.compile("[\\u4e00-\\u9fff]+|[A-Za-z]+");
        Set<String> words1 = tokenPattern.matcher(text1 != null ? text1 : "").results()
            .map(java.util.regex.MatchResult::group).collect(Collectors.toSet());
        Set<String> words2 = tokenPattern.matcher(text2 != null ? text2 : "").results()
            .map(java.util.regex.MatchResult::group).collect(Collectors.toSet());

        SimilarityResult r = new SimilarityResult();
        if (words1.isEmpty() || words2.isEmpty()) {
            r.setSimilarity(0.0);
            r.setUniqueIn1(words1.size());
            r.setUniqueIn2(words2.size());
            return r;
        }

        Set<String> common = new HashSet<>(words1);
        common.retainAll(words2);
        double similarity = (double) common.size() / Math.max(words1.size(), words2.size()) * 100;
        r.setSimilarity(Math.round(similarity * 10) / 10.0);
        List<String> commonList = new ArrayList<>(common);
        r.setCommonWords(commonList.subList(0, Math.min(20, commonList.size())));
        r.setUniqueIn1(words1.size() - common.size());
        r.setUniqueIn2(words2.size() - common.size());
        return r;
    }

    /** 引用格式检查 */
    public CitationCheckResult checkCitations(String text) {
        CitationCheckResult r = new CitationCheckResult();
        if (text == null || text.isBlank()) return r;

        int refSectionIdx = text.indexOf("参考文献");
        if (refSectionIdx < 0) refSectionIdx = text.indexOf("引用文献");
        if (refSectionIdx < 0) refSectionIdx = text.indexOf("参考资料");

        String body = refSectionIdx >= 0 ? text.substring(0, refSectionIdx) : text;
        String refs = refSectionIdx >= 0 ? text.substring(refSectionIdx) : "";

        Pattern citePattern = Pattern.compile("\\[(\\d+)\\]");
        Set<Integer> bodyRefs = citePattern.matcher(body).results()
            .map(mr -> Integer.parseInt(mr.group(1))).collect(Collectors.toSet());

        Pattern refPattern = Pattern.compile("^\\s*\\[(\\d+)\\]", Pattern.MULTILINE);
        Set<Integer> refNums = refPattern.matcher(refs).results()
            .map(mr -> Integer.parseInt(mr.group(1))).collect(Collectors.toSet());

        r.setCitationCount(bodyRefs.size());
        r.setReferenceCount(refNums.size());
        r.setHasReferenceSection(refSectionIdx >= 0);

        List<String> issues = new ArrayList<>();
        if (body.length() > 300 && bodyRefs.isEmpty() && refSectionIdx < 0) {
            issues.add("正文暂未发现引用标记，查重时可能因引用缺失而被整体判重");
        }
        if (!bodyRefs.isEmpty() && refSectionIdx < 0) {
            issues.add("正文已有引用编号，但未发现参考文献列表");
        }
        if (refSectionIdx >= 0 && bodyRefs.isEmpty() && refNums.isEmpty()) {
            issues.add("存在参考文献区，但正文未见对应引用标记");
        }
        Set<Integer> missing = new HashSet<>(bodyRefs);
        missing.removeAll(refNums);
        if (!missing.isEmpty()) {
            issues.add("正文引用编号缺少对应参考文献条目：" +
                missing.stream().sorted().map(String::valueOf).collect(Collectors.joining("、")));
        }
        Set<Integer> unused = new HashSet<>(refNums);
        unused.removeAll(bodyRefs);
        if (!unused.isEmpty()) {
            issues.add("参考文献存在未在正文引用的编号：" +
                unused.stream().sorted().map(String::valueOf).collect(Collectors.joining("、")));
        }
        r.setIssues(issues);
        return r;
    }

    /** 6-gram 索引 + 双向扩展：找出 text 与 sourceText 的重叠片段 */
    public List<MatchingFragment> findOverlappingFragments(String text, String sourceText) {
        List<MatchingFragment> result = new ArrayList<>();
        if (text == null || sourceText == null || text.length() < 6 || sourceText.length() < 6) {
            return result;
        }

        // 对 sourceText 建 6-gram 索引
        Map<String, List<Integer>> index = new HashMap<>();
        for (int i = 0; i <= sourceText.length() - 6; i++) {
            String gram = sourceText.substring(i, i + 6);
            index.computeIfAbsent(gram, k -> new ArrayList<>()).add(i);
        }

        // 遍历 text 的 6-gram，查找匹配并双向扩展
        Map<Integer, Integer> expanded = new LinkedHashMap<>(); // key=targetStart, value=length
        for (int i = 0; i <= text.length() - 6; i++) {
            String gram = text.substring(i, i + 6);
            List<Integer> sourcePositions = index.get(gram);
            if (sourcePositions == null) continue;

            for (int srcPos : sourcePositions) {
                // 双向扩展
                int extLeft = 0;
                while (i - extLeft - 1 >= 0 && srcPos - extLeft - 1 >= 0
                    && text.charAt(i - extLeft - 1) == sourceText.charAt(srcPos - extLeft - 1)) {
                    extLeft++;
                }
                int extRight = 6;
                while (i + extRight < text.length() && srcPos + extRight < sourceText.length()
                    && text.charAt(i + extRight) == sourceText.charAt(srcPos + extRight)) {
                    extRight++;
                }

                int targetStart = i - extLeft;
                int length = extLeft + extRight;
                if (length >= 10) {
                    expanded.merge(targetStart, length, Math::max);
                }
            }
        }

        // 过滤被更长匹配覆盖的片段，转为结果列表
        List<int[]> frags = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : expanded.entrySet()) {
            int start = e.getKey();
            int len = e.getValue();
            boolean covered = false;
            for (int[] f : frags) {
                if (f[0] <= start && start + len <= f[0] + f[1]) {
                    covered = true;
                    break;
                }
            }
            if (!covered) {
                frags.add(new int[]{start, len});
            }
        }

        // 按长度降序，取 Top 20
        frags.sort((a, b) -> Integer.compare(b[1], a[1]));
        for (int i = 0; i < Math.min(20, frags.size()); i++) {
            int[] f = frags.get(i);
            int targetStart = f[0];
            int length = f[1];
            // 从 sourceText 中找对应的 source 位置（用 6-gram 反查）
            String targetGram = text.substring(targetStart, Math.min(targetStart + 6, text.length()));
            List<Integer> srcPositions = index.get(targetGram);
            int srcStart = srcPositions != null ? srcPositions.get(0) : -1;

            String targetExcerpt = text.substring(targetStart, Math.min(targetStart + Math.min(length, 80), text.length()));
            String sourceExcerpt = "";
            if (srcStart >= 0) {
                sourceExcerpt = sourceText.substring(srcStart,
                    Math.min(srcStart + Math.min(length, 80), sourceText.length()));
            }
            result.add(new MatchingFragment(sourceExcerpt, targetExcerpt, length, srcStart, targetStart));
        }

        return result;
    }

    /** 计算重叠统计 */
    public OverlapResult calculateOverlap(String text, String sourceText) {
        OverlapResult r = new OverlapResult();
        if (text == null || sourceText == null || text.isEmpty()) return r;

        List<MatchingFragment> fragments = findOverlappingFragments(text, sourceText);
        r.setTopFragments(fragments);

        // 去重覆盖范围的总重叠字符数
        if (text.length() < 5000) {
            boolean[] covered = new boolean[text.length()];
            for (MatchingFragment f : fragments) {
                int end = Math.min(f.getTargetStart() + f.getLength(), text.length());
                for (int i = f.getTargetStart(); i < end; i++) covered[i] = true;
            }
            int overlapChars = 0;
            for (boolean b : covered) { if (b) overlapChars++; }
            r.setOverlapChars(overlapChars);
            r.setOverlapRatio(Math.round(overlapChars * 1000.0 / text.length()) / 10.0);
        } else {
            // 大文本用估算：取最长 Top 50 片段
            int estimated = fragments.stream().mapToInt(MatchingFragment::getLength).limit(50).sum();
            r.setOverlapChars(estimated);
            r.setOverlapRatio(Math.round(estimated * 1000.0 / text.length()) / 10.0);
        }

        return r;
    }

    /** 模拟查重率 */
    public double calculateSimulatedRate(RepetitionResult repetition, SimilarityResult similarity,
                                          OverlapResult overlap, String text) {
        if (text == null || text.isEmpty()) return 0.0;
        if (overlap != null && overlap.getOverlapRatio() > 0) {
            double simRate = similarity != null ? similarity.getSimilarity() : 0;
            double rate = simRate * 0.45 + overlap.getOverlapRatio() * 0.55;
            return Math.round(Math.min(100, rate) * 10.0) / 10.0;
        } else {
            int repCount = repetition != null && repetition.getRepeatedPhrases() != null
                ? repetition.getRepeatedPhrases().size() : 0;
            int longCount = repetition != null && repetition.getLongSentences() != null
                ? repetition.getLongSentences().size() : 0;
            int riskCount = repetition != null && repetition.getRiskParagraphs() != null
                ? repetition.getRiskParagraphs().size() : 0;
            double localRisk = Math.min(100, repCount * 3 + longCount * 2 + riskCount * 5);
            return Math.round(localRisk * 0.9 * 10.0) / 10.0;
        }
    }

    /** SSE 流式降重改写 */
    public SseEmitter reduce(String text, String sourceText, String mode, String model,
                              List<com.mianmiantong.dto.paper.PlagiarismReduceRequest.ReportAnnotation> annotations,
                              List<com.mianmiantong.dto.paper.ContextChunk> contextChunks) {
        SseEmitter emitter = new SseEmitter(120_000L);

        Map<String, String> modeLabels = Map.of("light", "轻度降重", "medium", "中度降重", "deep", "深度降重");
        String modeLabel = modeLabels.getOrDefault(mode != null ? mode : "medium", "中度降重");
        String safeMode = mode != null ? mode : "medium";
        String safeText = text != null ? text : "";
        String safeSource = sourceText != null ? sourceText : "";

        // 构建报告标注的待改写片段列表
        String flaggedPassages = buildFlaggedPassages(annotations);
        var sanitizedChunks = PaperContextSanitizer.sanitize(contextChunks);
        String contextBlock = PaperContextSanitizer.formatForPrompt(sanitizedChunks);

        String systemPrompt = getSystemPrompt("prompts/plagiarism_transform.txt");
        String userPrompt = renderPrompt("prompts/plagiarism_transform.txt", Map.of(
            "text", safeText,
            "source_text", safeSource,
            "mode", safeMode,
            "mode_label", modeLabel,
            "flagged_passages", flaggedPassages,
            "context_chunks", contextBlock
        ));

        List<ChatMessage> messages = List.of(
            new ChatMessage("user", userPrompt)
        );

        emitter.onTimeout(() -> {
            safeSend(emitter, "error", "降重超时");
            emitter.complete();
        });

        Long usageUserId = JwtAuthFilter.getCurrentUserId();
        CompletableFuture.runAsync(() -> {
            try {
                AiRequest aiRequest = new AiRequest(systemPrompt, messages, model, AiTaskType.FLASH).withUsage(usageUserId, "PAPER_REDUCE");
                aiGateway.streamChat(aiRequest, null, token -> {
                    safeSend(emitter, "token", token);
                });

                emitter.send(SseEmitter.event().name("finish")
                    .data("{\"phase\":\"reduce\"}"));
                emitter.complete();
            } catch (Exception e) {
                log.error("Plagiarism reduce SSE stream failed", e);
                safeSend(emitter, "error", e.getMessage() != null ? e.getMessage() : "降重失败");
                emitter.complete();
            }
        });

        return emitter;
    }

    /** 将报告标注构建为 AI 易处理的标注片段列表 */
    private String buildFlaggedPassages(List<PlagiarismReduceRequest.ReportAnnotation> annotations) {
        if (annotations == null || annotations.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (var ann : annotations) {
            String text = ann.getText();
            if (text == null || text.isBlank()) continue;
            String level = ann.getRiskLevel() != null ? ann.getRiskLevel() : "medium";
            String label = switch (level) {
                case "high" -> "【高危】";
                case "low" -> "【低危】";
                default -> "【中危】";
            };
            String excerpt = text.length() > 120 ? text.substring(0, 120) + "…" : text;
            sb.append(idx).append(". ").append(label).append(" ").append(excerpt).append("\n");
            idx++;
        }
        return sb.toString();
    }

    private void safeSend(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException ignored) {
            // SSE connection already closed by client
        }
    }

    private long countIn(String text, String word) {
        int cnt = 0, idx = 0;
        while ((idx = text.indexOf(word, idx)) != -1) { cnt++; idx++; }
        return cnt;
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
            return "你是一位专业的学术改写编辑。";
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

    // === Inner classes ===
    @lombok.Data
    public static class RepetitionResult {
        private List<Map.Entry<String, Integer>> repeatedPhrases = new ArrayList<>();
        private List<String> longSentences = new ArrayList<>();
        private List<String> riskParagraphs = new ArrayList<>();
    }

    @lombok.Data
    public static class SimilarityResult {
        private double similarity;
        private List<String> commonWords = new ArrayList<>();
        private int uniqueIn1;
        private int uniqueIn2;
    }

    @lombok.Data
    public static class CitationCheckResult {
        private int citationCount;
        private int referenceCount;
        private boolean hasReferenceSection;
        private List<String> issues = new ArrayList<>();
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class MatchingFragment {
        private String sourceExcerpt;
        private String targetExcerpt;
        private int length;
        private int sourceStart;
        private int targetStart;
    }

    @lombok.Data
    public static class OverlapResult {
        private int overlapChars;
        private double overlapRatio;
        private List<MatchingFragment> topFragments = new ArrayList<>();
    }
}
