package com.mianmiantong.service.ai.usage;

import com.mianmiantong.config.JwtAuthFilter;
import com.mianmiantong.dto.paper.PolishRequest;
import com.mianmiantong.entity.resume.Resume;
import com.mianmiantong.mapper.resume.ResumeMapper;
import com.mianmiantong.mapper.resume.ResumeAnalysisMapper;
import com.mianmiantong.service.ai.gateway.*;
import com.mianmiantong.service.document.DocumentAiService;
import com.mianmiantong.service.paper.PolishService;
import com.mianmiantong.service.resume.ResumeAnalysisService;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.mockito.ArgumentCaptor;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UsageAttributionTest {
    @Test void paperCapturesUserBeforeDispatchingAsyncWorkAndStillUsesSystemKey() {
        var gateway = mock(AiGateway.class);
        var req = new PolishRequest();
        req.setText("sample text");
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(42L, 0, List.of()));
        try { new PolishService(gateway).runPolish(req); }
        finally { SecurityContextHolder.clearContext(); }
        var request = ArgumentCaptor.forClass(AiRequest.class);
        verify(gateway, timeout(5000)).streamChat(request.capture(), isNull(), any());
        assertThat(request.getValue().usageUserId()).isEqualTo(42L);
        assertThat(request.getValue().feature()).isEqualTo("PAPER_POLISH");
        assertThat(JwtAuthFilter.getCurrentUserId()).isNull();
    }

    @Test void backgroundResumeAnalysisUsesPersistedOwner() {
        var gateway = mock(AiGateway.class);
        var resumes = mock(ResumeMapper.class);
        var resume = new Resume();
        resume.setId(1L); resume.setUserId(73L); resume.setParseStatus(1);
        resume.setParsedText("resume"); resume.setJobDescription("developer");
        when(resumes.selectById(1L)).thenReturn(resume);
        when(gateway.chat(any(), isNull())).thenReturn(new AiResponse("{\"overallScore\":5}", "m", 1, 1));
        new ResumeAnalysisService(resumes, mock(ResumeAnalysisMapper.class), gateway, mock(DocumentAiService.class))
                .analyzeQuickAsync(1L, "model");
        var request = ArgumentCaptor.forClass(AiRequest.class);
        verify(gateway, timeout(5000)).chat(request.capture(), isNull());
        assertThat(request.getValue().usageUserId()).isEqualTo(73L);
        assertThat(request.getValue().feature()).isEqualTo("RESUME");
    }
}
