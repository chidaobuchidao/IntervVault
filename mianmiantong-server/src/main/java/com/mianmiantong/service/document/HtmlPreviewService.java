package com.mianmiantong.service.document;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;

/**
 * 将 .docx 转换为可在小程序 web-view 中预览的 HTML 页面
 */
@Slf4j
@Service
public class HtmlPreviewService {

    public String convertDocxToHtml(byte[] docxBytes) {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docxBytes))) {
            StringBuilder html = new StringBuilder();
            html.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1\">")
                .append("<style>")
                .append("*{margin:0;padding:0;box-sizing:border-box}")
                .append("body{font-family:-apple-system,'Microsoft YaHei',sans-serif;padding:24px 16px 60px;font-size:15px;line-height:1.9;color:#333;background:#fff;word-wrap:break-word}")
                .append("h1{font-size:22px;font-weight:700;margin:16px 0 8px;color:#1a1a1a}")
                .append("h2{font-size:19px;font-weight:600;margin:14px 0 6px;color:#333}")
                .append("h3{font-size:17px;font-weight:600;margin:12px 0 4px;color:#444}")
                .append("p{margin:4px 0}")
                .append("ul,ol{padding-left:20px;margin:4px 0}")
                .append("li{margin:2px 0}")
                .append("table{border-collapse:collapse;width:100%;margin:8px 0;font-size:13px}")
                .append("td,th{border:1px solid #ddd;padding:6px 8px;text-align:left}")
                .append("th{background:#f5f5f5;font-weight:600}")
                .append("b,strong{font-weight:600}")
                .append("i,em{font-style:italic}")
                .append(".center{text-align:center}")
                .append(".right{text-align:right}")
                .append("</style></head><body>");

            for (IBodyElement elem : doc.getBodyElements()) {
                convertElement(elem, html);
            }

            html.append("</body></html>");
            return html.toString();
        } catch (Exception e) {
            log.error("HTML预览转换失败", e);
            throw new RuntimeException("HTML预览转换失败: " + e.getMessage());
        }
    }

    private void convertElement(IBodyElement elem, StringBuilder html) {
        if (elem instanceof XWPFParagraph para) {
            convertParagraph(para, html);
        } else if (elem instanceof XWPFTable table) {
            convertTable(table, html);
        }
    }

    private void convertParagraph(XWPFParagraph para, StringBuilder html) {
        String style = para.getStyle();
        boolean isHeading = style != null && (style.startsWith("Heading") || style.startsWith("heading"));

        String tag = "p";
        if (isHeading) {
            int level = 1;
            try {
                level = Integer.parseInt(style.replaceAll("[^0-9]", ""));
            } catch (NumberFormatException e) {
                log.debug("标题样式无法解析层级，回退为 h1: style={}", style);
            }
            tag = "h" + Math.min(level, 3);
        }

        StringBuilder line = new StringBuilder();
        for (XWPFRun run : para.getRuns()) {
            String text = run.getText(0);
            if (text == null) continue;
            text = escapeHtml(text);
            if (run.isBold()) text = "<b>" + text + "</b>";
            if (run.isItalic()) text = "<i>" + text + "</i>";
            line.append(text);
        }

        String content = line.toString().trim();
        if (content.isEmpty()) {
            html.append("<p><br></p>");
        } else {
            html.append("<").append(tag).append(">").append(content).append("</").append(tag).append(">");
        }
    }

    private void convertTable(XWPFTable table, StringBuilder html) {
        html.append("<table>");
        for (XWPFTableRow row : table.getRows()) {
            html.append("<tr>");
            for (XWPFTableCell cell : row.getTableCells()) {
                html.append("<td>");
                for (XWPFParagraph para : cell.getParagraphs()) {
                    convertParagraph(para, html);
                }
                html.append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</table>");
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
