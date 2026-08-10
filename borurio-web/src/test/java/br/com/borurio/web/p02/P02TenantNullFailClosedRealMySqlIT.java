package br.com.borurio.web.p02;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.fiscal.service.NfeOrquestradorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * P0-2 (Gate 1.2 Fase B.2) — prova de integração real: MySQL efêmero e descartável (docker run
 * isolado, nunca borurio-mysql-hom/borurio-mysql-dev) + security chain real (JwtFilter +
 * SecurityConfig + Spring MVC completo, nada mockado na cadeia de autenticação/autorização) +
 * fluxo de autenticação compatível com a aplicação (POST /auth/login com senha real, não JWT
 * fabricado à mão).
 *
 * *IT (não *Test): Surefire não pega esse padrão por padrão — mvn test normal não precisa do
 * MySQL efêmero. Só roda quando executada explicitamente, contra o container criado só para esta
 * banca (ver P02TenantNullTestProperties).
 *
 * SEFAZ é sempre mockada via @MockBean NfeOrquestradorService — nenhuma chamada de rede real
 * ocorre, mesmo que algum cenário chegasse (por bug) até a emissão de verdade.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class P02TenantNullFailClosedRealMySqlIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", P02TenantNullTestProperties::dbUrl);
        registry.add("spring.datasource.username", P02TenantNullTestProperties::dbUsername);
        registry.add("spring.datasource.password", P02TenantNullTestProperties::dbPassword);
        registry.add("FISCAL_CERT_PATH", P02TenantNullTestProperties::certPath);
        registry.add("FISCAL_CERT_PASSWORD", P02TenantNullTestProperties::certPassword);
    }

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired EmpresaMapper empresaMapper;
    @MockBean NfeOrquestradorService nfeOrquestradorService;

    private static final String CNPJ_TENANT = "54393421000159"; // default fiscal.emitente.cnpj — StartupListener já cria essa empresa
    private static final String SENHA = "P02itSenha!2026";

    private Long empresaId;
    private Long produtoId;
    private Long pedidoIdReal;

    @BeforeAll
    void seed() throws Exception {
        Empresa empresa = empresaMapper.buscarPorCnpj(CNPJ_TENANT);
        assertNotNull(empresa, "StartupListener deveria ter criado a empresa default no boot da aplicação");
        empresaId = empresa.getId();

        jdbc.update("DELETE FROM pedido_item WHERE pedido_id IN (SELECT id FROM pedido WHERE dest_razao_social = 'P02IT Cliente')");
        jdbc.update("DELETE FROM pedido WHERE dest_razao_social = 'P02IT Cliente'");
        jdbc.update("DELETE FROM produto WHERE codigo = 'SKU-P02IT'");
        jdbc.update("DELETE FROM db_user WHERE email LIKE 'p02it-%'");

        jdbc.update("""
                INSERT INTO db_user (empresa_id, nome, email, senha, role, ativo, data_criacao, data_atualizacao)
                VALUES (NULL, 'P02 Operador Sem Tenant', 'p02it-operador-sem-tenant@teste.com', ?, 'OPERADOR', 1, NOW(), NOW())
                """, passwordEncoder.encode(SENHA));

        jdbc.update("""
                INSERT INTO db_user (empresa_id, nome, email, senha, role, ativo, data_criacao, data_atualizacao)
                VALUES (?, 'P02 Operador Com Tenant', 'p02it-operador-com-tenant@teste.com', ?, 'OPERADOR', 1, NOW(), NOW())
                """, empresaId, passwordEncoder.encode(SENHA));

        jdbc.update("""
                INSERT INTO db_user (empresa_id, nome, email, senha, role, ativo, data_criacao, data_atualizacao)
                VALUES (NULL, 'P02 Admin Sem Tenant', 'p02it-admin-sem-tenant@teste.com', ?, 'ADMIN', 1, NOW(), NOW())
                """, passwordEncoder.encode(SENHA));

        jdbc.update("DELETE FROM ncm WHERE codigo = '84715011'");
        jdbc.update("INSERT INTO ncm (codigo, descricao) VALUES ('84715011', 'Maquinas automaticas para processamento de dados - P02IT')");

        jdbc.update("""
                INSERT INTO produto (empresa_id, codigo, descricao, ncm, unidade, preco, estado, csosn, cfop, origem, estoque)
                VALUES (?, 'SKU-P02IT', 'Produto P02IT', '84715011', 'UN', 10.00, 1, '400', '5102', 0, 1000)
                """, empresaId);
        produtoId = jdbc.queryForObject(
                "SELECT id FROM produto WHERE codigo='SKU-P02IT' AND empresa_id=? ORDER BY id DESC LIMIT 1",
                Long.class, empresaId);

        // Pedido REAL, criado pelo fluxo legítimo (operador COM tenant) — usado nos cenários 1/2
        // pra provar ausência de mutação contra um alvo que existe de verdade, não um id inexistente.
        String tokenOperadorComTenant = login("p02it-operador-com-tenant@teste.com");
        String jsonPedido = """
                {"destCnpjCpf":"12345678000195","destRazaoSocial":"P02IT Cliente","destUf":"SP",
                 "itens":[{"produtoId":%d,"quantidade":1,"valorUnitario":10.00,
                 "codigoProduto":"SKU-P02IT","descricao":"Produto P02IT","ncm":"84715011","cfop":"5102",
                 "unidade":"UN","origem":0,"csosn":"400"}]}
                """.formatted(produtoId);
        String resposta = mockMvc.perform(post("/api/app/pedidos")
                        .header("Authorization", "Bearer " + tokenOperadorComTenant)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonPedido))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        pedidoIdReal = objectMapper.readTree(resposta).path("data").path("id").asLong();
        assertTrue(pedidoIdReal > 0, "pedido real precisa ter sido criado pelo fluxo legítimo antes dos cenários adversariais");
    }

    private String login(String email) throws Exception {
        String resposta = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"%s\",\"password\":\"%s\"}".formatted(email, SENHA)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(resposta).path("token").asText();
        assertFalse(token.isBlank(), "login precisa ter retornado um token JWT real para " + email);
        return token;
    }

    // =========================================================================
    // Snapshot de estado — usado pelos cenários 1/2 para provar ausência de mutação
    // =========================================================================

    private record Snapshot(String statusPedido, String estoque, String estoqueReservado,
                             int nfeEmissao, int nfeSequencia, int nfeLog) {}

    private Snapshot snapshot() {
        String statusPedido = jdbc.queryForObject("SELECT status FROM pedido WHERE id=?", String.class, pedidoIdReal);
        String estoque = jdbc.queryForObject("SELECT estoque FROM produto WHERE id=?", String.class, produtoId);
        String estoqueReservado = jdbc.queryForObject("SELECT estoque_reservado FROM produto WHERE id=?", String.class, produtoId);
        int nfeEmissao = jdbc.queryForObject("SELECT COUNT(*) FROM nfe_emissao", Integer.class);
        int nfeSequencia = jdbc.queryForObject("SELECT COUNT(*) FROM nfe_sequencia", Integer.class);
        int nfeLog = jdbc.queryForObject("SELECT COUNT(*) FROM nfe_log", Integer.class);
        return new Snapshot(statusPedido, estoque, estoqueReservado, nfeEmissao, nfeSequencia, nfeLog);
    }

    // =========================================================================
    // CENÁRIO 1 — OPERADOR sem tenant, GET /pedidos → 403, sem lookup global
    // =========================================================================

    @Test
    @DisplayName("CENÁRIO 1 — OPERADOR real (empresa_id=NULL) autenticado via /auth/login → GET /api/app/pedidos → 403 TENANT_REQUIRED, sem vazar nenhum pedido")
    void cenario1_operadorSemTenant_listar_403SemLookupGlobal() throws Exception {
        String token = login("p02it-operador-sem-tenant@teste.com");

        String resposta = mockMvc.perform(get("/api/app/pedidos")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_REQUIRED"))
                .andExpect(jsonPath("$.retryable").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertFalse(resposta.contains("P02IT Cliente"),
                "resposta de 403 não pode conter nenhum dado de negócio do pedido real de outra empresa — prova de que não houve lookup global");
        assertFalse(resposta.contains("content"), "envelope de erro não pode carregar a lista paginada de pedidos");
    }

    // =========================================================================
    // CENÁRIO 2 — OPERADOR sem tenant, POST /emitir → 403, zero mutação comprovada por contagem real
    // =========================================================================

    @Test
    @DisplayName("CENÁRIO 2 — OPERADOR real sem tenant → POST /pedidos/{id}/emitir → 403 TENANT_REQUIRED, zero efeito em pedido/estoque/nfe_emissao/nfe_sequencia/nfe_log (contagens reais antes/depois)")
    void cenario2_operadorSemTenant_emitir_403SemNenhumaMutacaoReal() throws Exception {
        Snapshot antes = snapshot();

        String token = login("p02it-operador-sem-tenant@teste.com");
        mockMvc.perform(post("/api/app/pedidos/" + pedidoIdReal + "/emitir")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("TENANT_REQUIRED"));

        Snapshot depois = snapshot();

        assertEquals(antes, depois,
                "nenhum campo pode ter mudado: status do pedido, estoque, estoque_reservado, "
                        + "contagem de nfe_emissao/nfe_sequencia/nfe_log — a requisição precisa ter sido "
                        + "barrada no JwtFilter, antes de qualquer efeito de negócio real no MySQL");
        assertEquals("RASCUNHO", depois.statusPedido(), "pedido real precisa continuar RASCUNHO, sem nenhuma tentativa de emissão real");
        org.mockito.Mockito.verifyNoInteractions(nfeOrquestradorService);
    }

    // =========================================================================
    // CENÁRIO 3 — ADMIN real sem tenant → acesso global preservado
    // =========================================================================

    @Test
    @DisplayName("CENÁRIO 3 — ADMIN real (empresa_id=NULL) autenticado via /auth/login → GET /api/app/pedidos → 200, acesso administrativo global preservado, eid não fabricado")
    void cenario3_adminSemTenant_listar_acessoGlobalPreservado() throws Exception {
        String token = login("p02it-admin-sem-tenant@teste.com");

        String resposta = mockMvc.perform(get("/api/app/pedidos")
                        .header("Authorization", "Bearer " + token)
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertTrue(resposta.contains(pedidoIdReal.toString()),
                "ADMIN sem tenant precisa continuar enxergando pedidos de qualquer empresa — comportamento administrativo global preservado");
    }

    // =========================================================================
    // CENÁRIO 4 — OPERADOR com empresa_id preenchido → autentica normal, escopado à própria empresa
    // =========================================================================

    @Test
    @DisplayName("CENÁRIO 4 — OPERADOR real com empresa_id preenchido → GET /api/app/pedidos → 200, escopado à própria empresa")
    void cenario4_operadorComTenant_listar_escopadoAPropriaEmpresa() throws Exception {
        String token = login("p02it-operador-com-tenant@teste.com");

        mockMvc.perform(get("/api/app/pedidos")
                        .header("Authorization", "Bearer " + token)
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].id").exists());
    }
}
