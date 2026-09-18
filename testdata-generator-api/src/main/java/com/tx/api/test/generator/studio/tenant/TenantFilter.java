package com.tx.api.test.generator.studio.tenant;

import com.tx.api.test.generator.studio.config.StudioProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class TenantFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";

    private final StudioProperties properties;
    private final AuthService authService;

    public TenantFilter(StudioProperties properties, AuthService authService) {
        this.properties = properties;
        this.authService = authService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod()) || isStatic(request)) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            if (!properties.isSaasEnabled()) {
                TenantContext.set(new TenantContext.Scope(
                        TenantContext.LOCAL_TENANT_ID, null, null, "OWNER"));
                filterChain.doFilter(request, response);
                return;
            }
            String token = bearerToken(request);
            boolean publicPath = isPublic(request);
            AuthService.ResolvedSession resolved = StringUtils.isBlank(token)
                    ? null
                    : authService.resolve(token).orElse(null);
            if (resolved == null) {
                if (publicPath) {
                    filterChain.doFilter(request, response);
                    return;
                }
                unauthorized(response, "Sign in to continue");
                return;
            }
            String tenantId = StringUtils.defaultIfBlank(request.getHeader(TENANT_HEADER), resolved.tenant().getId());
            if (!resolved.tenant().getId().equals(tenantId)) {
                var membership = authService.membership(tenantId, resolved.account().getId()).orElse(null);
                if (membership == null) {
                    forbidden(response, "You are not a member of that workspace");
                    return;
                }
                TenantContext.set(new TenantContext.Scope(
                        tenantId, resolved.account().getId(), resolved.account().getEmail(), membership.role()));
            } else {
                TenantContext.set(new TenantContext.Scope(
                        resolved.tenant().getId(), resolved.account().getId(),
                        resolved.account().getEmail(), resolved.role()));
            }
            if (!isPendingAllowed(request)) {
                Tenant tenant = authService.findTenant(tenantId).orElse(resolved.tenant());
                if (!authService.isTenantReady(tenant)) {
                    forbidden(response,
                            "This workspace is not enabled yet. A SaaS admin must mark it ready for use.");
                    return;
                }
            }
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static boolean isPublic(HttpServletRequest request) {
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/h2-console")) {
            return true;
        }
        if ("GET".equalsIgnoreCase(method) && ("/api/testdata/config".equals(path) || "/api/auth/session".equals(path))) {
            return true;
        }
        return "POST".equalsIgnoreCase(method)
                && ("/api/auth/login".equals(path) || "/api/auth/register".equals(path));
    }

    private static boolean isPendingAllowed(HttpServletRequest request) {
        if (isPublic(request)) {
            return true;
        }
        String path = request.getRequestURI();
        String method = request.getMethod();
        if (path.startsWith("/api/saas/")) {
            return true;
        }
        if ("POST".equalsIgnoreCase(method) && "/api/auth/logout".equals(path)) {
            return true;
        }
        if ("POST".equalsIgnoreCase(method) && "/api/auth/switch-tenant".equals(path)) {
            return true;
        }
        return "POST".equalsIgnoreCase(method) && "/api/tenants".equals(path);
    }

    private static boolean isStatic(HttpServletRequest request) {
        String path = request.getRequestURI();
        return "/favicon.ico".equals(path) || "/error".equals(path);
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null) {
            return null;
        }
        String prefix = "bearer ";
        if (header.length() > prefix.length() && header.substring(0, prefix.length()).toLowerCase(Locale.ROOT).equals(prefix)) {
            return header.substring(prefix.length()).trim();
        }
        return null;
    }

    private static void unauthorized(HttpServletResponse response, String message) throws IOException {
        write(response, HttpServletResponse.SC_UNAUTHORIZED, message);
    }

    private static void forbidden(HttpServletResponse response, String message) throws IOException {
        write(response, HttpServletResponse.SC_FORBIDDEN, message);
    }

    private static void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message.replace("\"", "'") + "\"}");
    }
}
