package com.mianmiantong.service.document;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.util.Units;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;

/**
 * 将简历 Markdown 文本导出为 Word (.docx) 文件
 */
@Slf4j
@Service
public class WordExportService {

    /** Word 页边距：1 英寸 = 1440 Twips (Twentieths of a Point) */
    private static final BigInteger PAGE_MARGIN_ONE_INCH = BigInteger.valueOf(1440);

    public byte[] exportMarkdown(String markdown, String title) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            // 设置窄页边距
            CTSectPr sectPr = doc.getDocument().getBody().addNewSectPr();
            CTPageMar pageMar = sectPr.addNewPgMar();
            pageMar.setTop(PAGE_MARGIN_ONE_INCH);
            pageMar.setBottom(PAGE_MARGIN_ONE_INCH);
            pageMar.setLeft(PAGE_MARGIN_ONE_INCH);
            pageMar.setRight(PAGE_MARGIN_ONE_INCH);

            String[] lines = markdown.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty()) {
                    doc.createParagraph();
                    continue;
                }

                if (line.startsWith("# ")) {
                    addHeading(doc, line.substring(2), 1);
                } else if (line.startsWith("## ")) {
                    addHeading(doc, line.substring(3), 2);
                } else if (line.startsWith("### ")) {
                    addHeading(doc, line.substring(4), 3);
                } else if (line.startsWith("- ") || line.startsWith("* ")) {
                    addBullet(doc, line.substring(2));
                } else if (line.startsWith("> ")) {
                    addQuote(doc, line.substring(2));
                } else if (line.startsWith("---") || line.startsWith("***")) {
                    XWPFParagraph hr = doc.createParagraph();
                    XWPFRun run = hr.createRun();
                    run.setText("—".repeat(40));
                    run.setColor("999999");
                    run.setFontSize(8);
                } else {
                    addParagraph(doc, line);
                }
            }

            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("Word导出失败", e);
            throw new RuntimeException("Word导出失败: " + e.getMessage());
        }
    }

    private void addHeading(XWPFDocument doc, String text, int level) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("Heading" + level);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setBold(true);
    }

    private void addParagraph(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontSize(11);
    }

    private void addBullet(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setStyle("ListBullet");
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setFontSize(11);
    }

    private void addQuote(XWPFDocument doc, String text) {
        XWPFParagraph p = doc.createParagraph();
        p.setIndentationLeft(480);
        XWPFRun run = p.createRun();
        run.setText(text);
        run.setItalic(true);
        run.setColor("666666");
        run.setFontSize(11);
    }
}
