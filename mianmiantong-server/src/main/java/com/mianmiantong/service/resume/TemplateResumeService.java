package com.mianmiantong.service.resume;

import com.mianmiantong.config.JwtAuthFilter;

import com.mianmiantong.entity.resume.ResumeTemplate;
import com.mianmiantong.mapper.resume.ResumeTemplateMapper;
import com.mianmiantong.service.ai.gateway.AiGateway;
import com.mianmiantong.service.ai.gateway.AiRequest;
import com.mianmiantong.service.ai.gateway.AiResponse;
import com.mianmiantong.service.ai.gateway.AiTaskType;
import com.mianmiantong.service.ai.gateway.ChatMessage;
import com.mianmiantong.service.document.WordExportService;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模板简历生成服务 — AI 填充模板内容，WordExportService 渲染
 */
@Slf4j
@Service
public class TemplateResumeService {

    private final ResumeTemplateMapper templateMapper;
    private final AiGateway aiGateway;
    private final WordExportService wordExportService;

    public TemplateResumeService(ResumeTemplateMapper templateMapper,
                                 AiGateway aiGateway,
                                 WordExportService wordExportService) {
        this.templateMapper = templateMapper;
        this.aiGateway = aiGateway;
        this.wordExportService = wordExportService;
    }

    private static final String PROMPT = """
        你是资深技术面试官和职业顾问。请根据以下信息生成优化的简历内容。

        ## 用户背景
        %s

        ## 目标岗位
        %s

        ## 输出格式
        请生成完整简历，使用 Markdown 格式：
        # 姓名
        - 联系方式 | 邮箱

        ## 求职意向
        目标职位

        ## 技能特长
        - 技能1：描述
        - 技能2：描述

        ## 工作/项目经历
        ### 公司/项目名 | 时间段
        - 职责/成果1
        - 职责/成果2

        ## 教育背景
        ### 学校 | 学历 | 时间

        ## 自我评价
        2-3句话的专业总结

        ## 硬约束
        - 英文专业术语、技术名词、框架名称必须保留原文拼写，不得翻译或改写（如MyBatis、Spring Boot MVC、Docker、RESTful API、JSON等保持原样）。
        - 数学表达式和公式中的空格必须原样保留（如"1 + 1 = 2"不得改为"1+1=2"）。
        """;

    /**
     * 从已有简历生成模板化内容
     */
    public byte[] generateFromResume(Long resumeId, Long templateId,
                                      String parsedText, String jobDescription) {
        ResumeTemplate tpl = templateMapper.selectById(templateId);
        if (tpl == null) throw new IllegalArgumentException("模板不存在: templateId=" + templateId);

        String prompt = String.format(PROMPT, parsedText, jobDescription);
        List<ChatMessage> messages = List.of(
                new ChatMessage("user", "请生成简历")
        );

        AiRequest request = new AiRequest(prompt, messages, null, AiTaskType.FLASH).withUsage(JwtAuthFilter.getCurrentUserId(), "RESUME");
        AiResponse response = aiGateway.chat(request, null);
        log.info("AI模板简历生成完成: templateId={}, len={}", templateId, response.content().length());

        return wordExportService.exportMarkdown(response.content(),
                tpl.getName() + "简历");
    }

    /** 获取所有模板 */
    public List<ResumeTemplate> listTemplates() {
        return templateMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ResumeTemplate>()
                        .eq(ResumeTemplate::getIsActive, 1)
                        .orderByAsc(ResumeTemplate::getSortOrder)
        );
    }
}
