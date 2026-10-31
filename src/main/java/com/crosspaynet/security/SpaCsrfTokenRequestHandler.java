package com.crosspaynet.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * Single-Page Application (SPA) CSRF token request handler.
 *
 * <p>Uses {@link XorCsrfTokenRequestAttributeHandler} to provide BREACH protection when
 * rendering tokens in attributes, while allowing SPA frontends to submit the plain token
 * read from the {@code XSRF-TOKEN} cookie or {@code /api/v1/portal/csrf} endpoint in the
 * {@code X-XSRF-TOKEN} header.</p>
 */
final class SpaCsrfTokenRequestHandler extends CsrfTokenRequestAttributeHandler {

    private final CsrfTokenRequestHandler delegate = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        this.delegate.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String xsrfHeader = request.getHeader("X-XSRF-TOKEN");
        if (StringUtils.hasText(xsrfHeader)) {
            return xsrfHeader;
        }
        if (StringUtils.hasText(request.getHeader(csrfToken.getHeaderName()))) {
            return super.resolveCsrfTokenValue(request, csrfToken);
        }
        return this.delegate.resolveCsrfTokenValue(request, csrfToken);
    }
}
