package br.com.borurio.web.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proteção por janela deslizante de 60 s contra abuso nos endpoints
 * de autenticação e emissão de NF-e.
 *
 * Limites configuráveis via:
 *   RATE_LIMIT_LOGIN_MAX  (default 10 req/min por IP)
 *   RATE_LIMIT_EMITIR_MAX (default 30 req/min por IP)
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final long WINDOW_MS = 60_000L;

    @Value("${rate.limit.login.max:10}")
    private int loginMax;

    @Value("${rate.limit.emitir.max:30}")
    private int emitirMax;

    private final ConcurrentHashMap<String, long[]> buckets = new ConcurrentHashMap<>();

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {

        String path = request.getRequestURI();
        String ip   = resolveIp(request);

        if ("/auth/login".equals(path) && "POST".equals(request.getMethod())) {
            return check(response, "login:" + ip, loginMax);
        }
        if (path.matches("/api/app/pedidos/[^/]+/emitir") && "POST".equals(request.getMethod())) {
            return check(response, "emitir:" + ip, emitirMax);
        }
        return true;
    }

    private boolean check(HttpServletResponse response, String key, int limit) throws IOException {
        long now = System.currentTimeMillis();
        long[] bucket = buckets.compute(key, (k, existing) -> {
            if (existing == null || now - existing[0] >= WINDOW_MS) {
                return new long[]{now, 1};
            }
            existing[1]++;
            return existing;
        });

        if (bucket[1] > limit) {
            response.setStatus(429);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(
                    "{\"code\":429,\"message\":\"Muitas requisições. Tente novamente em instantes.\",\"success\":false}");
            return false;
        }
        return true;
    }

    private String resolveIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
