package br.com.borurio.web.service;

import br.com.borurio.fiscal.config.SefazReconciliacaoProperties;
import br.com.borurio.fiscal.dto.NfeCceEventoPreparado;
import br.com.borurio.fiscal.dto.NfeEventoRetorno;
import br.com.borurio.fiscal.entity.NfeEvento;
import br.com.borurio.fiscal.entity.NfeEventoIdempotencia;
import br.com.borurio.fiscal.mapper.NfeEventoIdempotenciaMapper;
import br.com.borurio.fiscal.mapper.NfeEventoMapper;
import br.com.borurio.fiscal.mapper.NfeEventoSequenciaMapper;
import br.com.borurio.fiscal.service.CertificadoContexto;
import br.com.borurio.fiscal.service.NfeCceClassificador;
import br.com.borurio.fiscal.service.NfeCceService;
import br.com.borurio.fiscal.service.NfeConsultaSituacaoService;
import br.com.borurio.fiscal.service.NfeEventoRetornoParser;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Gate CC-e (evento 110110, 13-08-2026) -- prova contra MySQL real da atomicidade da transação de
 * FINALIZAÇÃO ({@code aplicarFinalizacao}), distinta da transação de RESERVA já provada em
 * {@link NfeCceGateConcorrenciaRealMySqlIT}.
 *
 * Achado da banca (13-08-2026): o IT de concorrência prova rollback real da reserva
 * (DuplicateKeyException em {@code reservarNovaOperacao}), mas nunca exercita a transação de
 * finalização, que escreve em nfe_evento, nfe_evento_idempotencia e, condicionalmente,
 * nfe_evento_sequencia -- caminho de negócio diferente, precisa de prova própria.
 *
 * Técnica: um {@link NfeEventoIdempotenciaMapper} de teste decora o mapper real (mesma sessão
 * MyBatis/Spring da produção) e força uma falha real SÓ na 2ª escrita de aplicarFinalizacao
 * (idempotenciaMapper.atualizarResultado), depois que a 1ª escrita real (eventoMapper.
 * atualizarResultado) já aconteceu dentro da MESMA transação. Nenhuma flag de teste entra em
 * código de produção -- a decoração vive inteiramente neste arquivo de teste.
 *
 * *IT (não *Test): roda só quando executada explicitamente, contra um MySQL efêmero/descartável --
 * reutiliza {@link Gate3ReconciliacaoTestProperties} (mesmas variáveis GATE3_DB_*). Nunca toca
 * borurio-mysql-dev/hom.
 */
class NfeCceFinalizacaoRollbackRealMySqlIT {

    private static final String CNPJ = "99887755000144";
    private static final String UF = "SP";
    private static final Long EMPRESA_ID = 1L;
    private static SqlSessionFactory sqlSessionFactory;
    private static DataSourceTransactionManager transactionManager;

    @BeforeAll
    static void setUpSchema() throws Exception {
        Flyway.configure()
                .dataSource(Gate3ReconciliacaoTestProperties.dbUrl(), Gate3ReconciliacaoTestProperties.dbUsername(),
                        Gate3ReconciliacaoTestProperties.dbPassword())
                .locations("classpath:sql/migration")
                .load()
                .migrate();

        DataSource dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver",
                Gate3ReconciliacaoTestProperties.dbUrl(), Gate3ReconciliacaoTestProperties.dbUsername(),
                Gate3ReconciliacaoTestProperties.dbPassword());
        transactionManager = new DataSourceTransactionManager(dataSource);

        Environment env = new Environment("nfe-cce-finalizacao-rollback-it", new SpringManagedTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.addMapper(NfeEventoMapper.class);
        config.addMapper(NfeEventoSequenciaMapper.class);
        config.addMapper(NfeEventoIdempotenciaMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);

        limpar();
    }

    @AfterAll
    static void limparFinal() throws Exception {
        limpar();
    }

    private static void limpar() throws SQLException {
        try (Connection conn = novaConexao()) {
            conn.createStatement().execute("DELETE FROM nfe_evento_idempotencia WHERE empresa_id = 1");
            conn.createStatement().execute("DELETE FROM nfe_evento WHERE cnpj_emitente = '" + CNPJ + "'");
            conn.createStatement().execute("DELETE FROM nfe_evento_sequencia WHERE chave_nfe LIKE '352608990000%'");
        }
    }

    private static Connection novaConexao() throws SQLException {
        return DriverManager.getConnection(Gate3ReconciliacaoTestProperties.dbUrl(),
                Gate3ReconciliacaoTestProperties.dbUsername(), Gate3ReconciliacaoTestProperties.dbPassword());
    }

    private void inserirGateBootstrapped(String chave, int ultimoNSeq) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO nfe_evento_sequencia (chave_nfe, tipo_evento, ultimo_nseq_registrado) "
                             + "VALUES (?, '110110', ?)")) {
            ps.setString(1, chave);
            ps.setInt(2, ultimoNSeq);
            ps.executeUpdate();
        }
    }

    private NfeCceService cceServiceQueSempreRegistra() throws Exception {
        NfeCceService cceService = mock(NfeCceService.class);
        when(cceService.prepararEvento(any(), eq(CNPJ), eq(UF), any(), anyInt())).thenAnswer(inv -> {
            int nSeq = inv.getArgument(4);
            return new NfeCceEventoPreparado("ID110110" + nSeq, "2026-08-13T10:00:00-03:00",
                    "<evento-assinado/>", "hash-xyz", "chave-nao-usada-neste-mock", CNPJ, nSeq);
        });
        when(cceService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");
        return cceService;
    }

    private NfeEventoRetorno retornoRegistrado(String nProt) {
        NfeEventoRetorno r = new NfeEventoRetorno();
        r.setCStatLote(128);
        r.setInfEventoPresente(true);
        r.setCStatEvento(135);
        r.setXMotivoEvento("Evento registrado e vinculado a NF-e");
        r.setNProtEvento(nProt);
        return r;
    }

    /**
     * Decora o mapper real (SqlSessionTemplate -- participa da mesma transação Spring que o
     * TransactionTemplate do orquestrador abre/fecha) e força uma falha real SÓ em
     * atualizarResultado -- a 2ª escrita de aplicarFinalizacao, depois que a 1ª (nfe_evento) já
     * aconteceu de verdade na mesma transação. Todos os outros métodos delegam ao mapper real
     * (inserir/marcarTransmitido precisam funcionar de verdade para chegar ao estado TRANSMITIDO
     * antes da finalização).
     */
    private static final class IdempotenciaMapperComFalhaNaFinalizacao implements NfeEventoIdempotenciaMapper {
        private final NfeEventoIdempotenciaMapper delegate;

        IdempotenciaMapperComFalhaNaFinalizacao(NfeEventoIdempotenciaMapper delegate) {
            this.delegate = delegate;
        }

        @Override
        public NfeEventoIdempotencia buscarPorIdempotencyKey(String idempotencyKey) {
            return delegate.buscarPorIdempotencyKey(idempotencyKey);
        }

        @Override
        public NfeEventoIdempotencia buscarPorId(Long id) {
            return delegate.buscarPorId(id);
        }

        @Override
        public NfeEventoIdempotencia buscarPorIdempotencyKeyParaAtualizar(String idempotencyKey) {
            return delegate.buscarPorIdempotencyKeyParaAtualizar(idempotencyKey);
        }

        @Override
        public int inserir(NfeEventoIdempotencia idem) {
            return delegate.inserir(idem);
        }

        @Override
        public int atualizarResultado(NfeEventoIdempotencia idem) {
            throw new RuntimeException("Falha simulada na finalização -- forçada de propósito para provar rollback");
        }

        @Override
        public int marcarTransmitido(Long id) {
            return delegate.marcarTransmitido(id);
        }
    }

    private record EstadoPersistido(String eventoEstado, Integer eventoCstat, String eventoXmotivo,
                                     String eventoNprot, String eventoResolucaoOrigem,
                                     boolean eventoResolvidoEmPresente, String idemEstado,
                                     Integer idemCstat, String idemNprot, boolean idemResolvidoEmPresente,
                                     int seqUltimoNSeq, Long seqEventoAtivoId) {
    }

    private EstadoPersistido capturarEstado(long eventoId, long idemId, String chave) throws SQLException {
        try (Connection conn = novaConexao()) {
            String eventoEstado, eventoXmotivo, eventoNprot, eventoResolucaoOrigem;
            Integer eventoCstat;
            boolean eventoResolvidoEmPresente;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT estado, cstat, xmotivo, nprot, resolucao_origem, resolvido_em FROM nfe_evento WHERE id = ?")) {
                ps.setLong(1, eventoId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    eventoEstado = rs.getString("estado");
                    eventoCstat = (Integer) rs.getObject("cstat");
                    eventoXmotivo = rs.getString("xmotivo");
                    eventoNprot = rs.getString("nprot");
                    eventoResolucaoOrigem = rs.getString("resolucao_origem");
                    eventoResolvidoEmPresente = rs.getTimestamp("resolvido_em") != null;
                }
            }

            String idemEstado, idemNprot;
            Integer idemCstat;
            boolean idemResolvidoEmPresente;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT estado_resultado, cstat_resultado, nprot_resultado, resolvido_em "
                            + "FROM nfe_evento_idempotencia WHERE id = ?")) {
                ps.setLong(1, idemId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    idemEstado = rs.getString("estado_resultado");
                    idemCstat = (Integer) rs.getObject("cstat_resultado");
                    idemNprot = rs.getString("nprot_resultado");
                    idemResolvidoEmPresente = rs.getTimestamp("resolvido_em") != null;
                }
            }

            int seqUltimoNSeq;
            Long seqEventoAtivoId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT ultimo_nseq_registrado, evento_ativo_id FROM nfe_evento_sequencia "
                            + "WHERE chave_nfe = ? AND tipo_evento = '110110'")) {
                ps.setString(1, chave);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    seqUltimoNSeq = rs.getInt("ultimo_nseq_registrado");
                    seqEventoAtivoId = (Long) rs.getObject("evento_ativo_id");
                }
            }

            return new EstadoPersistido(eventoEstado, eventoCstat, eventoXmotivo, eventoNprot, eventoResolucaoOrigem,
                    eventoResolvidoEmPresente, idemEstado, idemCstat, idemNprot, idemResolvidoEmPresente,
                    seqUltimoNSeq, seqEventoAtivoId);
        }
    }

    @Test
    @DisplayName("aplicarFinalizacao: falha real após a 1ª escrita (nfe_evento) -- rollback real deixa "
            + "nfe_evento, nfe_evento_idempotencia e nfe_evento_sequencia exatamente no estado TRANSMITIDO anterior")
    void aplicarFinalizacao_falhaAposPrimeiraEscrita_rollbackCompletoPreservaEstadoAnterior() throws Exception {
        limpar();
        String chave = "35260899000000000191550020000000093000000027";
        String idemKey = java.util.UUID.randomUUID().toString();
        inserirGateBootstrapped(chave, 0);

        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(sqlSessionFactory);
        NfeEventoMapper eventoMapper = sessionTemplate.getMapper(NfeEventoMapper.class);
        NfeEventoSequenciaMapper sequenciaMapper = sessionTemplate.getMapper(NfeEventoSequenciaMapper.class);
        NfeEventoIdempotenciaMapper idempotenciaMapperReal = sessionTemplate.getMapper(NfeEventoIdempotenciaMapper.class);
        NfeEventoIdempotenciaMapper idempotenciaMapperComFalha = new IdempotenciaMapperComFalhaNaFinalizacao(idempotenciaMapperReal);

        NfeCceService cceService = cceServiceQueSempreRegistra();
        NfeEventoRetornoParser eventoRetornoParser = mock(NfeEventoRetornoParser.class);
        when(eventoRetornoParser.parse(any())).thenReturn(retornoRegistrado("135260000009002"));
        NfeConsultaSituacaoService consultaSituacaoService = mock(NfeConsultaSituacaoService.class);

        NfeCceOrquestradorService service = new NfeCceOrquestradorService(eventoMapper, sequenciaMapper,
                idempotenciaMapperComFalha, cceService, eventoRetornoParser, new NfeCceClassificador(),
                consultaSituacaoService, new SefazReconciliacaoProperties(), transactionManager);
        CertificadoContexto cert = mock(CertificadoContexto.class);

        // corrigir() percorre reserva (real) -> preparar/transmitir (mock, sem rede) -> finalização,
        // que é onde a falha forçada acontece -- nenhum atalho/reflection, é o próprio fluxo de
        // produção que leva o estado até o ponto exigido.
        RuntimeException falha = assertThrows(RuntimeException.class, () -> service.corrigir(
                9300L, EMPRESA_ID, CNPJ, UF, chave, "Correção do endereço do destinatário — teste de rollback", idemKey, cert));
        assertTrue(falha.getMessage() != null && falha.getMessage().contains("Falha simulada"),
                "a exceção que escapa precisa ser a falha forçada na finalização, não um erro genérico: " + falha);

        Long eventoId;
        Long idemId;
        try (Connection conn = novaConexao()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM nfe_evento WHERE chave_nfe = ? AND tipo_evento = '110110'")) {
                ps.setString(1, chave);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "a linha de nfe_evento da RESERVA precisa existir -- só a FINALIZAÇÃO falhou");
                    eventoId = rs.getLong("id");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM nfe_evento_idempotencia WHERE idempotency_key = ?")) {
                ps.setString(1, idemKey);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    idemId = rs.getLong("id");
                }
            }
        }

        EstadoPersistido depoisDaFalha = capturarEstado(eventoId, idemId, chave);

        // Estado esperado: exatamente o que marcarTransmitido (transação ANTERIOR, bem-sucedida)
        // deixou -- TRANSMITIDO dos dois lados, nenhum campo de resultado preenchido, sequência
        // ainda não avançada, gate ainda ocupado pela identidade em voo.
        assertEquals(NfeEvento.Estados.TRANSMITIDO, depoisDaFalha.eventoEstado(),
                "nfe_evento não pode ter avançado para REGISTRADO -- a escrita real que avançou o "
                        + "estado precisa ter sido revertida pelo rollback");
        assertNull(depoisDaFalha.eventoCstat(), "cstat de nfe_evento precisa continuar NULL após o rollback");
        assertNull(depoisDaFalha.eventoXmotivo());
        assertNull(depoisDaFalha.eventoNprot());
        assertNull(depoisDaFalha.eventoResolucaoOrigem());
        assertFalse(depoisDaFalha.eventoResolvidoEmPresente());

        assertEquals(NfeEventoIdempotencia.Estados.TRANSMITIDO, depoisDaFalha.idemEstado(),
                "nfe_evento_idempotencia não pode ter avançado para REGISTRADO -- a escrita que falhou "
                        + "nunca pode deixar rastro parcial");
        assertNull(depoisDaFalha.idemCstat());
        assertNull(depoisDaFalha.idemNprot());
        assertFalse(depoisDaFalha.idemResolvidoEmPresente());

        assertEquals(0, depoisDaFalha.seqUltimoNSeq(),
                "nfe_evento_sequencia.ultimo_nseq_registrado nunca pode avançar sem a finalização "
                        + "ter completado de verdade -- ela nem chegou a ser tentada, pois a exceção "
                        + "interrompeu a transação antes desse ponto do método");
        assertEquals(eventoId, depoisDaFalha.seqEventoAtivoId(),
                "gate precisa continuar ocupado pela MESMA identidade em voo -- nunca liberado por "
                        + "uma finalização que não completou");
    }
}
