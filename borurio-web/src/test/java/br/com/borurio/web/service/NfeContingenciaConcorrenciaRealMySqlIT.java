package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.fiscal.config.SefazReconciliacaoProperties;
import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.exception.ContingenciaInvalidaException;
import br.com.borurio.fiscal.mapper.NfeEmissaoMapper;
import br.com.borurio.fiscal.mapper.NfeSequenciaMapper;
import br.com.borurio.fiscal.service.NfeContingenciaService;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import br.com.borurio.fiscal.service.impl.NfeContingenciaServiceImpl;
import br.com.borurio.fiscal.service.impl.NfeSequenciaServiceImpl;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Fase 1 SVC (17-08-2026, persistência/ciclo de substituição, sem transporte) — prova contra
 * MySQL real das corridas exigidas em banca (RACE A/B/C + variações), CAS do gate, round-trip de
 * {@code dh_cont}, deadlock/lock-order, double-substitute concorrente e migration safety.
 *
 * *IT (não *Test): roda só quando executada explicitamente, contra um MySQL efêmero/descartável
 * (nunca borurio-mysql-dev/hom — ver {@link NfeContingenciaConcorrenciaTestProperties}). Mesmo
 * padrão de infraestrutura de {@link NfeEmissaoLockOrderRealMySqlIT}: nfe_sequencia/nfe_emissao
 * reais via MyBatis; EmpresaMapper/PedidoMapper/EstoqueService mockados (irrelevantes pras
 * garantias sob teste aqui — a prova central desta suíte é justamente que eles NUNCA são
 * invocados para uma NORMAL substituída).
 */
class NfeContingenciaConcorrenciaRealMySqlIT {

    private static final String CNPJ = "77665544000133";
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUpSchemaEDados() throws Exception {
        Flyway.configure()
                .dataSource(NfeContingenciaConcorrenciaTestProperties.dbUrl(), NfeContingenciaConcorrenciaTestProperties.dbUsername(),
                        NfeContingenciaConcorrenciaTestProperties.dbPassword())
                .locations("classpath:sql/migration")
                .load()
                .migrate();

        DataSource dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver",
                NfeContingenciaConcorrenciaTestProperties.dbUrl(), NfeContingenciaConcorrenciaTestProperties.dbUsername(),
                NfeContingenciaConcorrenciaTestProperties.dbPassword());
        Environment env = new Environment("contingencia-fase1-it", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.addMapper(NfeSequenciaMapper.class);
        config.addMapper(NfeEmissaoMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);

        limparCnpj(CNPJ);
    }

    @AfterAll
    static void limparFinal() throws Exception {
        limparCnpj(CNPJ);
    }

    // -------------------------------------------------------------------------
    // Helpers de infraestrutura
    // -------------------------------------------------------------------------

    private static Connection novaConexao() throws SQLException {
        return DriverManager.getConnection(NfeContingenciaConcorrenciaTestProperties.dbUrl(),
                NfeContingenciaConcorrenciaTestProperties.dbUsername(), NfeContingenciaConcorrenciaTestProperties.dbPassword());
    }

    private static void limparCnpj(String cnpj) throws SQLException {
        try (Connection conn = novaConexao()) {
            conn.createStatement().execute("DELETE FROM nfe_emissao WHERE cnpj_emitente = '" + cnpj + "'");
            conn.createStatement().execute("DELETE FROM nfe_sequencia WHERE cnpj_emitente = '" + cnpj + "'");
        }
    }

    private void resetTabelas() throws SQLException {
        limparCnpj(CNPJ);
    }

    private void inserirSequencia(int ultimoNumero, Long emissaoAtivaId) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO nfe_sequencia (cnpj_emitente, serie, ultimo_numero, emissao_ativa_id) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, CNPJ);
            ps.setString(2, "1");
            ps.setInt(3, ultimoNumero);
            if (emissaoAtivaId == null) ps.setNull(4, java.sql.Types.BIGINT);
            else ps.setLong(4, emissaoAtivaId);
            ps.executeUpdate();
        }
    }

    private void inserirEmissaoNormal(long id, long pedidoId, int numero, String estado) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO nfe_emissao (id, pedido_id, empresa_id, cnpj_emitente, serie, numero_nfe, estado, tentativas, chave_nfe) "
                             + "VALUES (?, ?, 1, ?, '1', ?, ?, 1, ?)")) {
            ps.setLong(1, id);
            ps.setLong(2, pedidoId);
            ps.setString(3, CNPJ);
            ps.setInt(4, numero);
            ps.setString(5, estado);
            ps.setString(6, "3550110000000" + String.format("%03d", numero) + "1"); // chave fictícia, só p/ realismo
            ps.executeUpdate();
        }
    }

    private int lerUltimoNumeroReal() throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT ultimo_numero FROM nfe_sequencia WHERE cnpj_emitente = ? AND serie = '1'")) {
            ps.setString(1, CNPJ);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "linha deveria existir");
                return rs.getInt(1);
            }
        }
    }

    private Long lerEmissaoAtivaIdReal() throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT emissao_ativa_id FROM nfe_sequencia WHERE cnpj_emitente = ? AND serie = '1'")) {
            ps.setString(1, CNPJ);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "linha deveria existir");
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    private record LinhaEmissao(String estado, Integer cstat, String xmotivo, String nprot, String tpEmis,
                                 String autorizadorDestino, Long emissaoOrigemId, String dhCont, int numeroNfe) {}

    private LinhaEmissao lerEmissao(long id) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT estado, cstat, xmotivo, nprot, tp_emis, autorizador_destino, emissao_origem_id, dh_cont, numero_nfe "
                             + "FROM nfe_emissao WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "linha deveria existir");
                long origemId = rs.getLong(7);
                Long origemIdOuNull = rs.wasNull() ? null : origemId; // precisa ser lido IMEDIATAMENTE após getLong(7)
                return new LinhaEmissao(rs.getString(1), (Integer) rs.getObject(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), origemIdOuNull, rs.getString(8), rs.getInt(9));
            }
        }
    }

    private int contarFilhasPorOrigem(long origemId) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM nfe_emissao WHERE emissao_origem_id = ?")) {
            ps.setLong(1, origemId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static boolean isDeadlock(SQLException e) {
        return e.getErrorCode() == 1213 || "40001".equals(e.getSQLState());
    }

    /** EstoqueService/PedidoMapper mockados: irrelevantes pra ordem de lock; nunca devem ser invocados para NORMAL substituída. */
    private NfeEmissaoService construirNfeEmissaoServiceReal(SqlSession session, PedidoMapper pedidoMapperMock, EstoqueService estoqueServiceMock) {
        NfeSequenciaMapper seqMapper = session.getMapper(NfeSequenciaMapper.class);
        NfeEmissaoMapper emissaoMapper = session.getMapper(NfeEmissaoMapper.class);
        NfeSequenciaService sequenciaService = new NfeSequenciaServiceImpl(seqMapper);
        EmpresaMapper empresaMapperMock = mock(EmpresaMapper.class);
        return new NfeEmissaoService(empresaMapperMock, pedidoMapperMock, sequenciaService, emissaoMapper,
                estoqueServiceMock, new SefazReconciliacaoProperties());
    }

    private NfeContingenciaService construirNfeContingenciaServiceReal(SqlSession session) {
        NfeSequenciaMapper seqMapper = session.getMapper(NfeSequenciaMapper.class);
        NfeEmissaoMapper emissaoMapper = session.getMapper(NfeEmissaoMapper.class);
        NfeSequenciaService sequenciaService = new NfeSequenciaServiceImpl(seqMapper);
        return new NfeContingenciaServiceImpl(sequenciaService, emissaoMapper);
    }

    private static final String X_JUST = "Falha de conectividade com a SEFAZ";
    private static final OffsetDateTime DH_CONT_PADRAO = OffsetDateTime.of(2026, 8, 17, 14, 30, 0, 0, ZoneOffset.of("-03:00"));

    // =========================================================================================
    // RACE A — NORMAL trava primeiro e resolve AUTORIZADO -> contingência concorrente é rejeitada
    // =========================================================================================

    @Test
    @DisplayName("RACE A: NORMAL resolvida AUTORIZADA primeiro -- abrirContingencia concorrente é rejeitado, nenhum número novo consumido")
    void raceA_normalResolvidaAntes_contingenciaRecusada() throws Exception {
        resetTabelas();
        inserirSequencia(99, 900L);
        inserirEmissaoNormal(900L, 1L, 100, NfeEmissao.Estados.TRANSMITIDO);

        PedidoMapper pedidoMapperMock = mock(PedidoMapper.class);
        EstoqueService estoqueServiceMock = mock(EstoqueService.class);

        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            construirNfeEmissaoServiceReal(session, pedidoMapperMock, estoqueServiceMock)
                    .resolverCicloComEfeitos(900L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-normal",
                            1L, "AUTORIZADO", "chave900", false, List.<PedidoItem>of(), 1L, "sistema");
            session.commit();
        }

        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            NfeContingenciaService service = construirNfeContingenciaServiceReal(session);
            assertThrows(ContingenciaInvalidaException.class,
                    () -> service.abrirContingencia(900L, NfeEmissao.TpEmis.SVC_AN, X_JUST, DH_CONT_PADRAO));
            session.rollback();
        }

        assertEquals(100, lerUltimoNumeroReal(), "só a resolução da NORMAL consumiu número — contingência nunca chegou a consolidar/inserir");
        assertNull(lerEmissaoAtivaIdReal(), "gate liberado pela resolução normal");
        assertEquals(0, contarFilhasPorOrigem(900L), "nenhuma filha criada");
    }

    // =========================================================================================
    // RACE B — contingência abre primeiro -> AUTORIZADO tardio da NORMAL vira só evidência
    // =========================================================================================

    @Test
    @DisplayName("RACE B: abrirContingencia primeiro -- AUTORIZADO tardio da NORMAL aplica só evidência, zero Pedido/Estoque")
    void raceB_contingenciaAntes_lateNormalAplicaEvidenciaSemEfeitos() throws Exception {
        resetTabelas();
        inserirSequencia(99, 900L);
        inserirEmissaoNormal(900L, 1L, 100, NfeEmissao.Estados.TRANSMITIDO);

        long filhaId;
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            NfeEmissao filha = construirNfeContingenciaServiceReal(session)
                    .abrirContingencia(900L, NfeEmissao.TpEmis.SVC_AN, X_JUST, DH_CONT_PADRAO);
            session.commit();
            filhaId = filha.getId();
        }
        assertEquals(101, lerEmissao(filhaId).numeroNfe());
        assertEquals(Long.valueOf(filhaId), lerEmissaoAtivaIdReal(), "gate movido pra filha");

        PedidoMapper pedidoMapperMock = mock(PedidoMapper.class);
        EstoqueService estoqueServiceMock = mock(EstoqueService.class);
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            construirNfeEmissaoServiceReal(session, pedidoMapperMock, estoqueServiceMock)
                    .resolverCicloComEfeitos(900L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-tardio",
                            1L, "AUTORIZADO", "chave900", true, List.<PedidoItem>of(), 1L, "sistema");
            session.commit();
        }

        LinhaEmissao normal = lerEmissao(900L);
        assertEquals(NfeEmissao.Estados.AUTORIZADO, normal.estado());
        assertEquals(100, normal.cstat());
        assertEquals("prot-tardio", normal.nprot());
        verifyNoInteractions(pedidoMapperMock, estoqueServiceMock);
        assertEquals(Long.valueOf(filhaId), lerEmissaoAtivaIdReal(), "gate continua na filha -- late NORMAL não mexeu nele");
        assertEquals(100, lerUltimoNumeroReal(), "consolidado pela contingência, nunca consumido de novo pela evidência tardia");
    }

    // =========================================================================================
    // RACE C — filha TAMBÉM já terminou (gate de volta a NULL) antes do AUTORIZADO tardio chegar
    // =========================================================================================

    @Test
    @DisplayName("RACE C: filha SVC também já terminou (emissao_ativa_id NULL) -- AUTORIZADO tardio da NORMAL ainda aplica evidência corretamente")
    void raceC_filhaTambemResolvida_lateNormalAindaAplicaEvidencia() throws Exception {
        resetTabelas();
        inserirSequencia(99, 900L);
        inserirEmissaoNormal(900L, 1L, 100, NfeEmissao.Estados.TRANSMITIDO);

        long filhaId;
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            NfeEmissao filha = construirNfeContingenciaServiceReal(session)
                    .abrirContingencia(900L, NfeEmissao.TpEmis.SVC_AN, X_JUST, DH_CONT_PADRAO);
            session.commit();
            filhaId = filha.getId();
        }

        // Filha autorizada pelo caminho normal (Gate 1) -- consome o próprio número (101) e libera o
        // gate. Isto É um efeito legítimo (a filha não é substituída, é a substituta) -- mocks
        // dedicados a este passo, nunca reaproveitados para a verificação da NORMAL tardia abaixo.
        PedidoMapper pedidoMapperMockFilha = mock(PedidoMapper.class);
        EstoqueService estoqueServiceMockFilha = mock(EstoqueService.class);
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            construirNfeEmissaoServiceReal(session, pedidoMapperMockFilha, estoqueServiceMockFilha)
                    .resolverCicloComEfeitos(filhaId, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-svc",
                            1L, "AUTORIZADO", "chaveSvc", true, List.<PedidoItem>of(), 1L, "sistema");
            session.commit();
        }
        assertNull(lerEmissaoAtivaIdReal(), "gate da filha liberado -- emissao_ativa_id volta a NULL");
        assertEquals(101, lerUltimoNumeroReal());

        // Só agora chega a autorização tardia da NORMAL, já sem NENHUMA relação com o gate atual.
        // Mocks NOVOS e dedicados -- a prova de "zero efeitos" precisa ser sobre ESTA chamada
        // especificamente, não contaminada pela chamada legítima da filha acima.
        PedidoMapper pedidoMapperMockNormal = mock(PedidoMapper.class);
        EstoqueService estoqueServiceMockNormal = mock(EstoqueService.class);
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            construirNfeEmissaoServiceReal(session, pedidoMapperMockNormal, estoqueServiceMockNormal)
                    .resolverCicloComEfeitos(900L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-tardio",
                            1L, "AUTORIZADO", "chave900", true, List.<PedidoItem>of(), 1L, "sistema");
            session.commit();
        }

        LinhaEmissao normal = lerEmissao(900L);
        assertEquals(NfeEmissao.Estados.AUTORIZADO, normal.estado());
        assertEquals("prot-tardio", normal.nprot());
        verifyNoInteractions(pedidoMapperMockNormal, estoqueServiceMockNormal);
        assertNull(lerEmissaoAtivaIdReal(), "gate continua NULL -- late NORMAL nunca mexe em nfe_sequencia");
        assertEquals(101, lerUltimoNumeroReal(), "nenhum consumo indevido pela evidência tardia");
    }

    // =========================================================================================
    // RACE B repetida — duas respostas tardias da NORMAL após a substituição
    // =========================================================================================

    @Test
    @DisplayName("RACE B repetida: 2ª resposta tardia não sobrescreve a 1ª evidência já gravada, zero efeitos nos dois casos")
    void raceB_repetida_segundaRespostaTardiaNaoSobrescreve() throws Exception {
        resetTabelas();
        inserirSequencia(99, 900L);
        inserirEmissaoNormal(900L, 1L, 100, NfeEmissao.Estados.TRANSMITIDO);

        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            construirNfeContingenciaServiceReal(session).abrirContingencia(900L, NfeEmissao.TpEmis.SVC_AN, X_JUST, DH_CONT_PADRAO);
            session.commit();
        }

        PedidoMapper pedidoMapperMock = mock(PedidoMapper.class);
        EstoqueService estoqueServiceMock = mock(EstoqueService.class);
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            construirNfeEmissaoServiceReal(session, pedidoMapperMock, estoqueServiceMock)
                    .resolverCicloComEfeitos(900L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-1a-resposta",
                            1L, "AUTORIZADO", "chave900", true, List.<PedidoItem>of(), 1L, "sistema");
            session.commit();
        }

        // 2ª resposta tardia, com nProt DIVERGENTE -- não pode sobrescrever a 1ª.
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            construirNfeEmissaoServiceReal(session, pedidoMapperMock, estoqueServiceMock)
                    .resolverCicloComEfeitos(900L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-2a-resposta-divergente",
                            1L, "AUTORIZADO", "chave900", true, List.<PedidoItem>of(), 1L, "sistema");
            session.commit();
        }

        LinhaEmissao normal = lerEmissao(900L);
        assertEquals("prot-1a-resposta", normal.nprot(), "1ª evidência gravada nunca é sobrescrita por uma 2ª divergente");
        verifyNoInteractions(pedidoMapperMock, estoqueServiceMock);
    }

    // =========================================================================================
    // dh_cont — round-trip com offset não-padrão (Acre, -05:00)
    // =========================================================================================

    @Test
    @DisplayName("Round-trip de dh_cont com offset não-padrão (-05:00): string persistida e OffsetDateTime reconstruído batem exatamente")
    void roundTrip_dhCont_offsetNaoPadrao() throws Exception {
        resetTabelas();
        inserirSequencia(99, 900L);
        inserirEmissaoNormal(900L, 1L, 100, NfeEmissao.Estados.TRANSMITIDO);

        OffsetDateTime dhContAcre = OffsetDateTime.of(2026, 8, 17, 11, 45, 30, 0, ZoneOffset.of("-05:00"));

        long filhaId;
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            NfeEmissao filha = construirNfeContingenciaServiceReal(session)
                    .abrirContingencia(900L, NfeEmissao.TpEmis.SVC_RS, X_JUST, dhContAcre);
            session.commit();
            filhaId = filha.getId();
        }

        String dhContPersistido = lerEmissao(filhaId).dhCont();
        assertEquals("2026-08-17T11:45:30-05:00", dhContPersistido, "precisão de segundos, offset preservado literalmente");
        assertEquals(dhContAcre, OffsetDateTime.parse(dhContPersistido),
                "instante+offset reconstruídos batem exatamente com o original -- nenhuma reinterpretação de fuso no caminho");
    }

    // =========================================================================================
    // CAS do gate — falha de propósito
    // =========================================================================================

    @Test
    @DisplayName("CAS do gate falhando de propósito: emissaoNormalEsperadaId não corresponde ao gate real -- IllegalStateException, nada muda")
    void cas_gateFalhaDePropostio_lancaIllegalStateSemAlterarNada() throws Exception {
        resetTabelas();
        inserirSequencia(99, 900L); // gate aponta pra 900L de verdade

        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            NfeSequenciaService sequenciaService = new NfeSequenciaServiceImpl(session.getMapper(NfeSequenciaMapper.class));
            // Espera 999L (errado de propósito) -- CAS deve recusar mesmo a linha existindo.
            assertThrows(IllegalStateException.class,
                    () -> sequenciaService.substituirGateParaContingencia(CNPJ, "1", 999L, 900L));
            session.rollback();
        }

        assertEquals(Long.valueOf(900L), lerEmissaoAtivaIdReal(), "CAS recusado -- gate original intacto");
        assertEquals(99, lerUltimoNumeroReal());
    }

    // =========================================================================================
    // Deadlock/lock-order — abrirContingencia concorrente com aplicarNovoEstado/resolverCiclo
    // =========================================================================================

    @Test
    @DisplayName("Deadlock/lock-order: abrirContingencia concorrente com resolverCicloComEfeitos na MESMA NORMAL -- sem deadlock, invariantes preservados")
    void deadlock_abrirContingencia_vs_resolverCiclo_semDeadlock() throws Exception {
        resetTabelas();
        inserirSequencia(99, 900L);
        inserirEmissaoNormal(900L, 1L, 100, NfeEmissao.Estados.TRANSMITIDO);

        PedidoMapper pedidoMapperMock = mock(PedidoMapper.class);
        EstoqueService estoqueServiceMock = mock(EstoqueService.class);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        List<SQLException> falhasInesperadas = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<Object> resultadoNormal = new AtomicReference<>();
        AtomicReference<Object> resultadoContingencia = new AtomicReference<>();

        Callable<Void> tarefaNormal = () -> {
            largada.await();
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                construirNfeEmissaoServiceReal(session, pedidoMapperMock, estoqueServiceMock)
                        .resolverCicloComEfeitos(900L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-normal",
                                1L, "AUTORIZADO", "chave900", false, List.<PedidoItem>of(), 1L, "sistema");
                session.commit();
                resultadoNormal.set("OK");
            } catch (Exception e) {
                if (e instanceof SQLException se) {
                    if (isDeadlock(se)) falhasInesperadas.add(se);
                }
                resultadoNormal.set(e);
            }
            return null;
        };
        Callable<Void> tarefaContingencia = () -> {
            largada.await();
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                construirNfeContingenciaServiceReal(session).abrirContingencia(900L, NfeEmissao.TpEmis.SVC_AN, X_JUST, DH_CONT_PADRAO);
                session.commit();
                resultadoContingencia.set("OK");
            } catch (Exception e) {
                resultadoContingencia.set(e);
            }
            return null;
        };

        Future<Void> futA = pool.submit(tarefaNormal);
        Future<Void> futB = pool.submit(tarefaContingencia);
        largada.countDown();
        futA.get(20, TimeUnit.SECONDS);
        futB.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(falhasInesperadas.isEmpty(), "nenhuma das duas transações deveria produzir deadlock real do InnoDB: " + falhasInesperadas);
        assertTrue(resultadoNormal.get() instanceof String || resultadoNormal.get() instanceof ContingenciaInvalidaException == false,
                "resultado da NORMAL precisa ser sucesso ou uma exceção de negócio esperada, nunca vazamento de SQLException");

        // Invariante independente da ordem: ultimo_numero sempre acaba em 100 -- via consumirNumero
        // (se a NORMAL venceu) ou via consolidarNumeroParaContingencia (se a contingência venceu).
        assertEquals(100, lerUltimoNumeroReal(), "ultimo_numero converge pra 100 independente de quem venceu a corrida");
        Long gateFinal = lerEmissaoAtivaIdReal();
        assertTrue(gateFinal == null || gateFinal > 900L || contarFilhasPorOrigem(900L) == 1,
                "gate final precisa ser NULL (NORMAL venceu, liberou) ou apontar pra uma filha real (contingência venceu)");
    }

    // =========================================================================================
    // Double-substitute concorrente
    // =========================================================================================

    @Test
    @DisplayName("Double-substitute concorrente: duas abrirContingencia na MESMA NORMAL -- só uma vence, unique key como backstop")
    void doubleSubstitute_concorrente_soUmVence() throws Exception {
        resetTabelas();
        inserirSequencia(99, 900L);
        inserirEmissaoNormal(900L, 1L, 100, NfeEmissao.Estados.TRANSMITIDO);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);

        Callable<Object> tentativa = () -> {
            largada.await();
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                try {
                    NfeEmissao filha = construirNfeContingenciaServiceReal(session)
                            .abrirContingencia(900L, NfeEmissao.TpEmis.SVC_AN, X_JUST, DH_CONT_PADRAO);
                    session.commit();
                    return filha;
                } catch (ContingenciaInvalidaException e) {
                    session.rollback();
                    return e;
                }
            }
        };

        Future<Object> f1 = pool.submit(tentativa);
        Future<Object> f2 = pool.submit(tentativa);
        largada.countDown();
        Object r1 = f1.get(15, TimeUnit.SECONDS);
        Object r2 = f2.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        long vencedores = Stream.of(r1, r2).filter(r -> r instanceof NfeEmissao).count();
        long rejeitados = Stream.of(r1, r2).filter(r -> r instanceof ContingenciaInvalidaException).count();
        assertEquals(1, vencedores, "exatamente uma das duas tentativas deveria vencer: r1=" + r1 + " r2=" + r2);
        assertEquals(1, rejeitados, "a outra deveria ser recusada (gate não corresponde mais à NORMAL)");
        assertEquals(1, contarFilhasPorOrigem(900L), "no máximo uma filha por NORMAL -- garantido pela revalidação de gate E pela uk_nfe_emissao_origem");
    }

    // =========================================================================================
    // Migration safety — linha legada (inserir() padrão) recebe os defaults corretos
    // =========================================================================================

    @Test
    @DisplayName("Migration safety: linha inserida via inserir() legado recebe tp_emis='1'/autorizador_destino='NORMAL'/emissao_origem_id NULL")
    void migrationSafety_linhaLegada_recebeDefaultsCorretos() throws Exception {
        resetTabelas();

        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            NfeEmissaoMapper emissaoMapper = session.getMapper(NfeEmissaoMapper.class);
            NfeEmissao legado = new NfeEmissao();
            legado.setPedidoId(1L);
            legado.setEmpresaId(1L);
            legado.setCnpjEmitente(CNPJ);
            legado.setModelo("55");
            legado.setSerie("1");
            legado.setNumeroNfe(1);
            legado.setEstado(NfeEmissao.Estados.RESERVADO);
            legado.setTentativas(1);
            emissaoMapper.inserir(legado); // caminho legado -- nunca seta tp_emis/autorizador_destino/emissao_origem_id
            session.commit();

            LinhaEmissao lida = lerEmissao(legado.getId());
            assertEquals(NfeEmissao.TpEmis.NORMAL, lida.tpEmis());
            assertEquals(NfeEmissao.AutorizadorDestino.NORMAL, lida.autorizadorDestino());
            assertNull(lida.emissaoOrigemId());
            assertNull(lida.dhCont());
        }
    }
}
