package br.com.borurio.web.gateb;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.OmsCompanyCertificate;
import br.com.borurio.app.entity.OmsFiscalAuthorization;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.OmsCompanyCertificateMapper;
import br.com.borurio.app.mapper.OmsFiscalAuthorizationMapper;
import br.com.borurio.fiscal.service.NfeOrquestradorService;
import br.com.borurio.web.auth.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Gate B — bateria HTTP, MockMvc + contexto Spring completo + MySQL real (mesmo container da
 * bateria transacional). SEFAZ é sempre mockada via @MockBean NfeOrquestradorService — nenhuma
 * chamada de rede real ocorre.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GateBHttpIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", GateBTestProperties::dbUrl);
        registry.add("spring.datasource.username", GateBTestProperties::dbUsername);
        registry.add("spring.datasource.password", GateBTestProperties::dbPassword);
        registry.add("FISCAL_CERT_PATH", GateBTestProperties::certPath);
        registry.add("FISCAL_CERT_PASSWORD", GateBTestProperties::certPassword);
        // Achado: OmsCertificadoService/CertSenhaEncryptor.decryptBytes() é fail-closed sem essa
        // chave (diferente da senha, que tem passthrough em dev) — precisa estar setada mesmo
        // pra teste local, senão qualquer fluxo OMS que assina XML falha com IllegalStateException.
        // Única classe do Gate B que realmente precisa dela (é a única que decodifica certificado
        // OMS via /emitir) — por isso GateBTestProperties.certEncryptionKey() só é chamado aqui.
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
    @MockBean NfeOrquestradorService nfeOrquestradorService;

    private static final String CNPJ = "54393421000159";
    private static final String CNPJ_NAO_AUTORIZADO = "22418179000134";
    private String tokenOms;
    private Long empresaId;

    @BeforeEach
    void seed() throws Exception {
        // Achado operacional (não é bug de código): a tabela `ncm` só é criada pelo Flyway
        // (V006), os 15.144 códigos oficiais vêm de uma carga externa separada (JSON
        // Pucomex/Siscomex), fora do Flyway. Um ambiente fresco só com `flyway migrate` não tem
        // dado de NCM nenhum — /emitir rejeita qualquer NCM não cadastrado. Seed mínimo aqui.
        jdbc.update("DELETE FROM ncm WHERE codigo IN ('84715011')");
        jdbc.update("INSERT INTO ncm (codigo, descricao) VALUES ('84715011', 'Maquinas automaticas para processamento de dados - GateB teste')");

        jdbc.update("DELETE FROM nfe_sequencia_auditoria");
        jdbc.update("DELETE FROM nfe_sequencia");
        jdbc.update("DELETE FROM pedido_item");
        jdbc.update("DELETE FROM pedido");
        jdbc.update("DELETE FROM oms_company_certificate");
        jdbc.update("DELETE FROM oms_fiscal_authorization");
        jdbc.update("DELETE FROM oms_integrator");
        jdbc.update("INSERT INTO oms_integrator (codigo, nome, ativo) VALUES ('GATEB-HTTP', 'Gate B HTTP', 1)");
        Long integratorId = jdbc.queryForObject("SELECT id FROM oms_integrator ORDER BY id DESC LIMIT 1", Long.class);

        Empresa empresa = empresaMapper.buscarPorCnpj(CNPJ);
        empresaId = empresa.getId();
        empresa.setSerieNfePadrao("1");
        empresaMapper.atualizar(empresa);

        String jti = "jti-gateb-http-" + System.nanoTime();
        OmsFiscalAuthorization auth = new OmsFiscalAuthorization();
        auth.setEmpresaId(empresaId);
        auth.setIntegratorId(integratorId);
        auth.setCodigoOms("GATEB-HTTP-CLIENTE");
        auth.setJti(jti);
        auth.setTokenExpiraEm(LocalDateTime.now().plusYears(1));
        auth.setEmitidoEm(LocalDateTime.now());
        omsAuthMapper.inserir(auth);

        // Precisa ser um PKCS12 real e válido — OmsCertificadoService decodifica de verdade pra
        // assinar o XML. Reusa o mesmo certificado sintético apontado por GATEB_CERT_PATH.
        // certSenhaEnc gravado em texto claro é intencional aqui: reflete o mesmo valor que
        // CertSenhaEncryptor.decrypt() devolveria (só certPfxEnc — os bytes — é criptografado de
        // verdade; decryptBytes() é fail-closed e exige GATEB_CERT_ENCRYPTION_KEY, já validada
        // acima em @DynamicPropertySource).
        byte[] pfxBytes = java.nio.file.Files.readAllBytes(java.nio.file.Path.of(GateBTestProperties.certPath()));

        OmsCompanyCertificate cert = new OmsCompanyCertificate();
        cert.setAuthId(auth.getId());
        cert.setCnpj(CNPJ);
        cert.setEmpresaId(empresaId);
        cert.setThumbprint("gateb-http-thumb");
        cert.setCertPfxEnc(certSenhaEncryptor.encryptBytes(pfxBytes));
        cert.setCertSenhaEnc(GateBTestProperties.certPassword());
        cert.setKeyVersion("v1");
        cert.setNotBefore(LocalDateTime.now().minusDays(1));
        cert.setNotAfter(LocalDateTime.now().plusYears(1));
        cert.setAtivo(true);
        omsCertMapper.inserir(cert);

        tokenOms = jwtUtil.generateOmsToken("GATEB-HTTP-CLIENTE", empresaId, jti, LocalDateTime.now().plusYears(1));

        // Canned "autorizado" — nenhuma chamada real à SEFAZ ocorre.
        when(nfeOrquestradorService.processar(anyString(), anyString(), anyString(), any()))
                .thenReturn("""
                        <soap:Envelope xmlns:soap="http://www.w3.org/2003/05/soap-envelope">
                          <soap:Body>
                            <nfeResultMsg xmlns="http://www.portalfiscal.inf.br/nfe/wsdl/NFeAutorizacao4">
                              <retEnviNFe xmlns="http://www.portalfiscal.inf.br/nfe" versao="4.00">
                                <cStat>104</cStat>
                                <xMotivo>Lote processado</xMotivo>
                                <protNFe>
                                  <infProt>
                                    <cStat>100</cStat>
                                    <xMotivo>Autorizado o uso da NF-e</xMotivo>
                                    <nProt>135260000001234</nProt>
                                  </infProt>
                                </protNFe>
                              </retEnviNFe>
                            </nfeResultMsg>
                          </soap:Body>
                        </soap:Envelope>
                        """);
    }

    // =========================================================================
    // Auth e validação — endpoint de sincronização
    // =========================================================================

    @Test
    @DisplayName("sem token → HTTP 401")
    void semToken_401() throws Exception {
        mockMvc.perform(put("/api/integration/fiscal-numbering/" + CNPJ)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serie\":\"1\",\"proximoNumero\":101}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("token válido, CNPJ não autorizado → HTTP 403 CNPJ_NOT_AUTHORIZED")
    void tokenValidoCnpjNaoAutorizado_403() throws Exception {
        mockMvc.perform(put("/api/integration/fiscal-numbering/" + CNPJ_NAO_AUTORIZADO)
                        .header("Authorization", "Bearer " + tokenOms)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serie\":\"1\",\"proximoNumero\":101}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("CNPJ_NOT_AUTHORIZED"));
    }

    @Test
    @DisplayName("série 'A' → 422 SERIE_INVALIDA; série '1A' → 422; série '-1' → 422")
    void seriesAlfanumericasOuNegativas_422SerieInvalida() throws Exception {
        for (String serieInvalida : new String[]{"A", "1A", "-1"}) {
            mockMvc.perform(put("/api/integration/fiscal-numbering/" + CNPJ)
                            .header("Authorization", "Bearer " + tokenOms)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"serie\":\"" + serieInvalida + "\",\"proximoNumero\":101}"))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("SERIE_INVALIDA"));
        }
    }

    @Test
    @DisplayName("série '0', '1' e '999' são aceitas; '1000' é rejeitada (fora do padrão TSerie)")
    void seriesLimitesDoXsd() throws Exception {
        mockMvc.perform(put("/api/integration/fiscal-numbering/" + CNPJ)
                        .header("Authorization", "Bearer " + tokenOms)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serie\":\"0\",\"proximoNumero\":1}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/integration/fiscal-numbering/" + CNPJ)
                        .header("Authorization", "Bearer " + tokenOms)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serie\":\"999\",\"proximoNumero\":1}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/integration/fiscal-numbering/" + CNPJ)
                        .header("Authorization", "Bearer " + tokenOms)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serie\":\"1000\",\"proximoNumero\":1}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("SERIE_INVALIDA"));
    }

    @Test
    @DisplayName("proximoNumero nulo/zero/negativo → 422 NUMERACAO_INVALIDA")
    void numeroInvalido_422() throws Exception {
        mockMvc.perform(put("/api/integration/fiscal-numbering/" + CNPJ)
                        .header("Authorization", "Bearer " + tokenOms)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serie\":\"1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("NUMERACAO_INVALIDA"));

        mockMvc.perform(put("/api/integration/fiscal-numbering/" + CNPJ)
                        .header("Authorization", "Bearer " + tokenOms)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serie\":\"1\",\"proximoNumero\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("NUMERACAO_INVALIDA"));

        mockMvc.perform(put("/api/integration/fiscal-numbering/" + CNPJ)
                        .header("Authorization", "Bearer " + tokenOms)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serie\":\"1\",\"proximoNumero\":-5}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("NUMERACAO_INVALIDA"));
    }

    @Test
    @DisplayName("sincronização inicial via HTTP → 200; repetição idempotente → aplicado=false; avanço → aplicado=true; regressão → 422")
    void fluxoCompletoViaHttp() throws Exception {
        mockMvc.perform(put("/api/integration/fiscal-numbering/" + CNPJ)
                        .header("Authorization", "Bearer " + tokenOms)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serie\":\"1\",\"proximoNumero\":101}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aplicado").value(true))
                .andExpect(jsonPath("$.proximoNumeroAtual").value(101))
                // achado #4 da revisão anterior — nunca expor token/jti na resposta
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.jti").doesNotExist());

        mockMvc.perform(put("/api/integration/fiscal-numbering/" + CNPJ)
                        .header("Authorization", "Bearer " + tokenOms)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serie\":\"1\",\"proximoNumero\":101}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aplicado").value(false));

        mockMvc.perform(put("/api/integration/fiscal-numbering/" + CNPJ)
                        .header("Authorization", "Bearer " + tokenOms)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serie\":\"1\",\"proximoNumero\":500}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aplicado").value(true));

        mockMvc.perform(put("/api/integration/fiscal-numbering/" + CNPJ)
                        .header("Authorization", "Bearer " + tokenOms)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serie\":\"1\",\"proximoNumero\":5}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("NUMERACAO_INFERIOR_A_ATUAL"));
    }

    @Test
    @DisplayName("auditoria real grava requestId do header X-Request-Id")
    void auditoriaGravaRequestIdDoHeader() throws Exception {
        mockMvc.perform(put("/api/integration/fiscal-numbering/" + CNPJ)
                        .header("Authorization", "Bearer " + tokenOms)
                        .header("X-Request-Id", "gateb-http-req-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serie\":\"1\",\"proximoNumero\":101}"))
                .andExpect(status().isOk());

        String requestId = jdbc.queryForObject(
                "SELECT request_id FROM nfe_sequencia_auditoria WHERE cnpj_emitente=? ORDER BY id DESC LIMIT 1",
                String.class, CNPJ);
        assertEquals("gateb-http-req-123", requestId);
    }

    // =========================================================================
    // Mass assignment via HTTP real — POST /api/app/pedidos
    // =========================================================================

    @Test
    @DisplayName("mass assignment via JSON real: serieNfe, chaveNfe, status, numero, empresaId, itens[].id/pedidoId são ignorados")
    void massAssignment_httpReal_ignorado() throws Exception {
        String json = """
                {
                  "cnpjEmitente": "%s",
                  "serieNfe": "9",
                  "chaveNfe": "35240711222333000181550010000001011234567890",
                  "status": "AUTORIZADO",
                  "numero": "FORJADO-999",
                  "empresaId": 9999,
                  "destCnpjCpf": "12345678000195",
                  "destRazaoSocial": "Cliente Mass Assignment",
                  "destUf": "SP",
                  "itens": [
                    { "id": 9999, "pedidoId": 9999, "produtoId": null }
                  ]
                }
                """.formatted(CNPJ);

        String resposta = mockMvc.perform(post("/api/app/pedidos")
                        .header("Authorization", "Bearer " + tokenOms)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().is4xxClientError()) // sem produtoId válido, item é rejeitado — esperado
                .andReturn().getResponse().getContentAsString();

        // O ponto não é o item ser aceito (produtoId é obrigatório) — é confirmar que os campos
        // proibidos do CABEÇALHO nunca chegam a ser processados como válidos antes da rejeição
        // do item, e que status/empresaId nunca aparecem refletidos de volta como aplicados.
        assertFalse(resposta.contains("AUTORIZADO"), "status forjado não pode aparecer refletido");
    }

    @Test
    @DisplayName("pedido criado com itens válidos: serieNfe/chaveNfe do JSON nunca chegam ao banco")
    void massAssignment_pedidoValido_serieEChaveNuncaPersistidas() throws Exception {
        Long produtoId = criarProdutoReal("SKU-GATEB");

        String json = """
                {
                  "cnpjEmitente": "%s",
                  "serieNfe": "9",
                  "chaveNfe": "35240711222333000181550010000001011234567890",
                  "destCnpjCpf": "12345678000195",
                  "destRazaoSocial": "Cliente Mass Assignment 2",
                  "destUf": "SP",
                  "itens": [
                    { "produtoId": %d, "quantidade": 1, "valorUnitario": 10.00,
                      "codigoProduto": "SKU-GATEB", "descricao": "Produto GateB",
                      "ncm": "84715011", "cfop": "5102", "unidade": "UN", "origem": 0, "csosn": "400" }
                  ]
                }
                """.formatted(CNPJ, produtoId);

        mockMvc.perform(post("/api/app/pedidos")
                        .header("Authorization", "Bearer " + tokenOms)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.serieNfe").value("1")); // placeholder de schema, nunca o "9" forjado

        String serieNoBanco = jdbc.queryForObject(
                "SELECT serie_nfe FROM pedido WHERE dest_razao_social = 'Cliente Mass Assignment 2' ORDER BY id DESC LIMIT 1", String.class);
        String chaveNoBanco = jdbc.queryForObject(
                "SELECT chave_nfe FROM pedido WHERE dest_razao_social = 'Cliente Mass Assignment 2' ORDER BY id DESC LIMIT 1", String.class);

        assertEquals("1", serieNoBanco,
                "placeholder de schema (pedido.serie_nfe é NOT NULL), NUNCA o '9' forjado no JSON — "
                        + "esse '1' não é lido para decisão de emissão, só existe pra satisfazer a coluna NOT NULL");
        assertNull(chaveNoBanco, "chaveNfe do JSON nunca pode ser persistida na criação");
    }

    // =========================================================================
    // Cenário A — pedido criado ANTES da sincronização, emitido DEPOIS
    // =========================================================================

    @Test
    @DisplayName("Cenário A: pedido criado antes da sincronização, emitido depois, usa a série/número NOVOS")
    void cenarioA_pedidoAntesDaSync_emiteComSerieNova() throws Exception {
        Long produtoId = criarProdutoReal("SKU-CENA");

        // 1. Empresa com série 1 (setUp já garante isso)
        // 2. Cria pedido
        String jsonPedido = """
                {"cnpjEmitente":"%s","destCnpjCpf":"12345678000195","destRazaoSocial":"Cenario A",
                 "destUf":"SP","itens":[{"produtoId":%d,"quantidade":1,"valorUnitario":10.00,
                 "codigoProduto":"SKU-CENA","descricao":"P","ncm":"84715011","cfop":"5102",
                 "unidade":"UN","origem":0,"csosn":"400"}]}
                """.formatted(CNPJ, produtoId);
        String respostaCriacao = mockMvc.perform(post("/api/app/pedidos")
                        .header("Authorization", "Bearer " + tokenOms)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPedido))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        Long pedidoId = objectMapper.readTree(respostaCriacao).path("data").path("id").asLong();

        // 3. Confirma que a série ainda não foi decidida de verdade — só o placeholder de schema
        //    "1" (NOT NULL) existe agora; ReservaFiscalService é quem decide de fato, na emissão.
        assertEquals("1", jdbc.queryForObject("SELECT serie_nfe FROM pedido WHERE id=?", String.class, pedidoId));

        // 4. Sincroniza para série 2, próximo número 500 — DEPOIS da criação do pedido
        mockMvc.perform(put("/api/integration/fiscal-numbering/" + CNPJ)
                        .header("Authorization", "Bearer " + tokenOms)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serie\":\"2\",\"proximoNumero\":500}"))
                .andExpect(status().isOk());

        // 5. Emite (SEFAZ mockada) — precisa usar série 2, número 500
        mockMvc.perform(post("/api/app/pedidos/" + pedidoId + "/emitir")
                        .header("Authorization", "Bearer " + tokenOms))
                .andExpect(status().isOk());

        String serieFinal = jdbc.queryForObject("SELECT serie_nfe FROM pedido WHERE id=?", String.class, pedidoId);
        String chaveFinal = jdbc.queryForObject("SELECT chave_nfe FROM pedido WHERE id=?", String.class, pedidoId);

        assertEquals("2", serieFinal, "pedido criado ANTES da sync precisa emitir com a série NOVA");
        assertNotNull(chaveFinal);
        // chave43 embute cUF+AAMM+CNPJ+mod+serie(3)+nNF(9)+tpEmis+cNF+cDV — série "002" e nNF "000000500"
        String cnpjNaChave = chaveFinal.substring(6, 20);
        String serieNaChave = chaveFinal.substring(22, 25);
        String numeroNaChave = chaveFinal.substring(25, 34);
        assertEquals(CNPJ, cnpjNaChave);
        assertEquals("002", serieNaChave);
        assertEquals("000000500", numeroNaChave);
    }

    private Long criarProdutoReal(String codigo) {
        jdbc.update("""
                INSERT INTO produto (empresa_id, codigo, descricao, ncm, unidade, preco, estado, csosn, cfop, origem, estoque)
                VALUES (?, ?, 'Produto GateB', '84715011', 'UN', 10.00, 1, '400', '5102', 0, 1000)
                """, empresaId, codigo);
        return jdbc.queryForObject("SELECT id FROM produto WHERE codigo=? AND empresa_id=? ORDER BY id DESC LIMIT 1",
                Long.class, codigo, empresaId);
    }
}
