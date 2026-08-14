package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.EstoqueMovimentoMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.app.mapper.ProdutoMapper;
import br.com.borurio.app.service.EstoqueService;
import br.com.borurio.app.service.impl.EstoqueServiceImpl;
import br.com.borurio.fiscal.config.SefazReconciliacaoProperties;
import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.mapper.NfeEmissaoMapper;
import br.com.borurio.fiscal.mapper.NfeSequenciaMapper;
import br.com.borurio.fiscal.service.NfeSequenciaService;
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
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Banca do Gate Estoque (13-08-2026, item 3) — prova contra MySQL real que uma empresa com
 * {@code controlaEstoque=false} nunca produz efeito de estoque, mesmo sob concorrência real, ao
 * mesmo tempo em que a garantia fiscal exactly-once (já provada em
 * {@link Gate3ReconciliacaoRealMySqlIT}) continua valendo. Diferente daquele IT, aqui
 * {@link EstoqueServiceImpl} é REAL (ProdutoMapper/EstoqueMovimentoMapper reais contra o MySQL
 * efêmero, não mock) — a prova não é "o método Java não foi chamado", é "a tabela não mudou".
 *
 * *IT (não *Test): roda só quando executada explicitamente, contra um MySQL efêmero/descartável
 * (nunca borurio-mysql-dev/hom — ver {@link Gate3ReconciliacaoTestProperties}).
 */
class NfeEmissaoEstoqueDesativadoRealMySqlIT {

    private static final String CNPJ = "77665544000133";
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
        Environment env = new Environment("estoque-desativado-it", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.addMapper(NfeSequenciaMapper.class);
        config.addMapper(NfeEmissaoMapper.class);
        config.addMapper(ProdutoMapper.class);
        config.addMapper(EstoqueMovimentoMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);

        limpar();
    }

    @AfterAll
    static void limparFinal() throws Exception {
        limpar();
    }

    private static Connection novaConexao() throws SQLException {
        return DriverManager.getConnection(Gate3ReconciliacaoTestProperties.dbUrl(),
                Gate3ReconciliacaoTestProperties.dbUsername(), Gate3ReconciliacaoTestProperties.dbPassword());
    }

    private static void limpar() throws SQLException {
        try (Connection conn = novaConexao()) {
            conn.createStatement().execute(
                    "DELETE FROM estoque_movimento WHERE empresa_id IN (SELECT id FROM empresa WHERE cnpj = '" + CNPJ + "')");
            conn.createStatement().execute(
                    "DELETE FROM produto WHERE empresa_id IN (SELECT id FROM empresa WHERE cnpj = '" + CNPJ + "')");
            conn.createStatement().execute("DELETE FROM nfe_emissao WHERE cnpj_emitente = '" + CNPJ + "'");
            conn.createStatement().execute("DELETE FROM nfe_sequencia WHERE cnpj_emitente = '" + CNPJ + "'");
            conn.createStatement().execute("DELETE FROM empresa WHERE cnpj = '" + CNPJ + "'");
        }
    }

    /** Empresa real (controlaEstoque=false é passado direto ao serviço, não lido desta linha — mas
     * o produto/estoque_movimento reais precisam de uma empresa real por causa da FK). */
    private long inserirEmpresa() throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO empresa (cnpj, razao_social, uf) VALUES (?, 'Empresa Teste Estoque Desativado', 'SP')",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, CNPJ);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    /** Produto com saldo ZERO — cenário explícito do pedido do CC (saldo zero não pode bloquear). */
    private long inserirProdutoSaldoZero(long empresaId) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO produto (empresa_id, codigo, descricao, ncm, cfop, unidade, preco, estado, estoque, estoque_reservado) "
                             + "VALUES (?, 'SKU-ESTOQUE-DESATIVADO', 'Produto teste saldo zero', '12345678', '5102', 'UN', 10.00, 1, 0, 0)",
                     java.sql.Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, empresaId);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private void inserirSequencia(int ultimoNumero, Long emissaoAtivaId) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO nfe_sequencia (cnpj_emitente, serie, ultimo_numero, emissao_ativa_id) VALUES (?, '1', ?, ?)")) {
            ps.setString(1, CNPJ);
            ps.setInt(2, ultimoNumero);
            if (emissaoAtivaId == null) ps.setNull(3, java.sql.Types.BIGINT);
            else ps.setLong(3, emissaoAtivaId);
            ps.executeUpdate();
        }
    }

    private void inserirEmissao(long id, long pedidoId, long empresaId, int numero, String estado, String chaveNfe) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO nfe_emissao (id, pedido_id, empresa_id, cnpj_emitente, serie, numero_nfe, estado, "
                             + "chave_nfe, tentativas, tentativas_consulta) VALUES (?, ?, ?, ?, '1', ?, ?, ?, 1, 0)")) {
            ps.setLong(1, id);
            ps.setLong(2, pedidoId);
            ps.setLong(3, empresaId);
            ps.setString(4, CNPJ);
            ps.setInt(5, numero);
            ps.setString(6, estado);
            ps.setString(7, chaveNfe);
            ps.executeUpdate();
        }
    }

    private int lerUltimoNumero() throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT ultimo_numero FROM nfe_sequencia WHERE cnpj_emitente = ? AND serie = '1'")) {
            ps.setString(1, CNPJ);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private Long lerEmissaoAtivaId() throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT emissao_ativa_id FROM nfe_sequencia WHERE cnpj_emitente = ? AND serie = '1'")) {
            ps.setString(1, CNPJ);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
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
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private BigDecimal[] lerSaldoProduto(long produtoId) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT estoque, estoque_reservado FROM produto WHERE id = ?")) {
            ps.setLong(1, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return new BigDecimal[]{rs.getBigDecimal(1), rs.getBigDecimal(2)};
            }
        }
    }

    private int contarMovimentos(long produtoId) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM estoque_movimento WHERE produto_id = ?")) {
            ps.setLong(1, produtoId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private NfeEmissaoService construirServiceReal(SqlSession session, PedidoMapper pedidoMapperCompartilhado) {
        NfeSequenciaMapper seqMapper = session.getMapper(NfeSequenciaMapper.class);
        NfeEmissaoMapper emissaoMapper = session.getMapper(NfeEmissaoMapper.class);
        ProdutoMapper produtoMapper = session.getMapper(ProdutoMapper.class);
        EstoqueMovimentoMapper movimentoMapper = session.getMapper(EstoqueMovimentoMapper.class);
        NfeSequenciaService sequenciaService = new NfeSequenciaServiceImpl(seqMapper);

        // EstoqueService REAL (não mock) — participa da MESMA sessão/transação MyBatis que
        // resolverCicloComEfeitos abre, mesmo padrão de integração real usado em produção. A
        // prova de "nunca toca estoque" precisa ser contra a tabela real, não contra a ausência
        // de chamada a um mock.
        EstoqueService estoqueServiceReal = new EstoqueServiceImpl(produtoMapper, movimentoMapper);

        EmpresaMapper empresaMapperMock = mock(EmpresaMapper.class);
        Empresa empresa = new Empresa();
        empresa.setId(1L);
        empresa.setSerieNfePadrao("1");
        when(empresaMapperMock.buscarPorCnpjParaAtualizar(any())).thenReturn(empresa);

        return new NfeEmissaoService(empresaMapperMock, pedidoMapperCompartilhado, sequenciaService, emissaoMapper,
                estoqueServiceReal, new SefazReconciliacaoProperties());
    }

    private List<PedidoItem> itensDoProduto(long produtoId) {
        PedidoItem item = new PedidoItem();
        item.setProdutoId(produtoId);
        item.setQuantidade(new BigDecimal("2"));
        return List.of(item);
    }

    @Test
    @DisplayName("controlaEstoque=false, saldo zero, 5 chamadas concorrentes de resolverCicloComEfeitos(AUTORIZADO) sobre o MESMO ciclo: "
            + "exactly-once fiscal continua valendo, e produto/estoque_movimento permanecem intocados na tabela real")
    void resolverCicloComEfeitos_controlaEstoqueFalse_concorrente_nuncaTocaEstoqueReal() throws Exception {
        limpar();
        long empresaId = inserirEmpresa();
        long produtoId = inserirProdutoSaldoZero(empresaId);
        inserirSequencia(4, 730L);
        inserirEmissao(730L, 5L, empresaId, 5, NfeEmissao.Estados.TRANSMITIDO,
                "35260500000000000191550010000000055000000016");

        PedidoMapper pedidoMapperCompartilhado = mock(PedidoMapper.class);
        List<PedidoItem> itens = itensDoProduto(produtoId);

        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Void>> futuros = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futuros.add(pool.submit((Callable<Void>) () -> {
                largada.await();
                try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                    NfeEmissaoService service = construirServiceReal(session, pedidoMapperCompartilhado);
                    try {
                        // controlaEstoque=FALSE — mesmo com saldo zero no produto e concorrência
                        // real disputando o mesmo ciclo, nenhuma chamada pode tocar estoque.
                        service.resolverCicloComEfeitos(730L, NfeEmissao.Estados.AUTORIZADO, 100, null, "prot-estoque-off",
                                5L, "AUTORIZADO", "chave-estoque-off", false, itens, empresaId, "sistema");
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

        // Garantia fiscal exactly-once continua valendo — controlaEstoque=false não afeta o
        // mecanismo de lock/consumo de número (mesma prova de Gate3ReconciliacaoRealMySqlIT,
        // agora combinada com estoque desativado).
        assertEquals(5, lerUltimoNumero(), "ultimo_numero deveria avançar de 4 para 5, nunca mais de uma vez");
        assertNull(lerEmissaoAtivaId(), "gate precisa estar liberado após o terminal");
        assertEquals(NfeEmissao.Estados.AUTORIZADO, lerEstadoEmissao(730L));

        // Prova real (não mock): saldo do produto exatamente como estava antes — nenhuma reserva,
        // baixa, desfazimento ou estorno, mesmo sob 5 tentativas concorrentes.
        BigDecimal[] saldo = lerSaldoProduto(produtoId);
        assertEquals(0, new BigDecimal("0.0000").compareTo(saldo[0]), "estoque total não pode ter mudado");
        assertEquals(0, new BigDecimal("0.0000").compareTo(saldo[1]), "estoque_reservado não pode ter mudado");

        // Prova real (não mock): nenhuma linha de movimento foi criada para este produto.
        assertEquals(0, contarMovimentos(produtoId),
                "nenhuma linha de estoque_movimento pode existir para uma empresa com controlaEstoque=false");
    }
}
