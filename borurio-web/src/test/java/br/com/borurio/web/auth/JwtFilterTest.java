
package br.com.borurio.web.auth;

import br.com.borurio.app.context.EmpresaContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * JwtFilter depende só de OmsTokenAuthorizationValidator — nunca de mapper/MyBatis diretamente
 * (Gate 7H, ponto 5). Este teste mocka a interface, sem contexto Spring nem banco.
 */
@ExtendWith(MockitoExtension.class)
class JwtFilterTest {

    @Mock private JwtUtil jwtUtil;
    @Mock private UserDetailsService userDetailsService;
    @Mock private OmsTokenAuthorizationValidator omsTokenValidator;
    @Mock private FilterChain chain;

    private JwtFilter filter;

    private static final String TOKEN = "token-fake-oms";
    private static final String JTI = "11111111-1111-1111-1111-111111111111";

    @BeforeEach
    void setUp() {
        filter = new JwtFilter(jwtUtil, userDetailsService, omsTokenValidator, new ObjectMapper());
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void limparContexto() {
        EmpresaContextHolder.clear();
    }

    private MockHttpServletRequest requestComToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }

    private void mockarClaimsOms() {
        when(jwtUtil.extractTipo(TOKEN)).thenReturn("OMS");
        when(jwtUtil.extractEmpresaId(TOKEN)).thenReturn(10L);
        when(jwtUtil.extractJti(TOKEN)).thenReturn(JTI);
        when(jwtUtil.extractUsername(TOKEN)).thenReturn("CLIENTE-OMS");
    }

    /** tipo != "OMS" (null, como um token de usuário comum nunca carrega claim "tipo"). */
    private void mockarClaimsUsuario(String username, Long empresaId) {
        when(jwtUtil.extractTipo(TOKEN)).thenReturn(null);
        when(jwtUtil.extractUsername(TOKEN)).thenReturn(username);
        when(jwtUtil.validateToken(TOKEN, username)).thenReturn(true);
        when(jwtUtil.extractEmpresaId(TOKEN)).thenReturn(empresaId);
    }

    private UserDetails userDetailsComRole(String username, String role) {
        return User.builder()
                .username(username)
                .password("hash-irrelevante")
                .authorities(AuthorityUtils.createAuthorityList("ROLE_" + role))
                .build();
    }

    // -------------------------------------------------------------------------
    // P0-2 (Gate 1.2 Fase B) — tenant-null fail-closed para usuário interno
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("A) OPERADOR + eid presente → autentica, chain executada, EmpresaContextHolder com o tenant DURANTE a requisição")
    void operadorComEid_autenticaESeguePraChain() throws Exception {
        mockarClaimsUsuario("operador@teste.com", 10L);
        when(userDetailsService.loadUserByUsername("operador@teste.com"))
                .thenReturn(userDetailsComRole("operador@teste.com", "OPERADOR"));

        Long[] empresaIdDuranteChain = new Long[1];
        doAnswer(inv -> {
            empresaIdDuranteChain[0] = EmpresaContextHolder.get();
            return null;
        }).when(chain).doFilter(any(), any());

        MockHttpServletRequest request = requestComToken();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(10L, empresaIdDuranteChain[0]);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("B) OPERADOR + eid null → 403 TENANT_REQUIRED, retryable=false, chain NÃO executada")
    void operadorSemEid_403TenantRequiredSemChamarChain() throws Exception {
        mockarClaimsUsuario("operador-sem-empresa@teste.com", null);
        when(userDetailsService.loadUserByUsername("operador-sem-empresa@teste.com"))
                .thenReturn(userDetailsComRole("operador-sem-empresa@teste.com", "OPERADOR"));

        MockHttpServletRequest request = requestComToken();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("TENANT_REQUIRED"));
        assertTrue(response.getContentAsString().contains("\"retryable\":false"));
        assertNull(SecurityContextHolder.getContext().getAuthentication(),
                "usuário negado nunca deve ficar autenticado no SecurityContext");
        assertNull(EmpresaContextHolder.get());
    }

    @Test
    @DisplayName("C) ADMIN + eid null → autentica, chain executada, contexto de empresa continua null (acesso global preservado)")
    void adminSemEid_autenticaContextoContinuaNull() throws Exception {
        mockarClaimsUsuario("admin@teste.com", null);
        when(userDetailsService.loadUserByUsername("admin@teste.com"))
                .thenReturn(userDetailsComRole("admin@teste.com", "ADMIN"));

        Long[] empresaIdDuranteChain = new Long[]{-1L}; // sentinela != null
        doAnswer(inv -> {
            empresaIdDuranteChain[0] = EmpresaContextHolder.get();
            return null;
        }).when(chain).doFilter(any(), any());

        MockHttpServletRequest request = requestComToken();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertNull(empresaIdDuranteChain[0], "ADMIN sem eid não pode ter empresa fabricada no contexto");
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("D) ADMIN + eid presente → autentica, contexto usa o eid do token (não vira acesso global automaticamente)")
    void adminComEid_contextoUsaEidDoToken() throws Exception {
        mockarClaimsUsuario("admin-com-empresa@teste.com", 20L);
        when(userDetailsService.loadUserByUsername("admin-com-empresa@teste.com"))
                .thenReturn(userDetailsComRole("admin-com-empresa@teste.com", "ADMIN"));

        Long[] empresaIdDuranteChain = new Long[1];
        doAnswer(inv -> {
            empresaIdDuranteChain[0] = EmpresaContextHolder.get();
            return null;
        }).when(chain).doFilter(any(), any());

        MockHttpServletRequest request = requestComToken();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(20L, empresaIdDuranteChain[0],
                "ADMIN com eid no token precisa ficar escopado a essa empresa, igual a qualquer outro usuário");
        assertEquals(200, response.getStatus());
    }

    @Test
    @DisplayName("F) token OMS sem eid → continua rejeitado pelo fluxo OMS existente (401 INVALID_OMS_TOKEN), nunca vira TENANT_REQUIRED de usuário interno")
    void tokenOmsSemEid_401InvalidOmsTokenNuncaTenantRequired() throws Exception {
        when(jwtUtil.extractTipo(TOKEN)).thenReturn("OMS");
        when(jwtUtil.extractEmpresaId(TOKEN)).thenReturn(null);
        when(jwtUtil.extractJti(TOKEN)).thenReturn(JTI);
        when(jwtUtil.extractUsername(TOKEN)).thenReturn("CLIENTE-OMS");

        MockHttpServletRequest request = requestComToken();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain, omsTokenValidator);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("INVALID_OMS_TOKEN"));
        assertFalse(response.getContentAsString().contains("TENANT_REQUIRED"));
    }

    @Test
    @DisplayName("G) ThreadLocal limpo após a requisição, mesmo com tenant setado durante a chain (OPERADOR com eid)")
    void empresaContextHolder_limpoAposRequisicao() throws Exception {
        mockarClaimsUsuario("operador@teste.com", 10L);
        when(userDetailsService.loadUserByUsername("operador@teste.com"))
                .thenReturn(userDetailsComRole("operador@teste.com", "OPERADOR"));

        MockHttpServletRequest request = requestComToken();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        assertNull(EmpresaContextHolder.get(), "EmpresaContextHolder precisa estar limpo depois do finally do filtro");
    }

    @Test
    @DisplayName("H) token inválido (validateToken=false) → comportamento existente preservado: segue sem autenticar, sem 403 novo")
    void tokenInvalido_comportamentoExistentePreservado() throws Exception {
        when(jwtUtil.extractTipo(TOKEN)).thenReturn(null);
        when(jwtUtil.extractUsername(TOKEN)).thenReturn("qualquer@teste.com");
        when(jwtUtil.validateToken(TOKEN, "qualquer@teste.com")).thenReturn(false);
        when(userDetailsService.loadUserByUsername("qualquer@teste.com"))
                .thenReturn(userDetailsComRole("qualquer@teste.com", "OPERADOR"));

        MockHttpServletRequest request = requestComToken();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(200, response.getStatus(), "token inválido nunca escrevia resposta própria antes — comportamento preservado");
    }

    @Test
    void semAuthorizationHeader_seguePraChainSemAutenticar() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(omsTokenValidator);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void tokenOmsAtivo_autenticaESeguePraChain() throws Exception {
        mockarClaimsOms();
        when(omsTokenValidator.validate(JTI)).thenReturn(
                OmsTokenAuthorizationContext.ativo(1L, 10L, "CLIENTE-OMS"));

        MockHttpServletRequest request = requestComToken();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals(200, response.getStatus());
    }

    @Test
    void tokenOmsRevogado_401InvalidOmsTokenSemChamarChain() throws Exception {
        mockarClaimsOms();
        when(omsTokenValidator.validate(JTI)).thenReturn(
                OmsTokenAuthorizationContext.inativo(OmsTokenValidationResult.REVOKED));

        MockHttpServletRequest request = requestComToken();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("INVALID_OMS_TOKEN"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void tokenOmsNaoEncontrado_mesmoErro401GenericoQueRevogado() throws Exception {
        mockarClaimsOms();
        when(omsTokenValidator.validate(JTI)).thenReturn(
                OmsTokenAuthorizationContext.inativo(OmsTokenValidationResult.NOT_FOUND));

        MockHttpServletRequest request = requestComToken();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("INVALID_OMS_TOKEN"));
    }

    @Test
    void tokenOmsExpiradoNoBanco_mesmoErro401GenericoQueRevogado() throws Exception {
        mockarClaimsOms();
        when(omsTokenValidator.validate(JTI)).thenReturn(
                OmsTokenAuthorizationContext.inativo(OmsTokenValidationResult.EXPIRED));

        MockHttpServletRequest request = requestComToken();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("INVALID_OMS_TOKEN"));
    }

    @Test
    void falhaAoConsultarAutorizacao_503FailClosedSemChamarChain() throws Exception {
        mockarClaimsOms();
        when(omsTokenValidator.validate(JTI)).thenReturn(
                OmsTokenAuthorizationContext.inativo(OmsTokenValidationResult.SERVICE_UNAVAILABLE));

        MockHttpServletRequest request = requestComToken();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertEquals(503, response.getStatus());
        assertTrue(response.getContentAsString().contains("AUTHORIZATION_SERVICE_UNAVAILABLE"));
        assertTrue(response.getContentAsString().contains("\"retryable\":true"));
    }

    @Test
    void claimsDoTokenDivergemDoRegistroAtivo_401InvalidOmsTokenSemChamarChain() throws Exception {
        mockarClaimsOms();
        // Token diz empresaId=10, mas o registro ativo no banco (mesmo jti) é de outra empresa —
        // cenário defensivo (Gate 7H, ponto 3): nunca confiar nos claims por si só.
        when(omsTokenValidator.validate(JTI)).thenReturn(
                OmsTokenAuthorizationContext.ativo(1L, 999L, "CLIENTE-OMS"));

        MockHttpServletRequest request = requestComToken();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, chain);

        verifyNoInteractions(chain);
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("INVALID_OMS_TOKEN"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // -------------------------------------------------------------------------
    // Regressão 31-08-2026 — "/api/integration/" era prefixo público inteiro em PUBLIC_PREFIXES
    // (desde 17-06, quando só existia fiscal-authorizations sob esse namespace). Quando
    // fiscal-numbering foi adicionado em 20-07 exigindo Bearer, ficou órfão de autenticação por
    // 5 semanas: shouldNotFilter() pulava doFilterInternal por completo, então mesmo um Bearer OMS
    // válido nunca era lido — a requisição seguia anônima e o SecurityConfig rejeitava com 401
    // genérico sem log nenhum (writeJson do authenticationEntryPoint não loga). Fix: só o path
    // exato de fiscal-authorizations é público; qualquer outro endpoint sob /api/integration/
    // passa pelo JwtFilter normalmente.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("shouldNotFilter: fiscal-authorizations continua público (path exato, X-Api-Key, sem JWT)")
    void shouldNotFilter_fiscalAuthorizations_continuaPublico() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/api/integration/fiscal-authorizations");

        assertTrue(filter.shouldNotFilter(request));
    }

    @Test
    @DisplayName("shouldNotFilter: fiscal-numbering NÃO é mais público — precisa passar pelo JwtFilter")
    void shouldNotFilter_fiscalNumbering_naoEhMaisPublico() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/api/integration/fiscal-numbering/22418179000134");

        assertFalse(filter.shouldNotFilter(request));
    }

    @Test
    @DisplayName("regressão: PUT fiscal-numbering com Bearer OMS válido chega até a chain autenticado "
            + "(antes do fix, shouldNotFilter bypassava o JwtFilter inteiro nesse path)")
    void regressao_fiscalNumbering_bearerOmsValido_autenticaEChegaAteChain() throws Exception {
        mockarClaimsOms();
        when(omsTokenValidator.validate(JTI)).thenReturn(
                OmsTokenAuthorizationContext.ativo(1L, 10L, "CLIENTE-OMS"));

        MockHttpServletRequest request = requestComToken();
        request.setServletPath("/api/integration/fiscal-numbering/22418179000134");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("regressão: POST fiscal-authorizations continua pulando o JwtFilter inteiro via doFilter() público")
    void regressao_fiscalAuthorizations_continuaPulandoJwtFilterViaDoFilterPublico() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setServletPath("/api/integration/fiscal-authorizations");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtUtil, omsTokenValidator);
    }
}
