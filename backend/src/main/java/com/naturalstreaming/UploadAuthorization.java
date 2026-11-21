package com.naturalstreaming;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(name = "ns.role", havingValue = "admin")
class UploadAuthorization extends OncePerRequestFilter {
    private final Sessions sessions;
    private final String origin;

    UploadAuthorization(Sessions sessions, @Value("${ns.console-origin}") String origin) {
        this.sessions = sessions;
        this.origin = origin;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getMethod().equals("POST") && request.getRequestURI().equals("/admin/assets/import"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        if (!origin.equals(request.getHeader("Origin"))) {
            reject(response, 403, "invalid_origin");
            return;
        }
        String token = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals("__Host-ns-console")) {
                    token = cookie.getValue();
                    break;
                }
            }
        }
        try {
            sessions.require("admin", token);
        } catch (ResponseStatusException error) {
            reject(response, error.getStatusCode().value(), error.getReason());
            return;
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, int status, String reason) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"" + reason + "\"}");
    }
}
