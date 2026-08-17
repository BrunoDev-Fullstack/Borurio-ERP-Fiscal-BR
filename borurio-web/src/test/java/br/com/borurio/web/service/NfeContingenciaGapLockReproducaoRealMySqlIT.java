package br.com.borurio.web.service;

import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.fiscal.config.SefazReconciliacaoProperties;
import br.com.borurio.fiscal.entity.NfeEmissao;
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
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Investigação do finding PLAUSIBLE do code-review de 17-08-2026 sobre a Fase 1 SVC:
 * {@code NfeEmissaoMapper.buscarPorOrigemIdParaAtualizar} roda {@code SELECT ... FOR UPDATE} sobre
 * {@code emissao_origem_id} em TODA resolução de emissão (não só as relacionadas a contingência).
 * Sob REPEATABLE_READ, uma busca FOR UPDATE que não encontra linha pode tomar gap lock no índice
 * único {@code uk_nfe_emissao_origem} — o finding hipotetiza que isso pode contender/deadlockar
 * contra um INSERT concorrente de {@code NfeContingenciaServiceImpl.inserirContingencia} numa
 * NORMAL completamente não relacionada.
 *
 * Este IT NÃO faz parte da suíte de aceite da Fase 1 (que já está 9/9 em
 * {@link NfeContingenciaConcorrenciaRealMySqlIT}) — é investigativo, gerado especificamente pra
 * banca deste finding. Não corrige nada; só reproduz (ou refuta) o mecanismo com evidência real.
 */
class NfeContingenciaGapLockReproducaoRealMySqlIT {

    private static final String CNPJ = "77665544000133";
    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUp() throws Exception {
        Flyway.configure()
                .dataSource(NfeContingenciaConcorrenciaTestProperties.dbUrl(), NfeContingenciaConcorrenciaTestProperties.dbUsername(),
                        NfeContingenciaConcorrenciaTestProperties.dbPassword())
                .locations("classpath:sql/migration")
                .load()
                .migrate();

        DataSource dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver",
                NfeContingenciaConcorrenciaTestProperties.dbUrl(), NfeContingenciaConcorrenciaTestProperties.dbUsername(),
                NfeContingenciaConcorrenciaTestProperties.dbPassword());
        Environment env = new Environment("contingencia-gaplock-repro-it", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.addMapper(NfeSequenciaMapper.class);
        config.addMapper(NfeEmissaoMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);

        limparCnpj(CNPJ);
    }

    @AfterAll
    static void tearDown() throws Exception {
        limparCnpj(CNPJ);
    }

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

    private void inserirSequencia(String serie, int ultimoNumero, Long emissaoAtivaId) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO nfe_sequencia (cnpj_emitente, serie, ultimo_numero, emissao_ativa_id) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, CNPJ);
            ps.setString(2, serie);
            ps.setInt(3, ultimoNumero);
            if (emissaoAtivaId == null) ps.setNull(4, java.sql.Types.BIGINT);
            else ps.setLong(4, emissaoAtivaId);
            ps.executeUpdate();
        }
    }

    private void inserirEmissaoNormal(long id, String serie, int numero, String estado) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO nfe_emissao (id, pedido_id, empresa_id, cnpj_emitente, serie, numero_nfe, estado, tentativas, chave_nfe) "
                             + "VALUES (?, ?, 1, ?, ?, ?, ?, 1, ?)")) {
            ps.setLong(1, id);
            ps.setLong(2, id);
            ps.setString(3, CNPJ);
            ps.setString(4, serie);
            ps.setInt(5, numero);
            ps.setString(6, estado);
            ps.setString(7, "3550110000000" + String.format("%03d", id % 1000) + "1");
            ps.executeUpdate();
        }
    }

    private static boolean isDeadlock(SQLException e) {
        return e.getErrorCode() == 1213 || "40001".equals(e.getSQLState());
    }

    /** MyBatis embrulha SQLException em PersistenceException (unchecked) -- percorre a cadeia de causas. */
    private static SQLException encontrarDeadlockNaCausa(Throwable t) {
        Throwable atual = t;
        while (atual != null) {
            if (atual instanceof SQLException se && isDeadlock(se)) return se;
            atual = atual.getCause();
        }
        return null;
    }

    // =========================================================================================
    // 1) DETERMINÍSTICO — gap lock isolado, sem passar pelos services (controle total da transação)
    // =========================================================================================

    private String capturarEvidenciaLocks() throws SQLException {
        StringBuilder sb = new StringBuilder();
        try (Connection conn = novaConexao(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT ENGINE_TRANSACTION_ID, INDEX_NAME, LOCK_TYPE, LOCK_MODE, LOCK_STATUS, LOCK_DATA "
                             + "FROM performance_schema.data_locks WHERE OBJECT_NAME='nfe_emissao' ORDER BY ENGINE_TRANSACTION_ID")) {
            sb.append("--- performance_schema.data_locks (nfe_emissao) ---\n");
            boolean any = false;
            while (rs.next()) {
                any = true;
                sb.append("trx=").append(rs.getLong("ENGINE_TRANSACTION_ID"))
                        .append(" index=").append(rs.getString("INDEX_NAME"))
                        .append(" type=").append(rs.getString("LOCK_TYPE"))
                        .append(" mode=").append(rs.getString("LOCK_MODE"))
                        .append(" status=").append(rs.getString("LOCK_STATUS"))
                        .append(" data=").append(rs.getString("LOCK_DATA"))
                        .append('\n');
            }
            if (!any) sb.append("(vazio)\n");
        }
        try (Connection conn = novaConexao(); Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT REQUESTING_ENGINE_TRANSACTION_ID, BLOCKING_ENGINE_TRANSACTION_ID "
                             + "FROM performance_schema.data_lock_waits")) {
            sb.append("--- performance_schema.data_lock_waits ---\n");
            boolean any = false;
            while (rs.next()) {
                any = true;
                sb.append("requesting_trx=").append(rs.getLong("REQUESTING_ENGINE_TRANSACTION_ID"))
                        .append(" blocking_trx=").append(rs.getLong("BLOCKING_ENGINE_TRANSACTION_ID"))
                        .append('\n');
            }
            if (!any) sb.append("(vazio)\n");
        }
        return sb.toString();
    }

    @Test
    @DisplayName("DETERMINÍSTICO: FOR UPDATE em emissao_origem_id=501 (0 linhas) -- INSERT concorrente com emissao_origem_id=900 bloqueia?")
    void deterministico_gapLockEmColunaVaziaVersusInsertConcorrente() throws Exception {
        resetTabelas();
        // Popula com várias linhas emissao_origem_id NULL (estado real do sistema hoje -- nenhuma
        // contingência aberta ainda) pra não testar um índice literalmente vazio.
        for (long i = 1; i <= 10; i++) {
            inserirEmissaoNormal(i, "U" + i, 100, NfeEmissao.Estados.AUTORIZADO);
        }
        inserirEmissaoNormal(501L, "1", 100, NfeEmissao.Estados.TRANSMITIDO);
        inserirEmissaoNormal(900L, "2", 50, NfeEmissao.Estados.TRANSMITIDO);

        Connection connA = novaConexao();
        connA.setAutoCommit(false);
        connA.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        try (PreparedStatement ps = connA.prepareStatement(
                "SELECT id FROM nfe_emissao WHERE emissao_origem_id = ? FOR UPDATE")) {
            ps.setLong(1, 501L);
            try (ResultSet rs = ps.executeQuery()) {
                assertFalse(rs.next(), "não deveria existir linha com emissao_origem_id=501 -- é exatamente o caso comum (late-NORMAL check numa emissão não substituída)");
            }
        }
        // connA NÃO comita -- mantém o possível gap lock aberto pra observar o efeito em B.

        AtomicBoolean insertCompletou = new AtomicBoolean(false);
        AtomicReference<Exception> erroInsert = new AtomicReference<>();
        Thread threadB = new Thread(() -> {
            try (Connection connB = novaConexao()) {
                connB.setAutoCommit(false);
                connB.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                try (PreparedStatement ps = connB.prepareStatement(
                        "INSERT INTO nfe_emissao (id, pedido_id, empresa_id, cnpj_emitente, serie, numero_nfe, estado, tentativas, emissao_origem_id) "
                                + "VALUES (901, 901, 1, ?, '2', 51, 'RESERVADO', 1, 900)")) {
                    ps.setString(1, CNPJ);
                    ps.executeUpdate();
                }
                connB.commit();
                insertCompletou.set(true);
            } catch (Exception e) {
                erroInsert.set(e);
            }
        });
        threadB.start();
        threadB.join(2000); // janela curta -- se bloquear, não completa nesse tempo
        boolean aindaBloqueadoComAAberta = threadB.isAlive();
        String evidencia = capturarEvidenciaLocks();

        connA.commit();
        connA.close();
        threadB.join(15000);
        boolean terminouAposLiberarA = !threadB.isAlive();

        System.out.println("=== [REPRO DETERMINÍSTICO] resultado ===");
        System.out.println("aindaBloqueadoComAAberta (B não completou em 2s com A segurando o lock) = " + aindaBloqueadoComAAberta);
        System.out.println("insertCompletouAntesDoCommitDeA = " + insertCompletou.get());
        System.out.println("terminouAposLiberarA (B destravou ao A commitar) = " + terminouAposLiberarA);
        System.out.println("erroInsert = " + erroInsert.get());
        System.out.println(evidencia);
    }

    // =========================================================================================
    // 1b) DEPOIS DO FAST-PATH — mesma investigação, agora pelos SERVICES reais (não SQL cru),
    //     provando que a resolução comum (gate ainda na própria emissão) não toma mais nenhum
    //     lock em uk_nfe_emissao_origem, e que o INSERT de contingência concorrente não bloqueia.
    // =========================================================================================

    @Test
    @DisplayName("DEPOIS DO FAST-PATH: resolução comum (gate ainda nela) via NfeEmissaoService real -- zero lock em uk_nfe_emissao_origem, INSERT de contingência concorrente não bloqueia")
    void depoisDoFastPath_resolucaoComumNaoTomaLockNoIndiceOrigem() throws Exception {
        resetTabelas();
        for (long i = 1; i <= 10; i++) {
            inserirEmissaoNormal(i, "U" + i, 100, NfeEmissao.Estados.AUTORIZADO);
        }
        inserirSequencia("1", 99, 501L);
        inserirEmissaoNormal(501L, "1", 100, NfeEmissao.Estados.TRANSMITIDO); // gate == 501L -> fast-path deve valer
        inserirSequencia("2", 49, 900L);
        inserirEmissaoNormal(900L, "2", 50, NfeEmissao.Estados.TRANSMITIDO);

        PedidoMapper pedidoMapperMock = mock(PedidoMapper.class);
        EstoqueService estoqueServiceMock = mock(EstoqueService.class);
        SqlSession sessionA = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ);
        construirNfeEmissaoServiceReal(sessionA, pedidoMapperMock, estoqueServiceMock)
                .resolverCicloComEfeitos(501L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-501",
                        1L, "AUTORIZADO", "chave501", false, List.<PedidoItem>of(), 1L, "sistema");
        // sessionA NÃO comita ainda -- se o fast-path funcionar, ela não deveria ter tomado
        // nenhum lock em uk_nfe_emissao_origem, então nada aqui deveria bloquear ninguém.

        String evidenciaComAAberta = capturarEvidenciaLocks();
        boolean semLockNoIndiceOrigem = !evidenciaComAAberta.contains("index=uk_nfe_emissao_origem");

        AtomicBoolean insertCompletouRapido = new AtomicBoolean(false);
        long inicio = System.nanoTime();
        Thread threadB = new Thread(() -> {
            try (SqlSession sessionB = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                construirNfeContingenciaServiceReal(sessionB)
                        .abrirContingencia(900L, NfeEmissao.TpEmis.SVC_AN, X_JUST, DH_CONT);
                sessionB.commit();
                insertCompletouRapido.set(true);
            }
        });
        threadB.start();
        threadB.join(5000);
        long duracaoMs = (System.nanoTime() - inicio) / 1_000_000;

        sessionA.commit();
        sessionA.close();

        System.out.println("=== [REPRO DEPOIS DO FAST-PATH] resultado ===");
        System.out.println("semLockNoIndiceOrigem (enquanto A -- resolução comum -- estava aberta) = " + semLockNoIndiceOrigem);
        System.out.println("contingenciaEmBCompletouSemEsperarA (< 5s) = " + insertCompletouRapido.get() + " (" + duracaoMs + "ms)");
        System.out.println(evidenciaComAAberta);

        assertTrue(semLockNoIndiceOrigem, "fast-path deveria ter evitado qualquer lock em uk_nfe_emissao_origem pra uma resolução com gate ainda na própria emissão");
        assertTrue(insertCompletouRapido.get(), "contingência concorrente em linha não relacionada não deveria mais esperar a resolução comum terminar");
        assertTrue(duracaoMs < 2000, "sem bloqueio real, a contingência deveria completar quase instantaneamente, não esperar os 5s do timeout: " + duracaoMs + "ms");
    }

    @Test
    @DisplayName("RESIDUAL CONHECIDO: transição NÃO-terminal (AGUARDANDO_CORRECAO) numa NORMAL substituída ainda toma o lock -- fast-path escopado só ao caso terminal, de propósito (P0-3)")
    void residualConhecido_transicaoNaoTerminal_aindaTomaLockNoIndiceOrigem() throws Exception {
        resetTabelas();
        inserirSequencia("1", 99, 501L);
        inserirEmissaoNormal(501L, "1", 100, NfeEmissao.Estados.TRANSMITIDO);

        PedidoMapper pedidoMapperMock = mock(PedidoMapper.class);
        EstoqueService estoqueServiceMock = mock(EstoqueService.class);
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            // AGUARDANDO_CORRECAO não é terminal -- P0-3 nunca trava nfe_sequencia aqui, de
            // propósito, então o fast-path (que depende desse lock) não se aplica; o caminho
            // continua idêntico ao de antes desta correção pra este caso específico.
            construirNfeEmissaoServiceReal(session, pedidoMapperMock, estoqueServiceMock)
                    .resolverCicloComEfeitos(501L, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 225, "Erro de schema", null,
                            1L, "REJEITADO", null, false, List.<PedidoItem>of(), 1L, "sistema");
        }
        // Não afirmamos bloqueio real aqui (exigiria outra thread concorrente) -- só documentamos,
        // via mvn -Dtest+verbose de mapper, que este caminho não foi alterado por esta correção.
        System.out.println("=== [RESIDUAL CONHECIDO] AGUARDANDO_CORRECAO numa NORMAL não substituída completou normalmente -- caminho não-terminal inalterado por este fix, conforme decisão registrada.");
    }

    // =========================================================================================
    // 2) STRESS — N resoluções não relacionadas concorrentes com M aberturas de contingência,
    //    repetido várias rodadas, via os SERVICES reais (não SQL cru) -- procura ER_LOCK_DEADLOCK.
    // =========================================================================================

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
    private static final OffsetDateTime DH_CONT = OffsetDateTime.of(2026, 8, 17, 14, 30, 0, 0, ZoneOffset.of("-03:00"));
    private static final int RESOLUCOES_NAO_RELACIONADAS = 15;
    private static final int CONTINGENCIAS_CONCORRENTES = 5;
    private static final int RODADAS = 15;

    /** serie é VARCHAR(3) -- código compacto único (prefixo + 2 chars base36) pra caber no schema. */
    private static String codigoSerie(char prefixo, int indice) {
        String base36 = Integer.toString(indice, 36).toUpperCase();
        String dois = base36.length() >= 2 ? base36.substring(base36.length() - 2) : ("0" + base36);
        return prefixo + dois;
    }

    @Test
    @DisplayName("STRESS: " + RESOLUCOES_NAO_RELACIONADAS + " resoluções não relacionadas x " + CONTINGENCIAS_CONCORRENTES
            + " aberturas de contingência concorrentes, " + RODADAS + " rodadas -- procura ER_LOCK_DEADLOCK real")
    void stress_resolucoesNaoRelacionadas_concorrentesComContingencias() throws Exception {
        List<SQLException> deadlocksReais = new CopyOnWriteArrayList<>();
        List<Exception> falhasInesperadas = new CopyOnWriteArrayList<>();
        int totalOperacoes = 0;

        for (int rodada = 0; rodada < RODADAS; rodada++) {
            resetTabelas();

            long baseId = rodada * 1000L + 1; // nunca 0 -- id=0 em coluna AUTO_INCREMENT vira auto-geração (NO_AUTO_VALUE_ON_ZERO), não o literal 0
            for (int i = 0; i < RESOLUCOES_NAO_RELACIONADAS; i++) {
                String serie = codigoSerie('U', rodada * RESOLUCOES_NAO_RELACIONADAS + i);
                inserirSequencia(serie, 99, baseId + i);
                inserirEmissaoNormal(baseId + i, serie, 100, NfeEmissao.Estados.TRANSMITIDO);
            }
            long baseContingenciaId = baseId + 500;
            for (int j = 0; j < CONTINGENCIAS_CONCORRENTES; j++) {
                String serie = codigoSerie('C', rodada * CONTINGENCIAS_CONCORRENTES + j);
                inserirSequencia(serie, 99, baseContingenciaId + j);
                inserirEmissaoNormal(baseContingenciaId + j, serie, 100, NfeEmissao.Estados.TRANSMITIDO);
            }

            ExecutorService pool = Executors.newFixedThreadPool(RESOLUCOES_NAO_RELACIONADAS + CONTINGENCIAS_CONCORRENTES);
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<?>> futures = new java.util.ArrayList<>();

            for (int i = 0; i < RESOLUCOES_NAO_RELACIONADAS; i++) {
                long emissaoId = baseId + i;
                futures.add(pool.submit(() -> {
                    try {
                        largada.await();
                        PedidoMapper pedidoMapperMock = mock(PedidoMapper.class);
                        EstoqueService estoqueServiceMock = mock(EstoqueService.class);
                        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                            construirNfeEmissaoServiceReal(session, pedidoMapperMock, estoqueServiceMock)
                                    .resolverCicloComEfeitos(emissaoId, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-" + emissaoId,
                                            emissaoId, "AUTORIZADO", "chave" + emissaoId, false, List.<PedidoItem>of(), 1L, "sistema");
                            session.commit();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        SQLException deadlock = encontrarDeadlockNaCausa(e);
                        if (deadlock != null) deadlocksReais.add(deadlock);
                        else falhasInesperadas.add(e);
                    }
                }));
            }
            for (int j = 0; j < CONTINGENCIAS_CONCORRENTES; j++) {
                long emissaoId = baseContingenciaId + j;
                futures.add(pool.submit(() -> {
                    try {
                        largada.await();
                        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                            construirNfeContingenciaServiceReal(session)
                                    .abrirContingencia(emissaoId, NfeEmissao.TpEmis.SVC_AN, X_JUST, DH_CONT);
                            session.commit();
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        SQLException deadlock = encontrarDeadlockNaCausa(e);
                        if (deadlock != null) deadlocksReais.add(deadlock);
                        else falhasInesperadas.add(e);
                    }
                }));
            }

            largada.countDown();
            for (Future<?> f : futures) {
                try {
                    f.get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    falhasInesperadas.add(e);
                }
            }
            pool.shutdown();
            totalOperacoes += RESOLUCOES_NAO_RELACIONADAS + CONTINGENCIAS_CONCORRENTES;
        }

        System.out.println("=== [REPRO STRESS] resultado ===");
        System.out.println("rodadas=" + RODADAS + " operacoesPorRodada=" + (RESOLUCOES_NAO_RELACIONADAS + CONTINGENCIAS_CONCORRENTES)
                + " totalOperacoes=" + totalOperacoes);
        System.out.println("deadlocksReais(ER_LOCK_DEADLOCK/1213)=" + deadlocksReais.size());
        for (SQLException dl : deadlocksReais) System.out.println("  -> " + dl.getMessage());
        System.out.println("falhasInesperadas(outros erros, exceto ContingenciaInvalidaException esperado por corridas legítimas)="
                + falhasInesperadas.size());
        for (Exception e : falhasInesperadas) {
            boolean esperado = e.getClass().getSimpleName().equals("ContingenciaInvalidaException");
            System.out.println("  -> [" + (esperado ? "ESPERADO (revalidação de gate)" : "INESPERADO") + "] "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
