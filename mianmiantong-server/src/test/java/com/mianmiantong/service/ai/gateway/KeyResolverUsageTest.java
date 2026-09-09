package com.mianmiantong.service.ai.gateway;

import com.mianmiantong.entity.user.UserAiConfig;
import com.mianmiantong.service.user.UserAiConfigService;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KeyResolverUsageTest {
    @Test void keyAndAttributionUseOneConfigurationSnapshot() {
        var configs = mock(UserAiConfigService.class);
        var personal = new UserAiConfig();
        personal.setApiKey("personal-fixture");
        when(configs.getByUserId(42L)).thenReturn(personal).thenReturn(null);
        var resolved = new KeyResolver(configs, "system-fixture").resolveCredentials(42L);
        assertThat(resolved.apiKey()).isEqualTo("personal-fixture");
        assertThat(resolved.source()).isEqualTo("PERSONAL");
        assertThat(resolved.toString()).doesNotContain("personal-fixture");
        verify(configs, times(1)).getByUserId(42L);
    }

    @Test void blankKeyAndAnonymousRequestUseSystemKey() {
        var configs = mock(UserAiConfigService.class);
        var blank = new UserAiConfig();
        blank.setApiKey(" ");
        when(configs.getByUserId(42L)).thenReturn(blank);
        var resolver = new KeyResolver(configs, "system-fixture");
        assertThat(resolver.resolveCredentials(42L).source()).isEqualTo("SYSTEM");
        assertThat(resolver.resolveCredentials(null).apiKey()).isEqualTo("system-fixture");
        verify(configs, never()).getByUserId(null);
    }
}
