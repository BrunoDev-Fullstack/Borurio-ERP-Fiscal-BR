package br.com.borurio.web.gateb;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate B — sobe o contexto Spring REAL contra o MySQL descartável (docker run, mesma imagem
 * mysql:8.4 do docker-compose.dev.yml, credenciais exclusivamente via variáveis de ambiente —
 * ver GateBTestProperties). Deixa o Flyway aplicar todas as migrations (V001..V032) do jeito
 * real, não mockado.
 *
 * Nomeada *IT (não *Test) deliberadamente — Surefire NÃO pega esse padrão por padrão, então
 * `mvn test` normal continua funcionando sem Docker. Só roda quando executada explicitamente
 * (ex: mvn test -Dtest=GateBSmokeIT -Dsurefire.failIfNoSpecifiedTests=false).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
class GateBSmokeIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", GateBTestProperties::dbUrl);
        registry.add("spring.datasource.username", GateBTestProperties::dbUsername);
        registry.add("spring.datasource.password", GateBTestProperties::dbPassword);
        // Certificado sintético autoassinado, gerado só pra este Gate B (keytool, fora do
        // repositório) — CertificadoServiceImpl carrega um certificado no @PostConstruct e
        // derruba o contexto Spring se o arquivo não existir. Não é certificado real/fiscal,
        // nunca assina nada de verdade — nenhum teste deste Gate B chama a SEFAZ.
        registry.add("FISCAL_CERT_PATH", GateBTestProperties::certPath);
        registry.add("FISCAL_CERT_PASSWORD", GateBTestProperties::certPassword);
    }

    @org.springframework.beans.factory.annotation.Autowired
    DataSource dataSource;

    @Test
    void flywayAplicouTodasAsMigrationsInclusiveV032() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {

            ResultSet rsHistory = st.executeQuery(
                    "SELECT COUNT(*) qtd, MAX(CAST(version AS UNSIGNED)) maxVersion FROM flyway_schema_history WHERE success = 1");
            assertTrue(rsHistory.next());
            int qtdSucesso = rsHistory.getInt("qtd");
            int maxVersion = rsHistory.getInt("maxVersion");
            System.out.println("[GateB] flyway_schema_history: " + qtdSucesso + " migrations aplicadas com sucesso, versão máxima=" + maxVersion);
            assertEquals(32, maxVersion, "Flyway precisa ter aplicado até V032");

            ResultSet rsFail = st.executeQuery("SELECT COUNT(*) qtd FROM flyway_schema_history WHERE success = 0");
            assertTrue(rsFail.next());
            assertEquals(0, rsFail.getInt("qtd"), "nenhuma migration deve ter falhado");

            ResultSet rsTable = st.executeQuery("SHOW TABLES LIKE 'nfe_sequencia_auditoria'");
            assertTrue(rsTable.next(), "tabela nfe_sequencia_auditoria precisa existir");

            ResultSet rsCols = st.executeQuery("SHOW FULL COLUMNS FROM nfe_sequencia_auditoria");
            StringBuilder cols = new StringBuilder();
            while (rsCols.next()) {
                cols.append(rsCols.getString("Field")).append("=").append(rsCols.getString("Type"))
                        .append("(null=").append(rsCols.getString("Null")).append(") ");
            }
            System.out.println("[GateB] nfe_sequencia_auditoria colunas: " + cols);

            ResultSet rsIdx = st.executeQuery("SHOW INDEX FROM nfe_sequencia WHERE Key_name = 'uk_emitente_serie'");
            assertTrue(rsIdx.next(), "uk_emitente_serie (V011) precisa existir em nfe_sequencia");
            System.out.println("[GateB] uk_emitente_serie confirmada em nfe_sequencia (unique=" + !rsIdx.getBoolean("Non_unique") + ")");

            ResultSet rsAuthVersao = st.executeQuery("SHOW COLUMNS FROM oms_fiscal_authorization LIKE 'versao'");
            assertTrue(rsAuthVersao.next(), "coluna versao (V032) precisa existir em oms_fiscal_authorization");

            ResultSet rsAuditTable = st.executeQuery("SHOW TABLES LIKE 'oms_fiscal_authorization_audit'");
            assertTrue(rsAuditTable.next(), "tabela oms_fiscal_authorization_audit (V032) precisa existir");
        }
    }
}
