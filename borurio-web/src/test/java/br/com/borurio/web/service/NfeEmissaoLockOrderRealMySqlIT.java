package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.exception.SequenciaComEmissaoAtivaException;
import br.com.borurio.fiscal.mapper.NfeEmissaoMapper;
import br.com.borurio.fiscal.mapper.NfeSequenciaMapper;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import br.com.borurio.fiscal.service.impl.NfeSequenciaServiceImpl;
import br.com.borurio.web.dto.AberturaCicloResultado;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
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
import static org.mockito.Mockito.when;

/**
 * P0-3 (07-08-2026, hardening pós-banca) — prova contra MySQL real da correção de ordem de lock
 * em {@link NfeEmissaoService#resolverCiclo}. Mesma classe de mecanismo (FOR UPDATE +
 * SERIALIZABLE) que a banca usou pra provar a corrupção do P0-1; aqui o alvo é a inversão AB-BA
 * entre abrirCiclo/retomarCicloAtivo (nfe_sequencia -> nfe_emissao, sempre foi assim) e a
 * resolverCiclo ANTES da correção (nfe_emissao -> nfe_sequencia).
 *
 * *IT (não *Test): Surefire não pega esse padrão por padrão — `mvn test` normal não precisa de
 * MySQL. Roda só quando executada explicitamente, contra um MySQL efêmero/descartável (nunca os
 * containers persistentes de dev/HOM — ver P03LockOrderTestProperties).
 *
 * EmpresaMapper/PedidoMapper são mockados nesta suíte: o lock de Empresa não faz parte da
 * inversão sob teste (documentado como sempre-primeiro desde ReservaFiscalService, inalterado por
 * este P0) e pedidoMapper.atualizarSerieReservada é um efeito colateral irrelevante pra ordem de
 * lock. nfe_sequencia e nfe_emissao — as duas tabelas em disputa — são sempre reais, via MyBatis
 * contra o MySQL efêmero, uma SqlSession (uma conexão, uma transação) por thread por cenário.
 */
class NfeEmissaoLockOrderRealMySqlIT {

    private static final String CNPJ = "77665544000133";
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUpSchemaEDados() throws Exception {
        Flyway.configure()
                .dataSource(P03LockOrderTestProperties.dbUrl(), P03LockOrderTestProperties.dbUsername(),
                        P03LockOrderTestProperties.dbPassword())
                .locations("classpath:sql/migration")
                .load()
                .migrate();

        DataSource dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver",
                P03LockOrderTestProperties.dbUrl(), P03LockOrderTestProperties.dbUsername(),
                P03LockOrderTestProperties.dbPassword());
        Environment env = new Environment("p0-3-lock-order-it", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.addMapper(NfeSequenciaMapper.class);
        config.addMapper(NfeEmissaoMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);

        limparCnpj(CNPJ);
    }

    @AfterAll
    static void limparFinal() throws Exception {
        limparCnpj(CNPJ);
        limparCnpj("11222333000181");
    }

    // -------------------------------------------------------------------------
    // Helpers de infraestrutura
    // -------------------------------------------------------------------------

    private static Connection novaConexao() throws SQLException {
        return DriverManager.getConnection(P03LockOrderTestProperties.dbUrl(),
                P03LockOrderTestProperties.dbUsername(), P03LockOrderTestProperties.dbPassword());
    }

    private static Connection novaConexaoSerializable() throws SQLException {
        Connection conn = novaConexao();
        conn.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
        conn.setAutoCommit(false);
        return conn;
    }

    private static boolean isDeadlock(SQLException e) {
        return e.getErrorCode() == 1213 || "40001".equals(e.getSQLState());
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

    private void inserirSequencia(String cnpj, String serie, int ultimoNumero, Long emissaoAtivaId) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO nfe_sequencia (cnpj_emitente, serie, ultimo_numero, emissao_ativa_id) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, cnpj);
            ps.setString(2, serie);
            ps.setInt(3, ultimoNumero);
            if (emissaoAtivaId == null) ps.setNull(4, java.sql.Types.BIGINT);
            else ps.setLong(4, emissaoAtivaId);
            ps.executeUpdate();
        }
    }

    private void inserirEmissao(long id, long pedidoId, String cnpj, String serie, int numero, String estado) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO nfe_emissao (id, pedido_id, empresa_id, cnpj_emitente, serie, numero_nfe, estado, tentativas) "
                             + "VALUES (?, ?, 1, ?, ?, ?, ?, 1)")) {
            ps.setLong(1, id);
            ps.setLong(2, pedidoId);
            ps.setString(3, cnpj);
            ps.setString(4, serie);
            ps.setInt(5, numero);
            ps.setString(6, estado);
            ps.executeUpdate();
        }
    }

    private int lerUltimoNumeroReal(String cnpj, String serie) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT ultimo_numero FROM nfe_sequencia WHERE cnpj_emitente = ? AND serie = ?")) {
            ps.setString(1, cnpj);
            ps.setString(2, serie);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "linha deveria existir");
                return rs.getInt(1);
            }
        }
    }

    private Long lerEmissaoAtivaIdReal(String cnpj, String serie) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT emissao_ativa_id FROM nfe_sequencia WHERE cnpj_emitente = ? AND serie = ?")) {
            ps.setString(1, cnpj);
            ps.setString(2, serie);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "linha deveria existir");
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    private String lerEstadoEmissao(long id) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement("SELECT estado FROM nfe_emissao WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "linha deveria existir");
                return rs.getString(1);
            }
        }
    }

    /** EmpresaMapper/PedidoMapper mockados (irrelevantes pra ordem de lock); nfe_sequencia/nfe_emissao reais. */
    private NfeEmissaoService construirServiceReal(SqlSession session, String serieEmpresa) {
        NfeSequenciaMapper seqMapper = session.getMapper(NfeSequenciaMapper.class);
        NfeEmissaoMapper emissaoMapper = session.getMapper(NfeEmissaoMapper.class);
        NfeSequenciaService sequenciaService = new NfeSequenciaServiceImpl(seqMapper);

        EmpresaMapper empresaMapperMock = mock(EmpresaMapper.class);
        Empresa empresa = new Empresa();
        empresa.setId(1L);
        empresa.setSerieNfePadrao(serieEmpresa);
        when(empresaMapperMock.buscarPorCnpjParaAtualizar(any())).thenReturn(empresa);

        PedidoMapper pedidoMapperMock = mock(PedidoMapper.class);
        br.com.borurio.app.service.EstoqueService estoqueServiceMock = mock(br.com.borurio.app.service.EstoqueService.class);

        return new NfeEmissaoService(empresaMapperMock, pedidoMapperMock, sequenciaService, emissaoMapper,
                estoqueServiceMock, new br.com.borurio.fiscal.config.SefazReconciliacaoProperties());
    }

    // =========================================================================================
    // A) Deadlock PRÉ-FIX — reproduzido por simulação controlada (raw JDBC) da ordem antiga
    // =========================================================================================

    @Test
    @DisplayName("A) Deadlock PRÉ-FIX reproduzido: ordem antiga de resolverCiclo (nfe_emissao->nfe_sequencia) contra abrirCiclo/retomarCicloAtivo (nfe_sequencia->nfe_emissao)")
    void deadlockPreFix_ordemAntiga_reproduzContraMySqlReal() throws Exception {
        resetTabelas();
        inserirSequencia(CNPJ, "1", 4, 900L);
        inserirEmissao(900L, 1L, CNPJ, "1", 5, NfeEmissao.Estados.TRANSMITIDO);

        CyclicBarrier barreira = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicReference<SQLException> deadlockDetectado = new AtomicReference<>();
        AtomicInteger sucessos = new AtomicInteger(0);
        List<Exception> falhasInesperadas = Collections.synchronizedList(new ArrayList<>());

        // abrirCiclo/retomarCicloAtivo: SEMPRE foi nfe_sequencia -> nfe_emissao (não mudou no P0-3).
        Callable<Void> ordemAbrirCiclo = () -> {
            try (Connection conn = novaConexaoSerializable()) {
                lockSequencia(conn, CNPJ, "1");
                barreira.await(10, TimeUnit.SECONDS);
                lockEmissao(conn, 900L);
                conn.commit();
                sucessos.incrementAndGet();
            } catch (SQLException e) {
                if (isDeadlock(e)) deadlockDetectado.compareAndSet(null, e);
                else falhasInesperadas.add(e);
            } catch (Exception e) {
                falhasInesperadas.add(e);
            }
            return null;
        };
        // resolverCiclo ANTES da correção: nfe_emissao -> nfe_sequencia (o bug).
        Callable<Void> ordemAntigaResolverCiclo = () -> {
            try (Connection conn = novaConexaoSerializable()) {
                lockEmissao(conn, 900L);
                barreira.await(10, TimeUnit.SECONDS);
                lockSequencia(conn, CNPJ, "1");
                conn.commit();
                sucessos.incrementAndGet();
            } catch (SQLException e) {
                if (isDeadlock(e)) deadlockDetectado.compareAndSet(null, e);
                else falhasInesperadas.add(e);
            } catch (Exception e) {
                falhasInesperadas.add(e);
            }
            return null;
        };

        List<Future<Void>> futures = pool.invokeAll(List.of(ordemAbrirCiclo, ordemAntigaResolverCiclo), 20, TimeUnit.SECONDS);
        for (Future<Void> f : futures) f.get();
        pool.shutdown();

        assertTrue(falhasInesperadas.isEmpty(), "nenhuma falha além do deadlock esperado: " + falhasInesperadas);
        assertNotNull(deadlockDetectado.get(), "ordem antiga precisa produzir deadlock real do InnoDB (ER_LOCK_DEADLOCK/1213)");
        assertEquals(1, sucessos.get(), "exatamente uma das duas transações vence; a outra é vítima do deadlock");
    }

    // =========================================================================================
    // B) Deadlock PÓS-FIX — não reproduzido quando as duas seguem a ordem canônica
    // =========================================================================================

    @Test
    @DisplayName("B) Deadlock PÓS-FIX não reproduzido: as duas transações seguem a mesma ordem canônica (nfe_sequencia->nfe_emissao)")
    void deadlockPosFix_ordemCorrigida_naoReproduzContraMySqlReal() throws Exception {
        resetTabelas();
        inserirSequencia(CNPJ, "1", 4, 901L);
        inserirEmissao(901L, 1L, CNPJ, "1", 5, NfeEmissao.Estados.TRANSMITIDO);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<SQLException> falhas = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger sucessos = new AtomicInteger(0);

        Callable<Void> ordemCorrigida = () -> {
            try (Connection conn = novaConexaoSerializable()) {
                lockSequencia(conn, CNPJ, "1");
                lockEmissao(conn, 901L);
                conn.commit();
                sucessos.incrementAndGet();
            } catch (SQLException e) {
                falhas.add(e);
            }
            return null;
        };

        // Sem barreira: contenção real e concorrente na MESMA ordem nas duas pontas — só pode
        // gerar espera comum (uma fila), nunca um ciclo AB-BA.
        List<Future<Void>> futures = pool.invokeAll(List.of(ordemCorrigida, ordemCorrigida), 20, TimeUnit.SECONDS);
        for (Future<Void> f : futures) f.get();
        pool.shutdown();

        assertTrue(falhas.isEmpty(), "ordem corrigida não deveria produzir NENHUMA falha, nem deadlock: " + falhas);
        assertEquals(2, sucessos.get(), "as duas transações completam, só serializadas por bloqueio comum, nunca por deadlock");
    }

    private static void lockSequencia(Connection conn, String cnpj, String serie) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT ultimo_numero FROM nfe_sequencia WHERE cnpj_emitente=? AND serie=? FOR UPDATE")) {
            ps.setString(1, cnpj);
            ps.setString(2, serie);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); }
        }
    }

    private static void lockEmissao(Connection conn, long id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT estado FROM nfe_emissao WHERE id=? FOR UPDATE")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); }
        }
    }

    // =========================================================================================
    // C) Corrida real: retomarCicloAtivo (via abrirCiclo) x resolverCiclo, mesma emissão
    // =========================================================================================

    @Test
    @DisplayName("C) Corrida real: retomarCicloAtivo (via abrirCiclo) concorrente com resolverCiclo da MESMA emissão — sem deadlock, estado final consistente")
    void race_retomarCicloAtivo_vs_resolverCiclo_semDeadlock_estadoFinalConsistente() throws Exception {
        resetTabelas();
        inserirSequencia(CNPJ, "1", 4, 910L);
        inserirEmissao(910L, 1L, CNPJ, "1", 5, NfeEmissao.Estados.TRANSMITIDO);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);

        Callable<Object> threadA = () -> {
            largada.await();
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.SERIALIZABLE)) {
                NfeEmissaoService service = construirServiceReal(session, "1");
                try {
                    AberturaCicloResultado r = service.abrirCiclo(1L, CNPJ);
                    session.commit();
                    return r;
                } catch (BusinessException e) {
                    session.rollback();
                    return e;
                }
            }
        };
        Callable<Object> threadB = () -> {
            largada.await();
            // resolverCiclo roda em REPEATABLE_READ em produção (ver @Transactional do método) —
            // a sessão precisa abrir no MESMO nível aqui, já que fora do Spring a anotação não
            // tem efeito nenhum (é só o nível passado a openSession() que vale de fato).
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                construirServiceReal(session, "1").resolverCiclo(910L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-race");
                session.commit();
            }
            return null;
        };

        Future<Object> futA = pool.submit(threadA);
        Future<Object> futB = pool.submit(threadB);
        largada.countDown();
        Object resultA = futA.get(20, TimeUnit.SECONDS);
        futB.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(resultA instanceof AberturaCicloResultado || resultA instanceof BusinessException,
                "resultado de A precisa ser um dos desfechos legítimos, nunca vazamento de SQLException/deadlock: " + resultA);
        if (resultA instanceof BusinessException be) {
            assertEquals("EMISSAO_AGUARDANDO_RECONCILIACAO", be.getErrorCode(),
                    "único desfecho de negócio possível pra A quando lê TRANSMITIDO antes de B resolver");
        }

        // B é determinístico nesta corrida (segue a mesma ordem canônica de A — nunca falha por
        // lock): sempre consome o número 5 e libera o gate.
        assertEquals("AUTORIZADO", lerEstadoEmissao(910L));
        assertEquals(5, lerUltimoNumeroReal(CNPJ, "1"));
        assertNull(lerEmissaoAtivaIdReal(CNPJ, "1"), "gate liberado após resolução terminal");
    }

    // =========================================================================================
    // D) Corrida real: resolverCiclo (terminal) x sincronização de numeração, mesma empresa/série
    // =========================================================================================

    @Test
    @DisplayName("D) Corrida real: resolverCiclo (terminal) concorrente com atualizarSequencia (sync OMS) mesma empresa/série — sem deadlock")
    void race_resolverCiclo_vs_atualizarSequencia_semDeadlock() throws Exception {
        resetTabelas();
        inserirSequencia(CNPJ, "1", 4, 920L);
        inserirEmissao(920L, 1L, CNPJ, "1", 5, NfeEmissao.Estados.TRANSMITIDO);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);

        Callable<Object> resolverCicloTask = () -> {
            largada.await();
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                construirServiceReal(session, "1").resolverCiclo(920L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-d");
                session.commit();
            }
            return null;
        };
        Callable<Object> syncTask = () -> {
            largada.await();
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.SERIALIZABLE)) {
                NfeSequenciaService sequenciaService = new NfeSequenciaServiceImpl(session.getMapper(NfeSequenciaMapper.class));
                try {
                    sequenciaService.atualizarSequencia(CNPJ, "1", 6); // OMS tentando avançar pra 6 durante a resolução
                    session.commit();
                    return "aplicado";
                } catch (SequenciaComEmissaoAtivaException e) {
                    session.rollback();
                    return "bloqueado_gate_ativo";
                } catch (IllegalStateException e) {
                    session.rollback();
                    return "bloqueado_regressao";
                }
            }
        };

        Future<Object> f1 = pool.submit(resolverCicloTask);
        Future<Object> f2 = pool.submit(syncTask);
        largada.countDown();
        f1.get(20, TimeUnit.SECONDS);
        Object syncResultado = f2.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals("AUTORIZADO", lerEstadoEmissao(920L));
        assertEquals(5, lerUltimoNumeroReal(CNPJ, "1"), "resolverCiclo sempre vence sua própria consolidação — 5 foi consumido");
        assertTrue("bloqueado_gate_ativo".equals(syncResultado) || "aplicado".equals(syncResultado),
                "sync ou foi bloqueado pelo gate ainda ativo (P0-1), ou aplicado depois do gate já ter liberado: " + syncResultado);
    }

    // =========================================================================================
    // E) Empresas (CNPJ) diferentes não se bloqueiam
    // =========================================================================================

    @Test
    @DisplayName("E) Empresas diferentes não se bloqueiam: resolverCiclo concorrente em CNPJs distintos completa sem esperar um pelo outro")
    void empresasDiferentes_naoSeBloqueiam() throws Exception {
        String cnpjB = "11222333000181";
        resetTabelas();
        limparCnpj(cnpjB);
        inserirSequencia(CNPJ, "1", 4, 930L);
        inserirEmissao(930L, 1L, CNPJ, "1", 5, NfeEmissao.Estados.TRANSMITIDO);
        inserirSequencia(cnpjB, "1", 9, 931L);
        inserirEmissao(931L, 2L, cnpjB, "1", 10, NfeEmissao.Estados.TRANSMITIDO);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        Callable<Long> tarefaA = () -> {
            largada.await();
            long inicio = System.nanoTime();
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                construirServiceReal(session, "1").resolverCiclo(930L, NfeEmissao.Estados.AUTORIZADO, 100, null, "e1");
                session.commit();
            }
            return System.nanoTime() - inicio;
        };
        Callable<Long> tarefaB = () -> {
            largada.await();
            long inicio = System.nanoTime();
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                construirServiceReal(session, "1").resolverCiclo(931L, NfeEmissao.Estados.AUTORIZADO, 100, null, "e2");
                session.commit();
            }
            return System.nanoTime() - inicio;
        };

        Future<Long> f1 = pool.submit(tarefaA);
        Future<Long> f2 = pool.submit(tarefaB);
        largada.countDown();
        long d1 = f1.get(10, TimeUnit.SECONDS);
        long d2 = f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals("AUTORIZADO", lerEstadoEmissao(930L));
        assertEquals("AUTORIZADO", lerEstadoEmissao(931L));
        assertTrue(d1 < TimeUnit.SECONDS.toNanos(3), "CNPJ diferente não deveria esperar lock de outro CNPJ");
        assertTrue(d2 < TimeUnit.SECONDS.toNanos(3), "CNPJ diferente não deveria esperar lock de outro CNPJ");
    }

    // =========================================================================================
    // F) Séries diferentes da mesma empresa não se bloqueiam em resolverCiclo
    // =========================================================================================

    @Test
    @DisplayName("F) Séries diferentes da mesma empresa não se bloqueiam em resolverCiclo (que não toca Empresa)")
    void seriesDiferentes_mesmaEmpresa_resolverCiclo_naoSeBloqueiam() throws Exception {
        resetTabelas();
        inserirSequencia(CNPJ, "1", 4, 940L);
        inserirEmissao(940L, 1L, CNPJ, "1", 5, NfeEmissao.Estados.TRANSMITIDO);
        inserirSequencia(CNPJ, "2", 7, 941L);
        inserirEmissao(941L, 2L, CNPJ, "2", 8, NfeEmissao.Estados.TRANSMITIDO);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        Callable<Long> serieUm = () -> {
            largada.await();
            long inicio = System.nanoTime();
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                construirServiceReal(session, "1").resolverCiclo(940L, NfeEmissao.Estados.AUTORIZADO, 100, null, "f1");
                session.commit();
            }
            return System.nanoTime() - inicio;
        };
        Callable<Long> serieDois = () -> {
            largada.await();
            long inicio = System.nanoTime();
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                construirServiceReal(session, "2").resolverCiclo(941L, NfeEmissao.Estados.AUTORIZADO, 100, null, "f2");
                session.commit();
            }
            return System.nanoTime() - inicio;
        };

        Future<Long> f1 = pool.submit(serieUm);
        Future<Long> f2 = pool.submit(serieDois);
        largada.countDown();
        long d1 = f1.get(10, TimeUnit.SECONDS);
        long d2 = f2.get(10, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals("AUTORIZADO", lerEstadoEmissao(940L));
        assertEquals("AUTORIZADO", lerEstadoEmissao(941L));
        assertTrue(d1 < TimeUnit.SECONDS.toNanos(3) && d2 < TimeUnit.SECONDS.toNanos(3),
                "séries diferentes da mesma empresa não deveriam esperar uma pela outra em resolverCiclo "
                        + "(nota: abrirCiclo, diferente de resolverCiclo, SERIALIZA entre séries da mesma empresa "
                        + "via lock de Empresa — comportamento pré-existente, fora do escopo do P0-3)");
    }

    // =========================================================================================
    // G) Rollback libera locks + gate inconsistente falha sem consumir/liberar
    // =========================================================================================

    @Test
    @DisplayName("G) Gate inconsistente falha explicitamente; rollback libera locks e mantém o gate consistente")
    void gateInconsistente_falhaERollback_liberaLocksSemAlterarEstado() throws Exception {
        resetTabelas();
        inserirSequencia(CNPJ, "1", 4, 999L); // gate aponta pra 999...
        inserirEmissao(950L, 1L, CNPJ, "1", 5, NfeEmissao.Estados.TRANSMITIDO); // ...mas tentamos resolver 950L

        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            NfeEmissaoService service = construirServiceReal(session, "1");
            assertThrows(IllegalStateException.class,
                    () -> service.resolverCiclo(950L, NfeEmissao.Estados.AUTORIZADO, 100, null, "g1"));
            session.rollback();
        }

        // Estado intacto, lido por conexão nova — nem consumiu número, nem liberou/alterou o gate.
        assertEquals(4, lerUltimoNumeroReal(CNPJ, "1"));
        assertEquals(999L, lerEmissaoAtivaIdReal(CNPJ, "1"));
        assertEquals(NfeEmissao.Estados.TRANSMITIDO, lerEstadoEmissao(950L));

        // Nova transação trava a MESMA linha imediatamente — rollback não deixou nenhum lock pendente.
        long inicio = System.nanoTime();
        try (SqlSession session2 = sqlSessionFactory.openSession(TransactionIsolationLevel.SERIALIZABLE)) {
            assertNotNull(session2.getMapper(NfeSequenciaMapper.class).buscarParaAtualizar(CNPJ, "1"));
            session2.commit();
        }
        long duracao = System.nanoTime() - inicio;
        assertTrue(duracao < TimeUnit.SECONDS.toNanos(2), "lock deveria ter sido liberado pelo rollback, não deveria esperar");
    }

    // =========================================================================================
    // H) TOCTOU real: chamadas concorrentes pro MESMO ciclo consomem uma única vez
    // =========================================================================================

    @Test
    @DisplayName("H) TOCTOU real: duas chamadas concorrentes de resolverCiclo pro MESMO id consomem o número exatamente uma vez")
    void toctouReal_chamadasConcorrentesMesmoId_consomeApenasUmaVez() throws Exception {
        resetTabelas();
        inserirSequencia(CNPJ, "1", 4, 960L);
        inserirEmissao(960L, 1L, CNPJ, "1", 5, NfeEmissao.Estados.TRANSMITIDO);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        Callable<Void> tarefa = () -> {
            largada.await();
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                construirServiceReal(session, "1").resolverCiclo(960L, NfeEmissao.Estados.AUTORIZADO, 100, null, "h");
                session.commit();
            }
            return null;
        };

        Future<Void> f1 = pool.submit(tarefa);
        Future<Void> f2 = pool.submit(tarefa);
        largada.countDown();
        f1.get(15, TimeUnit.SECONDS);
        f2.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals("AUTORIZADO", lerEstadoEmissao(960L));
        assertEquals(5, lerUltimoNumeroReal(CNPJ, "1"),
                "consumido exatamente uma vez — se tivesse consumido 2x, ultimo_numero seria 6, não 5");
        assertNull(lerEmissaoAtivaIdReal(CNPJ, "1"));
    }

    // =========================================================================================
    // I) Nenhum nNF duplicado — duas aberturas concorrentes num gate livre
    // =========================================================================================

    @Test
    @DisplayName("I) Nenhum nNF duplicado: duas aberturas concorrentes num gate livre — só uma vence, número nunca duplica")
    void nenhumNnfDuplicado_aberturaConcorrente_gateLivre() throws Exception {
        resetTabelas();
        inserirSequencia(CNPJ, "1", 10, null);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);

        Callable<Object> abrir1 = () -> {
            largada.await();
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.SERIALIZABLE)) {
                try {
                    AberturaCicloResultado r = construirServiceReal(session, "1").abrirCiclo(101L, CNPJ);
                    session.commit();
                    return r;
                } catch (BusinessException e) {
                    session.rollback();
                    return e;
                }
            }
        };
        Callable<Object> abrir2 = () -> {
            largada.await();
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.SERIALIZABLE)) {
                try {
                    AberturaCicloResultado r = construirServiceReal(session, "1").abrirCiclo(102L, CNPJ);
                    session.commit();
                    return r;
                } catch (BusinessException e) {
                    session.rollback();
                    return e;
                }
            }
        };

        Future<Object> f1 = pool.submit(abrir1);
        Future<Object> f2 = pool.submit(abrir2);
        largada.countDown();
        Object r1 = f1.get(15, TimeUnit.SECONDS);
        Object r2 = f2.get(15, TimeUnit.SECONDS);
        pool.shutdown();

        long vencedores = Stream.of(r1, r2).filter(r -> r instanceof AberturaCicloResultado).count();
        long rejeitados = Stream.of(r1, r2).filter(r -> r instanceof BusinessException).count();
        assertEquals(1, vencedores, "exatamente um dos dois pedidos deveria vencer o gate livre: r1=" + r1 + " r2=" + r2);
        assertEquals(1, rejeitados, "o outro deveria ser rejeitado com EMISSAO_EM_ANDAMENTO_NA_SERIE");

        int numeroVencedor = (int) Stream.of(r1, r2)
                .filter(r -> r instanceof AberturaCicloResultado)
                .map(r -> ((AberturaCicloResultado) r).emissao().getNumeroNfe())
                .findFirst().orElseThrow();
        assertEquals(11, numeroVencedor);

        BusinessException rejeitado = (BusinessException) Stream.of(r1, r2)
                .filter(r -> r instanceof BusinessException).findFirst().orElseThrow();
        assertEquals("EMISSAO_EM_ANDAMENTO_NA_SERIE", rejeitado.getErrorCode());
    }
}
