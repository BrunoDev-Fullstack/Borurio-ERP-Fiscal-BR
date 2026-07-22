package br.com.borurio.web.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
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
}
