package com.connecthub.payment.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JwtHeaderFilterTest {

    @Mock JwtUtil jwtUtil;
    @InjectMocks JwtHeaderFilter filter;

    @Test
    void withXUserIdHeader_setsAuthentication() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-User-Id", "42");

        SecurityContextHolder.clearContext();
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo("42");
    }

    @Test
    void withValidBearerToken_setsAuthentication() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer valid.token.here");
        when(jwtUtil.isValid("valid.token.here")).thenReturn(true);
        when(jwtUtil.getUserId("valid.token.here")).thenReturn(99);

        SecurityContextHolder.clearContext();
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo("99");
    }

    @Test
    void withNoHeader_noAuthentication() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        SecurityContextHolder.clearContext();
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    void withInvalidBearerToken_noAuthentication() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("Authorization", "Bearer bad.token");
        when(jwtUtil.isValid("bad.token")).thenReturn(false);

        SecurityContextHolder.clearContext();
        filter.doFilter(req, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
