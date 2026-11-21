package com.naturalstreaming;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
@ConditionalOnProperty(name = "ns.role", havingValue = "admin")
class UploadAuthorization extends OncePerRequestFilter {
    private final ConsoleAccess access;

    UploadAuthorization(ConsoleAccess access) {
        this.access = access;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !(request.getMethod().equals("POST") && request.getRequestURI().equals("/admin/assets/import"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        String token = null;
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookie.getName().equals(ConsoleAccess.COOKIE)) {
                    token = cookie.getValue();
                    break;
                }
            }
        }
        try {
            access.write(request.getHeader("Origin"), token, "assets.write");
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
