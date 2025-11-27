package br.com.borurio.web.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * =============================================================================
 * FILTRO DE AUTENTICAÇÃO JWT — BORURIO ERP FISCAL BR
 * =============================================================================
 * Funções:
 *   - Intercepta todas as requisições HTTP.
 *   - Valida JWT no cabeçalho Authorization.
 *   - Ignora rotas públicas (Auth, Swagger, Actuator, Testes Fiscais, NF-e pública).
 *   - Autentica o usuário no contexto de segurança se o token for válido.
 *
 * Ambiente:
 *   Compatível com dev, hom e prd.
 *
 * Autor: Bruno Ribeiro — DevSecOps / Fullstack Java
 * Revisão: 26/11/2025
 * =============================================================================
 */
@Slf4j
@Component
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;

    public JwtFilter(JwtUtil jwtUtil, @Lazy UserDetailsService userDetailsService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        final String path = request.getRequestURI();

        // =============================================================================
        // ROTAS PÚBLICAS (IGNORADAS PELO JWT)
        // =============================================================================
        boolean rotaPublica =
                path.startsWith("/auth/") ||
                        path.startsWith("/api/test/") ||
                        path.startsWith("/swagger-ui/") ||
                        path.startsWith("/v3/api-docs/") ||
                        path.startsWith("/actuator/") ||
                        path.equals("/ping") ||

                        // Fiscais públicas (evita travar envio)
                        path.startsWith("/nfe/") ||
                        path.startsWith("/api/fiscal/nfe/test/") ||
                        path.startsWith("/api/fiscal/nfe/status");

        if (rotaPublica) {
            chain.doFilter(request, response);
            return;
        }

        // =============================================================================
        // VALIDAÇÃO JWT
        // =============================================================================
        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);
        String username;

        try {
            username = jwtUtil.extractUsername(token);
        } catch (Exception e) {
            log.warn("Token JWT inválido ou malformado: {}", e.getMessage());
            chain.doFilter(request, response);
            return;
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {

            UserDetails userDetails = userDetailsService.loadUserByUsername(username);

            if (jwtUtil.validateToken(token, userDetails.getUsername())) {

                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );

                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authToken);

            } else {
                log.warn("Token JWT expirado ou inválido.");
            }
        }

        chain.doFilter(request, response);
    }
}
