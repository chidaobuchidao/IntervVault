package com.mianmiantong.controller.paper;

import com.mianmiantong.service.document.TemplatePreservingExportService;
import com.mianmiantong.service.document.ParagraphProfile;
import com.mianmiantong.service.document.DocumentAiService;
import com.mianmiantong.service.document.DocumentParseResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/paper")
public class PaperUploadController {

    /** DocMind OCR 轮询间隔（毫秒） */
    private static final int DOCMIND_POLL_INTERVAL_MS = 2000;
    /** DocMind OCR 最大轮询次数（2s × 15 = 30s 总超时） */
    private static final int DOCMIND_MAX_POLL_ATTEMPTS = 15;

    private final TemplatePreservingExportService exportService;
    private final DocumentAiService documentAiService;

    public PaperUploadController(TemplatePreservingExportService exportService,
                                  DocumentAiService documentAiService) {
        this.exportService = exportService;
        this.documentAiService = documentAiService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> uploadFile(@RequestParam("file") MultipartFile file) {
        try {
            String originalName = file.getOriginalFilename();
            String lowerName = originalName != null ? originalName.toLowerCase() : "";
            byte[] fileBytes = file.getBytes();

            List<Map<String, Object>> paraList;
            String fullText;

            if (lowerName.endsWith(".docx")) {
                // 修复旧版 WPS/Word 文档的错误编码（XML 声明 UTF-8 但实际是 GBK）
                fileBytes = fixDocxEncodingIfNeeded(fileBytes);
                List<ParagraphProfile> paragraphs = exportService.parseParagraphs(fileBytes);
                // 如果段落解析为空，尝试回退全文本提取
                if (paragraphs.isEmpty()) {
                    fullText = exportService.extractFallbackText(fileBytes);
                    if (fullText.isBlank()) {
                        return ResponseEntity.badRequest().body(Map.of("error", "文档解析失败：未提取到文本内容，请检查文档格式或尝试另存为标准 .docx"));
                    }
                    // 将回退文本按段落拆分为简单段落列表
                    String[] parts = fullText.split("\n\n");
                    paraList = new ArrayList<>();
                    for (int i = 0; i < parts.length; i++) {
                        String t = parts[i].trim();
                        if (t.length() > 3) {
                            paraList.add(Map.of("index", i, "text", t));
                        }
                    }
                } else {
                    fullText = paragraphs.stream()
                        .map(ParagraphProfile::text)
                        .collect(Collectors.joining("\n\n"));
                    paraList = paragraphs.stream()
                        .map(p -> {
                            Map<String, Object> m = new LinkedHashMap<>();
                            m.put("index", p.index());
                            m.put("text", p.text());
                            m.put("styleId", p.styleId() != null ? p.styleId() : "");
                            m.put("fontFamily", p.fontFamily() != null ? p.fontFamily() : "");
                            m.put("fontSize", p.fontSize() != null ? p.fontSize() : 12);
                            m.put("bold", p.bold());
                            m.put("italic", p.italic());
                            m.put("alignment", p.alignment());
                            return m;
                        })
                        .collect(Collectors.toList());
                }
            } else if (lowerName.endsWith(".pdf")) {
                paraList = extractPdfParagraphs(fileBytes);
                fullText = paraList.stream()
                    .map(p -> (String) p.get("text"))
                    .collect(Collectors.joining("\n\n"));
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "不支持的文件格式，请上传 .docx 或 .pdf 文件"));
            }

            // 文件解析完毕即丢弃，不落盘不缓存，隐私零持久化
            return ResponseEntity.ok(Map.of(
                "fullText", fullText,
                "paragraphs", paraList,
                "paragraphCount", paraList.size(),
                "fileType", lowerName.endsWith(".pdf") ? "pdf" : "docx"
            ));
        } catch (IOException e) {
            log.error("Failed to parse uploaded file", e);
            return ResponseEntity.badRequest().body(Map.of("error", "文件解析失败: " + e.getMessage()));
        }
    }

    /** Extract text from PDF and split into paragraphs */
    private List<Map<String, Object>> extractPdfParagraphs(byte[] fileBytes) throws IOException {
        List<Map<String, Object>> paragraphs = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(fileBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String fullText = stripper.getText(document);

            // Split by blank lines and numbered sections
            String[] parts = fullText.split("\\n\\s*\\n");
            int index = 0;
            for (String part : parts) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) continue;
                // Further split long paragraphs at numbered patterns (1. 2. or 1、2、etc.)
                String[] subParts = trimmed.split("(?=\\n(?=\\d+[.、．)）]))");
                for (String sub : subParts) {
                    String text = sub.trim().replaceAll("\\s+", " ");
                    if (text.length() > 5) {
                        paragraphs.add(Map.of("index", index++, "text", text));
                    }
                }
            }
        }
        return paragraphs;
    }

    /**
     * 解析检测报告（AIGC报告 / 查重报告）的 PDF 打印版或 Word 颜色标记版。
     * 返回被标记/高亮的文本段落列表，供前端映射到原文做针对性优化。
     */
    @PostMapping("/report-analyze")
    public ResponseEntity<Map<String, Object>> analyzeReport(@RequestParam("file") MultipartFile file,
                                                             @RequestParam(value = "sourceText", required = false) String sourceText) {
        try {
            String lowerName = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
            byte[] fileBytes = file.getBytes();
            List<Map<String, Object>> annotations;

            boolean fallbackUsed = false;
            if (lowerName.endsWith(".docx")) {
                annotations = extractDocxAnnotations(fileBytes);
            } else if (lowerName.endsWith(".pdf")) {
                annotations = extractPdfAnnotations(fileBytes);
                // PDFBox 提取为空（图片型PDF）→ 回退阿里云DocMind
                if (annotations.isEmpty()) {
                    annotations = extractPdfViaDocMind(fileBytes);
                    fallbackUsed = !annotations.isEmpty();
                }
            } else {
                return ResponseEntity.badRequest().body(Map.of("error", "不支持的报告格式，请上传 .docx 或 .pdf"));
            }

            // 如果提供了原文，用 LCS 从报告中提取与原文重叠的片段（即报告中引用的论文内容）
            if (sourceText != null && !sourceText.isBlank()) {
                List<Map<String, Object>> matched = extractOverlappingFragments(fileBytes, lowerName, sourceText);
                if (!matched.isEmpty()) {
                    annotations = mergeAnnotations(annotations, matched);
                }
            }

            return ResponseEntity.ok(Map.of(
                "annotations", annotations,
                "totalFlagged", annotations.size(),
                "riskBreakdown", summarizeRisk(annotations),
                "fallbackUsed", fallbackUsed
            ));
        } catch (IOException e) {
            log.error("Failed to parse report file", e);
            return ResponseEntity.badRequest().body(Map.of("error", "报告解析失败: " + e.getMessage()));
        }
    }

    /** 从 Word 颜色标记版中提取红色/黄色高亮的文本 */
    private List<Map<String, Object>> extractDocxAnnotations(byte[] fileBytes) throws IOException {
        List<Map<String, Object>> annotations = new ArrayList<>();
        try (org.apache.poi.xwpf.usermodel.XWPFDocument doc =
                 new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(fileBytes))) {

            for (org.apache.poi.xwpf.usermodel.XWPFParagraph para : doc.getParagraphs()) {
                for (org.apache.poi.xwpf.usermodel.XWPFRun run : para.getRuns()) {
                    String text = run.getText(0);
                    if (text == null || text.trim().length() < 4) continue;

                    String color = run.getColor();
                    String risk = classifyColor(color);
                    if (risk != null) {
                        annotations.add(Map.of(
                            "text", text.trim(),
                            "riskLevel", risk,
                            "color", color != null ? color : "unknown",
                            "source", "docx-color-mark"
                        ));
                    }

                    // 也检查高亮（highlight）
                    if (run.isHighlighted()) {
                        annotations.add(Map.of(
                            "text", text.trim(),
                            "riskLevel", "medium",
                            "color", "highlight",
                            "source", "docx-highlight"
                        ));
                    }
                }
            }
        }
        return annotations;
    }

    /** 从 PDF 中提取标注文本。仅支持文本型 PDF，图片型 PDF 返回空列表。 */
    private List<Map<String, Object>> extractPdfAnnotations(byte[] fileBytes) throws IOException {
        List<Map<String, Object>> annotations = new ArrayList<>();
        try (org.apache.pdfbox.pdmodel.PDDocument document =
                 org.apache.pdfbox.Loader.loadPDF(fileBytes)) {

            org.apache.pdfbox.text.PDFTextStripper stripper = new org.apache.pdfbox.text.PDFTextStripper();
            stripper.setSortByPosition(true);
            String fullText = stripper.getText(document);

            if (fullText == null || fullText.isBlank()) {
                log.warn("PDF is image-based, no extractable text. {} pages.", document.getNumberOfPages());
                return annotations;
            }

            log.info("PDF text extracted: {} chars", fullText.length());

            // 按段落扫描 AIGC / 查重报告关键词
            String[] paragraphs = fullText.split("\\n\\s*\\n");
            for (String para : paragraphs) {
                String trimmed = para.trim();
                if (trimmed.length() < 15 || trimmed.length() > 500) continue;
                if (isMetaLine(trimmed)) continue;

                String riskLevel = classifyAigcRisk(trimmed);
                if (riskLevel == null) riskLevel = classifyPlagiarismRisk(trimmed);

                if (riskLevel != null) {
                    String excerpt = trimmed.length() > 150 ? trimmed.substring(0, 150) + "..." : trimmed;
                    annotations.add(Map.of(
                        "text", excerpt,
                        "riskLevel", riskLevel,
                        "source", "pdf"
                    ));
                }
            }
        }
        return annotations;
    }

    private String classifyAigcRisk(String text) {
        for (String kw : AIGC_HIGH) { if (text.contains(kw)) return "high"; }
        for (String kw : AIGC_MEDIUM) { if (text.contains(kw)) return "medium"; }
        for (String kw : AIGC_LOW) { if (text.contains(kw)) return "low"; }
        for (String kw : AIGC_ANY) { if (text.contains(kw)) return "medium"; }
        return null;
    }

    private String classifyPlagiarismRisk(String text) {
        for (String kw : PLAG_HIGH) { if (text.contains(kw)) return "high"; }
        for (String kw : PLAG_MEDIUM) { if (text.contains(kw)) return "medium"; }
        return null;
    }

    private static final List<String> AIGC_HIGH = List.of(
        "高度疑似AI", "高度疑似人工智能", "高风险", "AI生成概率高",
        "极有可能由AI", "疑似AI生成", "疑似人工智能生成"
    );
    private static final List<String> AIGC_MEDIUM = List.of(
        "中度疑似", "中风险", "可能由AI", "AI参与", "中等风险"
    );
    private static final List<String> AIGC_LOW = List.of(
        "低度疑似", "低风险", "可能为人工", "AI生成概率低"
    );
    private static final List<String> AIGC_ANY = List.of(
        "AI生成", "AIGC", "人工智能生成", "AI写作", "AI痕迹", "AI检测"
    );
    private static final List<String> PLAG_HIGH = List.of(
        "重复率较高", "高度重复", "标红段落", "重复比例高", "相似度较高"
    );
    private static final List<String> PLAG_MEDIUM = List.of(
        "部分重复", "中等重复", "相似段落", "中度相似"
    );

    private boolean isMetaLine(String text) {
        String t = text.trim();
        if (t.length() < 15) return true;
        if (t.matches(".*第\\s*\\d+\\s*页.*")) return true;
        if (t.matches("^\\d+\\s*/\\s*\\d+$")) return true;
        if (t.contains("版权所有") || t.contains("机密")) return true;
        if (t.startsWith("http")) return true;
        return false;
    }

    /** 阿里云 DocMind 回退：图片型 PDF → 云端 OCR → 关键词匹配 */
    private List<Map<String, Object>> extractPdfViaDocMind(byte[] fileBytes) {
        try {
            log.info("Attempting DocMind cloud fallback for image-based PDF");
            ByteArrayInputStream stream = new ByteArrayInputStream(fileBytes);
            String taskId = documentAiService.submitParse(stream, "report.pdf");
            stream.close();

            // 轮询等待解析完成，最长 30 秒
            for (int i = 0; i < DOCMIND_MAX_POLL_ATTEMPTS; i++) {
                try { Thread.sleep(DOCMIND_POLL_INTERVAL_MS); } catch (InterruptedException e) { break; }
                DocumentParseResult result = documentAiService.getResult(taskId);
                if ("SUCCESS".equals(result.getStatus())) {
                    String text = result.getParsedText();
                    log.info("DocMind parsed {} chars", text != null ? text.length() : 0);
                    if (text != null && !text.isBlank()) {
                        return matchKeywords(text);
                    }
                    break;
                }
            }
            log.warn("DocMind parsing timed out or returned empty for taskId={}", taskId);
        } catch (Exception e) {
            log.warn("DocMind cloud fallback failed: {}", e.getMessage());
        }
        return List.of();
    }

    /** 对文本执行 AIGC/查重关键词匹配（复用本地提取逻辑） */
    private List<Map<String, Object>> matchKeywords(String text) {
        List<Map<String, Object>> annotations = new ArrayList<>();
        String[] paragraphs = text.split("\\n\\s*\\n");
        for (String para : paragraphs) {
            String trimmed = para.trim();
            if (trimmed.length() < 15 || trimmed.length() > 500) continue;
            if (isMetaLine(trimmed)) continue;
            String riskLevel = classifyAigcRisk(trimmed);
            if (riskLevel == null) riskLevel = classifyPlagiarismRisk(trimmed);
            if (riskLevel != null) {
                String excerpt = trimmed.length() > 150 ? trimmed.substring(0, 150) + "..." : trimmed;
                annotations.add(Map.of(
                    "text", excerpt,
                    "riskLevel", riskLevel,
                    "source", "docmind"
                ));
            }
        }
        return annotations;
    }

    /** 根据 Word 中的文字颜色判断风险等级 */
    private String classifyColor(String color) {
        if (color == null) return null;
        String c = color.toUpperCase().replace("#", "");
        // 红色系 → 高风险
        if (c.startsWith("FF") || c.startsWith("FE") || c.startsWith("FD")
            || c.startsWith("DC") || c.startsWith("C0") || c.startsWith("CC")
            || c.equals("RED") || c.startsWith("E")) {
            return "high";
        }
        // 黄色/橙色系 → 中风险
        if (c.startsWith("FFC") || c.startsWith("FFD") || c.startsWith("FFE")
            || c.startsWith("FF8") || c.startsWith("FF9") || c.startsWith("FFA")
            || c.startsWith("FFB") || c.equals("YELLOW") || c.startsWith("F5")) {
            return "medium";
        }
        return null;
    }

    /** 将报告中的标注映射到原文（用最长公共子串匹配） */
    private List<Map<String, Object>> mapAnnotationsToSource(List<Map<String, Object>> annotations, String sourceText) {
        for (Map<String, Object> ann : annotations) {
            String reportText = (String) ann.get("text");
            if (reportText == null || reportText.length() < 5) continue;

            // 在原文中找最匹配的段落
            String bestMatch = findBestMatch(reportText, sourceText);
            if (bestMatch != null) {
                ann.put("matchedSourceText", bestMatch);
                ann.put("matched", true);
            }
        }
        return annotations;
    }

    /** 从报告中提取与原文重叠的文本片段（LCS匹配，找出报告中引用的论文内容） */
    private List<Map<String, Object>> extractOverlappingFragments(byte[] fileBytes, String lowerName, String sourceText) {
        List<Map<String, Object>> fragments = new ArrayList<>();
        try {
            String reportText = "";
            if (lowerName.endsWith(".pdf")) {
                try (PDDocument doc = Loader.loadPDF(fileBytes)) {
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setSortByPosition(true);
                    reportText = stripper.getText(doc);
                }
            } else if (lowerName.endsWith(".docx")) {
                try (org.apache.poi.xwpf.usermodel.XWPFDocument doc =
                         new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(fileBytes))) {
                    StringBuilder sb = new StringBuilder();
                    for (org.apache.poi.xwpf.usermodel.XWPFParagraph p : doc.getParagraphs()) {
                        String t = p.getText(); if (t != null && !t.isBlank()) sb.append(t).append("\n");
                    }
                    reportText = sb.toString();
                }
            }
            if (reportText.isBlank()) return fragments;

            // 用滑动窗口 LCS 找报告中与原文重叠的长片段（≥30字）
            fragments = findLcsFragments(reportText, sourceText);
            log.info("LCS extracted {} overlapping fragments from report", fragments.size());
        } catch (Exception e) {
            log.warn("Failed to extract overlapping fragments: {}", e.getMessage());
        }
        return fragments;
    }

    /** 找两个文本中所有 ≥30 字的重叠片段 */
    private List<Map<String, Object>> findLcsFragments(String reportText, String sourceText) {
        List<Map<String, Object>> fragments = new ArrayList<>();
        int minLen = 30;
        int reportLen = reportText.length();
        int sourceLen = sourceText.length();

        // 使用简化的滑动窗口：对 sourceText 中每个位置，找与 reportText 的最长公共子串
        // 为效率，只在 reportText 中采样关键位置
        for (int si = 0; si < sourceLen - minLen; si += minLen / 2) {
            String snippet = sourceText.substring(si, Math.min(si + 80, sourceLen));
            // 在 reportText 中找这个 snippet 的最长匹配前缀
            int bestLen = 0;
            for (int rj = 0; rj < reportLen - minLen; rj++) {
                int k = 0;
                while (k < snippet.length() && si + k < sourceLen && rj + k < reportLen
                    && sourceText.charAt(si + k) == reportText.charAt(rj + k)) {
                    k++;
                }
                if (k > bestLen) bestLen = k;
                if (bestLen >= snippet.length()) break;
            }
            if (bestLen >= minLen) {
                String matched = sourceText.substring(si, si + bestLen);
                // 去重
                boolean duplicate = false;
                for (Map<String, Object> f : fragments) {
                    String existing = (String) f.get("text");
                    if (existing != null && (existing.contains(matched) || matched.contains(existing))) {
                        if (matched.length() > existing.length()) f.put("text", matched);
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    fragments.add(Map.of(
                        "text", matched,
                        "riskLevel", "medium",
                        "source", "lcs-overlap"
                    ));
                }
                si += bestLen - minLen / 2; // 跳过已匹配区域
            }
        }
        return fragments;
    }

    /** 合并关键词标注和 LCS 重叠片段，去重 */
    private List<Map<String, Object>> mergeAnnotations(List<Map<String, Object>> keywordAnnotations,
                                                        List<Map<String, Object>> lcsFragments) {
        List<Map<String, Object>> merged = new ArrayList<>(lcsFragments);
        for (Map<String, Object> ann : keywordAnnotations) {
            String text = (String) ann.get("text");
            if (text == null || text.length() < 10) continue;
            boolean duplicate = false;
            for (Map<String, Object> existing : merged) {
                String existingText = (String) existing.get("text");
                if (existingText != null && (existingText.contains(text) || text.contains(existingText))) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) merged.add(ann);
        }
        return merged;
    }

    /** 在原文中找到与报告片段最匹配的文本 */
    private String findBestMatch(String fragment, String source) {
        if (source.contains(fragment)) return fragment;
        // 用滑动窗口找最长公共子串
        String[] sourceChars = source.split("");
        String[] fragChars = fragment.split("");
        int maxLen = 0, bestStart = 0;
        int[] dp = new int[fragChars.length + 1];
        for (int i = 1; i <= sourceChars.length; i++) {
            for (int j = fragChars.length; j >= 1; j--) {
                if (sourceChars[i - 1].equals(fragChars[j - 1])) {
                    dp[j] = dp[j - 1] + 1;
                    if (dp[j] > maxLen) {
                        maxLen = dp[j];
                        bestStart = i - maxLen;
                    }
                } else {
                    dp[j] = 0;
                }
            }
        }
        if (maxLen > 20) {
            return source.substring(bestStart, bestStart + maxLen);
        }
        return null;
    }

    /**
     * 修复双重编码的 w:t 文本内容。
     * 只对 XML 中 <w:t> 标签内的文本做 Latin-1→GB18030 绕过，不影响 XML 结构。
     */
    private byte[] fixDoubleEncodedText(byte[] documentXml) {
        try {
            String xml = new String(documentXml, java.nio.charset.StandardCharsets.UTF_8);
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(<w:t[^>]*>)([^<]*)(</w:t>)");
            java.util.regex.Matcher matcher = pattern.matcher(xml);
            StringBuffer sb = new StringBuffer();
            boolean anyFixed = false;

            while (matcher.find()) {
                String tagOpen = matcher.group(1);
                String text = matcher.group(2);
                String tagClose = matcher.group(3);

                if (text.length() > 1) {
                    // 字符→低字节→GB18030 解码
                    byte[] raw = new byte[text.length()];
                    boolean allLatin = true;
                    for (int i = 0; i < text.length(); i++) {
                        char c = text.charAt(i);
                        if (c >= 256) { allLatin = false; break; }
                        raw[i] = (byte) c;
                    }
                    if (allLatin) {
                        try {
                            String fixed = new String(raw, java.nio.charset.Charset.forName("GB18030"));
                            long cn = fixed.codePoints().filter(c -> c >= 0x4E00 && c <= 0x9FFF).count();
                            if (cn > 0) {
                                text = fixed;
                                anyFixed = true;
                            }
                        } catch (Exception ignored) {}
                    }
                }
                matcher.appendReplacement(sb,
                    java.util.regex.Matcher.quoteReplacement(tagOpen + text + tagClose));
            }
            matcher.appendTail(sb);

            return anyFixed ? sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8) : null;
        } catch (Exception e) {
            log.warn("双重编码修复失败: {}", e.getMessage());
            return null;
        }
    }

    /** 尝试用指定编码解码 XML 字节，失败则返回原字节 */
    private byte[] tryDecode(byte[] xmlBytes, String charset) {
        try {
            String decoded = new String(xmlBytes, java.nio.charset.Charset.forName(charset));
            // 检查解码后是否仍然大量乱码
            long garbled = decoded.codePoints().filter(c -> c == 0xFFFD).count();
            if (garbled > 10) {
                log.info("{} 解码仍乱码: garbled={}, 尝试 GB18030", charset, garbled);
                decoded = new String(xmlBytes, java.nio.charset.Charset.forName("GB18030"));
            }
            return decoded.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("{} 解码失败: {}", charset, e.getMessage());
            return xmlBytes;
        }
    }

    /**
     * 修复旧版 WPS/中文 Word 文档的错误编码。
     * 有些文档 word/document.xml 声明为 UTF-8 但实际中文字符用 GBK 编码，
     * 导致 POI 读取出乱码。检测并修复。
     */
    private byte[] fixDocxEncodingIfNeeded(byte[] docxBytes) {
        try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(docxBytes);
             java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(bis)) {

            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos);

            java.util.zip.ZipEntry entry;
            byte[] documentXml = null;
            java.util.Map<String, byte[]> otherEntries = new java.util.LinkedHashMap<>();

            while ((entry = zis.getNextEntry()) != null) {
                byte[] entryBytes = zis.readAllBytes();
                if ("word/document.xml".equals(entry.getName())) {
                    documentXml = entryBytes;
                } else {
                    otherEntries.put(entry.getName(), entryBytes);
                }
                zis.closeEntry();
            }

            if (documentXml != null) {
                String utf8Text = new String(documentXml, java.nio.charset.StandardCharsets.UTF_8);
                // 统计替换字符 (U+FFFD) 和乱码特征 — 中文文档正常不应出现
                long garbled = utf8Text.codePoints().filter(c -> c == 0xFFFD).count();
                long totalChinese = utf8Text.codePoints()
                    .filter(c -> c >= 0x4E00 && c <= 0x9FFF).count();

                if (garbled > 10 && totalChinese < 5) {
                    log.info("检测到编码问题: garbled={}, chinese={}, 尝试 GBK 解码", garbled, totalChinese);
                    documentXml = tryDecode(documentXml, "GBK");
                }

                // 双重编码检测: 只修复 w:t 文本内容（Latin-1→GB18030 绕过）
                if (totalChinese < 5 && utf8Text.length() > 100) {
                    byte[] fixed = fixDoubleEncodedText(documentXml);
                    if (fixed != null) {
                        documentXml = fixed;
                        log.info("双重编码修复完成");
                    }
                }
            }

            // 重写 ZIP
            for (java.util.Map.Entry<String, byte[]> e : otherEntries.entrySet()) {
                zos.putNextEntry(new java.util.zip.ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
            if (documentXml != null) {
                zos.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
                zos.write(documentXml);
                zos.closeEntry();
            }
            zos.finish();
            return bos.toByteArray();

        } catch (Exception e) {
            log.warn("编码修复失败，使用原始文件: {}", e.getMessage());
            return docxBytes; // 回退到原始文件
        }
    }

    /** 统计风险分布 */
    private Map<String, Integer> summarizeRisk(List<Map<String, Object>> annotations) {
        int high = 0, medium = 0, low = 0;
        for (Map<String, Object> a : annotations) {
            String level = (String) a.get("riskLevel");
            if ("high".equals(level)) high++;
            else if ("medium".equals(level)) medium++;
            else low++;
        }
        return Map.of("high", high, "medium", medium, "low", low);
    }

}
