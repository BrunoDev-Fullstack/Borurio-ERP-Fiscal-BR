package br.com.borurio.web.auth;

import br.com.borurio.app.context.EmpresaContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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
import java.util.Map;
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
    private final OmsTokenAuthorizationValidator omsTokenValidator;
    private final ObjectMapper objectMapper;

    public JwtFilter(JwtUtil jwtUtil, @Lazy UserDetailsService userDetailsService,
                      OmsTokenAuthorizationValidator omsTokenValidator, ObjectMapper objectMapper) {
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
        this.omsTokenValidator = omsTokenValidator;
        this.objectMapper = objectMapper;
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
                if (!autenticarOms(token, request, response)) {
                    // Resposta já escrita (401/503) — não prossegue a cadeia de filtros.
                    return;
                }
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

    /**
     * @return true se a cadeia de filtros deve continuar; false se a resposta de erro já foi
     *         escrita (token inválido/revogado/expirado, ou falha ao consultar a autorização).
     */
    private boolean autenticarOms(String token, HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        // JJWT já valida assinatura e expiração em extractTipo; se chegou aqui, o token é válido.
        Long empresaIdToken   = jwtUtil.extractEmpresaId(token);
        String jti            = jwtUtil.extractJti(token);
        String codigoOmsToken = jwtUtil.extractUsername(token);  // sub = codigoOms

        if (empresaIdToken == null || jti == null) {
            log.warn("[OMS] Token sem claims obrigatórios (eid ou jti)");
            writeJson(response, 401, "INVALID_OMS_TOKEN", "Token OMS inválido, expirado ou revogado.", false);
            return false;
        }

        // Verifica revogação a cada requisição — sem cache. Achado do Gate 7H: esta checagem
        // nunca existia aqui; só OmsCertificadoService a fazia, e só nos fluxos que resolvem
        // certificado (ex.: /emitir), deixando o resto da API acessível com um token revogado.
        OmsTokenAuthorizationContext contexto = omsTokenValidator.validate(jti);
        switch (contexto.getStatus()) {
            case ACTIVE -> { /* segue */ }
            case SERVICE_UNAVAILABLE -> {
                log.error("[OMS] Falha ao validar autorização (fail-closed)");
                writeJson(response, 503, "AUTHORIZATION_SERVICE_UNAVAILABLE",
                        "Serviço de autorização OMS temporariamente indisponível.", true);
                return false;
            }
            default -> {
                // NOT_FOUND / REVOKED / EXPIRED colapsam no mesmo 401 genérico — nunca revelar
                // ao portador do token qual das três causas se aplica. Nunca logar o jti, nem
                // parcialmente — só authId (indisponível aqui, pois o registro não é confiável).
                log.warn("[OMS] Token rejeitado | causa={}", contexto.getStatus());
                writeJson(response, 401, "INVALID_OMS_TOKEN", "Token OMS inválido, expirado ou revogado.", false);
                return false;
            }
        }

        // Nunca confiar nos claims do próprio token para autorizar — empresaId/codigoOms usados
        // daqui em diante vêm do banco (contexto). Esta comparação é só uma checagem de
        // integridade extra: um token validamente assinado cujos claims divergem do registro
        // ativo (ex.: se uma mudança futura permitir alterar empresa_id/codigo_oms sem rotacionar
        // o jti) é rejeitado com o mesmo 401 genérico, sem revelar a causa específica.
        if (!contexto.getEmpresaId().equals(empresaIdToken) || !contexto.getCodigoOms().equals(codigoOmsToken)) {
            log.warn("[OMS] Claims do token divergem do registro ativo | authId={}", contexto.getAuthId());
            writeJson(response, 401, "INVALID_OMS_TOKEN", "Token OMS inválido, expirado ou revogado.", false);
            return false;
        }

        if (SecurityContextHolder.getContext().getAuthentication() != null) return true;

        Long empresaId    = contexto.getEmpresaId();
        String codigoOms  = contexto.getCodigoOms();

        OmsAuthenticationPrincipal principal = new OmsAuthenticationPrincipal(empresaId, jti, codigoOms);
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_OMS")));
        authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authToken);

        EmpresaContextHolder.set(empresaId);
        EmpresaContextHolder.setJtiAuth(jti);
        log.info("[OMS] Token autenticado | empresaId={} | codigoOms={}", empresaId, codigoOms);
        return true;
    }

    private void writeJson(HttpServletResponse response, int status, String errorCode,
                            String message, boolean retryable) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("code", status);
        body.put("message", message);
        body.put("data", null);
        body.put("retryable", retryable);
        String rid = MDC.get("requestId");
        if (rid != null) body.put("requestId", rid);
        body.put("errorCode", errorCode);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
