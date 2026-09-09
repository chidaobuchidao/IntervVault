package com.mianmiantong.service.paper;

import com.mianmiantong.config.JwtAuthFilter;

import com.mianmiantong.dto.paper.AiReduceRequest;
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

/**
 * AI 写作痕迹检测与降 AI 改写服务。
 * 检测规则参考 humanizer-zh + paper-checker 项目。
 * 采用四级严重度体系：CRITICAL > HIGH > MEDIUM > STYLE。
 */
@Slf4j
@Service
public class AiReduceService {

    private final AiGateway aiGateway;

    public AiReduceService(AiGateway aiGateway) {
        this.aiGateway = aiGateway;
    }

    // ============================================================
    // CRITICAL (权重 x8) — 致命 AI 痕迹
    // ============================================================

    private static final List<String> MECHANICAL_CONNECTORS = List.of(
        "值得注意的是", "综上所述", "不难发现", "总而言之",
        "与此同时", "在此基础上", "由此可见", "不仅如此",
        "换句话说", "更重要的是", "需要强调的是", "不可否认",
        "显而易见", "不言而喻", "正如我们所知", "归根结底",
        "需要指出的是", "值得一提的是", "众所周知", "总的来说"
    );

    private static final List<String> EMPTY_GRAND_WORDS = List.of(
        "赋能", "闭环", "智慧时代", "数字化转型", "生态",
        "愿景", "顶层设计", "协同增效", "降本增效", "打通壁垒",
        "深度融合", "创新驱动", "全方位", "多维度", "系统性",
        "范式", "底层逻辑", "抓手", "链路", "触达",
        "赛道", "破圈", "出圈", "颠覆", "革新",
        "沉淀", "对齐", "拉通", "复盘", "迭代"
    );

    private static final List<Pattern> THREE_PART_REGEX = List.of(
        Pattern.compile("首先[，,].*?其次[，,].*?最后"),
        Pattern.compile("一方面[，,].*?另一方面"),
        Pattern.compile("第一[，,].*?第二[，,].*?第三"),
        Pattern.compile("其一[，,].*?其二[，,].*?其三")
    );

    // ============================================================
    // HIGH (权重 x4) — 强 AI 信号
    // ============================================================

    private static final List<String> AI_HIGH_FREQ_WORDS = List.of(
        "助力", "彰显", "凸显", "焕发", "深度剖析",
        "加持", "至关重要", "深入探讨", "强调",
        "格局", "织锦", "展示", "关键性",
        "充满活力", "复杂", "复杂性", "相互作用", "宝贵的",
        "为……做出贡献", "奠定", "培养", "促进", "涵盖",
        "增强", "确保", "致力于",
        "标志着", "见证了", "反映了更广泛的",
        "不可磨灭的印记", "奠定了坚实的基础"
    );

    private static final List<String> FILLER_PHRASES = List.of(
        "值得一提的是", "需要指出的是", "不得不说",
        "毫无疑问", "正因如此", "具体来说", "简而言之", "换言之",
        "值得注意的是", "在这个时间点", "在大多数情况下",
        "在某种程度上", "为了实现这一目标",
        "可以这么说", "一般而言", "从某种程度上来说"
    );

    private static final List<Pattern> TEMPLATE_REGEX = List.of(
        Pattern.compile("随着.{1,20}的(不断)?发展"),
        Pattern.compile("在.{1,20}的背景[之下]"),
        Pattern.compile("在当今.{1,10}时代"),
        Pattern.compile("作为.{1,20}的重要(组成部分|环节|手段)"),
        Pattern.compile("对于.{1,20}而言[，,].{0,5}至关重要"),
        Pattern.compile("这不仅.{1,20}更是"),
        Pattern.compile("从.{1,10}角度(来看|来说|而言)"),
        Pattern.compile("无论是.{1,15}还是.{1,15}都"),
        Pattern.compile("可以说[，,]"),
        Pattern.compile("总的来说[，,]"),
        Pattern.compile("本文(旨在|从|通过|以|围绕|基于|重点)"),
        Pattern.compile("我将(从|通过|以|用|围绕|基于|重点)"),
        Pattern.compile("本研究(旨在|试图|尝试|致力于|聚焦于)"),
        Pattern.compile("通过(对.{1,20})?的(分析|研究|考察|探讨|梳理|审视)"),
        Pattern.compile("具有.{0,10}(重要|深远|重大|独特|特殊|关键)(的)?(意义|价值|作用|影响)"),
        Pattern.compile("为.{1,20}(提供|奠定|创造|开辟|构建)(了)?(基础|条件|路径|方向|思路)")
    );

    private static final List<Pattern> BALANCED_ARGUMENT_REGEX = List.of(
        Pattern.compile("虽然.{1,30}但是.{1,30}同时"),
        Pattern.compile("一方面.{1,30}另一方面.{1,30}总的来说")
    );

    // ============================================================
    // MEDIUM (权重 x2) — 中等信号
    // ============================================================

    private static final List<String> AI_ACADEMIC_PHRASES = List.of(
        "本文旨在", "研究表明", "具有重要意义",
        "进行了深入分析", "具有重要的理论意义和实践价值",
        "为此本文", "鉴于此", "进行了全面的分析",
        "进行了系统的研究", "取得了显著的成效",
        "具有广阔的应用前景", "发挥着重要作用", "引起了广泛关注",
        "得到了广泛的应用", "提供了有力的支撑",
        "提出了切实可行的", "进行了有益的探索",
        "提供了重要的参考价值", "提供了新的思路",
        "开辟了新的途径", "做出了重要贡献",
        "具有一定的参考价值", "受到了学术界的广泛关注",
        "具有十分重要的现实意义", "有待进一步研究",
        "具有重要影响", "持续深入", "不断推进",
        "为相关领域", "奠定了良好基础"
    );

    private static final List<Pattern> PASSIVE_REGEX = List.of(
        Pattern.compile("被广泛(应用|使用|采用|运用|关注|认可|接受)"),
        Pattern.compile("被认为是"),
        Pattern.compile("被(视为|看作|称为|誉为)"),
        Pattern.compile("受到了?(广泛|高度|普遍|学界的?)(关注|重视|认可)")
    );

    private static final List<String> HEDGING_PHRASES = List.of(
        "在一定程度上", "或许", "某种程度上", "相对而言",
        "总体来说", "一般来说", "通常情况下",
        "可能潜在地可能", "或许在某种程度上也许"
    );

    // ============================================================
    // STYLE (权重 x1.5) — 风格信号
    // ============================================================

    private static final List<String> ENGLISH_TRANSITIONS = List.of(
        "however", "moreover", "furthermore", "additionally",
        "consequently", "nevertheless", "nonetheless",
        "subsequently", "accordingly",
        "it is important to note", "it should be noted",
        "it is worth mentioning", "in conclusion",
        "in summary", "to summarize"
    );

    // ============================================================
    // 连接词密度统计
    // ============================================================

    private static final List<String> FLOW_CONNECTORS = List.of(
        "因此", "然而", "同时", "此外", "另外", "由此可见", "综上",
        "进一步说", "相较之下", "具体而言", "换句话说", "也就是说",
        "总体而言", "大体上", "总而言之", "简而言之",
        "首先", "其次", "最后", "第一", "第二", "第三"
    );

    // ============================================================
    // 模糊匹配模板 (Levenshtein)
    // ============================================================

    private static final List<String> FUZZY_TEMPLATES = List.of(
        "我将从以下几个方面展开论述",
        "本文将从多个维度进行分析",
        "本文旨在探讨相关问题的深层原因",
        "在当前的背景下显得尤为重要",
        "随着技术的不断发展与进步",
        "通过深入分析可以发现其中的规律",
        "具有重要的理论意义和实践价值",
        "为后续研究提供了重要的参考依据",
        "综上所述可以得出以下结论",
        "未来的研究方向主要包括以下几个方面",
        "通过上述分析可以清晰地看到",
        "在此基础上进一步深入探讨",
        "不仅具有重要的学术价值也具有广泛的应用前景",
        "为相关领域的进一步探索奠定了基础",
        "本文通过系统的文献梳理和实证分析",
        "从而提出具有针对性的对策建议",
        "以期为相关研究提供参考与借鉴",
        "充分体现了理论与实践相结合的原则",
        "对于深入理解相关问题具有重要意义",
        "为推动该领域的进一步发展贡献力量",
        "值得注意的是这一现象背后的深层原因",
        "不可否认的是这一领域仍存在诸多挑战",
        "从多个角度对该问题进行了全面分析",
        "在一定程度上反映了当前研究的局限性",
        "为构建更加完善的理论体系提供了支撑",
        "经过系统的整理与分析可以发现",
        "对这一问题的探讨有助于深化我们的理解",
        "尽管取得了显著进展但仍面临诸多困难",
        "由此可见相关研究仍处于探索阶段",
        "这一发现对于后续研究具有启示意义"
    );

    // ============================================================
    // 替换词典（用于生成改写建议）
    // ============================================================

    private static final Map<String, String> REPLACEMENT_MAP = new LinkedHashMap<>();
    static {
        REPLACEMENT_MAP.put("值得注意的是", "要看到");
        REPLACEMENT_MAP.put("综上所述", "总的来说");
        REPLACEMENT_MAP.put("不难发现", "可以看到");
        REPLACEMENT_MAP.put("总而言之", "总之");
        REPLACEMENT_MAP.put("与此同时", "同时");
        REPLACEMENT_MAP.put("由此可见", "由此看来");
        REPLACEMENT_MAP.put("不仅如此", "不止如此");
        REPLACEMENT_MAP.put("更重要的是", "更关键的是");
        REPLACEMENT_MAP.put("不可否认", "不能否认");
        REPLACEMENT_MAP.put("显而易见", "很明显");
        REPLACEMENT_MAP.put("众所周知", "我们都知道");
        REPLACEMENT_MAP.put("赋能", "帮助/助力");
        REPLACEMENT_MAP.put("闭环", "完整流程");
        REPLACEMENT_MAP.put("深度融合", "深度结合");
        REPLACEMENT_MAP.put("创新驱动", "创新推动");
        REPLACEMENT_MAP.put("全方位", "全面");
        REPLACEMENT_MAP.put("多维度", "多角度");
        REPLACEMENT_MAP.put("系统性", "系统");
        REPLACEMENT_MAP.put("顶层设计", "整体设计");
        REPLACEMENT_MAP.put("降本增效", "降低成本提高效率");
        REPLACEMENT_MAP.put("打通壁垒", "消除障碍");
        REPLACEMENT_MAP.put("底层逻辑", "基本原理");
        REPLACEMENT_MAP.put("抓手", "切入点");
        REPLACEMENT_MAP.put("链路", "路径");
        REPLACEMENT_MAP.put("触达", "到达");
        REPLACEMENT_MAP.put("具有重要的意义", "具有重要意义");
        REPLACEMENT_MAP.put("进行了深入分析", "深入分析了");
        REPLACEMENT_MAP.put("取得了显著的成效", "取得了明显效果");
        REPLACEMENT_MAP.put("具有广阔的应用前景", "应用前景广阔");
        REPLACEMENT_MAP.put("奠定了坚实的基础", "打下了基础");
        REPLACEMENT_MAP.put("提供了有力的支撑", "提供了有力支持");
        REPLACEMENT_MAP.put("引起了广泛关注", "受到广泛关注");
    }

    // ============================================================
    // 扫描
    // ============================================================

    public AiScanResult scanAiFeatures(String text) {
        AiScanResult r = new AiScanResult();
        if (text == null || text.isBlank()) return r;

        String[] sentences = text.split("[。！？?!；;]");
        List<String> validSentences = Arrays.stream(sentences)
            .map(String::trim).filter(s -> s.length() > 4).collect(Collectors.toList());

        int cnChars = countChineseChars(text);

        // === CRITICAL (×8) ===
        int criticalScore = 0;

        for (String phrase : MECHANICAL_CONNECTORS) {
            int count = countOccurrences(text, phrase);
            if (count > 0) {
                r.addIssue("机械连接词", "critical", phrase, count);
                criticalScore += count;
            }
        }

        for (String word : EMPTY_GRAND_WORDS) {
            int count = countOccurrences(text, word);
            if (count > 0) {
                r.addIssue("空洞宏大词", "critical", word, count);
                criticalScore += count;
            }
        }

        for (Pattern pattern : THREE_PART_REGEX) {
            if (pattern.matcher(text).find()) {
                r.addIssue("三段式结构", "critical",
                    pattern.pattern().substring(0, Math.min(30, pattern.pattern().length())), 1);
                criticalScore++;
            }
        }

        // === HIGH (×4) ===
        int highScore = 0;

        for (String word : AI_HIGH_FREQ_WORDS) {
            int count = countOccurrences(text, word);
            if (count > 0) {
                r.addIssue("AI高频词", "high", word, count);
                highScore += count;
            }
        }

        for (String phrase : FILLER_PHRASES) {
            int count = countOccurrences(text, phrase);
            if (count > 0) {
                r.addIssue("填充套话", "high", phrase, count);
                highScore += count;
            }
        }

        for (Pattern pattern : TEMPLATE_REGEX) {
            long hits = pattern.matcher(text).results().count();
            if (hits > 0) {
                r.addIssue("模板句式", "high",
                    pattern.pattern().substring(0, Math.min(30, pattern.pattern().length())), (int) hits);
                highScore += (int) hits;
            }
        }

        for (Pattern pattern : BALANCED_ARGUMENT_REGEX) {
            if (pattern.matcher(text).find()) {
                r.addIssue("过度两面论", "high", "虽然…但是…同时…", 1);
                highScore++;
            }
        }

        // === MEDIUM (×2) ===
        int mediumScore = 0;

        for (String phrase : AI_ACADEMIC_PHRASES) {
            if (text.contains(phrase)) {
                r.addIssue("学术套话", "medium", phrase, 1);
                mediumScore++;
            }
        }

        for (Pattern pattern : PASSIVE_REGEX) {
            if (pattern.matcher(text).find()) {
                r.addIssue("被动句式", "medium",
                    pattern.pattern().substring(0, Math.min(30, pattern.pattern().length())), 1);
                mediumScore++;
            }
        }

        int hedgeCount = 0;
        for (String phrase : HEDGING_PHRASES) {
            hedgeCount += countOccurrences(text, phrase);
        }
        if (hedgeCount >= 3) {
            r.addIssue("谨慎用语密集", "medium", "在一定程度上/或许/某种程度上", hedgeCount);
            mediumScore += hedgeCount;
        }

        // === STYLE (×1.5) ===
        int styleScore = 0;

        // 段落长度均匀度
        String[] paragraphs = text.split("\n{2,}");
        List<String> validParas = Arrays.stream(paragraphs)
            .map(String::trim).filter(p -> p.length() > 20).collect(Collectors.toList());
        if (validParas.size() >= 3) {
            double avgP = validParas.stream().mapToInt(String::length).average().orElse(0);
            double varP = validParas.stream()
                .mapToDouble(p -> Math.pow(p.length() - avgP, 2)).average().orElse(0);
            if (avgP > 0) {
                double cv = Math.sqrt(varP) / avgP;
                if (cv < 0.2) {
                    r.addIssue("段落过于均匀", "style",
                        "变异系数CV=" + String.format("%.2f", cv) + "，缺少详略变化", 1);
                    styleScore += 3;
                }
            }
        }

        // 句子长度均匀度（突发性）
        if (validSentences.size() > 5) {
            double avgS = validSentences.stream().mapToInt(String::length).average().orElse(0);
            double varS = validSentences.stream()
                .mapToDouble(s -> Math.pow(s.length() - avgS, 2)).average().orElse(0);
            if (avgS > 0) {
                double cv = Math.sqrt(varS) / avgS;
                if (cv < 0.25) {
                    r.addIssue("句长过于均匀", "style",
                        "变异系数CV=" + String.format("%.2f", cv) + "，缺少节奏变化", 1);
                    styleScore += 3;
                }
            }
        }

        // 重复句首
        Map<String, Integer> starters = new LinkedHashMap<>();
        for (String s : validSentences) {
            if (s.length() >= 2) {
                String st = s.substring(0, Math.min(2, s.length()));
                starters.merge(st, 1, Integer::sum);
            }
        }
        int maxRepeat = starters.values().stream().max(Integer::compareTo).orElse(0);
        if (maxRepeat >= 3) {
            String top = starters.entrySet().stream()
                .filter(e -> e.getValue() == maxRepeat).map(Map.Entry::getKey)
                .findFirst().orElse("");
            r.addIssue("重复句首", "style", "\"" + top + "…\" 出现 " + maxRepeat + " 次", 1);
            styleScore += 2;
        }

        // 英文过渡词密度
        String lowerText = text.toLowerCase();
        int enTransCount = 0;
        for (String et : ENGLISH_TRANSITIONS) {
            enTransCount += countOccurrences(lowerText, et.toLowerCase());
        }
        if (enTransCount > 3) {
            r.addIssue("英文过渡词密度", "style",
                "英文过渡词出现 " + enTransCount + " 次", enTransCount);
            styleScore += Math.min(enTransCount, 5);
        }

        // === 字符熵 ===
        double entropy = charEntropy(text);
        r.entropy = Math.round(entropy * 100.0) / 100.0;
        if (cnChars > 200 && entropy < 5.5) {
            r.addIssue("字符熵偏低", "style",
                "熵=" + String.format("%.2f", entropy) + "（越低越可预测，AI文本通常<5.5）", 1);
            styleScore += 4;
        }

        // === 连接词密度 ===
        long connectorCount = FLOW_CONNECTORS.stream()
            .mapToLong(c -> countOccurrences(text, c)).sum();
        if (connectorCount > validSentences.size() * 0.35 && !validSentences.isEmpty()) {
            r.addIssue("连接词密集", "style",
                "共出现 " + connectorCount + " 次，显得机械套话", (int) connectorCount);
            styleScore += 3;
        }

        // === 模糊匹配 ===
        int fuzzyHits = 0;
        for (String sentence : validSentences) {
            List<String> matched = fuzzyMatchSentence(sentence, 0.35);
            fuzzyHits += matched.size();
            if (!matched.isEmpty() && r.sentencesFlagged.size() < 15) {
                String flag = sentence.length() > 60 ? sentence.substring(0, 60) + "…" : sentence;
                if (!r.sentencesFlagged.contains(flag)) {
                    r.sentencesFlagged.add(flag);
                }
            }
        }
        if (fuzzyHits > 0) {
            r.addIssue("句式模糊匹配", "high",
                fuzzyHits + " 处与AI模板高度相似（相似度>65%）", fuzzyHits);
            highScore += Math.min(fuzzyHits, 5);
        }

        // === 综合评分 ===
        // 规则分：CRITICAL×8 + HIGH×4 + MEDIUM×2 + STYLE×1.5（上限75）
        int ruleScore = (int) Math.min(75,
            criticalScore * 8 + highScore * 4 + mediumScore * 2 + styleScore * 1.5);

        // 统计分：熵 + 突发性 + 均匀度（上限25）
        int statScore = 0;
        if (entropy < 5.5) statScore += 8;
        if (entropy < 5.0) statScore += 5;
        // 检查突发性
        if (validSentences.size() > 5) {
            double avgS = validSentences.stream().mapToInt(String::length).average().orElse(0);
            double varS = validSentences.stream()
                .mapToDouble(s -> Math.pow(s.length() - avgS, 2)).average().orElse(0);
            if (avgS > 0) {
                double cv = Math.sqrt(varS) / avgS;
                if (cv < 0.25) statScore += 7;
                if (cv < 0.15) statScore += 5;
            }
        }
        statScore = Math.min(25, statScore);

        r.score = Math.min(100, ruleScore + statScore);

        if (r.score >= 50) r.riskLevel = "高风险";
        else if (r.score >= 25) r.riskLevel = "中风险";
        else r.riskLevel = "低风险";

        // 拉取分级统计
        r.criticalCount = criticalScore;
        r.highCount = highScore;
        r.mediumCount = mediumScore;
        r.styleCount = styleScore;

        // 生成标记句 + 建议
        for (String s : validSentences) {
            boolean flagged = false;
            String suggestion = null;

            for (String phrase : MECHANICAL_CONNECTORS) {
                if (s.contains(phrase) && r.sentencesFlagged.size() < 15) {
                    String shortText = s.length() > 60 ? s.substring(0, 60) + "…" : s;
                    String repl = REPLACEMENT_MAP.getOrDefault(phrase, "删除此类机械连接词，直接陈述观点");
                    r.addFlaggedSentence(shortText, "机械连接词: " + phrase, repl);
                    flagged = true;
                    break;
                }
            }

            if (!flagged) {
                for (String word : EMPTY_GRAND_WORDS) {
                    if (s.contains(word) && r.sentencesFlagged.size() < 15) {
                        String shortText = s.length() > 60 ? s.substring(0, 60) + "…" : s;
                        String repl = REPLACEMENT_MAP.getOrDefault(word,
                            "用更朴素/具体的词汇替代，如\"" + word + "\"→更直白的表达");
                        r.addFlaggedSentence(shortText, "空洞宏大词: " + word, repl);
                        flagged = true;
                        break;
                    }
                }
            }

            if (!flagged && r.sentencesFlagged.size() < 15) {
                for (Pattern pattern : TEMPLATE_REGEX) {
                    if (pattern.matcher(s).find()) {
                        String shortText = s.length() > 60 ? s.substring(0, 60) + "…" : s;
                        r.addFlaggedSentence(shortText,
                            "模板句式匹配",
                            "重组句式结构，用更具体/个性化的表达替代模板化句式");
                        flagged = true;
                        break;
                    }
                }
            }
        }

        // 生成摘要信息
        if (r.criticalCount > 0) {
            r.aiSignals.add("检测到 " + r.criticalCount + " 个致命AI痕迹（机械连接词、空洞宏大词、三段式结构）");
        }
        if (r.highCount > 0) {
            r.aiSignals.add("检测到 " + r.highCount + " 个强AI信号（高频词、模板句式、套话等）");
        }
        if (entropy < 5.5) {
            r.aiSignals.add("字符熵偏低（" + String.format("%.2f", entropy) + "），用词可预测性高，呈AI文本特征");
        }

        if (r.criticalCount == 0) r.humanSignals.add("未检测到机械连接词和空洞宏大词");
        if (entropy > 6.5) r.humanSignals.add("字符熵正常（" + String.format("%.2f", entropy) + "），用词多样性良好");
        if (styleScore <= 2) r.humanSignals.add("句式节奏自然，未发现明显风格问题");

        return r;
    }

    // ============================================================
    // 字符熵计算（参考 paper-checker aigc-v2.ts）
    // ============================================================

    private double charEntropy(String text) {
        String chars = text.replaceAll("[^\\u4e00-\\u9fff]", "");
        if (chars.length() < 10) return 5.0;

        Map<String, Integer> bigrams = new LinkedHashMap<>();
        for (int i = 0; i < chars.length() - 1; i++) {
            String bg = chars.substring(i, i + 2);
            bigrams.merge(bg, 1, Integer::sum);
        }

        int total = bigrams.values().stream().mapToInt(Integer::intValue).sum();
        if (total == 0) return 5.0;

        double entropy = 0;
        for (int count : bigrams.values()) {
            double p = (double) count / total;
            if (p > 0) entropy -= p * Math.log(p) / Math.log(2);
        }
        return entropy;
    }

    private int countChineseChars(String text) {
        return (int) text.codePoints()
            .filter(c -> c >= 0x4e00 && c <= 0x9fff).count();
    }

    // ============================================================
    // Levenshtein 模糊匹配
    // ============================================================

    private int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(
                    dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    private double normalizedLevenshtein(String a, String b) {
        if (a.isEmpty() && b.isEmpty()) return 0;
        int maxLen = Math.max(a.length(), b.length());
        return maxLen == 0 ? 0 : (double) levenshtein(a, b) / maxLen;
    }

    private List<String> fuzzyMatchSentence(String sentence, double threshold) {
        List<String> hits = new ArrayList<>();
        String trimmed = sentence.trim();
        if (trimmed.length() < 6) return hits;
        for (String template : FUZZY_TEMPLATES) {
            int maxL = Math.max(trimmed.length(), template.length());
            int minL = Math.min(trimmed.length(), template.length());
            if ((double) minL / maxL < 0.45) continue;
            if (normalizedLevenshtein(trimmed, template) < threshold) {
                hits.add(template);
            }
        }
        return hits;
    }

    // ============================================================
    // SSE 流式改写
    // ============================================================

    public SseEmitter rewrite(String text, String mode, String model,
                               List<AiReduceRequest.FlaggedSentence> flaggedSentences,
                               List<com.mianmiantong.dto.paper.ContextChunk> contextChunks) {
        SseEmitter emitter = new SseEmitter(120_000L);

        Map<String, String> modeLabels = Map.of("light", "轻度去痕", "deep", "深度重构", "academic", "学术拟合");
        String modeLabel = modeLabels.getOrDefault(mode != null ? mode : "light", "轻度去痕");
        String safeMode = mode != null ? mode : "light";
        String safeText = text != null ? text : "";

        String flaggedPassages = buildFlaggedSentences(flaggedSentences);
        var sanitizedChunks = PaperContextSanitizer.sanitize(contextChunks);
        String contextBlock = PaperContextSanitizer.formatForPrompt(sanitizedChunks);

        String systemPrompt = getSystemPrompt("prompts/ai_reduce_transform.txt");
        String userPrompt = renderPrompt("prompts/ai_reduce_transform.txt", Map.of(
            "text", safeText, "mode", safeMode, "mode_label", modeLabel,
            "flagged_passages", flaggedPassages, "context_chunks", contextBlock
        ));

        List<ChatMessage> messages = List.of(
            new ChatMessage("user", userPrompt)
        );

        emitter.onTimeout(() -> {
            safeSend(emitter, "error", "去AI改写超时");
            emitter.complete();
        });

        Long usageUserId = JwtAuthFilter.getCurrentUserId();
        CompletableFuture.runAsync(() -> {
            try {
                AiRequest aiRequest = new AiRequest(systemPrompt, messages, model, AiTaskType.FLASH).withUsage(usageUserId, "AI_REDUCE");
                aiGateway.streamChat(aiRequest, null, token ->
                    safeSend(emitter, "token", token)
                );
                emitter.send(SseEmitter.event().name("finish")
                    .data("{\"phase\":\"ai_reduce\"}"));
                emitter.complete();
            } catch (Exception e) {
                log.error("AI reduce SSE stream failed", e);
                safeSend(emitter, "error", e.getMessage() != null ? e.getMessage() : "去AI改写失败");
                emitter.complete();
            }
        });

        return emitter;
    }

    private String buildFlaggedSentences(List<AiReduceRequest.FlaggedSentence> flaggedSentences) {
        if (flaggedSentences == null || flaggedSentences.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        int idx = 1;
        for (var fs : flaggedSentences) {
            String text = fs.getText();
            if (text == null || text.isBlank()) continue;
            String reason = fs.getReason() != null ? fs.getReason() : "";
            String excerpt = text.length() > 120 ? text.substring(0, 120) + "…" : text;
            sb.append(idx).append(". ").append(excerpt);
            if (!reason.isBlank()) sb.append("  — ").append(reason);
            sb.append("\n");
            idx++;
        }
        return sb.toString();
    }

    // ============================================================
    // Prompt 加载
    // ============================================================

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
            return "你是一位资深学术写作编辑。";
        }
    }

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

    private void safeSend(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException ignored) {}
    }

    private int countOccurrences(String text, String word) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(word, idx)) != -1) { count++; idx += word.length(); }
        return count;
    }

    // ============================================================
    // Inner classes
    // ============================================================

    @lombok.Data
    public static class AiScanResult {
        private int score;
        private String riskLevel = "低风险";
        private int criticalCount;
        private int highCount;
        private int mediumCount;
        private int styleCount;
        private double entropy;
        private List<AiScanIssue> issues = new ArrayList<>();
        private List<String> sentencesFlagged = new ArrayList<>();
        /** 标记句 + 改写建议（结构化） */
        private List<FlaggedSentence> flaggedSentences = new ArrayList<>();
        private List<String> aiSignals = new ArrayList<>();
        private List<String> humanSignals = new ArrayList<>();

        void addIssue(String category, String severity, String text, int count) {
            AiScanIssue issue = new AiScanIssue();
            issue.category = category;
            issue.severity = severity;
            issue.text = text;
            issue.count = count;
            issues.add(issue);
        }

        void addFlaggedSentence(String text, String reason, String suggestion) {
            FlaggedSentence fs = new FlaggedSentence();
            fs.text = text;
            fs.reason = reason;
            fs.suggestion = suggestion;
            // 同时维护旧字段兼容
            if (sentencesFlagged.size() < 20) {
                sentencesFlagged.add(text + "  — " + reason);
            }
            flaggedSentences.add(fs);
        }
    }

    @lombok.Data
    public static class AiScanIssue {
        private String category;
        private String severity; // critical | high | medium | style
        private String text;
        private int count;
    }

    @lombok.Data
    public static class FlaggedSentence {
        private String text;
        private String reason;
        private String suggestion;
    }
}
