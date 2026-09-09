package com.mianmiantong.service.interview;

import com.mianmiantong.config.JwtAuthFilter;
import com.mianmiantong.dto.interview.InterviewStartRequest;
import com.mianmiantong.entity.interview.InterviewSession;
import com.mianmiantong.entity.resume.Resume;
import com.mianmiantong.entity.user.UserAiConfig;
import com.mianmiantong.mapper.interview.InterviewSessionMapper;
import com.mianmiantong.mapper.resume.ResumeMapper;
import com.mianmiantong.service.ai.gateway.AiGateway;
import com.mianmiantong.service.ai.gateway.AiRequest;
import com.mianmiantong.service.ai.gateway.AiResponse;
import com.mianmiantong.service.ai.gateway.AiTaskType;
import com.mianmiantong.service.ai.gateway.ChatMessage;
import com.mianmiantong.service.coding.AlgorithmProblemService;
import com.mianmiantong.mapper.user.UserMapper;
import com.mianmiantong.service.user.QuotaService;
import com.mianmiantong.service.user.UserAiConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;

@Slf4j
@Service
public class InterviewService {

    /** 代码审查轮次 SSE 超时：需等待 AI 读代码并给评价，比普通问答长 */
    private static final long CODE_REVIEW_SSE_TIMEOUT_MS = 180_000L;
    /** 普通问答轮次 SSE 超时 */
    private static final long CHAT_SSE_TIMEOUT_MS = 120_000L;
    /** 编程题出题 SSE 超时：只做题目下发，无需等模型长输出 */
    private static final long CODING_PROBLEM_SSE_TIMEOUT_MS = 15_000L;

    @Value("${DEEPSEEK_API_KEY:}")
    private String systemApiKey;

    private final InterviewSessionMapper sessionMapper;
    private final AiGateway aiGateway;
    private final UserAiConfigService userAiConfigService;
    private final ResumeMapper resumeMapper;
    private final AlgorithmProblemService algorithmProblemService;
    private final UserMapper userMapper;
    private final QuotaService quotaService;
    private final InterviewPromptBuilder promptBuilder;
    private final InterviewTranscriptManager transcriptManager;
    private final InterviewReportParser reportParser;

    @Lazy @Autowired
    private InterviewService self;

    public InterviewService(InterviewSessionMapper sessionMapper, AiGateway aiGateway,
                            UserAiConfigService userAiConfigService, ResumeMapper resumeMapper,
                            AlgorithmProblemService algorithmProblemService,
                            UserMapper userMapper, QuotaService quotaService,
                            InterviewPromptBuilder promptBuilder,
                            InterviewTranscriptManager transcriptManager,
                            InterviewReportParser reportParser) {
        this.sessionMapper = sessionMapper;
        this.aiGateway = aiGateway;
        this.userAiConfigService = userAiConfigService;
        this.resumeMapper = resumeMapper;
        this.algorithmProblemService = algorithmProblemService;
        this.userMapper = userMapper;
        this.quotaService = quotaService;
        this.promptBuilder = promptBuilder;
        this.transcriptManager = transcriptManager;
        this.reportParser = reportParser;
    }

    /** 获取当前用户可用的 API Key（不扣配额）。Admin/有Key用户不限，无Key用户需有剩余配额。 */
    private String getUserApiKey() {
        Long userId = JwtAuthFilter.getCurrentUserId();
        if (userId == null) return null;

        // User has their own API key → unlimited
        UserAiConfig config = userAiConfigService.getByUserId(userId);
        if (config != null && config.getApiKey() != null && !config.getApiKey().isBlank()) {
            return config.getApiKey();
        }

        // Admin → system key, unlimited
        if (JwtAuthFilter.isAdmin()) {
            return resolveSystemApiKey();
        }

        // Regular user → check daily quota (don't consume)
        quotaService.checkQuota();
        return resolveSystemApiKey();
    }

    /** 消耗配额 */
    private void consumeQuota(String model) {
        quotaService.checkAndConsume(JwtAuthFilter.getCurrentUserId(), model);
    }

    /** System-level API key (from env/config, for admin use) */
    private String resolveSystemApiKey() {
        if (systemApiKey == null || systemApiKey.isBlank()) {
            throw new IllegalStateException("系统 API Key 未配置，请设置 DEEPSEEK_API_KEY 环境变量");
        }
        return systemApiKey;
    }

    /** 开始面试 — AI自主生成第一个问题（同时清理旧记录，保留最近5条） */
    @Transactional
    public Map<String, Object> start(InterviewStartRequest request) {
        Long userId = JwtAuthFilter.getCurrentUserId();
        String position = request.getPosition();

        cleanupOldSessions(userId);

        // 如果携带了简历，获取简历文本并注入 System Prompt
        String resumeContext = "";
        if (request.getResumeId() != null) {
            Resume resume = resumeMapper.selectById(request.getResumeId());
            if (resume != null && resume.getParseStatus() == 1) {
                resumeContext = String.format("""

                    ## 候选人简历背景

                    %s
                    """, resume.getParsedText());
            }
        }

        String systemPrompt = promptBuilder.buildSystemPrompt(position) + resumeContext;

        List<ChatMessage> initMessages = List.of(
            new ChatMessage("user", "面试现在开始。请你作为面试官，先让候选人做一个简短的自我介绍，然后问第一个技术问题。注意：你是面试官，候选人会回答你的问题。")
        );

        log.info("\n" + "=".repeat(80) + "\n" +
                 "【AI面试启动】岗位: {} | 用户ID: {}\n" +
                 "=".repeat(80),
                 position, userId);

        AiRequest aiRequest = new AiRequest(systemPrompt, initMessages, request.getModel(), AiTaskType.FLASH).withUsage(userId, "INTERVIEW");
        AiResponse aiResponse = aiGateway.chat(aiRequest, userId);
        String firstQuestion = aiResponse.content();

        log.info("\n" + "-".repeat(60) + "\n" +
                 "【AI原始输出 - 第1问】\n{}\n" +
                 "-".repeat(60),
                 firstQuestion);

        InterviewSession session = new InterviewSession();
        session.setUserId(userId);
        session.setPosition(position);
        session.setCurrentQuestionIndex(0);
        session.setModel(request.getModel());

        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "assistant", "content", firstQuestion, "time", LocalDateTime.now().toString()));
        session.setMessages(transcriptManager.toJson(messages));
        session.setStatus(0);

        sessionMapper.insert(session);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("question", firstQuestion);
        result.put("questionIndex", 1);
        return result;
    }

    /** 回答问题 — AI评估 + 决定继续还是结束 */
    @Transactional
    public Map<String, Object> answer(Long sessionId, String answer) {
        Long userId = JwtAuthFilter.getCurrentUserId();

        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new IllegalArgumentException("面试会话不存在");
        }
        if (session.getStatus() == 1) {
            throw new IllegalArgumentException("面试已结束");
        }

        // 编程环节拦截：不经过面试AI，单独调用出题AI
        if (answer.contains("[进入编程环节]")) {
            return handleCodingRound(session, answer);
        }

        List<Map<String, Object>> messages = transcriptManager.parseMessages(session.getMessages());
        String systemPrompt = promptBuilder.buildSystemPrompt(session.getPosition());

        messages.add(Map.of("role", "user", "content", answer, "time", LocalDateTime.now().toString()));
        int nextIndex = session.getCurrentQuestionIndex() + 1;

        List<ChatMessage> aiMessages = new ArrayList<>(transcriptManager.toChatMessages(messages));
        aiMessages.add(new ChatMessage("user", promptBuilder.buildContextHint(nextIndex)));

        log.info("\n" + "=".repeat(60) + "\n" +
                 "【第{}轮对话】sessionId: {}\n" +
                 "用户回答: {}\n" +
                 "-".repeat(40) + "\n" +
                 "对话上下文(最近3轮):\n{}\n" +
                 "-".repeat(40),
                 nextIndex, sessionId, answer, transcriptManager.summarizeContext(aiMessages));

        AiRequest aiRequest = new AiRequest(systemPrompt, aiMessages, session.getModel(), AiTaskType.FLASH).withUsage(userId, "INTERVIEW");
        AiResponse aiResponseObj = aiGateway.chat(aiRequest, userId);
        String aiResponse = aiResponseObj.content();

        boolean finished = aiResponse.contains("[面试结束]");

        log.info("\n" + "-".repeat(40) + "\n" +
                 "【AI原始输出 - 第{}轮】(结束={})\n{}\n" +
                 "-".repeat(40),
                 nextIndex, finished, aiResponse);

        messages.add(Map.of("role", "assistant", "content", aiResponse, "time", LocalDateTime.now().toString()));
        session.setCurrentQuestionIndex(nextIndex);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);

        if (finished) {
            Map<String, Object> report = reportParser.parseReport(aiResponse, "[面试结束]");

            boolean hasInterviewReport = session.getFeedback() != null
                && !session.getFeedback().isEmpty();

            if (hasInterviewReport) {
                session.setCodingScore(reportParser.extractScore(report));
                session.setCodingDimensions(transcriptManager.toJson(report.get("dimensions")));
                session.setCodingFeedback((String) report.get("feedback"));
                session.setCodingSuggestion((String) report.get("suggestion"));
            } else {
                session.setOverallScore(reportParser.extractScore(report));
                session.setDimensions(transcriptManager.toJson(report.get("dimensions")));
                session.setFeedback((String) report.get("feedback"));
            }
            session.setStatus(1);
            session.setFinishTime(LocalDateTime.now());

            result.put("finished", true);
            result.put("report", report);
            result.put("hasCodingRound", hasInterviewReport);

            log.info("\n" + "=".repeat(60) + "\n" +
                     "【面试报告解析成功】总分: {} | 维度数: {} | 建议: {}\n" +
                     "完整报告:\n{}\n" +
                     "=".repeat(60),
                     session.getOverallScore(),
                     report.get("dimensions") instanceof List ? ((List<?>) report.get("dimensions")).size() : 0,
                     report.get("suggestion"),
                     transcriptManager.toJson(report));

            String jsonStr = reportParser.extractJson(aiResponse, "[面试结束]");
            if (jsonStr == null && aiResponse.contains("{")) {
                jsonStr = aiResponse.substring(aiResponse.indexOf("{"), aiResponse.lastIndexOf("}") + 1);
            }
            String closingMsg = aiResponse.replace("[面试结束]", "").replace(jsonStr != null ? jsonStr : "", "").trim();
            if (!closingMsg.isEmpty()) {
                result.put("closingMessage", closingMsg);
            }
        } else {
            result.put("finished", false);
            result.put("question", aiResponse);
            result.put("questionIndex", nextIndex + 1);
        }

        session.setMessages(transcriptManager.toJson(messages));
        sessionMapper.updateById(session);
        return result;
    }

    /** 流式回答问题 — AI评估 + 实时推送token到前端（支持代码审查） */
    public SseEmitter answerStream(Long sessionId, String answer,
                                   String code, String codeLang, String codeFile) {
        Long userId = JwtAuthFilter.getCurrentUserId();

        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            return errorEmitter("面试会话不存在");
        }
        if (session.getStatus() == 1) {
            return errorEmitter("面试已结束");
        }

        // 编程环节拦截：先后台生成面试报告，再出题
        if (answer.contains("[进入编程环节]")) {
            saveInterviewReportAsync(session);
            return handleCodingRoundStream(session, answer);
        }

        List<Map<String, Object>> messages = transcriptManager.parseMessages(session.getMessages());
        String systemPrompt = promptBuilder.buildSystemPrompt(session.getPosition());

        String fullAnswer = answer != null ? answer : "";
        boolean isCodeReview = code != null && !code.isEmpty();

        if (!isCodeReview) {
            messages.add(Map.of("role", "user", "content", fullAnswer, "time", LocalDateTime.now().toString()));
        }
        // For code review: handled separately below, user message added after review
        int nextIndex = session.getCurrentQuestionIndex() + 1;

        List<ChatMessage> chatMsgs = new ArrayList<>(transcriptManager.toChatMessages(messages));

        String contextHint;
        if (isCodeReview) {
            contextHint = ""; // Not used for code review path
        } else {
            contextHint = promptBuilder.buildContextHint(nextIndex);
        }

        SseEmitter emitter = new SseEmitter(
                isCodeReview ? CODE_REVIEW_SSE_TIMEOUT_MS : CHAT_SSE_TIMEOUT_MS);

        emitter.onTimeout(() -> {
            log.warn("SSE流超时: sessionId={}", sessionId);
            try {
                emitter.send(SseEmitter.event().name("error").data("AI响应超时，请重试"));
            } catch (Exception sendEx) {
                log.debug("SSE超时通知发送失败(连接已关闭): sessionId={}", sessionId, sendEx);
            }
            emitter.complete();
        });
        emitter.onError(ex -> {
            log.error("SSE流错误: sessionId={}", sessionId, ex);
        });

        String userApiKey = getUserApiKey();

        if (isCodeReview) {
            // ===== 代码审查：独立 AI 调用 =====
            String langLabel = codeLang != null ? codeLang : "java";
            String fileLabel = codeFile != null ? codeFile : "Solution";
            String codingAnswer = "请审查以下代码，从正确性、代码风格、时间复杂度、边界处理、可读性五个维度评分。\n\n```" + langLabel + "\n// " + fileLabel + "\n" + code + "\n```\n\n输出格式（仅输出以下JSON，不要其他文字）：\n[笔试结束]{\"score\":8,\"feedback\":\"总体评价\",\"dimensions\":[{\"name\":\"正确性\",\"score\":8,\"comment\":\"...\"},{\"name\":\"代码风格\",\"score\":8,\"comment\":\"...\"},{\"name\":\"时间复杂度\",\"score\":8,\"comment\":\"...\"},{\"name\":\"边界处理\",\"score\":8,\"comment\":\"...\"},{\"name\":\"可读性\",\"score\":8,\"comment\":\"...\"}],\"suggestion\":\"提升建议\"}";

            List<ChatMessage> codingMsgs = new ArrayList<>(transcriptManager.toChatMessages(messages));
            codingMsgs.add(new ChatMessage("user", codingAnswer));
            final List<ChatMessage> codingMessages = codingMsgs;
            final String sessionModel = session.getModel();

            log.info("\n" + "=".repeat(60) + "\n" +
                     "【代码审查 - 独立AI调用】sessionId: {}\n" +
                     "-".repeat(40),
                     sessionId);

            CompletableFuture.runAsync(() -> {
                StringBuilder codingResponse = new StringBuilder();
                try {
                    AiRequest codingAiRequest = new AiRequest(systemPrompt, codingMessages, sessionModel, AiTaskType.FLASH).withUsage(userId, "INTERVIEW");
                    aiGateway.streamChat(codingAiRequest, userId, token -> {
                        codingResponse.append(token);
                        try {
                            emitter.send(SseEmitter.event().name("token").data(token));
                        } catch (Exception e) {
                            throw new RuntimeException("SSE发送失败", e);
                        }
                    });

                    String codingText = codingResponse.toString();
                    log.info("\n" + "-".repeat(40) + "\n" +
                             "【代码审查 - AI输出】\n{}\n" +
                             "-".repeat(40), codingText);

                    // 解析 [笔试结束] JSON 并存储笔试报告
                    Map<String, Object> codingReport = reportParser.parseReport(codingText, "[笔试结束]");
                    if (!codingReport.isEmpty()) {
                        session.setCodingScore(reportParser.extractScore(codingReport));
                        session.setCodingDimensions(transcriptManager.toJson(codingReport.get("dimensions")));
                        session.setCodingFeedback((String) codingReport.get("feedback"));
                        session.setCodingSuggestion((String) codingReport.get("suggestion"));
                    }

                    // Save coding review to messages
                    messages.add(Map.of("role", "user", "content",
                            answer != null ? answer : "请审查代码",
                            "time", LocalDateTime.now().toString()));
                    messages.add(Map.of("role", "assistant", "content", codingText,
                            "time", LocalDateTime.now().toString()));
                    session.setMessages(transcriptManager.toJson(messages));
                    session.setStatus(1);
                    session.setFinishTime(LocalDateTime.now());

                    // 精准保存：用 LambdaUpdateWrapper 避免覆盖后台线程写好的面试报告
                    sessionMapper.update(null,
                        new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InterviewSession>()
                            .eq(InterviewSession::getId, session.getId())
                            .set(InterviewSession::getMessages, session.getMessages())
                            .set(InterviewSession::getStatus, session.getStatus())
                            .set(InterviewSession::getFinishTime, session.getFinishTime())
                            .set(InterviewSession::getCodingScore, session.getCodingScore())
                            .set(InterviewSession::getCodingDimensions, session.getCodingDimensions())
                            .set(InterviewSession::getCodingFeedback, session.getCodingFeedback())
                            .set(InterviewSession::getCodingSuggestion, session.getCodingSuggestion()));

                    Map<String, Object> finishData = new LinkedHashMap<>();
                    finishData.put("finished", true);
                    finishData.put("hasCodingRound", true);
                    emitter.send(SseEmitter.event().name("finish")
                            .data(transcriptManager.toJson(finishData)));
                    emitter.complete();

                } catch (Exception e) {
                    log.error("代码审查AI调用失败", e);
                    try {
                        emitter.send(SseEmitter.event().name("error").data("代码审查失败"));
                    } catch (Exception sendEx) {
                        log.debug("代码审查错误通知发送失败(连接已关闭): sessionId={}", sessionId, sendEx);
                    }
                    emitter.complete();
                }
            });

            return emitter;
        }

        // ===== 普通面试对话 =====
        chatMsgs.add(new ChatMessage("user", contextHint));
        final List<ChatMessage> chatMessages = chatMsgs;

        log.info("\n" + "=".repeat(60) + "\n" +
                 "【第{}轮对话 - 流式】sessionId: {}\n" +
                 "用户回答: {}\n" +
                 "-".repeat(40) + "\n" +
                 "对话上下文(最近3轮):\n{}\n" +
                 "-".repeat(40),
                 nextIndex, sessionId, fullAnswer.length() > 200 ? fullAnswer.substring(0, 200) + "..." : fullAnswer, transcriptManager.summarizeContext(chatMessages));

        final String streamModel = session.getModel();
        CompletableFuture.runAsync(() -> {
            StringBuilder fullResponse = new StringBuilder();
            boolean[] interviewFinished = {false};
            boolean[] codingFinished = {false};

            try {
                AiRequest streamAiRequest = new AiRequest(systemPrompt, chatMessages, streamModel, AiTaskType.FLASH).withUsage(userId, "INTERVIEW");
                aiGateway.streamChat(streamAiRequest, userId, token -> {
                    fullResponse.append(token);
                    try {
                        emitter.send(SseEmitter.event().name("token").data(token));
                    } catch (Exception e) {
                        throw new RuntimeException("SSE发送失败", e);
                    }
                    String content = fullResponse.toString();
                    if (content.contains("[面试结束]")) {
                        interviewFinished[0] = true;
                    }
                    if (content.contains("[笔试结束]")) {
                        codingFinished[0] = true;
                    }
                });

                String aiResponse = fullResponse.toString();
                messages.add(Map.of("role", "assistant", "content", aiResponse,
                        "time", LocalDateTime.now().toString()));

                boolean finished = interviewFinished[0] || codingFinished[0];
                log.info("\n" + "-".repeat(40) + "\n" +
                         "【AI流式输出完成 - 第{}轮】(面试结束={}, 笔试结束={})\n{}\n" +
                         "-".repeat(40),
                         nextIndex, interviewFinished[0], codingFinished[0], aiResponse);

                if (finished) {
                    try {
                        // 优先解析 [笔试结束] JSON，其次 [面试结束] JSON
                        String marker = codingFinished[0] ? "[笔试结束]" : "[面试结束]";
                        Map<String, Object> report = reportParser.parseReport(aiResponse, marker);
                        if (report.isEmpty()) {
                            report = reportParser.buildFallbackReport();
                        }

                        // 笔试结束 → 存为笔试报告；面试结束且有面试报告 → 也存为笔试报告
                        boolean saveAsCoding = codingFinished[0] || hasInterviewReport(session);

                        if (saveAsCoding) {
                            session.setCodingScore(reportParser.extractScore(report));
                            session.setCodingDimensions(transcriptManager.toJson(report.get("dimensions")));
                            session.setCodingFeedback((String) report.get("feedback"));
                            session.setCodingSuggestion((String) report.get("suggestion"));
                        } else {
                            session.setOverallScore(reportParser.extractScore(report));
                            session.setDimensions(transcriptManager.toJson(report.get("dimensions")));
                            session.setFeedback((String) report.get("feedback"));
                        }
                        session.setStatus(1);
                        session.setFinishTime(LocalDateTime.now());

                        Map<String, Object> finishData = new LinkedHashMap<>();
                        finishData.put("finished", true);
                        finishData.put("report", report);
                        finishData.put("hasCodingRound", saveAsCoding);

                        String jsonStr = reportParser.extractJson(aiResponse, marker);
                        String closingMsg = aiResponse
                                .replace("[面试结束]", "").replace("[笔试结束]", "")
                                .replace(jsonStr != null ? jsonStr : "", "").trim();
                        if (!closingMsg.isEmpty()) {
                            finishData.put("message", closingMsg);
                        }

                        emitter.send(SseEmitter.event().name("finish")
                                .data(transcriptManager.toJson(finishData)));

                        log.info("\n" + "=".repeat(60) + "\n" +
                                 "【报告解析成功】类型: {} | marker: {} | 总分: {}\n" +
                                 "=".repeat(60),
                                 saveAsCoding ? "笔试" : "面试", marker,
                                 saveAsCoding ? session.getCodingScore() : session.getOverallScore());

                    } catch (Exception e) {
                        log.warn("解析AI报告JSON失败: {}", e.getMessage());
                        if (hasInterviewReport(session)) {
                            session.setCodingScore(6);
                            session.setCodingFeedback("编程评估已完成");
                        } else {
                            session.setOverallScore(6);
                            session.setFeedback("面试已完成。");
                        }
                        session.setStatus(1);
                        session.setFinishTime(LocalDateTime.now());
                        emitter.send(SseEmitter.event().name("finish")
                                .data("{\"finished\":true,\"report\":{\"score\":6,\"feedback\":\"评估已完成\"}}"));
                    }
                } else {
                    Map<String, Object> finishData = new LinkedHashMap<>();
                    finishData.put("finished", false);
                    finishData.put("questionIndex", nextIndex + 1);
                    emitter.send(SseEmitter.event().name("finish")
                            .data(transcriptManager.toJson(finishData)));
                }

                session.setMessages(transcriptManager.toJson(messages));
                session.setCurrentQuestionIndex(nextIndex);
                self.saveAnswerData(session);

                emitter.complete();

            } catch (Exception e) {
                log.error("流式对话异常: sessionId={}", sessionId, e);
                try {
                    if (fullResponse.length() > 0) {
                        messages.add(Map.of("role", "assistant", "content",
                                fullResponse.toString(), "time", LocalDateTime.now().toString()));
                        session.setMessages(transcriptManager.toJson(messages));
                        session.setCurrentQuestionIndex(nextIndex);
                        self.saveAnswerData(session);
                    }
                    emitter.send(SseEmitter.event().name("error")
                            .data("AI服务暂时不可用: " + e.getMessage()));
                } catch (Exception sendEx) {
                    log.debug("流式对话错误通知发送失败(连接已关闭): sessionId={}", sessionId, sendEx);
                }
                emitter.complete();
            }
        });

        return emitter;
    }

    /** 保存面试数据（独立事务，供异步回调使用） */
    @Transactional
    public void saveAnswerData(InterviewSession session) {
        sessionMapper.updateById(session);
    }

    /** 手动结束面试 — 将现有对话交给AI生成报告 */
    @Transactional
    public Map<String, Object> end(Long sessionId) {
        Long userId = JwtAuthFilter.getCurrentUserId();

        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new IllegalArgumentException("面试会话不存在");
        }
        if (session.getStatus() == 1) {
            throw new IllegalArgumentException("面试已结束");
        }

        List<Map<String, Object>> messages = transcriptManager.parseMessages(session.getMessages());
        String transcript = transcriptManager.buildTranscript(messages);

        String systemPrompt = promptBuilder.buildEvaluationPrompt(transcript);
        List<ChatMessage> aiMessages = List.of(
            new ChatMessage("user", "请对以上面试对话进行评估，输出[面试结束]+JSON。")
        );

        AiRequest aiRequest = new AiRequest(systemPrompt, aiMessages, session.getModel(), AiTaskType.FLASH).withUsage(userId, "INTERVIEW");
        AiResponse aiResponseObj = aiGateway.chat(aiRequest, userId);
        String aiResponse = aiResponseObj.content();

        log.info("\n" + "-".repeat(40) + "\n" +
                 "【AI最终报告 - 原始输出】\n{}\n" +
                 "-".repeat(40),
                 aiResponse);
        messages.add(Map.of("role", "assistant", "content", aiResponse, "time", LocalDateTime.now().toString()));
        session.setStatus(1);
        session.setFinishTime(LocalDateTime.now());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);

        Map<String, Object> report = reportParser.parseReport(aiResponse, "[面试结束]");
        if (!report.isEmpty()) {
            session.setOverallScore(reportParser.extractScore(report));
            session.setDimensions(transcriptManager.toJson(report.get("dimensions")));
            session.setFeedback((String) report.get("feedback"));
            result.put("finished", true);
            result.put("report", report);
        } else {
            log.warn("解析面试报告JSON失败");
            session.setOverallScore(1);
            session.setFeedback("面试已结束，未能生成有效评估报告");
            session.setDimensions("[]");
            result.put("finished", true);
            result.put("report", Map.of("score", 1, "feedback", "面试已结束，未能生成有效评估", "dimensions", List.of()));
        }

        session.setMessages(transcriptManager.toJson(messages));
        sessionMapper.updateById(session);
        return result;
    }

    /** 面试历史（仅最近5条） */
    public List<InterviewSession> getHistory() {
        Long userId = JwtAuthFilter.getCurrentUserId();
        return sessionMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InterviewSession>()
                .eq(InterviewSession::getUserId, userId)
                .orderByDesc(InterviewSession::getCreateTime)
                .last("LIMIT 5")
        );
    }

    private boolean hasInterviewReport(InterviewSession session) {
        return session.getFeedback() != null && !session.getFeedback().isEmpty();
    }

    /** SSE 错误事件：避免 @ExceptionHandler 对 SSE 端点返回 Result 导致 406 */
    private SseEmitter errorEmitter(String message) {
        SseEmitter emitter = new SseEmitter();
        try {
            emitter.send(SseEmitter.event().name("error")
                    .data("{\"message\":\"" + message.replace("\"", "\\\"") + "\"}"));
        } catch (Exception sendEx) {
            log.debug("错误事件发送失败(连接已关闭): message={}", message, sendEx);
        }
        emitter.complete();
        return emitter;
    }

    /** 进入笔试时，后台生成并存储面试报告（基于笔试前的对话） */
    private void saveInterviewReportAsync(InterviewSession session) {
        final Long sessionId = session.getId();
        final Long userId = session.getUserId();
        final String sessionModel = session.getModel();
        final String messagesJson = session.getMessages();
        log.info("saveInterviewReportAsync启动: sessionId={}", sessionId);
        CompletableFuture.runAsync(() -> {
            try {
                List<Map<String, Object>> messages = transcriptManager.parseMessages(messagesJson);
                String transcript = transcriptManager.buildReportTranscript(messages);

                String systemPrompt = promptBuilder.buildEvaluationPrompt(transcript);
                List<ChatMessage> reportMessages = List.of(
                    new ChatMessage("user", "请评估以上面试对话")
                );
                AiRequest aiRequest = new AiRequest(systemPrompt, reportMessages, sessionModel, AiTaskType.FLASH).withUsage(userId, "INTERVIEW");
                AiResponse aiResponseObj = aiGateway.chat(aiRequest, userId);
                String aiResponse = aiResponseObj.content();

                Map<String, Object> report = reportParser.parseReport(aiResponse, "[面试结束]");
                if (report.isEmpty()) {
                    log.warn("面试报告生成失败: AI未输出JSON, sessionId={}", sessionId);
                    return;
                }

                // 用 LambdaUpdateWrapper 精准更新，不碰 messages 字段
                sessionMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InterviewSession>()
                        .eq(InterviewSession::getId, sessionId)
                        .set(InterviewSession::getOverallScore, reportParser.extractScore(report))
                        .set(InterviewSession::getDimensions, transcriptManager.toJson(report.get("dimensions")))
                        .set(InterviewSession::getFeedback, (String) report.get("feedback")));
                log.info("面试报告后台生成成功: sessionId={}, score={}", sessionId,
                    report.get("score"));
            } catch (Exception e) {
                log.warn("面试报告后台生成失败: sessionId={}, error={}", sessionId, e.getMessage(), e);
            }
        });
    }

    /** 清理旧会话：只保留最近5条，使用批量删除 */
    private void cleanupOldSessions(Long userId) {
        List<InterviewSession> all = sessionMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<InterviewSession>()
                .select(InterviewSession::getId)
                .eq(InterviewSession::getUserId, userId)
                .orderByDesc(InterviewSession::getCreateTime)
        );
        if (all.size() <= 5) return;
        List<Long> ids = all.subList(5, all.size()).stream()
                .map(InterviewSession::getId).toList();
        sessionMapper.deleteBatchIds(ids);
        log.info("批量清理旧面试记录: userId={}, count={}", userId, ids.size());
    }

    /** 面试详情 */
    public InterviewSession getDetail(Long id) {
        Long userId = JwtAuthFilter.getCurrentUserId();
        InterviewSession session = sessionMapper.selectById(id);
        if (session == null || !session.getUserId().equals(userId)) {
            throw new IllegalArgumentException("面试记录不存在");
        }
        return session;
    }

    // ===== 编程环节专用 =====

    /** 从题库随机选题，生成 [编程题目] JSON（与原有格式完全兼容） */
    private Map<String, Object> handleCodingRound(InterviewSession session, String answer) {
        String codingProblem = algorithmProblemService.generateCodingProblem(session.getPosition());

        log.info("\n" + "=".repeat(60) + "\n" +
                 "【题库出题】sessionId: {} | 岗位: {}\n{}\n" +
                 "=".repeat(60),
                 session.getId(), session.getPosition(), codingProblem);

        List<Map<String, Object>> messages = transcriptManager.parseMessages(session.getMessages());
        messages.add(Map.of("role", "user", "content", "[进入编程环节]", "time", LocalDateTime.now().toString()));
        messages.add(Map.of("role", "assistant", "content", codingProblem, "time", LocalDateTime.now().toString()));
        session.setMessages(transcriptManager.toJson(messages));
        session.setCurrentQuestionIndex(session.getCurrentQuestionIndex() + 1);
        // 精准更新，不覆盖后台线程刚写好的面试报告
        sessionMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InterviewSession>()
                .eq(InterviewSession::getId, session.getId())
                .set(InterviewSession::getMessages, session.getMessages())
                .set(InterviewSession::getCurrentQuestionIndex, session.getCurrentQuestionIndex()));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session.getId());
        result.put("content", codingProblem);
        return result;
    }

    private SseEmitter handleCodingRoundStream(InterviewSession session, String answer) {
        SseEmitter emitter = new SseEmitter(CODING_PROBLEM_SSE_TIMEOUT_MS);
        String codingProblem = algorithmProblemService.generateCodingProblem(session.getPosition());

        log.info("\n" + "=".repeat(60) + "\n" +
                 "【题库出题 - 流式】sessionId: {} | 岗位: {}\n{}\n" +
                 "=".repeat(60),
                 session.getId(), session.getPosition(), codingProblem);

        List<Map<String, Object>> messages = transcriptManager.parseMessages(session.getMessages());
        messages.add(Map.of("role", "user", "content", "[进入编程环节]", "time", LocalDateTime.now().toString()));
        messages.add(Map.of("role", "assistant", "content", codingProblem, "time", LocalDateTime.now().toString()));
        session.setMessages(transcriptManager.toJson(messages));
        session.setCurrentQuestionIndex(session.getCurrentQuestionIndex() + 1);
        // 精准更新，不覆盖后台线程刚写好的面试报告
        sessionMapper.update(null,
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<InterviewSession>()
                .eq(InterviewSession::getId, session.getId())
                .set(InterviewSession::getMessages, session.getMessages())
                .set(InterviewSession::getCurrentQuestionIndex, session.getCurrentQuestionIndex()));

        emitter.onTimeout(() -> {
            try {
                emitter.send(SseEmitter.event().name("error").data("出题超时"));
            } catch (Exception sendEx) {
                log.debug("出题超时通知发送失败(连接已关闭)", sendEx);
            }
            emitter.complete();
        });

        // 将完整 JSON 作为 token 推送，前端 composable 会累积后解析 [编程题目]
        new Thread(() -> {
            try {
                emitter.send(SseEmitter.event().name("token").data(codingProblem));
                emitter.send(SseEmitter.event().name("finish")
                    .data("{\"finished\":false,\"coding\":true}"));
                emitter.complete();
            } catch (Exception e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data("编程题生成失败"));
                    emitter.complete();
                } catch (Exception ex) { /* ignore */ }
            }
        }).start();

        return emitter;
    }
}
