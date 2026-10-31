package com.crosspaynet.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint exposing CSRF token metadata for browser and Single-Page Application (SPA) clients.
 */
@RestController
public class CsrfController {

    private final CookieCsrfTokenRepository csrfTokenRepository;

    public CsrfController(CookieCsrfTokenRepository csrfTokenRepository) {
        this.csrfTokenRepository = csrfTokenRepository;
    }

    @GetMapping({"/api/v1/portal/csrf", "/api/v1/auth/csrf"})
    public ResponseEntity<CsrfTokenResponse> getCsrfToken(HttpServletRequest request, HttpServletResponse response) {
        DeferredCsrfToken deferredCsrfToken = (DeferredCsrfToken) request.getAttribute(DeferredCsrfToken.class.getName());
        CsrfToken token = deferredCsrfToken != null ? deferredCsrfToken.get() : null;
        if (token == null) {
            token = this.csrfTokenRepository.loadToken(request);
        }
        if (token == null) {
            token = this.csrfTokenRepository.generateToken(request);
        }
        this.csrfTokenRepository.saveToken(token, request, response);

        String parameterName = token.getParameterName() != null ? token.getParameterName() : "_csrf";
        return ResponseEntity.ok(new CsrfTokenResponse(
                token.getToken(),
                "X-XSRF-TOKEN",
                parameterName
        ));
    }

    public record CsrfTokenResponse(String token, String headerName, String parameterName) {}
}
