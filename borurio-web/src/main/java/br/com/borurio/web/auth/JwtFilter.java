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
 * -----------------------------------------------------------------------------
 * Responsável por interceptar todas as requisições HTTP e validar o token JWT
 * presente no cabeçalho "Authorization".
 *
 * Fluxo de validação:
 *   1. Extrai o token JWT do cabeçalho Authorization.
 *   2. Decodifica e valida a assinatura do token via {@link JwtUtil}.
 *   3. Caso o token seja válido, autentica o usuário no contexto de segurança.
 *
 * Esta classe é marcada como {@link Component}, sendo gerenciada pelo Spring,
 * e executa uma única vez por requisição (extends {@link OncePerRequestFilter}).
 *
 * -----------------------------------------------------------------------------
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
     * Construtor com injeção @Lazy para evitar ciclo de dependência entre
     * SecurityConfig → JwtFilter → UserDetailsServiceImpl → PasswordEncoder.
     *
     * @param jwtUtil utilitário de manipulação e validação de tokens JWT.
     * @param userDetailsService serviço que carrega detalhes do usuário autenticado.
     */
    public JwtFilter(JwtUtil jwtUtil, @Lazy UserDetailsService userDetailsService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
    }

    /**
     * Intercepta e valida o token JWT presente na requisição.
     *
     * @param request  requisição HTTP.
     * @param response resposta HTTP.
     * @param chain    cadeia de filtros da requisição.
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");
        final String token;
        final String username;

        // Caso o cabeçalho Authorization não exista ou não comece com "Bearer ", segue fluxo normal
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        // Extrai o token JWT do cabeçalho
        token = authHeader.substring(7);
        username = jwtUtil.extractUsername(token);

        // Valida o token e autentica o usuário no contexto de segurança
        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);

            if (jwtUtil.validateToken(token, userDetails.getUsername())) {
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails, null, userDetails.getAuthorities());

                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        // Continua o fluxo da requisição
        chain.doFilter(request, response);
    }
}
