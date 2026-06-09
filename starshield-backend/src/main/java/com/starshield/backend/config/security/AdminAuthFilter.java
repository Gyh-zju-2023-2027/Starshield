package com.starshield.backend.config.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starshield.backend.common.Result;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 管理 / 控制面写操作 / reindex 等高危接口的 API Key 鉴权。
 */
public class AdminAuthFilter extends OncePerRequestFilter {

    static final String HEADER_ADMIN_TOKEN = "X-Admin-Token";

    private final AdminAuthProperties properties;
    private final ObjectMapper objectMapper;

    public AdminAuthFilter(AdminAuthProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    static boolean requiresAuth(String uri, String method) {
        if (uri == null) {
            return false;
        }
        if (uri.startsWith("/api/admin/")) {
            return true;
        }
        if (uri.startsWith("/api/crawl")) {
            return true;
        }
        if ("POST".equalsIgnoreCase(method) && "/api/archive/reindex".equals(uri)) {
            return true;
        }
        if (uri.startsWith("/api/control/")
                && ("PUT".equalsIgnoreCase(method)
                || "POST".equalsIgnoreCase(method)
                || "DELETE".equalsIgnoreCase(method))) {
            return true;
        }
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (!properties.isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String uri = request.getRequestURI();
        if (!requiresAuth(uri, request.getMethod())) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!isAuthorized(request)) {
            writeUnauthorized(response);
            return;
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAuthorized(HttpServletRequest request) {
        String expected = properties.getAdminApiKey().trim();
        String token = request.getHeader(HEADER_ADMIN_TOKEN);
        if (token != null && !token.isBlank() && expected.equals(token.trim())) {
            return true;
        }
        String auth = request.getHeader("Authorization");
        if (auth != null && auth.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String bearer = auth.substring(7).trim();
            return expected.equals(bearer);
        }
        return false;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), Result.error(401, "未授权，请提供有效的管理令牌"));
    }
}
