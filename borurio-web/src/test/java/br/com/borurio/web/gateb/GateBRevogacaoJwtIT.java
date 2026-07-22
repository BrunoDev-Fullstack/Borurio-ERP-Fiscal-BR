package br.com.borurio.web.gateb;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.MotivoAdminOms;
import br.com.borurio.app.entity.OmsCompanyCertificate;
import br.com.borurio.app.entity.OmsFiscalAuthorization;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.OmsCompanyCertificateMapper;
import br.com.borurio.app.mapper.OmsFiscalAuthorizationMapper;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.dto.OmsAuthorizationAdminResponse;
import br.com.borurio.web.dto.OmsAuthorizationRevogarRequest;
import br.com.borurio.web.dto.OmsAuthorizationRotacionarRequest;
import br.com.borurio.web.service.OmsAuthorizationAdminService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Gate B — revogação e rotação administrativa de autorização OMS contra MySQL real (mesmo
 * container descartável dos demais GateB*IT). Prova de ponta a ponta do hotfix: JwtFilter
 * (achado do Gate 7H) agora consulta OmsTokenAuthorizationValidatorImpl a cada requisição, e um
 * token revogado deixa de funcionar imediatamente, sem esperar expiração.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GateBRevogacaoJwtIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", GateBTestProperties::dbUrl);
        registry.add("spring.datasource.username", GateBTestProperties::dbUsername);
        registry.add("spring.datasource.password", GateBTestProperties::dbPassword);
        // Exigido só para o contexto Spring subir (CertificadoServiceImpl).
        registry.add("FISCAL_CERT_PATH", GateBTestProperties::certPath);
        registry.add("FISCAL_CERT_PASSWORD", GateBTestProperties::certPassword);
        // Necessário para o seed gravar um OmsCompanyCertificate válido (certSenhaEncryptor.encryptBytes
        // é fail-closed sem esta chave).
        registry.add("cert.encryption.key", GateBTestProperties::certEncryptionKey);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JwtUtil jwtUtil;
    @Autowired EmpresaMapper empresaMapper;
    @Autowired OmsFiscalAuthorizationMapper omsAuthMapper;
    @Autowired OmsCompanyCertificateMapper omsCertMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired br.com.borurio.web.service.CertSenhaEncryptor certSenhaEncryptor;
    @Autowired OmsAuthorizationAdminService omsAuthorizationAdminService;

    private static final String CNPJ = "54393421000159";
    private static final String ADMIN_EMAIL = "gateb-revogacao-admin@teste.com";

    private Long authId;
    private String tokenOriginal;
    private Long integratorId;
    private Long empresaId;

    @BeforeEach
    void seed() throws Exception {
        jdbc.update("DELETE FROM oms_fiscal_authorization_audit");
        jdbc.update("DELETE FROM nfe_sequencia_auditoria");
        jdbc.update("DELETE FROM nfe_sequencia");
        jdbc.update("DELETE FROM oms_company_certificate");
        jdbc.update("DELETE FROM oms_fiscal_authorization");
        jdbc.update("DELETE FROM oms_integrator");
        jdbc.update("INSERT INTO oms_integrator (codigo, nome, ativo) VALUES ('GATEB-REVOGACAO', 'Gate B Revogacao', 1)");
        integratorId = jdbc.queryForObject("SELECT id FROM oms_integrator ORDER BY id DESC LIMIT 1", Long.class);

        jdbc.update("DELETE FROM db_user WHERE email = ?", ADMIN_EMAIL);
        jdbc.update("""
                INSERT INTO db_user (empresa_id, nome, email, senha, role, ativo, data_criacao, data_atualizacao)
                VALUES (NULL, 'Admin Gate B', ?, 'hash-nao-usado-neste-teste', 'ADMIN', 1, NOW(), NOW())
                """, ADMIN_EMAIL);

        Empresa empresa = empresaMapper.buscarPorCnpj(CNPJ);
        empresa.setSerieNfePadrao("1");
        empresaMapper.atualizar(empresa);
        empresaId = empresa.getId();

        String jti = "jti-gateb-revogacao-" + System.nanoTime();
        OmsFiscalAuthorization auth = new OmsFiscalAuthorization();
        auth.setEmpresaId(empresa.getId());
        auth.setIntegratorId(integratorId);
        auth.setCodigoOms("GATEB-REVOGACAO-CLIENTE");
        auth.setJti(jti);
        auth.setTokenExpiraEm(LocalDateTime.now().plusYears(1));
        auth.setEmitidoEm(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        omsAuthMapper.inserir(auth);
        authId = auth.getId();

        // Necessário para o endpoint de sincronização aceitar o token (cnpjAutorizadoParaJti
        // exige um certificado ativo para o par auth/CNPJ, não só a linha de autorização).
        byte[] pfxBytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(GateBTestProperties.certPath()));
        OmsCompanyCertificate cert = new OmsCompanyCertificate();
        cert.setAuthId(authId);
        cert.setCnpj(CNPJ);
        cert.setEmpresaId(empresa.getId());
        cert.setThumbprint("gateb-revogacao-thumb");
        cert.setCertPfxEnc(certSenhaEncryptor.encryptBytes(pfxBytes));
        cert.setCertSenhaEnc(GateBTestProperties.certPassword());
        cert.setKeyVersion("v1");
        cert.setNotBefore(LocalDateTime.now().minusDays(1));
        cert.setNotAfter(LocalDateTime.now().plusYears(1));
        cert.setAtivo(true);
        omsCertMapper.inserir(cert);

        tokenOriginal = jwtUtil.generateOmsToken(
                "GATEB-REVOGACAO-CLIENTE", empresa.getId(), jti, auth.getTokenExpiraEm(), auth.getEmitidoEm());
    }

    /**
     * @WithMockUser é aplicado por método de teste e reaparece em TODA chamada mockMvc.perform()
     * daquele método (inclusive nas que enviam um Bearer OMS) — JwtFilter.autenticarOms() teria o
     * curto-circuito "já autenticado" acionado pelo contexto do admin, nunca autenticando o token
     * OMS de fato. Testes que misturam chamada administrativa com chamada de token OMS no MESMO
     * método usam este post-processor por requisição em vez de @WithMockUser no método.
     */
    private MockHttpServletRequestBuilder comoAdmin(MockHttpServletRequestBuilder builder) {
        return builder.with(user(ADMIN_EMAIL).roles("ADMIN"));
    }

    private boolean tokenOmsFunciona(String token) throws Exception {
        int status = mockMvc.perform(put("/api/integration/fiscal-numbering/" + CNPJ)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serie\":\"1\",\"proximoNumero\":101}"))
                .andReturn().getResponse().getStatus();
        return status == 200;
    }

    /**
     * Popula o SecurityContext da THREAD ATUAL como ADMIN — necessário porque
     * OmsAuthorizationAdminService.resolverExecutorId() lê SecurityContextHolder, que é
     * ThreadLocal por padrão. Usado pelos testes de concorrência que chamam o serviço
     * diretamente (não via MockMvc) a partir de threads de um ExecutorService.
     */
    private void autenticarComoAdminNaThreadAtual() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ADMIN_EMAIL, null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
    }

    /** Segunda autorização OMS independente, para os testes de isolamento entre authIds diferentes. */
    private Long criarSegundaAutorizacao(String jtiOriginal) {
        OmsFiscalAuthorization auth = new OmsFiscalAuthorization();
        auth.setEmpresaId(empresaId);
        auth.setIntegratorId(integratorId);
        auth.setCodigoOms("GATEB-REVOGACAO-CLIENTE-B");
        auth.setJti(jtiOriginal);
        auth.setTokenExpiraEm(LocalDateTime.now().plusYears(1));
        auth.setEmitidoEm(LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS));
        omsAuthMapper.inserir(auth);
        return auth.getId();
    }

    private OmsAuthorizationRotacionarRequest requestRotacionar(Long expectedVersion) {
        OmsAuthorizationRotacionarRequest req = new OmsAuthorizationRotacionarRequest();
        req.setMotivoCodigo(MotivoAdminOms.ROTINA_SEGURANCA);
        req.setExpectedVersion(expectedVersion);
        return req;
    }

    // =========================================================================
    // Concorrência real (MySQL) — corrida da mesma Idempotency-Key
    // =========================================================================

    @Test
    @DisplayName("duas rotações CONCORRENTES com a MESMA Idempotency-Key e o MESMO authId → ambas recebem o mesmo token, só uma auditoria, versao incrementa só 1 vez")
    void duasRotacoesConcorrentes_mesmaChaveMesmoAuthId_replayConsistente() throws Exception {
        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        Map<Integer, OmsAuthorizationAdminResponse> respostas = new ConcurrentHashMap<>();
        Map<Integer, Exception> erros = new ConcurrentHashMap<>();

        for (int i = 0; i < 2; i++) {
            int idx = i;
            pool.submit(() -> {
                try {
                    largada.await();
                    autenticarComoAdminNaThreadAtual();
                    respostas.put(idx, omsAuthorizationAdminService.rotacionar(authId, requestRotacionar(0L), key, "conc-" + idx));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    erros.put(idx, e);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
        }
        largada.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertTrue(erros.isEmpty(), "nenhuma das duas deveria lançar exceção — erros: " + erros);
        assertEquals(2, respostas.size());
        assertEquals("ROTACIONADA", respostas.get(0).getStatus());
        assertEquals("ROTACIONADA", respostas.get(1).getStatus());
        assertEquals(respostas.get(0).getToken(), respostas.get(1).getToken(),
                "as duas chamadas com a MESMA Idempotency-Key devem receber o MESMO token — achado do GPT corrigido");
        assertEquals(respostas.get(0).getVersao(), respostas.get(1).getVersao());

        Integer totalAuditorias = jdbc.queryForObject(
                "SELECT COUNT(*) FROM oms_fiscal_authorization_audit WHERE idempotency_key = ?", Integer.class, key);
        assertEquals(1, totalAuditorias, "só 1 linha de auditoria para a mesma Idempotency-Key, mesmo com 2 chamadas concorrentes");

        Long versaoFinal = jdbc.queryForObject("SELECT versao FROM oms_fiscal_authorization WHERE id = ?", Long.class, authId);
        assertEquals(1L, versaoFinal, "versao precisa ter incrementado exatamente 1 vez (0->1), nunca 2 vezes");
    }

    @Test
    @DisplayName("mesma Idempotency-Key usada em OUTRA autorização (sequencial) → 409 IDEMPOTENCY_KEY_CONFLICT, sem vazar token, sem mutar a segunda autorização")
    void mesmaChaveEmOutraAutorizacao_conflitoSemMutacao() {
        String jtiOriginalB = "jti-b-original-" + System.nanoTime();
        Long authIdB = criarSegundaAutorizacao(jtiOriginalB);
        String key = UUID.randomUUID().toString();

        autenticarComoAdminNaThreadAtual();
        OmsAuthorizationAdminResponse respA = omsAuthorizationAdminService.rotacionar(authId, requestRotacionar(0L), key, "seq-a");
        assertEquals("ROTACIONADA", respA.getStatus());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> omsAuthorizationAdminService.rotacionar(authIdB, requestRotacionar(0L), key, "seq-b"));
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", ex.getErrorCode());
        assertEquals(409, ex.getHttpStatus());

        Long versaoB = jdbc.queryForObject("SELECT versao FROM oms_fiscal_authorization WHERE id=?", Long.class, authIdB);
        String jtiB = jdbc.queryForObject("SELECT jti FROM oms_fiscal_authorization WHERE id=?", String.class, authIdB);
        assertEquals(0L, versaoB, "authIdB não pode ter sofrido NENHUMA mutação");
        assertEquals(jtiOriginalB, jtiB, "jti de authIdB precisa permanecer o original — nenhum vazamento do token de A");

        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("mesma Idempotency-Key usada em EVENTO diferente (revogar depois rotacionar, sequencial) → 409 IDEMPOTENCY_KEY_CONFLICT")
    void mesmaChaveEmEventoDiferente_conflito() {
        String key = UUID.randomUUID().toString();
        autenticarComoAdminNaThreadAtual();

        OmsAuthorizationRevogarRequest reqRevogar = new OmsAuthorizationRevogarRequest();
        reqRevogar.setMotivoCodigo(MotivoAdminOms.ROTINA_SEGURANCA);
        OmsAuthorizationAdminResponse respRevogar = omsAuthorizationAdminService.revogar(authId, reqRevogar, key, "seq-revoke");
        assertEquals("REVOGADA", respRevogar.getStatus());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> omsAuthorizationAdminService.rotacionar(authId, requestRotacionar(1L), key, "seq-rotate"));
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", ex.getErrorCode());

        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("PROVA DE ROLLBACK REAL: duas rotações concorrentes em AUTORIZAÇÕES DIFERENTES com a MESMA chave — a perdedora fica INTOCADA no MySQL real (jti/versao inalterados)")
    void duasRotacoesConcorrentes_authIdsDiferentesMesmaChave_rollbackRealComprovado() throws Exception {
        String jtiOriginalB = "jti-b-original-" + System.nanoTime();
        Long authIdB = criarSegundaAutorizacao(jtiOriginalB);
        String jtiOriginalA = jdbc.queryForObject("SELECT jti FROM oms_fiscal_authorization WHERE id=?", String.class, authId);

        String key = UUID.randomUUID().toString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        Map<Long, OmsAuthorizationAdminResponse> respostas = new ConcurrentHashMap<>();
        Map<Long, Exception> erros = new ConcurrentHashMap<>();

        for (Long idAuth : List.of(authId, authIdB)) {
            pool.submit(() -> {
                try {
                    largada.await();
                    autenticarComoAdminNaThreadAtual();
                    respostas.put(idAuth, omsAuthorizationAdminService.rotacionar(idAuth, requestRotacionar(0L), key, "race-" + idAuth));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    erros.put(idAuth, e);
                } finally {
                    SecurityContextHolder.clearContext();
                }
            });
        }
        largada.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, respostas.size(), "exatamente uma das duas autorizações deve suceder — respostas=" + respostas.keySet());
        assertEquals(1, erros.size(), "a outra precisa falhar com conflito — erros=" + erros.keySet());

        Exception falhou = erros.values().iterator().next();
        assertInstanceOf(BusinessException.class, falhou);
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", ((BusinessException) falhou).getErrorCode());

        Long idVencedor = respostas.keySet().iterator().next();
        Long idPerdedor = idVencedor.equals(authId) ? authIdB : authId;
        String jtiOriginalPerdedor = idPerdedor.equals(authId) ? jtiOriginalA : jtiOriginalB;

        Long versaoVencedor = jdbc.queryForObject("SELECT versao FROM oms_fiscal_authorization WHERE id=?", Long.class, idVencedor);
        assertEquals(1L, versaoVencedor, "vencedora precisa ter a versao incrementada");

        Long versaoPerdedor = jdbc.queryForObject("SELECT versao FROM oms_fiscal_authorization WHERE id=?", Long.class, idPerdedor);
        String jtiPerdedor = jdbc.queryForObject("SELECT jti FROM oms_fiscal_authorization WHERE id=?", String.class, idPerdedor);
        assertEquals(0L, versaoPerdedor, "perdedora não pode ter sofrido NENHUMA mutação — prova de rollback REAL do MySQL, não simulado");
        assertEquals(jtiOriginalPerdedor, jtiPerdedor, "jti da perdedora precisa permanecer o original — rollback real desfez o UPDATE por completo");

        Integer totalAuditoriasChave = jdbc.queryForObject(
                "SELECT COUNT(*) FROM oms_fiscal_authorization_audit WHERE idempotency_key = ?", Integer.class, key);
        assertEquals(1, totalAuditoriasChave, "só 1 linha de auditoria para a chave, mesmo com a corrida real");
    }

    @Test
    @DisplayName("token OMS válido funciona antes de qualquer ação administrativa")
    void tokenValido_funcionaAntesDaRevogacao() throws Exception {
        assertTrue(tokenOmsFunciona(tokenOriginal));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = "ADMIN")
    @DisplayName("revogar sem Idempotency-Key → 400 INVALID_IDEMPOTENCY_KEY")
    void revogarSemIdempotencyKey_400() throws Exception {
        mockMvc.perform(post("/api/admin/oms-authorizations/" + authId + "/revogar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivoCodigo\":\"ROTINA_SEGURANCA\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("INVALID_IDEMPOTENCY_KEY"));
    }

    @Test
    @WithMockUser(username = "operador@teste.com", roles = "OPERADOR")
    @DisplayName("autenticado sem ROLE_ADMIN → 403 no endpoint administrativo")
    void semRoleAdmin_403() throws Exception {
        mockMvc.perform(post("/api/admin/oms-authorizations/" + authId + "/revogar")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivoCodigo\":\"ROTINA_SEGURANCA\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("fluxo completo: revoga → token antigo passa a 401 → repetir revogação é idempotente → rotaciona → token novo funciona → replay retorna o mesmo token → expectedVersion velho falha")
    void fluxoCompletoRevogacaoRotacao() throws Exception {
        // 1. Revoga
        String revogarKey = UUID.randomUUID().toString();
        mockMvc.perform(comoAdmin(post("/api/admin/oms-authorizations/" + authId + "/revogar"))
                        .header("Idempotency-Key", revogarKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivoCodigo\":\"SUSPEITA_VAZAMENTO\",\"motivoDetalhe\":\"token exposto em log\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOGADA"))
                .andExpect(jsonPath("$.versao").value(1))
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));

        // 2. Token antigo deixa de funcionar IMEDIATAMENTE (achado do Gate 7H corrigido)
        mockMvc.perform(put("/api/integration/fiscal-numbering/" + CNPJ)
                        .header("Authorization", "Bearer " + tokenOriginal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serie\":\"1\",\"proximoNumero\":101}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("INVALID_OMS_TOKEN"));

        // 3. Repetir a revogação é idempotente — 200, sem incrementar versao de novo
        mockMvc.perform(comoAdmin(post("/api/admin/oms-authorizations/" + authId + "/revogar"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivoCodigo\":\"SUSPEITA_VAZAMENTO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOGADA"))
                .andExpect(jsonPath("$.versao").value(1));

        // 4. Rotaciona com expectedVersion = versao pós-revogação (reativação)
        String rotacionarKey = UUID.randomUUID().toString();
        String respostaRotacao = mockMvc.perform(comoAdmin(post("/api/admin/oms-authorizations/" + authId + "/rotacionar"))
                        .header("Idempotency-Key", rotacionarKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivoCodigo\":\"ROTINA_SEGURANCA\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROTACIONADA"))
                .andExpect(jsonPath("$.versao").value(2))
                .andExpect(jsonPath("$.token").exists())
                .andReturn().getResponse().getContentAsString();

        String tokenNovo = objectMapper.readTree(respostaRotacao).path("token").asText();
        assertNotEquals(tokenOriginal, tokenNovo);

        // 5. Token novo funciona
        assertTrue(tokenOmsFunciona(tokenNovo));

        // 6. Replay da MESMA Idempotency-Key da rotação retorna o MESMO token, sem nova escrita na auditoria
        int linhasAuditoriaAntesDoReplay = jdbc.queryForObject(
                "SELECT COUNT(*) FROM oms_fiscal_authorization_audit", Integer.class);

        String respostaReplay = mockMvc.perform(comoAdmin(post("/api/admin/oms-authorizations/" + authId + "/rotacionar"))
                        .header("Idempotency-Key", rotacionarKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivoCodigo\":\"ROTINA_SEGURANCA\",\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ROTACIONADA"))
                .andReturn().getResponse().getContentAsString();

        String tokenReplay = objectMapper.readTree(respostaReplay).path("token").asText();
        assertEquals(tokenNovo, tokenReplay, "replay deve regenerar exatamente o mesmo JWT (HS256 determinístico)");

        int linhasAuditoriaDepoisDoReplay = jdbc.queryForObject(
                "SELECT COUNT(*) FROM oms_fiscal_authorization_audit", Integer.class);
        assertEquals(linhasAuditoriaAntesDoReplay, linhasAuditoriaDepoisDoReplay, "replay não deve gravar nova linha de auditoria");

        // 7. expectedVersion desatualizado (a versao já avançou para 2) → 409 AUTHORIZATION_CHANGED
        mockMvc.perform(comoAdmin(post("/api/admin/oms-authorizations/" + authId + "/rotacionar"))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivoCodigo\":\"ROTINA_SEGURANCA\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("AUTHORIZATION_CHANGED"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = "ADMIN")
    @DisplayName("motivoCodigo=OUTRO sem motivoDetalhe → 422 MOTIVO_DETALHE_OBRIGATORIO")
    void motivoOutroSemDetalhe_422() throws Exception {
        mockMvc.perform(post("/api/admin/oms-authorizations/" + authId + "/revogar")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivoCodigo\":\"OUTRO\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("MOTIVO_DETALHE_OBRIGATORIO"));
    }

    @Test
    @WithMockUser(username = ADMIN_EMAIL, roles = "ADMIN")
    @DisplayName("autorização inexistente → 404 OMS_AUTHORIZATION_NOT_FOUND")
    void autorizacaoInexistente_404() throws Exception {
        mockMvc.perform(post("/api/admin/oms-authorizations/999999/revogar")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivoCodigo\":\"ROTINA_SEGURANCA\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("OMS_AUTHORIZATION_NOT_FOUND"));
    }
}
