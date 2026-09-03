package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.mapper.NfeEmissaoMapper;
import br.com.borurio.fiscal.mapper.NfeSequenciaMapper;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import br.com.borurio.fiscal.service.impl.NfeSequenciaServiceImpl;
import br.com.borurio.web.dto.AberturaCicloResultado;
import br.com.borurio.web.dto.AbandonoCicloResultado;
import br.com.borurio.web.dto.TransporteNaoEntregueResultado;
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
import org.junit.jupiter.api.BeforeEach;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Prova, contra MySQL real, do invariante central da numeração pós-incidente de 02-09-2026:
 * os recovery administrativos ABANDONADO e TRANSPORTE_NAO_ENTREGUE seguem o "modelo gap" —
 * encerram o ciclo, liberam o gate e AVANCAM {@code nfe_sequencia.ultimo_numero} até o
 * {@code numero_nfe} daquele ciclo, nunca além, nunca regredindo. O bug corrigido: deixar
 * {@code ultimo_numero} atrás do nNF de uma linha de {@code nfe_emissao} que continua ocupando o
 * slot {@code uk_nfe_emissao_numero (cnpj_emitente, modelo, serie, numero_nfe)} — o próximo
 * {@code abrirCiclo} recalcula o candidato e colide no INSERT ("Duplicate entry ... for key
 * 'nfe_emissao.uk_nfe_emissao_numero'").
 *
 * *IT (não *Test): Surefire não roda este padrão em `mvn test`. Executa só explicitamente,
 * contra um MySQL efêmero/descartável (P03_DB_URL / P03_DB_USERNAME / P03_DB_PASSWORD — ver
 * {@link P03LockOrderTestProperties}), nunca os containers persistentes de dev/HOM.
 *
 * EmpresaMapper/PedidoMapper e os colaboradores de Pedido/Estoque/NfeDocumento são mockados: o
 * foco é a dupla nfe_sequencia × nfe_emissao, ambas reais via MyBatis. Em
 * marcarTransporteNaoEntregue, {@code pedidoMapper.buscarPorId} devolve null de propósito — os
 * efeitos de Pedido/Estoque têm cobertura própria em NfeEmissaoServiceTest; aqui só importa o
 * contador e a constraint.
 */
class NfeRecoveryGapRealMySqlIT {

    private static final String CNPJ_JZHENG = "22418179000134"; // série 5 — pedido 67 (cStat 225 / ABANDONADO)
    private static final String CNPJ_JCHO   = "54393421000159"; // série 1 — pedido 68 (gateway 403 / TRANSPORTE_NAO_ENTREGUE)
    private static final String MODELO = "55";

    private static SqlSessionFactory sqlSessionFactory;

    @BeforeAll
    static void setUpSchema() throws Exception {
        Flyway.configure()
                .dataSource(P03LockOrderTestProperties.dbUrl(), P03LockOrderTestProperties.dbUsername(),
                        P03LockOrderTestProperties.dbPassword())
                .locations("classpath:sql/migration")
                .load()
                .migrate();

        DataSource dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver",
                P03LockOrderTestProperties.dbUrl(), P03LockOrderTestProperties.dbUsername(),
                P03LockOrderTestProperties.dbPassword());
        Environment env = new Environment("nfe-recovery-gap-it", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.addMapper(NfeSequenciaMapper.class);
        config.addMapper(NfeEmissaoMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);
    }

    @BeforeEach
    void limparAntes() throws Exception {
        limparCnpj(CNPJ_JZHENG);
        limparCnpj(CNPJ_JCHO);
    }

    @AfterAll
    static void limparDepois() throws Exception {
        limparCnpj(CNPJ_JZHENG);
        limparCnpj(CNPJ_JCHO);
    }

    // =========================================================================================
    // 1) Série 5 (J.ZHENG) — abandonarCiclo → ultimo_numero=1, gate NULL, próximo ciclo pega nNF 2
    // =========================================================================================

    @Test
    @DisplayName("1) abandonarCiclo série 5: ultimo_numero 0→1, gate NULL, novo abrirCiclo reserva nNF 2 sem violar uk_nfe_emissao_numero")
    void abandonarCiclo_serie5_avancaContador_eNovoCicloPegaProximoNumero() throws Exception {
        inserirSequencia(CNPJ_JZHENG, "5", 0, 2L);
        long idEmissao = inserirEmissao(CNPJ_JZHENG, "5", 1, NfeEmissao.Estados.AGUARDANDO_CORRECAO, null, 0);
        atualizarGateParaId(CNPJ_JZHENG, "5", idEmissao);

        AbandonoCicloResultado r;
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            r = servico(session, "5").abandonarCiclo(idEmissao, "descricao chinesa incorrigivel no pedido 67 (xProd fora do charset)");
            session.commit();
        }

        assertEquals(1, r.ultimoNumeroResultante());
        assertTrue(r.sequenciaAvancada());
        assertTrue(r.gateLiberado());
        assertFalse(r.idempotente());
        assertEquals(1, lerUltimoNumero(CNPJ_JZHENG, "5"), "ultimo_numero avançado até o nNF do ciclo abandonado");
        assertNull(lerGate(CNPJ_JZHENG, "5"), "gate liberado");
        assertEquals(NfeEmissao.Estados.ABANDONADO, lerEstado(idEmissao));

        // Próximo ciclo da mesma série: candidato = 1 + 1 = 2. INSERT tem que passar.
        AberturaCicloResultado novo;
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.SERIALIZABLE)) {
            novo = servico(session, "5").abrirCiclo(680L, CNPJ_JZHENG);
            session.commit();
        }
        assertEquals(2, novo.emissao().getNumeroNfe(), "nNF 1 foi queimado; o próximo ciclo pega 2");
        assertEquals(2, contarEmissoes(CNPJ_JZHENG, "5"), "duas linhas: nNF 1 (ABANDONADO) e nNF 2 (RESERVADO)");
    }

    // =========================================================================================
    // 2) Série 1 (JCHO) — marcarTransporteNaoEntregue → ultimo_numero=49 (gap grande, sem exigir +1)
    // =========================================================================================

    @Test
    @DisplayName("2) marcarTransporteNaoEntregue série 1: ultimo_numero 48→49 (gap grande), gate NULL, estado TRANSPORTE_NAO_ENTREGUE")
    void marcarTransporteNaoEntregue_serie1_avancaContadorAte49() throws Exception {
        inserirSequencia(CNPJ_JCHO, "1", 48, 3L);
        long idEmissao = inserirEmissao(CNPJ_JCHO, "1", 49, NfeEmissao.Estados.PENDENTE_CONFIRMACAO,
                "35260954393421000159550010000000491699768389", 0);
        atualizarGateParaId(CNPJ_JCHO, "1", idEmissao);

        TransporteNaoEntregueResultado r;
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            r = servico(session, "1").marcarTransporteNaoEntregue(idEmissao, "gateway 403 - lote nao chegou ao autorizador da SEFAZ");
            session.commit();
        }

        assertEquals(49, r.ultimoNumeroResultante());
        assertTrue(r.sequenciaAvancada());
        assertTrue(r.gateLiberado());
        assertEquals(49, lerUltimoNumero(CNPJ_JCHO, "1"));
        assertNull(lerGate(CNPJ_JCHO, "1"));
        assertEquals(NfeEmissao.Estados.TRANSPORTE_NAO_ENTREGUE, lerEstado(idEmissao));
    }

    // =========================================================================================
    // 3) Recovery repetido NÃO transforma o contador em 2,3,4... — idempotente de verdade
    // =========================================================================================

    @Test
    @DisplayName("3) abandonarCiclo repetido (3x): ultimo_numero fica em 1, nunca vira 2/3/4")
    void abandonarCiclo_repetido_naoAvancaAlemDoNNF() throws Exception {
        inserirSequencia(CNPJ_JZHENG, "5", 0, 2L);
        long idEmissao = inserirEmissao(CNPJ_JZHENG, "5", 1, NfeEmissao.Estados.AGUARDANDO_CORRECAO, null, 0);
        atualizarGateParaId(CNPJ_JZHENG, "5", idEmissao);

        for (int i = 1; i <= 3; i++) {
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                AbandonoCicloResultado r = servico(session, "5").abandonarCiclo(idEmissao, "chamada idempotente numero " + i);
                session.commit();
                assertEquals(1, r.ultimoNumeroResultante(), "chamada " + i + " nunca ultrapassa o nNF");
                if (i > 1) {
                    assertTrue(r.idempotente(), "da 2a chamada em diante é idempotente");
                    assertFalse(r.sequenciaAvancada(), "contador já em dia — nada a avançar");
                }
            }
        }
        assertEquals(1, lerUltimoNumero(CNPJ_JZHENG, "5"));
        assertEquals(1, contarEmissoes(CNPJ_JZHENG, "5"), "nenhuma linha nova de nfe_emissao criada pelas repetições");
    }

    // =========================================================================================
    // 4) ultimo_numero já >= nNF → recovery não altera o contador (nunca regride)
    // =========================================================================================

    @Test
    @DisplayName("4) abandonarCiclo com ultimo_numero já ADIANTE (5) do nNF (1): contador intocado, sequenciaAvancada=false")
    void abandonarCiclo_contadorAdiante_naoRegride() throws Exception {
        inserirSequencia(CNPJ_JZHENG, "5", 5, 2L);
        long idEmissao = inserirEmissao(CNPJ_JZHENG, "5", 1, NfeEmissao.Estados.AGUARDANDO_CORRECAO, null, 0);
        atualizarGateParaId(CNPJ_JZHENG, "5", idEmissao);

        AbandonoCicloResultado r;
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            r = servico(session, "5").abandonarCiclo(idEmissao, "abandono com contador ja adiantado");
            session.commit();
        }

        assertEquals(5, r.ultimoNumeroResultante(), "contador não regride");
        assertFalse(r.sequenciaAvancada());
        assertTrue(r.gateLiberado());
        assertEquals(5, lerUltimoNumero(CNPJ_JZHENG, "5"));
        assertEquals(NfeEmissao.Estados.ABANDONADO, lerEstado(idEmissao));
    }

    // =========================================================================================
    // 5) Reparo idempotente do estado BRICADO — estado já ABANDONADO mas ultimo_numero atrás
    //    (é EXATAMENTE o estado atual de HOM antes de reaplicar os recoveries)
    // =========================================================================================

    @Test
    @DisplayName("5) abandonarCiclo idempotente sobre estado bricado (ABANDONADO + ultimo_numero=0): repara o contador para 1")
    void abandonarCiclo_estadoBricado_reparaContador() throws Exception {
        inserirSequencia(CNPJ_JZHENG, "5", 0, null); // gate já livre, mas contador atrás
        long idEmissao = inserirEmissao(CNPJ_JZHENG, "5", 1, NfeEmissao.Estados.ABANDONADO, null, 0);

        AbandonoCicloResultado r;
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            r = servico(session, "5").abandonarCiclo(idEmissao, "reaplicacao do recovery apos correcao do modelo gap");
            session.commit();
        }

        assertTrue(r.idempotente(), "estado já era ABANDONADO — não re-marca");
        assertTrue(r.sequenciaAvancada(), "mas o contador estava atrás e foi reparado");
        assertEquals(1, r.ultimoNumeroResultante());
        assertEquals(1, lerUltimoNumero(CNPJ_JZHENG, "5"));

        // E a partir daqui, abrir ciclo novo funciona sem colidir.
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.SERIALIZABLE)) {
            AberturaCicloResultado novo = servico(session, "5").abrirCiclo(999L, CNPJ_JZHENG);
            session.commit();
            assertEquals(2, novo.emissao().getNumeroNfe());
        }
    }

    // =========================================================================================
    // 6) Cobertura EXPLÍCITA da uk_nfe_emissao_numero: sem o avanço do contador, abrirCiclo colide
    // =========================================================================================

    @Test
    @DisplayName("6) uk_nfe_emissao_numero: gate liberado SEM avançar o contador (bug antigo) → abrirCiclo colide no INSERT; com o recovery, não colide")
    void ukNfeEmissaoNumero_semAvancoColide_comRecoveryNaoColide() throws Exception {
        // 6a) Reproduz o bug antigo: encerra a linha e libera o gate na mão, deixando ultimo_numero=0.
        inserirSequencia(CNPJ_JZHENG, "5", 0, null);
        long idPreso = inserirEmissao(CNPJ_JZHENG, "5", 1, NfeEmissao.Estados.ABANDONADO, null, 0);

        RuntimeException colisao = assertThrows(RuntimeException.class, () -> {
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.SERIALIZABLE)) {
                servico(session, "5").abrirCiclo(1000L, CNPJ_JZHENG); // candidato = 0 + 1 = 1 → colide com idPreso
                session.commit();
            }
        });
        assertTrue(mensagemInclui(colisao, "uk_nfe_emissao_numero") || mensagemInclui(colisao, "Duplicate entry"),
                "sem o avanço do contador, o INSERT bate na UNIQUE: " + colisao);

        // 6b) Mesmo cenário, mas passando pelo recovery corrigido primeiro (repara o contador).
        limparCnpj(CNPJ_JZHENG);
        inserirSequencia(CNPJ_JZHENG, "5", 0, null);
        long idAbandonado = inserirEmissao(CNPJ_JZHENG, "5", 1, NfeEmissao.Estados.ABANDONADO, null, 0);
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            servico(session, "5").abandonarCiclo(idAbandonado, "reaplicacao do recovery modelo gap");
            session.commit();
        }
        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.SERIALIZABLE)) {
            AberturaCicloResultado novo = servico(session, "5").abrirCiclo(1001L, CNPJ_JZHENG);
            session.commit();
            assertEquals(2, novo.emissao().getNumeroNfe(), "após o reparo, o candidato é 2 — sem colisão");
        }
    }

    // =========================================================================================
    // 7) Concorrência recovery × recovery — mesmo id, sem exceção, contador determinístico
    // =========================================================================================

    @Test
    @DisplayName("7) abandonarCiclo concorrente (2 threads, mesmo id): sem deadlock/erro, ultimo_numero=1, gate NULL, estado ABANDONADO")
    void abandonarCiclo_concorrente_mesmoId_determinístico() throws Exception {
        inserirSequencia(CNPJ_JZHENG, "5", 0, 2L);
        long idEmissao = inserirEmissao(CNPJ_JZHENG, "5", 1, NfeEmissao.Estados.AGUARDANDO_CORRECAO, null, 0);
        atualizarGateParaId(CNPJ_JZHENG, "5", idEmissao);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        List<Throwable> erros = Collections.synchronizedList(new ArrayList<>());

        Callable<Void> tarefa = () -> {
            largada.await();
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                servico(session, "5").abandonarCiclo(idEmissao, "abandono concorrente");
                session.commit();
            } catch (Throwable t) {
                erros.add(t);
            }
            return null;
        };

        Future<Void> f1 = pool.submit(tarefa);
        Future<Void> f2 = pool.submit(tarefa);
        largada.countDown();
        f1.get(20, TimeUnit.SECONDS);
        f2.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(erros.isEmpty(), "nenhuma das duas chamadas pode vazar exceção: " + erros);
        assertEquals(1, lerUltimoNumero(CNPJ_JZHENG, "5"));
        assertNull(lerGate(CNPJ_JZHENG, "5"));
        assertEquals(NfeEmissao.Estados.ABANDONADO, lerEstado(idEmissao));
        assertEquals(1, contarEmissoes(CNPJ_JZHENG, "5"));
    }

    // =========================================================================================
    // 8) Concorrência recovery × abrirCiclo — nunca DuplicateKey; desfecho sempre íntegro
    // =========================================================================================

    @Test
    @DisplayName("8) abandonarCiclo × abrirCiclo concorrentes na mesma série: nenhum vaza SQLException; final consistente (ultimo_numero=1, sem nNF duplicado)")
    void abandonarCiclo_vs_abrirCiclo_concorrentes_semColisao() throws Exception {
        inserirSequencia(CNPJ_JZHENG, "5", 0, 2L);
        long idEmissao = inserirEmissao(CNPJ_JZHENG, "5", 1, NfeEmissao.Estados.AGUARDANDO_CORRECAO, null, 0);
        atualizarGateParaId(CNPJ_JZHENG, "5", idEmissao);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        List<Throwable> inesperados = Collections.synchronizedList(new ArrayList<>());

        Callable<Object> recovery = () -> {
            largada.await();
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                AbandonoCicloResultado r = servico(session, "5").abandonarCiclo(idEmissao, "abandono concorrente com abertura");
                session.commit();
                return r;
            } catch (BusinessException e) {
                return e;
            } catch (Throwable t) {
                inesperados.add(t);
                return t;
            }
        };
        Callable<Object> abertura = () -> {
            largada.await();
            try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.SERIALIZABLE)) {
                AberturaCicloResultado r = servico(session, "5").abrirCiclo(681L, CNPJ_JZHENG);
                session.commit();
                return r;
            } catch (BusinessException e) {
                return e; // emissaoEmAndamentoNaSerie, se a abertura correr antes do abandono — desfecho legítimo
            } catch (Throwable t) {
                inesperados.add(t);
                return t;
            }
        };

        Future<Object> fr = pool.submit(recovery);
        Future<Object> fa = pool.submit(abertura);
        largada.countDown();
        Object resRecovery = fr.get(20, TimeUnit.SECONDS);
        Object resAbertura = fa.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        assertTrue(inesperados.isEmpty(), "nenhuma SQLException/DuplicateKey pode vazar: " + inesperados);
        assertTrue(resRecovery instanceof AbandonoCicloResultado, "o recovery sempre conclui: " + resRecovery);
        assertEquals(NfeEmissao.Estados.ABANDONADO, lerEstado(idEmissao));
        assertEquals(1, lerUltimoNumero(CNPJ_JZHENG, "5"), "contador consolidado no nNF do ciclo abandonado");

        if (resAbertura instanceof AberturaCicloResultado ab) {
            // A abertura venceu depois do abandono: pega o próximo nNF (2), nunca o queimado (1).
            assertEquals(2, ab.emissao().getNumeroNfe());
            assertEquals(2, contarEmissoes(CNPJ_JZHENG, "5"));
        } else {
            assertTrue(resAbertura instanceof BusinessException, "ou abriu, ou recusou com BusinessException: " + resAbertura);
            assertEquals(1, contarEmissoes(CNPJ_JZHENG, "5"), "abertura recusada não deixou linha nova");
        }
    }

    // -------------------------------------------------------------------------
    // Infra
    // -------------------------------------------------------------------------

    /** nfe_sequencia/nfe_emissao reais; Empresa/Pedido/Estoque/NfeDocumento mockados. */
    private NfeEmissaoService servico(SqlSession session, String serieEmpresa) {
        NfeSequenciaMapper seqMapper = session.getMapper(NfeSequenciaMapper.class);
        NfeEmissaoMapper emissaoMapper = session.getMapper(NfeEmissaoMapper.class);
        NfeSequenciaService sequenciaService = new NfeSequenciaServiceImpl(seqMapper);

        EmpresaMapper empresaMapperMock = mock(EmpresaMapper.class);
        Empresa empresa = new Empresa();
        empresa.setId(1L);
        empresa.setSerieNfePadrao(serieEmpresa);
        when(empresaMapperMock.buscarPorCnpjParaAtualizar(any())).thenReturn(empresa);

        PedidoMapper pedidoMapperMock = mock(PedidoMapper.class);
        // buscarPorId devolve null → marcarTransporteNaoEntregue não toca Pedido/Estoque nesta suíte.

        return new NfeEmissaoService(empresaMapperMock, pedidoMapperMock,
                mock(br.com.borurio.app.mapper.PedidoItemMapper.class), sequenciaService, emissaoMapper,
                mock(br.com.borurio.fiscal.mapper.NfeDocumentoMapper.class),
                mock(br.com.borurio.app.service.EstoqueService.class),
                new br.com.borurio.fiscal.config.SefazReconciliacaoProperties());
    }

    private static Connection novaConexao() throws SQLException {
        return DriverManager.getConnection(P03LockOrderTestProperties.dbUrl(),
                P03LockOrderTestProperties.dbUsername(), P03LockOrderTestProperties.dbPassword());
    }

    private static void limparCnpj(String cnpj) throws SQLException {
        try (Connection conn = novaConexao()) {
            conn.createStatement().execute("DELETE FROM nfe_emissao WHERE cnpj_emitente = '" + cnpj + "'");
            conn.createStatement().execute("DELETE FROM nfe_sequencia WHERE cnpj_emitente = '" + cnpj + "'");
        }
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

    /** Insere um ciclo real e devolve o id gerado. */
    private long inserirEmissao(String cnpj, String serie, int numero, String estado,
                                 String chave, int tentativasConsulta) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO nfe_emissao (pedido_id, empresa_id, cnpj_emitente, modelo, serie, numero_nfe, "
                             + "estado, tentativas, chave_nfe, tentativas_consulta, cstat, xmotivo) "
                             + "VALUES (?, 1, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?)",
                     PreparedStatement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, 6700L + numero);
            ps.setString(2, cnpj);
            ps.setString(3, MODELO);
            ps.setString(4, serie);
            ps.setInt(5, numero);
            ps.setString(6, estado);
            if (chave == null) ps.setNull(7, java.sql.Types.VARCHAR);
            else ps.setString(7, chave);
            ps.setInt(8, tentativasConsulta);
            ps.setInt(9, 225);
            ps.setString(10, "Rejeicao: Falha no Schema XML do lote de NFe");
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                assertTrue(rs.next(), "id gerado");
                return rs.getLong(1);
            }
        }
    }

    private void atualizarGateParaId(String cnpj, String serie, long emissaoId) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE nfe_sequencia SET emissao_ativa_id = ? WHERE cnpj_emitente = ? AND serie = ?")) {
            ps.setLong(1, emissaoId);
            ps.setString(2, cnpj);
            ps.setString(3, serie);
            ps.executeUpdate();
        }
    }

    private int lerUltimoNumero(String cnpj, String serie) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT ultimo_numero FROM nfe_sequencia WHERE cnpj_emitente = ? AND serie = ?")) {
            ps.setString(1, cnpj);
            ps.setString(2, serie);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "linha de nfe_sequencia deveria existir");
                return rs.getInt(1);
            }
        }
    }

    private Long lerGate(String cnpj, String serie) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT emissao_ativa_id FROM nfe_sequencia WHERE cnpj_emitente = ? AND serie = ?")) {
            ps.setString(1, cnpj);
            ps.setString(2, serie);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "linha de nfe_sequencia deveria existir");
                long v = rs.getLong(1);
                return rs.wasNull() ? null : v;
            }
        }
    }

    private String lerEstado(long id) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement("SELECT estado FROM nfe_emissao WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "linha de nfe_emissao deveria existir");
                return rs.getString(1);
            }
        }
    }

    private int contarEmissoes(String cnpj, String serie) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM nfe_emissao WHERE cnpj_emitente = ? AND serie = ?")) {
            ps.setString(1, cnpj);
            ps.setString(2, serie);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        }
    }

    private static boolean mensagemInclui(Throwable t, String needle) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c.getMessage() != null && c.getMessage().contains(needle)) return true;
        }
        return false;
    }
}
