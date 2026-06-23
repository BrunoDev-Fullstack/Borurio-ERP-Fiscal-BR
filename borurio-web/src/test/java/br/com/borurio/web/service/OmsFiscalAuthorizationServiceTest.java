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
import org.junit.jupiter.api.Nested;
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

    private static final String TEST_SENHA  = "test123";
    private static final String TEST_CNPJ   = "12345678000195";
    private static final String TEST_CNPJ2  = "11444777000161";
    private static final String CODIGO_OMS  = "OMS-EMP-001";
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
    // Falhas na autenticação / API Key
    // =========================================================================

    @Nested
    class ApiKeyTests {

        @Test
        void autorizarComApiKeyAusente_lançaInvalidApiKey() {
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.autorizar(null, buildRequest(TEST_CNPJ, testPfxBase64, TEST_SENHA)));

            assertEquals("INVALID_API_KEY", ex.getErrorCode());
            assertEquals(401, ex.getHttpStatus());
            verifyNoInteractions(omsApiKeyMapper, empresaMapper, omsAuthMapper, omsCertMapper);
        }

        @Test
        void autorizarComApiKeyInvalida_lançaInvalidApiKey() {
            when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(null);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.autorizar("bad-key", buildRequest(TEST_CNPJ, testPfxBase64, TEST_SENHA)));

            assertEquals("INVALID_API_KEY", ex.getErrorCode());
            assertEquals(401, ex.getHttpStatus());
            verifyNoInteractions(empresaMapper, omsAuthMapper, omsCertMapper);
        }
    }

    // =========================================================================
    // Falhas na empresa
    // =========================================================================

    @Nested
    class EmpresaTests {

        @Test
        void autorizarComEmpresaInativa_lançaCompanyInactive() {
            Empresa inativa = mockEmpresa(TEST_CNPJ);
            inativa.setAtivo(false);
            when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());
            when(empresaMapper.buscarPorCnpj(TEST_CNPJ)).thenReturn(inativa);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.autorizar("key", buildRequest(TEST_CNPJ, testPfxBase64, TEST_SENHA)));

            assertEquals("COMPANY_INACTIVE", ex.getErrorCode());
            assertEquals(422, ex.getHttpStatus());
            verifyNoInteractions(omsAuthMapper, omsCertMapper);
        }

        @Test
        void autorizarSemEmpresaPrecadastrada_criaEmpresaAutomaticamente() {
            when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());
            when(empresaMapper.buscarPorCnpj(TEST_CNPJ)).thenReturn(null); // sem pré-cadastro
            doAnswer(inv -> {
                ((Empresa) inv.getArgument(0)).setId(200L);
                return 1;
            }).when(empresaMapper).inserir(any());
            when(omsAuthMapper.buscarPorSlot(any(), any())).thenReturn(null);
            doAnswer(inv -> {
                ((OmsFiscalAuthorization) inv.getArgument(0)).setId(10L);
                return 1;
            }).when(omsAuthMapper).inserir(any());
            when(encryptor.encryptBytes(any())).thenReturn(new byte[]{1, 2, 3});
            when(encryptor.encrypt(anyString())).thenReturn("ENC(senha)");
            when(jwtUtil.generateOmsToken(any(), any(), any(), any(), any())).thenReturn("jwt-novo");

            OmsFiscalAuthorizationResponse resp = service.autorizar("key",
                    buildRequest(TEST_CNPJ, testPfxBase64, TEST_SENHA));

            assertNotNull(resp);
            assertEquals("jwt-novo", resp.getToken());
            assertEquals(TEST_CNPJ, resp.getCnpj());

            // Empresa deve ter sido criada automaticamente
            verify(empresaMapper).inserir(argThat(e ->
                    e.getCnpj().equals(TEST_CNPJ) && Boolean.TRUE.equals(e.getAtivo())));
            // Slot criado com empresa auto-criada
            verify(omsAuthMapper).inserir(argThat(a ->
                    a.getCodigoOms().equals(CODIGO_OMS) && a.getEmpresaId().equals(200L)));
            // Cert inserido com cnpj e empresaId
            verify(omsCertMapper).inserir(argThat(c ->
                    c.getCnpj().equals(TEST_CNPJ) && c.getEmpresaId().equals(200L)));
        }
    }

    // =========================================================================
    // Falhas na validação do certificado
    // =========================================================================

    @Nested
    class CertificadoValidacaoTests {

        @Test
        void autorizarComBase64Invalido_lançaInvalidCertificate() {
            when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.autorizar("key",
                            buildRequest(TEST_CNPJ, "!!!INVALIDO_BASE64!!!", TEST_SENHA)));

            assertEquals("INVALID_CERTIFICATE", ex.getErrorCode());
        }

        @Test
        void autorizarComSenhaIncorreta_lançaInvalidCertificate() {
            when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.autorizar("key",
                            buildRequest(TEST_CNPJ, testPfxBase64, "senha-errada")));

            assertEquals("INVALID_CERTIFICATE", ex.getErrorCode());
        }

        @Test
        void autorizarComCertExpirado_lançaCertificateExpired() {
            when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());

            KeyStore mockKs = mock(KeyStore.class);
            X509Certificate certExpirado = mock(X509Certificate.class);
            when(certExpirado.getNotAfter()).thenReturn(Date.from(Instant.EPOCH));

            doReturn(mockKs).when(service).carregarKeyStore(any(), anyString());
            doReturn(certExpirado).when(service).extrairCertificado(any());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.autorizar("key",
                            buildRequest(TEST_CNPJ, testPfxBase64, TEST_SENHA)));

            assertEquals("CERTIFICATE_EXPIRED", ex.getErrorCode());
        }

        @Test
        void autorizarComCnpjDivergente_lançaCnpjCertificateMismatch() {
            // Cert tem CNPJ 12345678000195; requisição envia CNPJ diferente
            when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.autorizar("key",
                            buildRequest(TEST_CNPJ2, testPfxBase64, TEST_SENHA)));

            assertEquals("CNPJ_CERTIFICATE_MISMATCH", ex.getErrorCode());
            assertTrue(ex.getMessage().contains(TEST_CNPJ),
                    "Mensagem deve indicar o CNPJ real do certificado");
        }
    }

    // =========================================================================
    // CASO A — primeira autorização para o cliente OMS
    // =========================================================================

    @Nested
    class PrimeiraAutorizacaoTests {

        @Test
        void primeiraAutorizacao_comEmpresaJaExistente_insereSlotECertRetornaToken() {
            when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());
            when(empresaMapper.buscarPorCnpj(TEST_CNPJ)).thenReturn(mockEmpresa(TEST_CNPJ));
            when(omsAuthMapper.buscarPorSlot(5L, CODIGO_OMS)).thenReturn(null);
            doAnswer(inv -> {
                ((OmsFiscalAuthorization) inv.getArgument(0)).setId(10L);
                return 1;
            }).when(omsAuthMapper).inserir(any());
            when(encryptor.encryptBytes(any())).thenReturn(new byte[]{1});
            when(encryptor.encrypt(any())).thenReturn("ENC");
            when(jwtUtil.generateOmsToken(any(), any(), any(), any(), any())).thenReturn("jwt-novo");

            OmsFiscalAuthorizationResponse resp = service.autorizar("key",
                    buildRequest(TEST_CNPJ, testPfxBase64, TEST_SENHA));

            assertNotNull(resp);
            assertEquals("jwt-novo", resp.getToken());
            assertEquals(100L, resp.getEmpresaId());
            assertEquals(TEST_CNPJ, resp.getCnpj());

            verify(omsAuthMapper).inserir(argThat(a ->
                    CODIGO_OMS.equals(a.getCodigoOms()) && a.getEmpresaId() != null));
            verify(omsAuthMapper, never()).atualizarToken(any(), any(), any());
            verify(omsCertMapper).inserir(argThat(c ->
                    c.getAuthId().equals(10L)
                    && c.getCnpj().equals(TEST_CNPJ)
                    && Boolean.TRUE.equals(c.getAtivo())));
            verify(jwtUtil).generateOmsToken(eq(CODIGO_OMS), eq(100L), any(), any(), any());
            // Empresa não deve ser criada (já existe)
            verify(empresaMapper, never()).inserir(any());
        }
    }

    // =========================================================================
    // CASO B — mesmo CNPJ, mesmo certificado (renovação de token)
    // =========================================================================

    @Nested
    class MesmoCertificadoTests {

        @Test
        void mesmoCnpjMesmoCertificado_naoAlteraCertNemJtiRetornaToken() throws Exception {
            OmsFiscalAuthorization authExistente = mockAuth(50L, "jti-original");

            String thumbprintReal = calcularThumbprint();
            OmsCompanyCertificate certAtivo = mockCert(thumbprintReal, TEST_CNPJ);

            when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());
            when(empresaMapper.buscarPorCnpj(TEST_CNPJ)).thenReturn(mockEmpresa(TEST_CNPJ));
            when(omsAuthMapper.buscarPorSlot(5L, CODIGO_OMS)).thenReturn(authExistente);
            when(omsCertMapper.buscarAtivoPorAuthIdECnpj(50L, TEST_CNPJ)).thenReturn(certAtivo);
            when(encryptor.encryptBytes(any())).thenReturn(new byte[]{1});
            when(encryptor.encrypt(any())).thenReturn("ENC");
            when(jwtUtil.generateOmsToken(any(), any(), any(), any(), any())).thenReturn("jwt-original");

            OmsFiscalAuthorizationResponse resp = service.autorizar("key",
                    buildRequest(TEST_CNPJ, testPfxBase64, TEST_SENHA));

            assertEquals("jwt-original", resp.getToken());

            // Caso B: nenhuma alteração no slot nem no certificado
            verify(omsAuthMapper, never()).inserir(any());
            verify(omsAuthMapper, never()).atualizarToken(any(), any(), any());
            verify(omsCertMapper, never()).desativarCertsAtivos(any(), any(), any());
            verify(omsCertMapper, never()).inserir(any());
            // Token gerado com o JTI original
            verify(jwtUtil).generateOmsToken(eq(CODIGO_OMS), any(), eq("jti-original"), any(), any());
        }
    }

    // =========================================================================
    // CASO C — mesmo CNPJ, certificado novo (atualização de cert)
    // =========================================================================

    @Nested
    class CertificadoNovoMesmoCnpjTests {

        @Test
        void mesmoCnpjNovoCertificado_atualizaApenasCertDoMesmoCnpjMantemJti() {
            OmsFiscalAuthorization authExistente = mockAuth(50L, "jti-original");
            // Cert ativo com thumbprint DIFERENTE do cert atual
            OmsCompanyCertificate certAtivo = mockCert("aabbcc-thumbprint-antigo", TEST_CNPJ);

            when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());
            when(empresaMapper.buscarPorCnpj(TEST_CNPJ)).thenReturn(mockEmpresa(TEST_CNPJ));
            when(omsAuthMapper.buscarPorSlot(5L, CODIGO_OMS)).thenReturn(authExistente);
            when(omsCertMapper.buscarAtivoPorAuthIdECnpj(50L, TEST_CNPJ)).thenReturn(certAtivo);
            when(encryptor.encryptBytes(any())).thenReturn(new byte[]{4, 5});
            when(encryptor.encrypt(any())).thenReturn("ENC");
            when(jwtUtil.generateOmsToken(any(), any(), any(), any(), any())).thenReturn("jwt-renovado");

            OmsFiscalAuthorizationResponse resp = service.autorizar("key",
                    buildRequest(TEST_CNPJ, testPfxBase64, TEST_SENHA));

            assertEquals("jwt-renovado", resp.getToken());

            // Slot não criado — apenas atualizado (tokenExpiraEm, JTI mantido)
            verify(omsAuthMapper, never()).inserir(any());
            verify(omsAuthMapper).atualizarToken(
                    eq(50L),
                    eq("jti-original"),    // JTI deve ser o mesmo
                    any());                // tokenExpiraEm atualizado

            // Cert do CNPJ específico desativado e novo inserido
            verify(omsCertMapper).desativarCertsAtivos(eq(50L), eq(TEST_CNPJ), any());
            verify(omsCertMapper).inserir(argThat(c ->
                    c.getAuthId().equals(50L)
                    && c.getCnpj().equals(TEST_CNPJ)));
        }
    }

    // =========================================================================
    // CASO D — novo CNPJ para cliente OMS existente (mesmo token)
    // =========================================================================

    @Nested
    class NovoCnpjMesmoClienteOmsTests {

        @Test
        void novoCnpjMesmoClienteOms_mantemTokenExistenteAdicionaCert() throws Exception {
            OmsFiscalAuthorization authExistente = mockAuth(50L, "jti-original");
            authExistente.setEmpresaId(100L);

            // Segundo CNPJ: cert de teste tem CNPJ TEST_CNPJ, mas o slot existe com outro OMS
            // Simulamos: slot encontrado, busca por (auth, TEST_CNPJ) retorna null (CNPJ novo)
            when(omsApiKeyMapper.findAtivaPorHash(any())).thenReturn(mockApiKey());
            when(empresaMapper.buscarPorCnpj(TEST_CNPJ)).thenReturn(mockEmpresa(TEST_CNPJ));
            when(omsAuthMapper.buscarPorSlot(5L, CODIGO_OMS)).thenReturn(authExistente);
            when(omsCertMapper.buscarAtivoPorAuthIdECnpj(50L, TEST_CNPJ)).thenReturn(null); // CNPJ novo
            when(encryptor.encryptBytes(any())).thenReturn(new byte[]{7, 8});
            when(encryptor.encrypt(any())).thenReturn("ENC");
            when(jwtUtil.generateOmsToken(any(), any(), any(), any(), any())).thenReturn("jwt-original");

            OmsFiscalAuthorizationResponse resp = service.autorizar("key",
                    buildRequest(TEST_CNPJ, testPfxBase64, TEST_SENHA));

            assertEquals("jwt-original", resp.getToken());

            // Slot não criado nem atualizado — token original preservado
            verify(omsAuthMapper, never()).inserir(any());
            verify(omsAuthMapper, never()).atualizarToken(any(), any(), any());

            // Cert desativado não (CNPJ novo, sem ativo anterior)
            verify(omsCertMapper, never()).desativarCertsAtivos(any(), any(), any());

            // Cert inserido para o novo CNPJ
            verify(omsCertMapper).inserir(argThat(c ->
                    c.getAuthId().equals(50L)
                    && c.getCnpj().equals(TEST_CNPJ)));

            // Token regenerado com o JTI original
            verify(jwtUtil).generateOmsToken(eq(CODIGO_OMS), eq(100L), eq("jti-original"), any(), any());
        }
    }

    // =========================================================================
    // Erro de CNPJ não autorizado (OmsCertificadoService)
    // =========================================================================

    @Nested
    class CnpjNaoAutorizadoTests {

        @Test
        void certNotFoundForCnpj_lançaErroClaro() {
            // Testa que BusinessException.certNotFoundForCnpj gera código e status corretos
            BusinessException ex = BusinessException.certNotFoundForCnpj("12345678000195");

            assertEquals("CERT_NOT_FOUND_FOR_CNPJ", ex.getErrorCode());
            assertEquals(422, ex.getHttpStatus());
            assertTrue(ex.getMessage().contains("12345678000195"));
        }

        @Test
        void cnpjNotAuthorizedForOmsClient_lançaErroClaro() {
            BusinessException ex = BusinessException.cnpjNotAuthorizedForOmsClient("12345678000195");

            assertEquals("CNPJ_NOT_AUTHORIZED", ex.getErrorCode());
            assertEquals(403, ex.getHttpStatus());
            assertTrue(ex.getMessage().contains("12345678000195"));
        }
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

    private OmsFiscalAuthorization mockAuth(Long id, String jti) {
        OmsFiscalAuthorization auth = new OmsFiscalAuthorization();
        auth.setId(id);
        auth.setJti(jti);
        auth.setEmpresaId(100L);
        auth.setIntegratorId(5L);
        auth.setCodigoOms(CODIGO_OMS);
        return auth;
    }

    private OmsCompanyCertificate mockCert(String thumbprint, String cnpj) {
        OmsCompanyCertificate c = new OmsCompanyCertificate();
        c.setThumbprint(thumbprint);
        c.setCnpj(cnpj);
        c.setAtivo(true);
        return c;
    }

    private String calcularThumbprint() throws Exception {
        byte[] pfxBytes = Base64.getDecoder().decode(testPfxBase64);
        KeyStore ks = service.carregarKeyStore(pfxBytes, TEST_SENHA);
        X509Certificate x509 = service.extrairCertificado(ks);
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(x509.getEncoded());
        StringBuilder sb = new StringBuilder(64);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private OmsFiscalAuthorizationRequest buildRequest(String cnpj, String certBase64, String senha) {
        OmsFiscalAuthorizationRequest req = new OmsFiscalAuthorizationRequest();
        req.setCodigoEmpresaOms(CODIGO_OMS);
        req.setCnpj(cnpj);
        req.setCertBase64(certBase64);
        req.setCertSenha(senha);
        return req;
    }
}
