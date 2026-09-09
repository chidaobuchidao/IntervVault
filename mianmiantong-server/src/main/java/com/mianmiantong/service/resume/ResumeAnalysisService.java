package com.mianmiantong.service.resume;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mianmiantong.entity.resume.Resume;
import com.mianmiantong.entity.resume.ResumeAnalysis;
import com.mianmiantong.mapper.resume.ResumeAnalysisMapper;
import com.mianmiantong.mapper.resume.ResumeMapper;
import com.mianmiantong.service.ai.gateway.AiGateway;
import com.mianmiantong.service.ai.gateway.AiRequest;
import com.mianmiantong.service.ai.gateway.AiResponse;
import com.mianmiantong.service.ai.gateway.AiTaskType;
import com.mianmiantong.service.ai.gateway.ChatMessage;
import com.mianmiantong.service.document.DocumentAiService;
import com.mianmiantong.service.document.DocumentParseResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class ResumeAnalysisService {

    private final ResumeMapper resumeMapper;
    private final ResumeAnalysisMapper analysisMapper;
    private final AiGateway aiGateway;
    private final DocumentAiService documentAiService;
    private final ObjectMapper objectMapper;

    /** Phase 1: 快速评分，轻量快速 */
    private static final String QUICK_PROMPT = """
        你是资深HR。快速评估这份简历（目标岗位：%s），输出JSON：
        {
          "overallScore": 7,
          "dimensions": [
            {"name":"结构完整性","score":7,"comment":"简评"},
            {"name":"关键词匹配","score":5,"comment":"简评"},
            {"name":"项目描述","score":6,"comment":"简评"},
            {"name":"排版可读","score":6,"comment":"简评"},
            {"name":"语言表达","score":7,"comment":"简评"}
          ],
          "missingKeywords": ["关键词1"],
          "suggestion": "一句话总体建议"
        }
        简历内容：
        %s
        要求：JSON一行，不要markdown标记。简评15字以内。英文专业术语（如MyBatis、Spring Boot MVC、Docker、RESTful API、JSON等）保持原样不翻译不改写。
        """;

    /** Phase 2: 深度优化 */
    private static final String DEEP_PROMPT = """
        你是简历优化专家。基于以下简历和评分，进行深度优化（目标岗位：%s）：

        当前评分：%d/10，缺失关键词：%s

        简历内容：
        %s

        输出JSON（一行，不要markdown代码围栏）：
        {
          "highlights": [
            {"section":"段落名","before":"原文片段（15-50字，精确引用原文）","after":"对应优化后的纯文本片段","reason":"理由"}
          ],
          "optimizedText": "完整优化后简历，段落之间用\\n\\n分隔",
          "interviewQuestions": ["追问1","追问2","追问3"]
        }

        【硬约束 — 违反即不合格】
        1. 全部输出禁止使用任何emoji表情符号。
        2. highlights[].before 必须是原文的精确引用（15-50字），用于前端对比展示，不得改写或重组。
        3. highlights[].after 必须是 before 对应片段的优化版本，保持相近长度。
        4. 英文专业术语、技术名词、框架名称必须保留原文拼写，不得翻译、替换或改写（如MyBatis、Spring Boot MVC、Docker、Kubernetes、RESTful API、JSON、HTML等保持原样）。
        5. 数学表达式和公式中的空格必须原样保留（如"1 + 1 = 2"不得改为"1+1=2"）。
        6. 禁止改写或合并个人信息/联系方式字段块（如姓名、求职意向、出生年月、籍贯、民族、政治面貌、学历、手机、现居地、工作年限、邮箱）。这些字段在 optimizedText 中必须保持原有换行、空格和字段分隔；不得把多个字段压缩成连续文本。
        7. highlights 不要选择个人信息/联系方式字段块作为 before/after 优化项；只优化经历、项目、技能、自我评价等正文内容。
        """;

    public ResumeAnalysisService(ResumeMapper resumeMapper,
                                  ResumeAnalysisMapper analysisMapper,
                                  AiGateway aiGateway,
                                  DocumentAiService documentAiService) {
        this.resumeMapper = resumeMapper;
        this.analysisMapper = analysisMapper;
        this.aiGateway = aiGateway;
        this.documentAiService = documentAiService;
        this.objectMapper = new ObjectMapper();
        // AI 可能返回 literal newlines 等未转义控制字符，Jackson 默认拒绝
        this.objectMapper.configure(
                com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.mappedFeature(), true);
    }

    /** Phase 1: 快速评分（异步后台） */
    public void analyzeQuickAsync(Long resumeId, String model) {
        CompletableFuture.runAsync(() -> {
            try {
                Resume resume = resumeMapper.selectById(resumeId);
                if (resume == null) {
                    log.warn("快速评分跳过: resumeId={} 不存在", resumeId);
                    return;
                }
                if (resume.getParseStatus() != 1) {
                    log.warn("快速评分跳过: resumeId={}, parseStatus={}", resumeId, resume.getParseStatus());
                    // 写入失败状态，让前端感知
                    ResumeAnalysis analysis = upsert(resumeId);
                    analysis.setDeepStatus(-1);
                    analysis.setSuggestion("简历解析未完成，无法进行AI分析。请重新上传简历。");
                    save(analysis);
                    return;
                }

                String prompt = String.format(QUICK_PROMPT, resume.getJobDescription(), resume.getParsedText());
                List<ChatMessage> messages = List.of(new ChatMessage("user", "开始评估"));
                AiRequest aiRequest = new AiRequest(prompt, messages, model, AiTaskType.FLASH).withUsage(resume.getUserId(), "RESUME");
                AiResponse aiResponse = aiGateway.chat(aiRequest, null);
                Map<String, Object> report = objectMapper.readValue(extractJson(aiResponse.content()), Map.class);

                ResumeAnalysis analysis = upsert(resumeId);
                analysis.setOverallScore(toInt(report.get("overallScore")));
                analysis.setDimensions(toJson(report.get("dimensions")));
                analysis.setMissingKeywords(toJson(report.get("missingKeywords")));
                analysis.setSuggestion((String) report.get("suggestion"));
                analysis.setDeepStatus(0);
                save(analysis);
                log.info("快速评分完成: resumeId={}, score={}", resumeId, analysis.getOverallScore());
            } catch (Exception e) {
                log.error("快速评分失败: resumeId={}", resumeId, e);
                try {
                    ResumeAnalysis analysis = upsert(resumeId);
                    analysis.setDeepStatus(-1);
                    analysis.setSuggestion("AI分析失败: " + (e.getMessage() != null ? e.getMessage().substring(0, Math.min(100, e.getMessage().length())) : "未知错误"));
                    save(analysis);
                } catch (Exception ignored) {
                    log.error("保存失败状态时出错: resumeId={}", resumeId, ignored);
                }
            }
        });
    }

    /** Phase 2: 深度优化 SSE 流式（含重试与断点续传） */
    public SseEmitter analyzeDeepStream(Long resumeId, String model) {
        Resume resume = resumeMapper.selectById(resumeId);
        ResumeAnalysis analysis = upsert(resumeId);

        // 先尝试从 partial 恢复（在重试次数检查之前，因为数据已经有了）
        String partial = analysis.getPartialResponse();
        if (partial != null && !partial.isBlank()) {
            try {
                String recovered = extractJson(partial);
                Map<String, Object> recoveredReport = objectMapper.readValue(recovered, Map.class);
                if (recoveredReport.get("highlights") != null || recoveredReport.get("optimizedText") != null) {
                    analysis.setHighlights(toJson(recoveredReport.get("highlights")));
                    analysis.setOptimizedText((String) recoveredReport.get("optimizedText"));
                    analysis.setInterviewQuestions(toJson(recoveredReport.get("interviewQuestions")));
                    analysis.setDeepStatus(2);
                    analysis.setPartialResponse(null);
                    save(analysis);
                    log.info("深度优化从partial恢复成功: resumeId={}", resumeId);
                    SseEmitter recoveryEmitter = new SseEmitter();
                    recoveryEmitter.onCompletion(() -> {});
                    recoveryEmitter.send(SseEmitter.event().name("finish")
                            .data(objectMapper.writeValueAsString(Map.of(
                                    "resumeId", resumeId, "deepStatus", 2, "phase", "deep", "recovered", true))));
                    recoveryEmitter.complete();
                    return recoveryEmitter;
                }
            } catch (Exception ignored) {
                log.info("从partial恢复失败，将继续SSE: resumeId={}", resumeId);
            }
        }

        // 检查重试次数
        int retryCount = analysis.getRetryCount() != null ? analysis.getRetryCount() : 0;
        if (retryCount >= 3) {
            SseEmitter rejectEmitter = new SseEmitter();
            rejectEmitter.onCompletion(() -> {});
            try {
                rejectEmitter.send(SseEmitter.event().name("error")
                        .data("{\"message\":\"已达最大重试次数(3次)\",\"retryCount\":" + retryCount + "}"));
                rejectEmitter.complete();
            } catch (Exception ignored) {}
            return rejectEmitter;
        }

        // 标记进行中，重试次数+1
        analysis.setRetryCount(retryCount + 1);
        analysis.setDeepStatus(1);
        save(analysis);

        // 构建 prompt
        String basePrompt = buildDeepPrompt(resume, analysis);
        String userMessage;
        if (partial != null && !partial.isBlank()) {
            userMessage = "之前的输出被中断，已输出的内容：\n" + partial + "\n\n请从断点处继续输出完整的JSON，不要重复已输出的内容。";
        } else {
            userMessage = "开始深度优化";
        }

        List<ChatMessage> messages = List.of(new ChatMessage("user", userMessage));
        SseEmitter emitter = new SseEmitter(600_000L);
        StringBuilder buf = new StringBuilder();
        if (partial != null) buf.append(partial);

        emitter.onTimeout(() -> {
            safeSavePartial(analysis, buf.toString());
            safeSend(emitter, "error", "{\"message\":\"分析超时，已保存中间结果，可重试\",\"retryCount\":" + analysis.getRetryCount() + "}");
            emitter.complete();
        });

        emitter.onError(ex -> {
            safeSavePartial(analysis, buf.toString());
        });

        CompletableFuture.runAsync(() -> {
            try {
                final long[] lastSaveTime = {System.currentTimeMillis()};

                AiRequest aiRequest = new AiRequest(basePrompt, messages, model, AiTaskType.FLASH).withUsage(resume.getUserId(), "RESUME");
                aiGateway.streamChat(aiRequest, null, token -> {
                    buf.append(token);
                    safeSend(emitter, "token", token);

                    // 每 30 秒保存中间结果（仅用于崩溃恢复，不需高频写入）
                    long now = System.currentTimeMillis();
                    if (now - lastSaveTime[0] >= 30000) {
                        lastSaveTime[0] = now;
                        safeSavePartial(analysis, buf.toString());
                    }
                });

                // 解析最终 JSON
                String fullResponse = buf.toString();
                String jsonStr = extractJson(fullResponse);
                log.info("深度优化AI响应长度: {}, JSON长度: {}", fullResponse.length(), jsonStr.length());
                Map<String, Object> report = objectMapper.readValue(jsonStr, Map.class);

                analysis.setHighlights(toJson(report.get("highlights")));
                analysis.setOptimizedText((String) report.get("optimizedText"));
                analysis.setInterviewQuestions(toJson(report.get("interviewQuestions")));
                analysis.setDeepStatus(2);
                analysis.setPartialResponse(null); // 清除中间结果
                save(analysis);

                emitter.send(SseEmitter.event().name("finish")
                        .data(objectMapper.writeValueAsString(Map.of(
                                "resumeId", resumeId, "deepStatus", 2,
                                "phase", "deep"))));
                emitter.complete();
                log.info("深度优化(SSE)完成: resumeId={}, retry={}", resumeId, analysis.getRetryCount());

            } catch (Exception e) {
                log.error("深度优化(SSE)失败: resumeId={}, 响应前500字: {}", resumeId,
                    buf.length() > 500 ? buf.substring(0, 500) : buf.toString(), e);
                safeSavePartial(analysis, buf.toString());
                safeSend(emitter, "error", "{\"message\":\"AI分析失败\",\"retryCount\":" + analysis.getRetryCount() + "}");
                try {
                    analysis.setDeepStatus(-1);
                    save(analysis);
                } catch (Exception ignored) {}
                emitter.complete();
            }
        });

        return emitter;
    }

    /** Phase 1: 快速评分 SSE（兼容旧版，保留） */
    public SseEmitter analyzeStream(Long resumeId) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null || resume.getParseStatus() != 1) {
            throw new IllegalArgumentException("简历不存在或尚未解析完成");
        }

        String prompt = String.format(QUICK_PROMPT, resume.getJobDescription(), resume.getParsedText());
        List<ChatMessage> messages = List.of(new ChatMessage("user", "开始评估"));

        SseEmitter emitter = new SseEmitter(120_000L);
        StringBuilder buf = new StringBuilder();

        emitter.onTimeout(() -> safeSend(emitter, "error", "分析超时"));

        CompletableFuture.runAsync(() -> {
            try {
                AiRequest aiRequest = new AiRequest(prompt, messages, null, AiTaskType.FLASH).withUsage(resume.getUserId(), "RESUME");
                aiGateway.streamChat(aiRequest, null, token -> {
                    buf.append(token);
                    safeSend(emitter, "token", token);
                });

                Map<String, Object> report = objectMapper.readValue(extractJson(buf.toString()), Map.class);

                ResumeAnalysis analysis = upsert(resumeId);
                analysis.setOverallScore(toInt(report.get("overallScore")));
                analysis.setDimensions(toJson(report.get("dimensions")));
                analysis.setMissingKeywords(toJson(report.get("missingKeywords")));
                analysis.setSuggestion((String) report.get("suggestion"));
                analysis.setDeepStatus(0); // 待深度优化
                save(analysis);

                emitter.send(SseEmitter.event().name("finish")
                        .data(objectMapper.writeValueAsString(Map.of(
                                "resumeId", resumeId, "overallScore", analysis.getOverallScore(),
                                "phase", "quick"))));
                emitter.complete();
            } catch (Exception e) {
                log.error("快速分析失败: resumeId={}", resumeId, e);
                safeSend(emitter, "error", e.getMessage());
                emitter.complete();
            }
        });
        return emitter;
    }

    /** Phase 2: 深度优化，异步后台执行 */
    public void analyzeDeepAsync(Long resumeId) {
        CompletableFuture.runAsync(() -> {
            try {
                Resume resume = resumeMapper.selectById(resumeId);
                ResumeAnalysis analysis = upsert(resumeId);

                analysis.setDeepStatus(1); // 进行中
                save(analysis);

                String keywords = analysis.getMissingKeywords() != null ? analysis.getMissingKeywords() : "无";
                String prompt = String.format(DEEP_PROMPT, resume.getJobDescription(),
                        analysis.getOverallScore() != null ? analysis.getOverallScore() : 5,
                        keywords, resume.getParsedText());

                List<ChatMessage> messages = List.of(new ChatMessage("user", "开始深度优化"));
                AiRequest aiRequest = new AiRequest(prompt, messages, null, AiTaskType.FLASH).withUsage(resume.getUserId(), "RESUME");
                AiResponse aiResponse = aiGateway.chat(aiRequest, null);

                String jsonStr = extractJson(aiResponse.content());
                log.info("深度优化(异步)AI响应长度: {}, JSON长度: {}", aiResponse.content().length(), jsonStr.length());
                Map<String, Object> report = objectMapper.readValue(jsonStr, Map.class);
                analysis.setHighlights(toJson(report.get("highlights")));
                analysis.setOptimizedText((String) report.get("optimizedText"));
                analysis.setInterviewQuestions(toJson(report.get("interviewQuestions")));
                analysis.setDeepStatus(2); // 完成
                save(analysis);
                log.info("深度优化完成: resumeId={}", resumeId);
            } catch (Exception e) {
                log.error("深度优化(异步)失败: resumeId={}", resumeId, e);
                try {
                    ResumeAnalysis analysis = upsert(resumeId);
                    analysis.setDeepStatus(-1);
                    save(analysis);
                } catch (Exception ignored) {}
            }
        });
    }

    /** 检查是否可以重试 */
    public Map<String, Object> retryDeepOptimize(Long resumeId) {
        ResumeAnalysis analysis = analysisMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResumeAnalysis>()
                        .eq(ResumeAnalysis::getResumeId, resumeId));
        Map<String, Object> result = new LinkedHashMap<>();
        if (analysis == null) {
            result.put("retryable", false);
            result.put("message", "分析记录不存在");
            return result;
        }
        int count = analysis.getRetryCount() != null ? analysis.getRetryCount() : 0;
        result.put("retryable", count < 3);
        result.put("retryCount", count);
        result.put("remaining", 3 - count);
        result.put("hasPartial", analysis.getPartialResponse() != null
                && !analysis.getPartialResponse().isBlank());
        return result;
    }

    /** 获取深度优化状态（含重试与断点信息） */
    public Map<String, Object> getDeepStatus(Long resumeId) {
        ResumeAnalysis analysis = analysisMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResumeAnalysis>()
                        .eq(ResumeAnalysis::getResumeId, resumeId));
        Map<String, Object> result = new LinkedHashMap<>();
        if (analysis == null) {
            result.put("deepStatus", null);
            return result;
        }
        result.put("deepStatus", analysis.getDeepStatus());
        result.put("retryCount", analysis.getRetryCount() != null ? analysis.getRetryCount() : 0);
        result.put("hasPartial", analysis.getPartialResponse() != null
                && !analysis.getPartialResponse().isBlank());
        return result;
    }

    public Map<String, Object> getReport(Long resumeId) {
        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) throw new IllegalArgumentException("简历不存在");

        // 如果解析还在进行中，主动轮询一次阿里云状态
        if (resume.getParseStatus() == 0 && resume.getDocTaskId() != null) {
            try {
                DocumentParseResult parseResult = documentAiService.getResult(resume.getDocTaskId());
                if ("SUCCESS".equals(parseResult.getStatus())) {
                    resume.setParseStatus(1);
                    resume.setParsedText(parseResult.getParsedText());
                    resumeMapper.updateById(resume);
                    log.info("getReport触发解析完成: resumeId={}", resumeId);
                } else if ("FAIL".equals(parseResult.getStatus())) {
                    resume.setParseStatus(-1);
                    resumeMapper.updateById(resume);
                    log.warn("getReport检测到解析失败: resumeId={}", resumeId);
                }
            } catch (Exception e) {
                log.warn("getReport轮询解析状态异常: resumeId={}", resumeId, e);
            }
        }

        ResumeAnalysis analysis = analysisMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResumeAnalysis>()
                        .eq(ResumeAnalysis::getResumeId, resumeId));

        if (analysis == null) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("resumeId", resumeId);
            result.put("fileName", resume.getFileName());
            result.put("parseStatus", resume.getParseStatus());
            result.put("overallScore", null);
            result.put("suggestion", null);
            result.put("deepStatus", null);
            result.put("dimensions", null);
            result.put("missingKeywords", null);
            result.put("highlights", null);
            result.put("optimizedText", null);
            result.put("interviewQuestions", null);
            return result;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("resumeId", resumeId);
        result.put("fileName", resume.getFileName());
        result.put("parseStatus", resume.getParseStatus());

        if (analysis == null) {
            result.put("overallScore", null);
            result.put("suggestion", null);
            result.put("deepStatus", null);
            result.put("dimensions", null);
            result.put("missingKeywords", null);
            result.put("highlights", null);
            result.put("optimizedText", null);
            result.put("interviewQuestions", null);
            return result;
        }

        result.put("overallScore", analysis.getOverallScore());
        result.put("jobDescription", resume.getJobDescription());
        result.put("dimensions", parseJson(analysis.getDimensions()));
        result.put("missingKeywords", parseJson(analysis.getMissingKeywords()));
        result.put("suggestion", analysis.getSuggestion());
        result.put("deepStatus", analysis.getDeepStatus());
        result.put("highlights", parseJson(analysis.getHighlights()));
        result.put("optimizedText", analysis.getOptimizedText());
        result.put("interviewQuestions", parseJson(analysis.getInterviewQuestions()));
        return result;
    }

    private ResumeAnalysis upsert(Long resumeId) {
        ResumeAnalysis existing = analysisMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResumeAnalysis>()
                        .eq(ResumeAnalysis::getResumeId, resumeId));
        if (existing != null) return existing;
        ResumeAnalysis a = new ResumeAnalysis();
        a.setResumeId(resumeId);
        analysisMapper.insert(a);
        return a;
    }

    private void save(ResumeAnalysis a) {
        if (a.getId() != null) analysisMapper.updateById(a);
        else analysisMapper.insert(a);
    }

    private void safeSend(SseEmitter e, String name, String data) {
        try { e.send(SseEmitter.event().name(name).data(data)); } catch (Exception ignored) {}
    }

    /**
     * 从AI返回的文本中提取JSON对象。
     * 用括号计数法找到匹配的 { }，跳过字符串内的括号和转义字符。
     * 同时处理 markdown 代码围栏 ```json ... ```。
     */
    private String extractJson(String s) {
        // 去掉 markdown 代码围栏
        String text = s.replaceAll("```\\w*\\s*", "").replaceAll("```", "").trim();

        int start = text.indexOf("{");
        if (start < 0) return s;

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == '{') depth++;
                else if (c == '}') {
                    depth--;
                    if (depth == 0) return text.substring(start, i + 1);
                }
            }
        }

        // 括号没闭合，回退到旧逻辑
        int end = text.lastIndexOf("}") + 1;
        return (end > start) ? text.substring(start, end) : s;
    }

    private int toInt(Object v) { return v instanceof Number n ? n.intValue() : 0; }

    private String toJson(Object obj) {
        try { return objectMapper.writeValueAsString(obj); } catch (JsonProcessingException e) { return "null"; }
    }

    private Object parseJson(String json) {
        if (json == null) return null;
        try { return objectMapper.readValue(json, Object.class); } catch (JsonProcessingException e) { return json; }
    }

    /** 构建深度优化 prompt */
    private String buildDeepPrompt(Resume resume, ResumeAnalysis analysis) {
        String keywords = analysis.getMissingKeywords() != null ? analysis.getMissingKeywords() : "无";
        return String.format(DEEP_PROMPT, resume.getJobDescription(),
                analysis.getOverallScore() != null ? analysis.getOverallScore() : 5,
                keywords, resume.getParsedText());
    }

    /** 安全保存 partialResponse */
    private void safeSavePartial(ResumeAnalysis analysis, String partial) {
        try {
            analysis.setPartialResponse(partial);
            save(analysis);
        } catch (Exception e) {
            log.warn("保存中间结果失败: resumeId={}", analysis.getResumeId());
        }
    }
}
