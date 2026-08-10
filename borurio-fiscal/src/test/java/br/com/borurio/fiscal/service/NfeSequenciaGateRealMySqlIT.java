package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.entity.NfeSequencia;
import br.com.borurio.fiscal.exception.SequenciaComEmissaoAtivaException;
import br.com.borurio.fiscal.mapper.NfeSequenciaMapper;
import br.com.borurio.fiscal.service.impl.NfeSequenciaServiceImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0-1 (07-08-2026, hardening pós-banca) — prova contra MySQL real, mesma classe de mecanismo
 * (FOR UPDATE + SERIALIZABLE) que a banca usou para reproduzir a corrupção original.
 *
 * *IT (não *Test): Surefire não pega esse padrão por padrão — `mvn test` normal não precisa de
 * MySQL. Só roda quando executada explicitamente, com um MySQL efêmero/descartável (nunca os
 * containers persistentes de dev/HOM — ver Gate1AuditTestProperties).
 *
 * Usa MyBatis puro (sem Spring) — NfeSequenciaServiceImpl é instanciado diretamente, cada cenário
 * roda dentro de uma SqlSession própria (TransactionIsolationLevel.SERIALIZABLE, autocommit=false),
 * a mesma semântica de @Transactional(isolation = SERIALIZABLE) usada em produção, mas sem
 * precisar montar o contexto Spring inteiro (Empresa, certificados, etc.) — o que está sob teste
 * aqui é especificamente o mapeamento real de emissao_ativa_id e o comportamento do guard novo
 * contra o InnoDB de verdade, não o restante do fluxo de sincronização (já coberto por 7 testes
 * unitários em FiscalNumberingServiceTest, cenários A-G).
 */
class NfeSequenciaGateRealMySqlIT {

    private static final String CNPJ = "11222333000199";
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUpSchemaEDados() throws Exception {
        DataSource dataSource = new org.apache.ibatis.datasource.pooled.PooledDataSource(
                "com.mysql.cj.jdbc.Driver",
                Gate1AuditTestProperties.dbUrl(),
                Gate1AuditTestProperties.dbUsername(),
                Gate1AuditTestProperties.dbPassword());
        Environment env = new Environment("gate1-p0-1-it", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.addMapper(NfeSequenciaMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);

        try (Connection conn = DriverManager.getConnection(
                Gate1AuditTestProperties.dbUrl(), Gate1AuditTestProperties.dbUsername(), Gate1AuditTestProperties.dbPassword())) {
            conn.createStatement().execute("DELETE FROM nfe_sequencia WHERE cnpj_emitente = '" + CNPJ + "'");
        }
    }

    @AfterAll
    static void limpar() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                Gate1AuditTestProperties.dbUrl(), Gate1AuditTestProperties.dbUsername(), Gate1AuditTestProperties.dbPassword())) {
            conn.createStatement().execute("DELETE FROM nfe_sequencia WHERE cnpj_emitente = '" + CNPJ + "'");
        }
    }

    /** Insere/reseta a linha de nfe_sequencia direto via JDBC puro — estado de partida do cenário. */
    private void prepararLinha(String serie, int ultimoNumero, Long emissaoAtivaId) throws Exception {
        try (Connection conn = DriverManager.getConnection(
                Gate1AuditTestProperties.dbUrl(), Gate1AuditTestProperties.dbUsername(), Gate1AuditTestProperties.dbPassword())) {
            conn.createStatement().execute(
                    "DELETE FROM nfe_sequencia WHERE cnpj_emitente = '" + CNPJ + "' AND serie = '" + serie + "'");
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO nfe_sequencia (cnpj_emitente, serie, ultimo_numero, emissao_ativa_id) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, CNPJ);
                ps.setString(2, serie);
                ps.setInt(3, ultimoNumero);
                if (emissaoAtivaId == null) ps.setNull(4, java.sql.Types.BIGINT);
                else ps.setLong(4, emissaoAtivaId);
                ps.executeUpdate();
            }
        }
    }

    private int lerUltimoNumeroReal(String serie) throws Exception {
        try (Connection conn = DriverManager.getConnection(
                Gate1AuditTestProperties.dbUrl(), Gate1AuditTestProperties.dbUsername(), Gate1AuditTestProperties.dbPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT ultimo_numero FROM nfe_sequencia WHERE cnpj_emitente = ? AND serie = ?")) {
            ps.setString(1, CNPJ);
            ps.setString(2, serie);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "linha deveria existir");
                return rs.getInt(1);
            }
        }
    }

    private Long lerEmissaoAtivaIdReal(String serie) throws Exception {
        try (Connection conn = DriverManager.getConnection(
                Gate1AuditTestProperties.dbUrl(), Gate1AuditTestProperties.dbUsername(), Gate1AuditTestProperties.dbPassword());
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT emissao_ativa_id FROM nfe_sequencia WHERE cnpj_emitente = ? AND serie = ?")) {
            ps.setString(1, CNPJ);
            ps.setString(2, serie);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "linha deveria existir");
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    @Test
    @DisplayName("DEPOIS DA CORREÇÃO — avanço de numeração com emissão ativa: 409/exceção real contra MySQL, ultimo_numero permanece intacto")
    void atualizarSequencia_realMySql_emissaoAtivaBloqueiaAvanco_estadoIntacto() throws Exception {
        prepararLinha("1", 4, 501L); // gate ocupado — nNF 5 em voo

        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.SERIALIZABLE)) {
            NfeSequenciaMapper mapperReal = session.getMapper(NfeSequenciaMapper.class);
            NfeSequenciaServiceImpl service = new NfeSequenciaServiceImpl(mapperReal);

            SequenciaComEmissaoAtivaException ex = assertThrows(SequenciaComEmissaoAtivaException.class,
                    () -> service.atualizarSequencia(CNPJ, "1", 6)); // OMS tentando sincronizar próximo=6 (ultrapassa o 5 em voo)

            assertEquals(CNPJ, ex.getCnpjEmitente());
            assertEquals("1", ex.getSerie());
            assertEquals(501L, ex.getEmissaoAtivaId());

            session.rollback(); // equivalente ao rollback automático do Spring quando a exceção sobe
        }

        // Estado no banco, lido por uma conexão NOVA (fora de qualquer transação/lock): igual ao
        // que era antes da tentativa — exatamente o contrário do que a banca reproduziu antes da
        // correção (onde ultimo_numero avançava para 5, corrompendo o gate de nNF 5 em voo).
        assertEquals(4, lerUltimoNumeroReal("1"), "ultimo_numero NÃO pode ter avançado");
        assertEquals(501L, lerEmissaoAtivaIdReal("1"), "gate continua íntegro, apontando pro mesmo ciclo");
    }

    @Test
    @DisplayName("DEPOIS DA CORREÇÃO — avanço de numeração com gate LIVRE continua funcionando normalmente contra MySQL real")
    void atualizarSequencia_realMySql_gateLivre_avancaNormalmente() throws Exception {
        prepararLinha("2", 4, null);

        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.SERIALIZABLE)) {
            NfeSequenciaMapper mapperReal = session.getMapper(NfeSequenciaMapper.class);
            NfeSequenciaServiceImpl service = new NfeSequenciaServiceImpl(mapperReal);

            service.atualizarSequencia(CNPJ, "2", 6);
            session.commit();
        }

        assertEquals(5, lerUltimoNumeroReal("2"), "gate livre: comportamento homologado preservado, avança normalmente");
    }

    @Test
    @DisplayName("DEPOIS DA CORREÇÃO — sincronização idempotente (regra 1) permanece aceita mesmo com gate ocupado, contra MySQL real")
    void atualizarSequencia_realMySql_idempotenteComGateOcupado_naoBloqueia() throws Exception {
        prepararLinha("3", 4, 777L);

        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.SERIALIZABLE)) {
            NfeSequenciaMapper mapperReal = session.getMapper(NfeSequenciaMapper.class);
            NfeSequenciaServiceImpl service = new NfeSequenciaServiceImpl(mapperReal);

            assertDoesNotThrow(() -> service.atualizarSequencia(CNPJ, "3", 5)); // alvo=4 == atual=4, no-op
            session.commit();
        }

        assertEquals(4, lerUltimoNumeroReal("3"));
        assertEquals(777L, lerEmissaoAtivaIdReal("3"), "gate nem foi tocado — chamada idempotente não mexe em nada");
    }
}
