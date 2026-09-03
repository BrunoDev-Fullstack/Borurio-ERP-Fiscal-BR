package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.fiscal.config.SefazReconciliacaoProperties;
import br.com.borurio.fiscal.entity.NfeEmissao;
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
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gate 3 (reconciliação, 10-08-2026) — prova contra MySQL real dos dois P0 da auditoria:
 *   P0-A: exactly-once do conjunto nfe_emissao + nfe_sequencia + Pedido + Estoque
 *         (NfeEmissaoService.resolverCicloComEfeitos).
 *   P0-B: claim atômico da janela de reconciliação — duas chamadas concorrentes nunca vencem
 *         a mesma janela (NfeEmissaoMapper.tentarAdquirirJanelaConsulta).
 *
 * *IT (não *Test): roda só quando executada explicitamente, contra um MySQL efêmero/descartável
 * (nunca borurio-mysql-dev/hom — ver Gate3ReconciliacaoTestProperties). EmpresaMapper/PedidoMapper/
 * EstoqueService são mockados (mesmo padrão de NfeEmissaoLockOrderRealMySqlIT) — nfe_sequencia e
 * nfe_emissao são sempre reais, via MyBatis contra o MySQL efêmero.
 */
class Gate3ReconciliacaoRealMySqlIT {

    private static final String CNPJ = "88776655000122";
    private static SqlSessionFactory sqlSessionFactory;

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
        Environment env = new Environment("gate3-reconciliacao-it", new JdbcTransactionFactory(), dataSource);
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
    // Infraestrutura
    // -------------------------------------------------------------------------

    private static Connection novaConexao() throws SQLException {
        return DriverManager.getConnection(Gate3ReconciliacaoTestProperties.dbUrl(),
                Gate3ReconciliacaoTestProperties.dbUsername(), Gate3ReconciliacaoTestProperties.dbPassword());
    }

    private static void limparCnpj(String cnpj) throws SQLException {
        try (Connection conn = novaConexao()) {
            conn.createStatement().execute("DELETE FROM nfe_emissao WHERE cnpj_emitente = '" + cnpj + "'");
            conn.createStatement().execute("DELETE FROM nfe_sequencia WHERE cnpj_emitente = '" + cnpj + "'");
        }
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

    private void inserirEmissao(long id, long pedidoId, String serie, int numero, String estado,
                                 String chaveNfe, int tentativasConsulta) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO nfe_emissao (id, pedido_id, empresa_id, cnpj_emitente, serie, numero_nfe, estado, "
                             + "chave_nfe, tentativas, tentativas_consulta) VALUES (?, ?, 1, ?, ?, ?, ?, ?, 1, ?)")) {
            ps.setLong(1, id);
            ps.setLong(2, pedidoId);
            ps.setString(3, CNPJ);
            ps.setString(4, serie);
            ps.setInt(5, numero);
            ps.setString(6, estado);
            ps.setString(7, chaveNfe);
            ps.setInt(8, tentativasConsulta);
            ps.executeUpdate();
        }
    }

    private int lerUltimoNumero(String serie) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT ultimo_numero FROM nfe_sequencia WHERE cnpj_emitente = ? AND serie = ?")) {
            ps.setString(1, CNPJ);
            ps.setString(2, serie);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private Long lerEmissaoAtivaId(String serie) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT emissao_ativa_id FROM nfe_sequencia WHERE cnpj_emitente = ? AND serie = ?")) {
            ps.setString(1, CNPJ);
            ps.setString(2, serie);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
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
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private NfeEmissaoService construirServiceReal(SqlSession session, EstoqueService estoqueServiceCompartilhado,
                                                     PedidoMapper pedidoMapperCompartilhado) {
        NfeSequenciaMapper seqMapper = session.getMapper(NfeSequenciaMapper.class);
        NfeEmissaoMapper emissaoMapper = session.getMapper(NfeEmissaoMapper.class);
        NfeSequenciaService sequenciaService = new NfeSequenciaServiceImpl(seqMapper);

        EmpresaMapper empresaMapperMock = mock(EmpresaMapper.class);
        Empresa empresa = new Empresa();
        empresa.setId(1L);
        empresa.setSerieNfePadrao("1");
        when(empresaMapperMock.buscarPorCnpjParaAtualizar(any())).thenReturn(empresa);

        return new NfeEmissaoService(empresaMapperMock, pedidoMapperCompartilhado, mock(br.com.borurio.app.mapper.PedidoItemMapper.class), sequenciaService, emissaoMapper,
                mock(br.com.borurio.fiscal.mapper.NfeDocumentoMapper.class), estoqueServiceCompartilhado, new SefazReconciliacaoProperties());
    }

    private List<PedidoItem> itensPadrao() {
        PedidoItem item = new PedidoItem();
        item.setProdutoId(1L);
        item.setQuantidade(new BigDecimal("2"));
        return List.of(item);
    }

    // =========================================================================================
    // P0-B: claim atômico da janela de reconciliação sob concorrência real
    // =========================================================================================

    @Test
    @DisplayName("P0-B: 10 chamadas concorrentes de tentarAdquirirJanelaConsulta sobre o MESMO ciclo — exatamente uma vence")
    void tentarAdquirirJanelaConsulta_dezChamadasConcorrentes_apenasUmaVence() throws Exception {
        limparCnpj(CNPJ);
        inserirSequencia("1", 4, 700L);
        inserirEmissao(700L, 1L, "1", 5, NfeEmissao.Estados.PENDENTE_CONFIRMACAO,
                "35260500000000000191550010000000051000000019", 0);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Boolean>> futuros = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futuros.add(pool.submit(() -> {
                largada.await();
                try (SqlSession session = sqlSessionFactory.openSession(true)) { // autocommit — UPDATE única, curta
                    NfeEmissaoService service = construirServiceReal(session, mock(EstoqueService.class), mock(PedidoMapper.class));
                    return service.tentarAdquirirJanelaConsulta(700L);
                }
            }));
        }
        largada.countDown();

        int vencedores = 0;
        for (Future<Boolean> f : futuros) {
            if (f.get(15, TimeUnit.SECONDS)) vencedores++;
        }
        pool.shutdown();

        assertEquals(1, vencedores, "exatamente uma das " + threads + " chamadas concorrentes deveria vencer a janela de backoff");
    }

    // =========================================================================================
    // P0-A: exactly-once do conjunto nfe_emissao + nfe_sequencia + Pedido + Estoque
    // =========================================================================================

    @Test
    @DisplayName("P0-A: 5 chamadas concorrentes de resolverCicloComEfeitos(AUTORIZADO) sobre o MESMO ciclo — baixa de estoque e atualização de Pedido exatamente uma vez")
    void resolverCicloComEfeitos_autorizado_concorrente_exactlyOnce() throws Exception {
        limparCnpj(CNPJ);
        inserirSequencia("1", 4, 710L);
        inserirEmissao(710L, 2L, "1", 5, NfeEmissao.Estados.TRANSMITIDO,
                "35260500000000000191550010000000052000000010", 0);

        EstoqueService estoqueServiceCompartilhado = mock(EstoqueService.class);
        PedidoMapper pedidoMapperCompartilhado = mock(PedidoMapper.class);

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Void>> futuros = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futuros.add(pool.submit((Callable<Void>) () -> {
                largada.await();
                try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                    NfeEmissaoService service = construirServiceReal(session, estoqueServiceCompartilhado, pedidoMapperCompartilhado);
                    try {
                        service.resolverCicloComEfeitos(710L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-concorrencia",
                                2L, "AUTORIZADO", "chave-x", true, itensPadrao(), 10L, "sistema");
                        session.commit();
                    } catch (Exception e) {
                        session.rollback();
                        throw e;
                    }
                }
                return null;
            }));
        }
        largada.countDown();
        for (Future<Void> f : futuros) f.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        // Efeito fiscal: número consumido exatamente uma vez, gate liberado.
        assertEquals(5, lerUltimoNumero("1"), "ultimo_numero deveria avançar de 4 para 5, nunca mais de uma vez");
        assertNull(lerEmissaoAtivaId("1"), "gate precisa estar liberado após o terminal");
        assertEquals(NfeEmissao.Estados.AUTORIZADO, lerEstado(710L));

        // Efeito operacional: exactly-once, mesmo com 5 chamadas concorrentes tentando aplicar.
        verify(estoqueServiceCompartilhado, times(1)).baixaDefinitivaItens(any(), any(), any(), anyString());
        verify(pedidoMapperCompartilhado, times(1)).atualizarStatus(eq2L(), eqAutorizado(), any());
    }

    @Test
    @DisplayName("P0-A: 5 chamadas concorrentes de resolverCicloComEfeitos(NUMERO_OCUPADO) — consome número e desfaz reserva exatamente uma vez; próximo ciclo usa o número seguinte")
    void resolverCicloComEfeitos_numeroOcupado_concorrente_exactlyOnce_proximoNumeroSeguinte() throws Exception {
        limparCnpj(CNPJ);
        inserirSequencia("1", 4, 720L);
        inserirEmissao(720L, 3L, "1", 5, NfeEmissao.Estados.PENDENTE_CONFIRMACAO,
                "35260500000000000191550010000000053000000018", 0);

        EstoqueService estoqueServiceCompartilhado = mock(EstoqueService.class);
        PedidoMapper pedidoMapperCompartilhado = mock(PedidoMapper.class);

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Void>> futuros = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futuros.add(pool.submit((Callable<Void>) () -> {
                largada.await();
                try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                    NfeEmissaoService service = construirServiceReal(session, estoqueServiceCompartilhado, pedidoMapperCompartilhado);
                    try {
                        service.resolverCicloComEfeitos(720L, NfeEmissao.Estados.NUMERO_OCUPADO, 205, "NF-e já denegada", null,
                                3L, "ERRO", "chave-y", true, itensPadrao(), 10L, "sistema");
                        session.commit();
                    } catch (Exception e) {
                        session.rollback();
                        throw e;
                    }
                }
                return null;
            }));
        }
        largada.countDown();
        for (Future<Void> f : futuros) f.get(20, TimeUnit.SECONDS);
        pool.shutdown();

        assertEquals(5, lerUltimoNumero("1"), "número 5 precisa ser consumido (queimado) exatamente uma vez, nunca reaproveitado");
        assertNull(lerEmissaoAtivaId("1"), "gate precisa estar liberado — próximo pedido pode abrir ciclo novo");
        assertEquals(NfeEmissao.Estados.NUMERO_OCUPADO, lerEstado(720L));
        verify(estoqueServiceCompartilhado, times(1)).desfazerReservaItens(any(), any(), any(), anyString());
        verify(estoqueServiceCompartilhado, times(0)).baixaDefinitivaItens(any(), any(), any(), anyString());

        // Próximo pedido da mesma série: abre ciclo novo com o PRÓXIMO número (6), nunca reutiliza o 5 queimado.
        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            NfeEmissaoService service = construirServiceReal(session, mock(EstoqueService.class), mock(PedidoMapper.class));
            AberturaCicloResultado resultado = service.abrirCiclo(4L, CNPJ);
            assertEquals(6, resultado.emissao().getNumeroNfe(),
                    "o número 5 (NUMERO_OCUPADO) nunca pode ser reaproveitado — próximo pedido usa 6");
        }
    }

    private static Long eq2L() { return org.mockito.ArgumentMatchers.eq(2L); }
    private static String eqAutorizado() { return org.mockito.ArgumentMatchers.eq("AUTORIZADO"); }
}
