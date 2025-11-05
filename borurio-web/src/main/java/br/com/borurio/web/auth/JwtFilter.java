package br.com.borurio.web.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
 * COMPONENTE DE SEGURANÇA: JwtFilter
 * =============================================================================
 * Finalidade:
 *   - Interceptar todas as requisições HTTP.
 *   - Validar o token JWT presente no cabeçalho "Authorization".
 *   - Autenticar o usuário no contexto do Spring Security quando o token for válido.
 *
 * Fluxo de execução:
 *   1. Verifica se a rota é pública. Se for, ignora a validação JWT.
 *   2. Extrai o token JWT do cabeçalho Authorization.
 *   3. Valida assinatura e expiração do token via {@link JwtUtil}.
 *   4. Se válido, autentica o usuário no contexto de segurança.
 *
 * Observações:
 *   - Executado uma única vez por requisição (extends {@link OncePerRequestFilter}).
 *   - Projetado para operar em ambiente Stateless (sem sessão).
 *
 * =============================================================================
 * Projeto: Borurio ERP Fiscal BR
 * Módulo: borurio-web
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * =============================================================================
 */
@Component
public class JwtFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;

    /**
     * Construtor com injeção Lazy para evitar dependência circular:
     * SecurityConfig → JwtFilter → UserDetailsServiceImpl → PasswordEncoder.
     */
    public JwtFilter(JwtUtil jwtUtil, @Lazy UserDetailsService userDetailsService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
    }

    /**
     * Executa a filtragem de cada requisição HTTP.
     * - Ignora endpoints públicos.
     * - Valida token JWT nas demais rotas.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {

        final String path = request.getRequestURI();

        // =============================================================================
        // ROTAS PÚBLICAS (IGNORADAS PELO FILTRO JWT)
        // -----------------------------------------------------------------------------
        // Permite acesso direto sem validação de token:
        // Swagger, Actuator, Login, Ping e endpoints de teste.
        // =============================================================================
        if (path.startsWith("/auth/")
                || path.startsWith("/api/test/")
                || path.startsWith("/api/fiscal/nfe/test/")
                || path.startsWith("/swagger-ui/")
                || path.startsWith("/v3/api-docs/")
                || path.startsWith("/actuator/")
                || path.equals("/ping")) {

            chain.doFilter(request, response);
            return;
        }

        // =============================================================================
        // VALIDAÇÃO DO TOKEN JWT
        // =============================================================================
        final String authHeader = request.getHeader("Authorization");
        final String token;
        final String username;

        // Se não houver cabeçalho Authorization ou não começar com "Bearer ", segue sem autenticação
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        // Extrai o token JWT do cabeçalho
        token = authHeader.substring(7);
        username = jwtUtil.extractUsername(token);

        // Se o token for válido, autentica o usuário
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
            }
        }

        // Continua o fluxo normal
        chain.doFilter(request, response);
    }
}
