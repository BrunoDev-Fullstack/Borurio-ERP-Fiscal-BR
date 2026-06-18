package br.com.borurio.web.auth;

import br.com.borurio.app.context.EmpresaContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.context.annotation.Lazy;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;

@Component
public class JwtFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtFilter.class);

    // Prefixos e paths exatos que nunca exigem JWT.
    // shouldNotFilter() faz OncePerRequestFilter pular doFilterInternal por completo
    // e chamar chain.doFilter() diretamente, sem tocar no SecurityContext.
    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/actuator/",
            "/auth/",
            "/swagger-ui",
            "/v3/api-docs",
            "/api/test/",
            "/api/fiscal/nfe/test/",
            "/api/integration/"
    );

    private static final Set<String> PUBLIC_EXACT = Set.of(
            "/ping",
            "/actuator"
    );

    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;

    public JwtFilter(JwtUtil jwtUtil, @Lazy UserDetailsService userDetailsService) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) throws ServletException {
        String path = request.getServletPath();
        return PUBLIC_EXACT.contains(path)
                || PUBLIC_PREFIXES.stream().anyMatch(path::startsWith);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain chain
    ) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        log.debug("JWT FILTER → {}", request.getRequestURI());

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            chain.doFilter(request, response);
            return;
        }

        String token = authHeader.substring(7);

        try {
            String tipo = jwtUtil.extractTipo(token);

            if ("OMS".equals(tipo)) {
                autenticarOms(token, request);
            } else {
                autenticarUsuario(token, request);
            }

        } catch (Exception e) {
            log.error("Erro ao processar JWT: {}", e.getMessage(), e);
            SecurityContextHolder.clearContext();
        }

        try {
            chain.doFilter(request, response);
        } finally {
            EmpresaContextHolder.clear();
        }
    }

    private void autenticarUsuario(String token, HttpServletRequest request) {
        String username = jwtUtil.extractUsername(token);
        if (username == null || SecurityContextHolder.getContext().getAuthentication() != null) return;

        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        if (!jwtUtil.validateToken(token, userDetails.getUsername())) {
            log.warn("Token inválido para usuário: {}", username);
            return;
        }

        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                userDetails, null, userDetails.getAuthorities());
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);

        Long empresaId = jwtUtil.extractEmpresaId(token);
        EmpresaContextHolder.set(empresaId);
        log.info("Usuário autenticado: {} | empresaId={}", username, empresaId);
    }

    private void autenticarOms(String token, HttpServletRequest request) {
        // JJWT já valida assinatura e expiração em extractTipo; se chegou aqui, o token é válido.
        Long empresaId  = jwtUtil.extractEmpresaId(token);
        String jti      = jwtUtil.extractJti(token);
        String codigoOms = jwtUtil.extractUsername(token);  // sub = codigoOms

        if (empresaId == null || jti == null) {
            log.warn("[OMS] Token sem claims obrigatórios (eid ou jti)");
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() != null) return;

        OmsAuthenticationPrincipal principal = new OmsAuthenticationPrincipal(empresaId, jti, codigoOms);
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_OMS")));
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);

        EmpresaContextHolder.set(empresaId);
        EmpresaContextHolder.setJtiAuth(jti);
        log.info("[OMS] Token autenticado | empresaId={} | codigoOms={}", empresaId, codigoOms);
    }
}