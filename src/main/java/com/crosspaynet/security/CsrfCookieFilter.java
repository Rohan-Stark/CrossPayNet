package com.crosspaynet.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that forces resolution of the {@link DeferredCsrfToken} so that
 * the {@code CookieCsrfTokenRepository} commits the {@code XSRF-TOKEN} cookie
 * to the HTTP response on every request.
 */
final class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        DeferredCsrfToken deferredCsrfToken = (DeferredCsrfToken) request.getAttribute(DeferredCsrfToken.class.getName());
        if (deferredCsrfToken != null) {
            // Invoking get() forces evaluation and commits the cookie to the HTTP response
            deferredCsrfToken.get();
        }
        filterChain.doFilter(request, response);
    }
}
