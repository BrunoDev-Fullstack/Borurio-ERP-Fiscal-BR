package br.com.borurio.web.service;

import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.fiscal.config.SefazReconciliacaoProperties;
import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.entity.NfeEvento;
import br.com.borurio.fiscal.mapper.NfeEmissaoMapper;
import br.com.borurio.fiscal.mapper.NfeEventoMapper;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.session.TransactionIsolationLevel;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.flywaydb.core.Flyway;
import org.mybatis.spring.SqlSessionTemplate;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Gate de cancelamento (evento 110111, 12-08-2026) — prova contra MySQL real dos dois pontos de
 * concorrência do desenho aprovado:
 *   1) claim estrutural via UNIQUE KEY (chave_nfe, tipo_evento, n_seq_evento) — N chamadas
 *      concorrentes de reivindicar() para a MESMA identidade nova nunca criam duas linhas.
 *   2) finalizar() exactly-once — N chamadas concorrentes sobre o MESMO evento TRANSMITIDO
 *      aplicam efeitos (NfeEmissao=CANCELADO + Pedido=CANCELADO + estorno de estoque) uma única
 *      vez, e a autorização original (cstat/nprot de nfe_emissao) nunca é sobrescrita.
 *
 * *IT (não *Test): roda só quando executada explicitamente, contra um MySQL efêmero/descartável
 * -- reutiliza Gate3ReconciliacaoTestProperties (mesmas variáveis de ambiente GATE3_DB_*, credenciais
 * de infraestrutura de teste, não específicas de nenhum gate). Nunca toca borurio-mysql-dev/hom.
 */
class NfeEventoConcorrenciaRealMySqlIT {

    private static final String CNPJ = "99887766000155";
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
        Environment env = new Environment("nfe-evento-concorrencia-it", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.addMapper(NfeEventoMapper.class);
        config.addMapper(NfeEmissaoMapper.class);
        config.addMapper(PedidoMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);

        limpar();
    }

    @AfterAll
    static void limparFinal() throws Exception {
        limpar();
        try (Connection conn = novaConexao()) {
            conn.createStatement().execute("DELETE FROM pedido WHERE cnpj_emitente = '" + CNPJ + "'");
        }
    }

    // -------------------------------------------------------------------------
    // Infraestrutura
    // -------------------------------------------------------------------------

    private static Connection novaConexao() throws SQLException {
        return DriverManager.getConnection(Gate3ReconciliacaoTestProperties.dbUrl(),
                Gate3ReconciliacaoTestProperties.dbUsername(), Gate3ReconciliacaoTestProperties.dbPassword());
    }

    private static void limpar() throws SQLException {
        try (Connection conn = novaConexao()) {
            conn.createStatement().execute("DELETE FROM nfe_evento WHERE cnpj_emitente = '" + CNPJ + "'");
            conn.createStatement().execute("DELETE FROM nfe_emissao WHERE cnpj_emitente = '" + CNPJ + "'");
        }
    }

    private void inserirEmissaoAutorizada(long id, long pedidoId, String chaveNfe) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO nfe_emissao (id, pedido_id, empresa_id, cnpj_emitente, serie, numero_nfe, "
                             + "estado, chave_nfe, cstat, xmotivo, nprot, tentativas, resolvido_em) "
                             + "VALUES (?, ?, 1, ?, '1', 5, 'AUTORIZADO', ?, 100, 'Autorizado o uso da NF-e', "
                             + "'135260000000001', 1, NOW())")) {
            ps.setLong(1, id);
            ps.setLong(2, pedidoId);
            ps.setString(3, CNPJ);
            ps.setString(4, chaveNfe);
            ps.executeUpdate();
        }
    }

    private void inserirPedidoAutorizado(long pedidoId, String chaveNfe) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO pedido (id, numero, cnpj_emitente, dest_cnpj_cpf, dest_razao_social, "
                             + "status, chave_nfe) VALUES (?, ?, ?, '12345678900', 'Cliente Teste Rollback', "
                             + "'AUTORIZADO', ?)")) {
            ps.setLong(1, pedidoId);
            ps.setString(2, "PED-" + pedidoId);
            ps.setString(3, CNPJ);
            ps.setString(4, chaveNfe);
            ps.executeUpdate();
        }
    }

    private String lerStatusPedido(long pedidoId) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement("SELECT status FROM pedido WHERE id = ?")) {
            ps.setLong(1, pedidoId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private String lerEstadoEvento(long eventoId) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement("SELECT estado FROM nfe_evento WHERE id = ?")) {
            ps.setLong(1, eventoId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private int contarEventos(String chaveNfe) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM nfe_evento WHERE chave_nfe = ? AND tipo_evento = '110111'")) {
            ps.setString(1, chaveNfe);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private long inserirEventoTransmitido(long pedidoId, long emissaoId, String chaveNfe) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO nfe_evento (pedido_id, emissao_id, empresa_id, cnpj_emitente, chave_nfe, "
                             + "tipo_evento, n_seq_evento, id_evento, estado, transmitido_em) "
                             + "VALUES (?, ?, 1, ?, ?, '110111', 1, ?, 'TRANSMITIDO', NOW())",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, pedidoId);
            ps.setLong(2, emissaoId);
            ps.setString(3, CNPJ);
            ps.setString(4, chaveNfe);
            ps.setString(5, "ID110111" + chaveNfe + "01");
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                assertTrue(keys.next());
                return keys.getLong(1);
            }
        }
    }

    private String lerEstadoEmissao(long id) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement("SELECT estado FROM nfe_emissao WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private int lerCstatEmissao(long id) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement("SELECT cstat FROM nfe_emissao WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private String lerNprotEmissao(long id) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement("SELECT nprot FROM nfe_emissao WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private NfeEventoService construirServiceReal(SqlSession session, EstoqueService estoqueServiceCompartilhado,
                                                    PedidoMapper pedidoMapperCompartilhado) {
        NfeEventoMapper eventoMapper = session.getMapper(NfeEventoMapper.class);
        NfeEmissaoMapper emissaoMapper = session.getMapper(NfeEmissaoMapper.class);
        return new NfeEventoService(eventoMapper, emissaoMapper, pedidoMapperCompartilhado, estoqueServiceCompartilhado,
                new SefazReconciliacaoProperties());
    }

    private List<PedidoItem> itensPadrao() {
        PedidoItem item = new PedidoItem();
        item.setProdutoId(1L);
        item.setQuantidade(new BigDecimal("2"));
        return List.of(item);
    }

    // =========================================================================================
    // Claim estrutural (UNIQUE KEY) sob concorrência real
    // =========================================================================================

    @Test
    @DisplayName("10 chamadas concorrentes de reivindicar() para a MESMA identidade nova — nunca duas linhas em nfe_evento")
    void reivindicar_dezChamadasConcorrentes_nuncaDuasLinhas() throws Exception {
        limpar();
        String chave = "35260899000000000191550010000000091000000010";
        long pedidoId = 900L;
        long emissaoId = 900L;
        inserirEmissaoAutorizada(emissaoId, pedidoId, chave);

        // SqlSessionTemplate (não SqlSession bruta): é a MESMA camada que o MyBatis-Spring usa
        // em produção para os beans @Mapper injetados via Spring -- só ela aplica a tradução de
        // exceção (SQLIntegrityConstraintViolationException -> DuplicateKeyException) que
        // NfeEventoService.inserirNovo depende para tratar a corrida do INSERT. Thread-safe por
        // desenho (diferente de SqlSession bruta), então uma única instância pode ser
        // compartilhada por todas as threads, igual a um bean singleton real.
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(sqlSessionFactory);
        NfeEventoMapper eventoMapper = sessionTemplate.getMapper(NfeEventoMapper.class);
        NfeEmissaoMapper emissaoMapperTemplate = sessionTemplate.getMapper(NfeEmissaoMapper.class);
        NfeEventoService service = new NfeEventoService(eventoMapper, emissaoMapperTemplate, mock(PedidoMapper.class),
                mock(EstoqueService.class), new SefazReconciliacaoProperties());

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<NfeEventoService.Claim>> futuros = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futuros.add(pool.submit(() -> {
                largada.await();
                return service.reivindicar(pedidoId, emissaoId, 1L, CNPJ, chave, "Cliente desistiu da compra");
            }));
        }
        largada.countDown();

        for (Future<NfeEventoService.Claim> f : futuros) {
            assertNotNull(f.get(15, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertEquals(1, contarEventos(chave), "10 chamadas concorrentes para a mesma identidade fiscal nunca podem criar mais de uma linha");
    }

    // =========================================================================================
    // finalizar() exactly-once sob concorrência real — preserva evidência de autorização original
    // =========================================================================================

    @Test
    @DisplayName("5 chamadas concorrentes de finalizar(REGISTRADO) sobre o MESMO evento — efeitos aplicados exatamente uma vez; cstat/nProt de autorização originais preservados")
    void finalizar_registrado_concorrente_exactlyOnce_preservaAutorizacaoOriginal() throws Exception {
        limpar();
        String chave = "35260899000000000191550010000000092000000019";
        long pedidoId = 901L;
        long emissaoId = 901L;
        inserirEmissaoAutorizada(emissaoId, pedidoId, chave);
        long eventoId = inserirEventoTransmitido(pedidoId, emissaoId, chave);

        EstoqueService estoqueServiceCompartilhado = mock(EstoqueService.class);
        PedidoMapper pedidoMapperCompartilhado = mock(PedidoMapper.class);

        NfeEventoService.Decisao decisao = new NfeEventoService.Decisao(
                NfeEvento.Estados.REGISTRADO, 135, "Evento registrado e vinculado a NF-e", "135260000009999", false);

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Void>> futuros = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futuros.add(pool.submit((Callable<Void>) () -> {
                largada.await();
                try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                    NfeEventoService service = construirServiceReal(session, estoqueServiceCompartilhado, pedidoMapperCompartilhado);
                    try {
                        service.finalizar(eventoId, decisao, NfeEvento.OrigensResolucao.EVENTO_DIRETO,
                                emissaoId, pedidoId, true, itensPadrao(), 1L, "sistema");
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

        // Projeção aplicada exatamente uma vez.
        assertEquals(NfeEmissao.Estados.CANCELADO, lerEstadoEmissao(emissaoId));
        verify(estoqueServiceCompartilhado, times(1)).estornarBaixaItens(any(), any(), any(), anyString());
        verify(pedidoMapperCompartilhado, times(1)).atualizarStatus(pedidoId, "CANCELADO", chave);

        // Evidência de autorização original NUNCA sobrescrita pelo evento de cancelamento.
        assertEquals(100, lerCstatEmissao(emissaoId), "cstat da autorização original (100) precisa sobreviver ao cancelamento");
        assertEquals("135260000000001", lerNprotEmissao(emissaoId), "nProt de autorização original precisa sobreviver ao cancelamento");
    }

    @Test
    @DisplayName("5 chamadas concorrentes de finalizar(REJEITADO) sobre o MESMO evento — nenhum efeito aplicado, mesmo sob concorrência")
    void finalizar_rejeitado_concorrente_nenhumEfeito() throws Exception {
        limpar();
        String chave = "35260899000000000191550010000000093000000018";
        long pedidoId = 902L;
        long emissaoId = 902L;
        inserirEmissaoAutorizada(emissaoId, pedidoId, chave);
        long eventoId = inserirEventoTransmitido(pedidoId, emissaoId, chave);

        EstoqueService estoqueServiceCompartilhado = mock(EstoqueService.class);
        PedidoMapper pedidoMapperCompartilhado = mock(PedidoMapper.class);

        NfeEventoService.Decisao decisao = new NfeEventoService.Decisao(
                NfeEvento.Estados.REJEITADO, 280, "Rejeição: dado inconsistente", null, false);

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Void>> futuros = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futuros.add(pool.submit((Callable<Void>) () -> {
                largada.await();
                try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                    NfeEventoService service = construirServiceReal(session, estoqueServiceCompartilhado, pedidoMapperCompartilhado);
                    try {
                        service.finalizar(eventoId, decisao, NfeEvento.OrigensResolucao.EVENTO_DIRETO,
                                emissaoId, pedidoId, true, itensPadrao(), 1L, "sistema");
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

        assertEquals(NfeEmissao.Estados.AUTORIZADO, lerEstadoEmissao(emissaoId), "rejeição nunca projeta CANCELADO");
        verify(estoqueServiceCompartilhado, times(0)).estornarBaixaItens(any(), any(), any(), anyString());
        verify(pedidoMapperCompartilhado, times(0)).atualizarStatus(any(), any(), any());
    }

    // =========================================================================================
    // Rollback real -- exceção forçada APÓS escritas parciais reais (nfe_evento + NfeEmissao +
    // Pedido), ANTES do fim da finalização -- prova via SQL direto que nada fica parcialmente
    // persistido (achado de banca, 12-08-2026, ponto 1).
    // =========================================================================================

    @Test
    @DisplayName("Rollback real: exceção forçada depois de nfe_evento/NfeEmissao/Pedido gravados (antes do fim da finalização) — nada fica parcialmente persistido")
    void finalizar_excecaoForcadaAposEscritasParciais_rollbackCompleto() throws Exception {
        limpar();
        String chave = "35260899000000000191550010000000094000000017";
        long pedidoId = 903L;
        long emissaoId = 903L;
        inserirEmissaoAutorizada(emissaoId, pedidoId, chave);
        inserirPedidoAutorizado(pedidoId, chave);
        long eventoId = inserirEventoTransmitido(pedidoId, emissaoId, chave);

        // estoqueService e o ULTIMO passo de finalizar() -- lanca a exceção só depois que
        // nfe_evento.atualizarResultado + NfeEmissao.marcarCancelado + Pedido.atualizarStatus já
        // executaram de verdade na mesma conexão/transação (mapper real, nunca mockado nesta prova).
        EstoqueService estoqueServiceQuebrado = mock(EstoqueService.class);
        doThrow(new RuntimeException("Falha simulada de estoque -- forçada de propósito para provar rollback"))
                .when(estoqueServiceQuebrado).estornarBaixaItens(any(), any(), any(), anyString());

        NfeEventoService.Decisao decisao = new NfeEventoService.Decisao(
                NfeEvento.Estados.REGISTRADO, 135, "Evento registrado e vinculado a NF-e", "135260000009999", false);

        try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
            NfeEventoMapper eventoMapper = session.getMapper(NfeEventoMapper.class);
            NfeEmissaoMapper emissaoMapper = session.getMapper(NfeEmissaoMapper.class);
            PedidoMapper pedidoMapperReal = session.getMapper(PedidoMapper.class);
            NfeEventoService service = new NfeEventoService(eventoMapper, emissaoMapper, pedidoMapperReal,
                    estoqueServiceQuebrado, new SefazReconciliacaoProperties());

            RuntimeException falha = assertThrows(RuntimeException.class, () ->
                    service.finalizar(eventoId, decisao, NfeEvento.OrigensResolucao.EVENTO_DIRETO,
                            emissaoId, pedidoId, true, itensPadrao(), 1L, "sistema"));
            assertTrue(falha.getMessage().contains("Falha simulada"));

            // ROLLBACK real da mesma sessão/conexão -- desfaz nfe_evento + NfeEmissao + Pedido,
            // que já tinham sido escritos (mas não commitados) antes da exceção.
            session.rollback();
        }

        // Prova via SQL direto (conexões NOVAS, fora da sessão que rodou o teste) que nada ficou
        // parcialmente persistido -- os três estados voltam exatamente ao que eram antes da chamada.
        assertEquals(NfeEvento.Estados.TRANSMITIDO, lerEstadoEvento(eventoId),
                "nfe_evento precisa voltar ao estado anterior — nunca ficar REGISTRADO sem os demais efeitos");
        assertEquals(NfeEmissao.Estados.AUTORIZADO, lerEstadoEmissao(emissaoId),
                "NfeEmissao precisa voltar a AUTORIZADO — nunca ficar CANCELADO sem Pedido/estoque");
        assertEquals("AUTORIZADO", lerStatusPedido(pedidoId),
                "Pedido precisa voltar a AUTORIZADO — a escrita real (não mockada) precisa ter sido revertida");
    }
}
