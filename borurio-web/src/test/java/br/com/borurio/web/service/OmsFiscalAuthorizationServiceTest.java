package br.com.borurio.web.service;

import br.com.borurio.app.entity.*;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.*;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.dto.OmsFiscalAuthorizationRequest;
import br.com.borurio.web.dto.OmsFiscalAuthorizationResponse;
import br.com.borurio.web.util.PkiTestUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OmsFiscalAuthorizationServiceTest {

    private static final String TEST_SENHA = "test123";
    private static final String TEST_CNPJ  = "12345678000195";
    private static String testPfxBase64;

    @Mock private OmsApiKeyMapper              omsApiKeyMapper;
    @Mock private OmsFiscalAuthorizationMapper omsAuthMapper;
    @Mock private OmsCompanyCertificateMapper  omsCertMapper;
    @Mock private EmpresaMapper                empresaMapper;
    @Mock private CertSenhaEncryptor           encryptor;
    @Mock private JwtUtil                      jwtUtil;

    private OmsFiscalAuthorizationService service;

    @BeforeAll
    static void gerarCertificadoTeste() throws Exception {
        testPfxBase64 = PkiTestUtil.gerarPkcs12Base64(
                "CN=EMPRESA TESTE LTDA:12345678000195,OU=RFB e-CNPJ A1,O=ICP-Brasil,C=BR",
                TEST_SENHA,
                3650);
    }

    @BeforeEach
    void setUp() {
        service = Mockito.spy(new OmsFiscalAuthorizationService(
                omsApiKeyMapper, omsAuthMapper, omsCertMapper, empresaMapper, encryptor, jwtUtil));
    }

    // =========================================================================
    // Falhas na autenticação / pré-condições
    // =========================================================================

    @Test
    void autorizarComApiKeyAusente_lançaInvalidApiKey() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.autorizar(null, buildRequest(TEST_CNPJ, testPfxBase64, TEST_SENHA)));

        assertEquals("INVALID_API_KEY", ex.getErrorCode());
        assertEquals(401, ex.getHttpStatus());
        verifyNoInteractions(omsApiKeyMapper, empresaMapper, omsAuthMapper, omsCertMapper, encryptor, jwtUtil);
    }

    @Test
    void autorizarComApiKeyInvalida_lançaInvalidApiKey() {
        when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.autorizar("bad-key", buildRequest(TEST_CNPJ, testPfxBase64, TEST_SENHA)));

        assertEquals("INVALID_API_KEY", ex.getErrorCode());
        assertEquals(401, ex.getHttpStatus());
        // Não deve chegar aos mappers de empresa ou certificado
        verifyNoInteractions(empresaMapper, omsAuthMapper, omsCertMapper, encryptor, jwtUtil);
    }

    @Test
    void autorizarComEmpresaNaoEncontrada_lançaCompanyNotFound() {
        when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());
        when(empresaMapper.buscarPorCnpj(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.autorizar("key", buildRequest(TEST_CNPJ, testPfxBase64, TEST_SENHA)));

        assertEquals("COMPANY_NOT_FOUND", ex.getErrorCode());
        assertEquals(422, ex.getHttpStatus());
        verifyNoInteractions(omsAuthMapper, omsCertMapper, encryptor, jwtUtil);
    }

    @Test
    void autorizarComEmpresaInativa_lançaCompanyNotFound() {
        Empresa inativa = mockEmpresa(TEST_CNPJ);
        inativa.setAtivo(false);
        when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());
        when(empresaMapper.buscarPorCnpj(any())).thenReturn(inativa);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.autorizar("key", buildRequest(TEST_CNPJ, testPfxBase64, TEST_SENHA)));

        assertEquals("COMPANY_NOT_FOUND", ex.getErrorCode());
    }

    // =========================================================================
    // Falhas na validação do certificado
    // =========================================================================

    @Test
    void autorizarComBase64Invalido_lançaInvalidCertificate() {
        when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());
        when(empresaMapper.buscarPorCnpj(any())).thenReturn(mockEmpresa(TEST_CNPJ));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.autorizar("key",
                        buildRequest(TEST_CNPJ, "!!!INVALIDO_BASE64!!!", TEST_SENHA)));

        assertEquals("INVALID_CERTIFICATE", ex.getErrorCode());
        assertEquals(422, ex.getHttpStatus());
    }

    @Test
    void autorizarComSenhaIncorretaNoKeyStore_lançaInvalidCertificate() {
        when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());
        when(empresaMapper.buscarPorCnpj(any())).thenReturn(mockEmpresa(TEST_CNPJ));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.autorizar("key",
                        buildRequest(TEST_CNPJ, testPfxBase64, "senha-errada")));

        assertEquals("INVALID_CERTIFICATE", ex.getErrorCode());
    }

    @Test
    void autorizarComCertExpirado_lançaCertificateExpired() {
        when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());
        when(empresaMapper.buscarPorCnpj(any())).thenReturn(mockEmpresa(TEST_CNPJ));

        KeyStore mockKs = mock(KeyStore.class);
        X509Certificate certExpirado = mock(X509Certificate.class);
        when(certExpirado.getNotAfter()).thenReturn(Date.from(Instant.EPOCH));

        doReturn(mockKs).when(service).carregarKeyStore(any(), anyString());
        doReturn(certExpirado).when(service).extrairCertificado(any());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.autorizar("key",
                        buildRequest(TEST_CNPJ, testPfxBase64, TEST_SENHA)));

        assertEquals("CERTIFICATE_EXPIRED", ex.getErrorCode());
        assertEquals(422, ex.getHttpStatus());
    }

    @Test
    void autorizarComCnpjDivergente_lançaCnpjCertificateMismatch() {
        // Cert de teste tem CNPJ 12345678000195; requisição envia CNPJ diferente
        String cnpjDivergente = "11444777000161";
        when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());
        when(empresaMapper.buscarPorCnpj(any())).thenReturn(mockEmpresa(cnpjDivergente));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.autorizar("key",
                        buildRequest(cnpjDivergente, testPfxBase64, TEST_SENHA)));

        assertEquals("CNPJ_CERTIFICATE_MISMATCH", ex.getErrorCode());
        assertEquals(422, ex.getHttpStatus());
        assertTrue(ex.getMessage().contains(TEST_CNPJ),
                "Mensagem deve indicar o CNPJ real do certificado");
    }

    // =========================================================================
    // Fluxo de autorização bem-sucedido
    // =========================================================================

    @Test
    void primeiraAutorizacao_insereSlotERetornaToken() {
        when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());
        when(empresaMapper.buscarPorCnpj(any())).thenReturn(mockEmpresa(TEST_CNPJ));
        when(omsAuthMapper.buscarPorSlot(any(), any(), any())).thenReturn(null);
        doAnswer(inv -> {
            ((OmsFiscalAuthorization) inv.getArgument(0)).setId(10L);
            return 1;
        }).when(omsAuthMapper).inserir(any());
        doAnswer(inv -> {
            ((OmsCompanyCertificate) inv.getArgument(0)).setId(20L);
            return 1;
        }).when(omsCertMapper).inserir(any());
        when(encryptor.encryptBytes(any())).thenReturn(new byte[]{1, 2, 3});
        when(encryptor.encrypt(anyString())).thenReturn("ENC(senha)");
        when(jwtUtil.generateOmsToken(any(), any(), any(), any())).thenReturn("jwt-novo");

        OmsFiscalAuthorizationResponse resp = service.autorizar("key",
                buildRequest(TEST_CNPJ, testPfxBase64, TEST_SENHA));

        assertNotNull(resp);
        assertEquals("jwt-novo", resp.getToken());
        assertEquals(100L, resp.getEmpresaId());
        assertEquals(TEST_CNPJ, resp.getCnpj());
        assertNotNull(resp.getTokenExpiraEm());

        // Verifica que o slot foi criado, cert desativado (nenhum ativo anterior) e inserido
        verify(omsAuthMapper).inserir(argThat(a -> a.getCodigoOms().equals("OMS-EMP-001")));
        verify(omsAuthMapper, never()).atualizarToken(any(), any(), any());
        verify(omsCertMapper).desativarCertsAtivos(eq(10L), any());
        verify(omsCertMapper).inserir(argThat(c -> c.getAuthId().equals(10L) && c.getAtivo()));
        verify(jwtUtil).generateOmsToken(eq("OMS-EMP-001"), eq(100L), any(), any());
    }

    @Test
    void reautorizacao_atualizaTokenESubstituiCertificado() {
        OmsFiscalAuthorization authExistente = new OmsFiscalAuthorization();
        authExistente.setId(50L);
        authExistente.setJti("jti-antigo");

        when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());
        when(empresaMapper.buscarPorCnpj(any())).thenReturn(mockEmpresa(TEST_CNPJ));
        when(omsAuthMapper.buscarPorSlot(any(), any(), any())).thenReturn(authExistente);
        doAnswer(inv -> {
            ((OmsCompanyCertificate) inv.getArgument(0)).setId(30L);
            return 1;
        }).when(omsCertMapper).inserir(any());
        when(encryptor.encryptBytes(any())).thenReturn(new byte[]{4, 5, 6});
        when(encryptor.encrypt(anyString())).thenReturn("ENC(nova-senha)");
        when(jwtUtil.generateOmsToken(any(), any(), any(), any())).thenReturn("jwt-renovado");

        OmsFiscalAuthorizationResponse resp = service.autorizar("key",
                buildRequest(TEST_CNPJ, testPfxBase64, TEST_SENHA));

        assertNotNull(resp);
        assertEquals("jwt-renovado", resp.getToken());

        // Deve atualizar o slot existente, nunca criar novo
        verify(omsAuthMapper, never()).inserir(any());
        verify(omsAuthMapper).atualizarToken(eq(50L),
                argThat(novoJti -> !novoJti.equals("jti-antigo")), any());

        // Deve desativar cert anterior e inserir novo
        verify(omsCertMapper).desativarCertsAtivos(eq(50L), any());
        verify(omsCertMapper).inserir(argThat(c -> c.getAuthId().equals(50L)));
    }

    @Test
    void reautorizacaoComMesmoCert_reutilizaCertSemInserir() throws Exception {
        OmsFiscalAuthorization authExistente = new OmsFiscalAuthorization();
        authExistente.setId(50L);
        authExistente.setJti("jti-antigo");

        // Calcular thumbprint real do cert de teste para simular "mesmo cert já armazenado"
        byte[] pfxBytes = Base64.getDecoder().decode(testPfxBase64);
        KeyStore ks = service.carregarKeyStore(pfxBytes, TEST_SENHA);
        X509Certificate x509 = service.extrairCertificado(ks);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(x509.getEncoded());
        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) sb.append(String.format("%02x", b));
        String thumbprintReal = sb.toString();

        OmsCompanyCertificate certAtivo = new OmsCompanyCertificate();
        certAtivo.setThumbprint(thumbprintReal);
        certAtivo.setAtivo(true);

        when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());
        when(empresaMapper.buscarPorCnpj(any())).thenReturn(mockEmpresa(TEST_CNPJ));
        when(omsAuthMapper.buscarPorSlot(any(), any(), any())).thenReturn(authExistente);
        when(omsCertMapper.buscarAtivoPorAuthId(eq(50L))).thenReturn(certAtivo);
        when(encryptor.encryptBytes(any())).thenReturn(new byte[]{4, 5, 6});
        when(encryptor.encrypt(anyString())).thenReturn("ENC(senha)");
        when(jwtUtil.generateOmsToken(any(), any(), any(), any())).thenReturn("jwt-renovado");

        OmsFiscalAuthorizationResponse resp = service.autorizar("key",
                buildRequest(TEST_CNPJ, testPfxBase64, TEST_SENHA));

        assertNotNull(resp);
        assertEquals("jwt-renovado", resp.getToken());

        // Slot atualizado — nunca criado
        verify(omsAuthMapper, never()).inserir(any());
        verify(omsAuthMapper).atualizarToken(eq(50L),
                argThat(novoJti -> !novoJti.equals("jti-antigo")), any());

        // Mesmo thumbprint → cert NÃO deve ser desativado nem reinserido
        verify(omsCertMapper, never()).desativarCertsAtivos(any(), any());
        verify(omsCertMapper, never()).inserir(any());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private OmsApiKey mockApiKey() {
        OmsApiKey key = new OmsApiKey();
        key.setId(1L);
        key.setIntegratorId(5L);
        key.setAtivo(true);
        return key;
    }

    private Empresa mockEmpresa(String cnpj) {
        Empresa e = new Empresa();
        e.setId(100L);
        e.setCnpj(cnpj);
        e.setRazaoSocial("EMPRESA TESTE LTDA");
        e.setAtivo(true);
        return e;
    }

    private OmsFiscalAuthorizationRequest buildRequest(String cnpj, String certBase64, String senha) {
        OmsFiscalAuthorizationRequest req = new OmsFiscalAuthorizationRequest();
        req.setCodigoEmpresaOms("OMS-EMP-001");
        req.setCnpj(cnpj);
        req.setCertBase64(certBase64);
        req.setCertSenha(senha);
        return req;
    }
}
