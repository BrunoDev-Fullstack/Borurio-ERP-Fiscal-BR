package br.com.borurio.web.service;

import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.mapper.PedidoItemMapper;
import br.com.borurio.app.mapper.PedidoMapper;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Correção controlada de texto fiscal (03-09-2026, V1 — acordo com OMS) — prova contra MySQL real
 * dos dois invariantes acordados com o Xiao Li:
 *   (5) a correção só grava quando o pedido AINDA está em RASCUNHO/REJEITADO/ERRO;
 *   (6) correção e {@code /emitir} nunca correm ao mesmo tempo sobre o mesmo pedido — o UPDATE
 *       condicional em {@code pedido} serializa as duas pela mesma linha (mesmo mecanismo já
 *       provado para {@code reivindicarParaEmissao}, ver {@link NfeEmissaoLockOrderRealMySqlIT}).
 *
 * Testado no nível do mapper (PedidoMapper/PedidoItemMapper), não via PedidoOperacaoService —
 * merge/validação de texto já têm cobertura de unidade em PedidoOperacaoServiceTest; aqui o que
 * importa é o comportamento do UPDATE condicional contra lock real de linha do InnoDB.
 *
 * *IT (não *Test): roda só quando executada explicitamente, contra um MySQL efêmero/descartável
 * (nunca borurio-mysql-dev/hom — ver Gate3ReconciliacaoTestProperties, reaproveitada aqui).
 */
class PedidoCorrecaoConcorrenciaRealMySqlIT {

    private static SqlSessionFactory sqlSessionFactory;
    private static final String CNPJ = "99887766000155";

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
        Environment env = new Environment("pedido-correcao-concorrencia-it", new JdbcTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.addMapper(PedidoMapper.class);
        config.addMapper(PedidoItemMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);

        limparCnpj();
    }

    @AfterAll
    static void limparFinal() throws Exception {
        limparCnpj();
    }

    private static Connection novaConexao() throws SQLException {
        return DriverManager.getConnection(Gate3ReconciliacaoTestProperties.dbUrl(),
                Gate3ReconciliacaoTestProperties.dbUsername(), Gate3ReconciliacaoTestProperties.dbPassword());
    }

    private static void limparCnpj() throws SQLException {
        try (Connection conn = novaConexao()) {
            conn.createStatement().execute(
                    "DELETE i FROM pedido_item i JOIN pedido p ON p.id = i.pedido_id WHERE p.cnpj_emitente = '" + CNPJ + "'");
            conn.createStatement().execute("DELETE FROM pedido WHERE cnpj_emitente = '" + CNPJ + "'");
            conn.createStatement().execute("DELETE FROM empresa WHERE cnpj = '" + CNPJ + "'");
            conn.createStatement().execute("DELETE FROM produto WHERE codigo = 'COD-IT-CORRECAO'");
        }
    }

    /** FK NOT NULL de pedido_item.produto_id — garante um produto real antes de cada cenário. */
    private long obterOuCriarProduto() throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM produto WHERE codigo = 'COD-IT-CORRECAO'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO produto (codigo, descricao, ncm, cfop, unidade, preco, estado)
                     VALUES ('COD-IT-CORRECAO', 'Produto Teste IT Correção', '12345678', '5102', 'UN', 100.00, 1)
                     """, Statement.RETURN_GENERATED_KEYS)) {
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    /** FK NOT NULL de pedido.empresa_id — garante uma empresa real antes de cada cenário. */
    private long obterOuCriarEmpresa() throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement("SELECT id FROM empresa WHERE cnpj = ?")) {
            ps.setString(1, CNPJ);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getLong(1);
            }
        }
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO empresa (cnpj, razao_social, uf, crt, serie_nfe_padrao, ind_final_padrao, ativo,
                         controle_estoque_ativo, nome_fantasia)
                     VALUES (?, 'Empresa Teste IT Correção', 'SP', '1', '1', '1', 1, 1, 'Fantasia IT Correção')
                     """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, CNPJ);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    /** Insere um pedido REJEITADO com um item — ponto de partida de todos os cenários. */
    private long inserirPedidoRejeitadoComItem() throws SQLException {
        long empresaId = obterOuCriarEmpresa();
        long produtoId = obterOuCriarProduto();
        String numero = "IT" + (System.nanoTime() % 100_000_000L); // numero é VARCHAR(20) — só precisa ser único
        long pedidoId;
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO pedido (
                         empresa_id, numero, cnpj_emitente, dest_cnpj_cpf, dest_razao_social, dest_bairro,
                         natureza_operacao, serie_nfe, status, valor_total, data_pedido, data_atualizacao
                     ) VALUES (
                         ?, ?, ?, '12345678000195', 'Cliente Original', 'Bairro Original',
                         'VENDA DE MERCADORIA', '1', 'REJEITADO', 100.00, NOW(), NOW()
                     )
                     """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, empresaId);
            ps.setString(2, numero);
            ps.setString(3, CNPJ);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                assertTrue(rs.next());
                pedidoId = rs.getLong(1);
            }
        }
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement("""
                     INSERT INTO pedido_item (
                         pedido_id, produto_id, quantidade, valor_unitario, valor_total,
                         codigo_produto, descricao, ncm, cfop, unidade, origem, csosn
                     ) VALUES (?, ?, 1, 100.00, 100.00, 'COD1', 'Descrição original', '12345678', '5102', 'UN', 0, '102')
                     """, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, pedidoId);
            ps.setLong(2, produtoId);
            ps.executeUpdate();
        }
        return pedidoId;
    }

    private String lerStatus(long pedidoId) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement("SELECT status FROM pedido WHERE id = ?")) {
            ps.setLong(1, pedidoId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private String lerBairro(long pedidoId) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement("SELECT dest_bairro FROM pedido WHERE id = ?")) {
            ps.setLong(1, pedidoId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getString(1);
            }
        }
    }

    private Pedido cabecalhoParaCorrecao(long pedidoId, String bairro) throws SQLException {
        Pedido p = new Pedido();
        p.setId(pedidoId);
        p.setDestRazaoSocial("Cliente Original");
        p.setDestBairro(bairro);
        p.setNaturezaOperacao("VENDA DE MERCADORIA");
        return p;
    }

    // =========================================================================================
    // 1) Correção bem-sucedida: pedido REJEITADO -> header e item persistidos
    // =========================================================================================

    @Test
    @DisplayName("REJEITADO: corrigirCamposFiscais + atualizarDescricao persistem, status não muda")
    void corrigir_pedidoRejeitado_persisteHeaderEItem() throws Exception {
        long pedidoId = inserirPedidoRejeitadoComItem();

        try (SqlSession session = sqlSessionFactory.openSession(true)) { // autocommit — cenário sem concorrência
            PedidoMapper pedidoMapper = session.getMapper(PedidoMapper.class);
            PedidoItemMapper itemMapper = session.getMapper(PedidoItemMapper.class);

            int linhasHeader = pedidoMapper.corrigirCamposFiscais(cabecalhoParaCorrecao(pedidoId, "Bairro Corrigido"));
            assertEquals(1, linhasHeader);

            long itemId;
            try (Connection conn = novaConexao();
                 PreparedStatement ps = conn.prepareStatement("SELECT id FROM pedido_item WHERE pedido_id = ?")) {
                ps.setLong(1, pedidoId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    itemId = rs.getLong(1);
                }
            }
            int linhasItem = itemMapper.atualizarDescricao(itemId, pedidoId, "Descrição corrigida");
            assertEquals(1, linhasItem);
        }

        assertEquals("Bairro Corrigido", lerBairro(pedidoId));
        assertEquals("REJEITADO", lerStatus(pedidoId), "correção nunca muda o status por si só");
    }

    // =========================================================================================
    // 2) Guard atômico: pedido já AUTORIZADO -> rowsAffected=0, nenhuma coluna tocada
    // =========================================================================================

    @Test
    @DisplayName("AUTORIZADO: corrigirCamposFiscais não afeta nenhuma linha (fora do conjunto RASCUNHO/REJEITADO/ERRO)")
    void corrigir_pedidoAutorizado_zeroLinhasAfetadas() throws Exception {
        long pedidoId = inserirPedidoRejeitadoComItem();
        try (Connection conn = novaConexao()) {
            conn.createStatement().execute("UPDATE pedido SET status = 'AUTORIZADO' WHERE id = " + pedidoId);
        }

        try (SqlSession session = sqlSessionFactory.openSession(true)) {
            PedidoMapper pedidoMapper = session.getMapper(PedidoMapper.class);
            int linhas = pedidoMapper.corrigirCamposFiscais(cabecalhoParaCorrecao(pedidoId, "Bairro Não Deveria Entrar"));
            assertEquals(0, linhas);
        }

        assertEquals("Bairro Original", lerBairro(pedidoId), "nenhuma coluna pode ser tocada quando rowsAffected=0");
    }

    // =========================================================================================
    // 3) Concorrência real: /emitir reivindica o pedido ENQUANTO a correção está presa no lock —
    //    a correção só prossegue depois que a reivindicação commita, e aí encontra o pedido já
    //    fora do conjunto emissível -> rowsAffected=0. Nunca as duas escrevem "ao mesmo tempo".
    // =========================================================================================

    @Test
    @DisplayName("Concorrência: reivindicarParaEmissao (commit primeiro) bloqueia e depois derruba a correção concorrente")
    void concorrencia_reivindicarEmissaoPrimeiro_correcaoConcorrenteFalhaAposDesbloquear() throws Exception {
        long pedidoId = inserirPedidoRejeitadoComItem();

        CountDownLatch lockAdquirido = new CountDownLatch(1);
        CountDownLatch podeCommitar = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // Thread A: reivindica pra emissão (segura o lock de linha até "podeCommitar" abrir).
            Future<Integer> futA = pool.submit(() -> {
                try (SqlSession session = sqlSessionFactory.openSession(TransactionIsolationLevel.REPEATABLE_READ)) {
                    PedidoMapper mapper = session.getMapper(PedidoMapper.class);
                    int linhas = mapper.reivindicarParaEmissao(pedidoId);
                    lockAdquirido.countDown();
                    assertTrue(podeCommitar.await(10, TimeUnit.SECONDS));
                    session.commit();
                    return linhas;
                }
            });

            assertTrue(lockAdquirido.await(5, TimeUnit.SECONDS), "A precisa ter executado o UPDATE (lock adquirido) antes de B tentar");

            // Thread B: tenta corrigir ENQUANTO A ainda não commitou — precisa bloquear na mesma linha.
            Future<Integer> futB = pool.submit(() -> {
                try (SqlSession session = sqlSessionFactory.openSession(true)) {
                    PedidoMapper mapper = session.getMapper(PedidoMapper.class);
                    return mapper.corrigirCamposFiscais(cabecalhoParaCorrecao(pedidoId, "Bairro Da Correcao Concorrente"));
                }
            });

            // B não pode terminar enquanto A segura o lock (prova de que elas NUNCA correm ao mesmo tempo).
            assertThrows(java.util.concurrent.TimeoutException.class, () -> futB.get(500, TimeUnit.MILLISECONDS));

            podeCommitar.countDown();

            assertEquals(1, futA.get(10, TimeUnit.SECONDS), "A venceu o claim de emissão");
            int linhasB = futB.get(10, TimeUnit.SECONDS);
            assertEquals(0, linhasB, "B só destrava DEPOIS que A commitou — e encontra status=EMITINDO, fora do conjunto emissível");
        } finally {
            pool.shutdown();
        }

        assertEquals("EMITINDO", lerStatus(pedidoId));
        assertEquals("Bairro Original", lerBairro(pedidoId), "correção derrubada não pode ter tocado nenhuma coluna");
    }
}
