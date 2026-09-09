package com.mianmiantong.config;

import com.mianmiantong.common.JwtUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

class JwtAuthFilterTest {

    private final JwtUtil jwtUtil = new JwtUtil("jwt-filter-test-signing-key-32-bytes-minimum", 60_000);
    private final JwtAuthFilter filter = new JwtAuthFilter(jwtUtil);

    @BeforeEach
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {"dev-token-1", "dev-token-42", "dev-token-invalid", "dev-token-"})
    void developerBearerTokenCannotAuthenticate(String token) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + token);

        assertUnauthenticated(request);
    }

    @Test
    void developerQueryTokenCannotAuthenticate() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("token", "dev-token-42");

        assertUnauthenticated(request);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1})
    void signedBearerTokenAuthenticatesItsUserAndRole(int role) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + jwtUtil.generateToken(42L, "test-user", role));

        filter.doFilter(request, new MockHttpServletResponse(), (req, response) -> {
            assertTrue(SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
            assertEquals(42L, JwtAuthFilter.getCurrentUserId());
            assertEquals(role, JwtAuthFilter.getCurrentUserRole());
            assertEquals(role == 1, JwtAuthFilter.isAdmin());
        });
    }

    @Test
    void signedQueryTokenStillAuthenticates() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setParameter("token", jwtUtil.generateToken(42L, "test-user"));

        filter.doFilter(request, new MockHttpServletResponse(), (req, response) -> {
            assertEquals(42L, JwtAuthFilter.getCurrentUserId());
            assertFalse(JwtAuthFilter.isAdmin());
        });
    }

    @Test
    void tokenSignedByAnotherKeyCannotAuthenticate() throws Exception {
        JwtUtil otherSigner = new JwtUtil("different-test-signing-key-32-bytes-minimum", 60_000);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + otherSigner.generateToken(42L, "test-user", 1));

        assertUnauthenticated(request);
    }

    private void assertUnauthenticated(MockHttpServletRequest request) throws Exception {
        filter.doFilter(request, new MockHttpServletResponse(), (req, response) -> {
            assertNull(SecurityContextHolder.getContext().getAuthentication());
            assertNull(JwtAuthFilter.getCurrentUserId());
            assertFalse(JwtAuthFilter.isAdmin());
        });
    }
}
