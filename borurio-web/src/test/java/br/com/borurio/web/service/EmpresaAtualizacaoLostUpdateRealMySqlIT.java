package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.service.EmpresaService;
import br.com.borurio.app.service.impl.EmpresaServiceImpl;
import br.com.borurio.web.dto.EmpresaAtualizacaoRequest;
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
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Banca arquitetural PUT /empresas (14-08-2026) — prova, contra MySQL real, que a correção por
 * lock pessimista ({@code SELECT ... FOR UPDATE} + merge + UPDATE numa única transação) elimina o
 * lost update comprovado na banca anterior. Este arquivo SUBSTITUI o IT que provava o defeito
 * (mesma classe, mesmo nome) — depois da correção, {@code EmpresaAtualizacaoService} não é mais
 * capaz de perder uma alteração concorrente, então não há mais nada de "defeito" pra provar aqui.
 *
 * Infra Spring-real: {@link DataSourceTransactionManager} + {@link SpringManagedTransactionFactory}
 * + {@link SqlSessionTemplate} — a MESMA integração que a auto-configuração do
 * mybatis-spring-boot-starter usa em produção (confirmado: não há nenhum bean {@code SqlSessionFactory}
 * customizado em {@code borurio-web}, então o starter monta o padrão, que já usa
 * {@code SpringManagedTransactionFactory}). Diferente do IT anterior (que usava
 * {@code JdbcTransactionFactory} + {@code SqlSession} manual — suficiente pra PROVAR o defeito, mas
 * não pra provar a correção transacional): aqui {@link EmpresaAtualizacaoService} é construído com
 * um {@link org.springframework.transaction.PlatformTransactionManager} real e mappers vindos do
 * MESMO {@link SqlSessionTemplate},
 * exatamente como o Spring monta o bean em produção — o service abre sua própria fronteira
 * transacional via {@code TransactionTemplate} internamente, sem precisar de proxy AOP.
 *
 * *IT (não *Test): roda só quando executada explicitamente, contra um MySQL efêmero/descartável —
 * reutiliza {@link Gate3ReconciliacaoTestProperties}. Nunca toca borurio-mysql-dev/hom.
 */
class EmpresaAtualizacaoLostUpdateRealMySqlIT {

    private static SqlSessionFactory sqlSessionFactory;
    private static DataSourceTransactionManager transactionManager;
    private static final String CNPJ = "55443322000199";
    private static final String CNPJ_OUTRA_EMPRESA = "44332211000188";

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

        Environment env = new Environment("empresa-lost-update-it", new SpringManagedTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.addMapper(EmpresaMapper.class);
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
            conn.createStatement().execute("DELETE FROM empresa WHERE cnpj IN ('" + CNPJ + "', '" + CNPJ_OUTRA_EMPRESA + "')");
        }
    }

    private long inserirEmpresa(String cnpj) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO empresa (cnpj, razao_social, uf, crt, serie_nfe_padrao, ind_final_padrao, ativo, "
                             + "controle_estoque_ativo, nome_fantasia) "
                             + "VALUES (?, 'Empresa Teste Lost Update', 'SP', '1', '1', '1', 1, 1, 'Fantasia Original')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, cnpj);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private long inserirEmpresa() throws SQLException {
        return inserirEmpresa(CNPJ);
    }

    private record LinhaEmpresa(String cnpj, String nomeFantasia, boolean controleEstoqueAtivo) {}

    private LinhaEmpresa lerLinha(long id) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT cnpj, nome_fantasia, controle_estoque_ativo FROM empresa WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return new LinhaEmpresa(rs.getString(1), rs.getString(2), rs.getBoolean(3));
            }
        }
    }

    private EmpresaMapper mapperReal() {
        return new SqlSessionTemplate(sqlSessionFactory).getMapper(EmpresaMapper.class);
    }

    private EmpresaAtualizacaoService construirServiceReal() {
        EmpresaMapper mapper = mapperReal();
        EmpresaService empresaService = new EmpresaServiceImpl(mapper);
        CertSenhaEncryptor encryptor = new CertSenhaEncryptor();
        encryptor.init(); // sem CERT_ENCRYPTION_KEY -- passthrough, irrelevante (nenhum cenário aqui toca certSenha)
        return new EmpresaAtualizacaoService(empresaService, mapper, encryptor, transactionManager);
    }

    /**
     * Decora {@link EmpresaService} pra segurar o lock de linha por {@code atrasoMs} DEPOIS de
     * adquirido (dentro da mesma transação de {@link EmpresaAtualizacaoService#aplicar}) — usado
     * só pra tornar determinística, sob teste, a ordem de conclusão de duas transações concorrentes
     * que disputam a MESMA linha. Sinaliza {@code lockAdquirido} assim que entra em
     * {@code atualizar()} (merge já concluído, lock já detido desde a leitura FOR UPDATE).
     */
    private static class EmpresaServiceComAtrasoNaEscrita implements EmpresaService {
        private final EmpresaService delegate;
        private final long atrasoMs;
        private final CountDownLatch lockAdquirido;

        EmpresaServiceComAtrasoNaEscrita(EmpresaService delegate, long atrasoMs, CountDownLatch lockAdquirido) {
            this.delegate = delegate;
            this.atrasoMs = atrasoMs;
            this.lockAdquirido = lockAdquirido;
        }

        @Override public List<Empresa> listarTodas() { return delegate.listarTodas(); }
        @Override public Empresa buscarPorId(Long id) { return delegate.buscarPorId(id); }
        @Override public Empresa buscarPorCnpj(String cnpj) { return delegate.buscarPorCnpj(cnpj); }
        @Override public Empresa salvar(Empresa empresa) { return delegate.salvar(empresa); }

        @Override
        public Empresa atualizar(Long id, Empresa empresa) {
            lockAdquirido.countDown();
            try {
                Thread.sleep(atrasoMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return delegate.atualizar(id, empresa);
        }
    }

    // =========================================================================================
    // 1) Campos diferentes -- lost update corrigido: AMBAS as alterações sobrevivem
    // =========================================================================================

    @Test
    @DisplayName("CORRIGIDO: duas requisições concorrentes em CAMPOS DIFERENTES da mesma empresa -- nomeFantasia=A E "
            + "controleEstoqueAtivo=B sobrevivem, nenhuma alteração é perdida")
    void duasAtualizacoesConcorrentes_camposDiferentes_ambasSobrevivem() throws Exception {
        limpar();
        long id = inserirEmpresa();

        EmpresaAtualizacaoService service = construirServiceReal();

        EmpresaAtualizacaoRequest reqA = new EmpresaAtualizacaoRequest();
        reqA.setNomeFantasia("Fantasia Alterada Por A");
        EmpresaAtualizacaoRequest reqB = new EmpresaAtualizacaoRequest();
        reqB.setControleEstoqueAtivo(false);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            Future<Empresa> futA = pool.submit(() -> { largada.await(); return service.aplicar(id, reqA); });
            Future<Empresa> futB = pool.submit(() -> { largada.await(); return service.aplicar(id, reqB); });
            largada.countDown();
            futA.get(20, TimeUnit.SECONDS);
            futB.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        LinhaEmpresa linha = lerLinha(id);
        assertEquals("Fantasia Alterada Por A", linha.nomeFantasia(), "alteração de A não pode ser perdida");
        assertFalse(linha.controleEstoqueAtivo(), "alteração de B não pode ser perdida");
    }

    // =========================================================================================
    // 2) Mesmo campo -- serializado e determinístico pela ordem de aquisição do lock
    // =========================================================================================

    @Test
    @DisplayName("mesmo campo concorrente: resultado final é EXATAMENTE o valor de quem adquiriu o lock por último -- "
            + "nunca um valor corrompido/parcialmente mesclado")
    void duasAtualizacoesConcorrentes_mesmoCampo_resultadoDeterministicoPorOrdemDeLock() throws Exception {
        limpar();
        long id = inserirEmpresa();

        EmpresaMapper mapper = mapperReal();
        CertSenhaEncryptor encryptor = new CertSenhaEncryptor();
        encryptor.init();

        CountDownLatch lockAdquiridoPorA = new CountDownLatch(1);
        EmpresaService servicoLento = new EmpresaServiceComAtrasoNaEscrita(new EmpresaServiceImpl(mapper), 800, lockAdquiridoPorA);
        EmpresaAtualizacaoService serviceA = new EmpresaAtualizacaoService(servicoLento, mapper, encryptor, transactionManager);
        EmpresaAtualizacaoService serviceB = new EmpresaAtualizacaoService(new EmpresaServiceImpl(mapper), mapper, encryptor, transactionManager);

        EmpresaAtualizacaoRequest reqA = new EmpresaAtualizacaoRequest();
        reqA.setNomeFantasia("Valor Definido Por A");
        EmpresaAtualizacaoRequest reqB = new EmpresaAtualizacaoRequest();
        reqB.setNomeFantasia("Valor Definido Por B");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Empresa> futA = pool.submit(() -> serviceA.aplicar(id, reqA));
            assertTrue(lockAdquiridoPorA.await(5, TimeUnit.SECONDS), "A precisa ter adquirido o lock (e começado a segurá-lo) antes de B tentar");
            Future<Empresa> futB = pool.submit(() -> serviceB.aplicar(id, reqB));

            futA.get(20, TimeUnit.SECONDS);
            futB.get(20, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        // B só consegue o próprio lock DEPOIS que A libera (commit) -- lê o estado já atualizado
        // por A e sobrescreve com o próprio valor. Determinístico: sempre o valor de quem adquiriu
        // o lock por último, nunca uma mistura dos dois.
        assertEquals("Valor Definido Por B", lerLinha(id).nomeFantasia(),
                "quem adquire o lock por último (B, só depois que A já commitou) sempre vence de forma determinística");
    }

    // =========================================================================================
    // 3) Empresas diferentes -- mutuamente independentes
    // =========================================================================================

    @Test
    @DisplayName("duas empresas diferentes atualizadas concorrentemente -- mutuamente independentes, sem corrupção cruzada")
    void duasEmpresasDiferentes_atualizadasConcorrentemente_semCorrupcaoCruzada() throws Exception {
        limpar();
        long id1 = inserirEmpresa(CNPJ);
        long id2 = inserirEmpresa(CNPJ_OUTRA_EMPRESA);

        EmpresaAtualizacaoService service = construirServiceReal();

        EmpresaAtualizacaoRequest req1 = new EmpresaAtualizacaoRequest();
        req1.setNomeFantasia("Fantasia Empresa 1");
        EmpresaAtualizacaoRequest req2 = new EmpresaAtualizacaoRequest();
        req2.setNomeFantasia("Fantasia Empresa 2");

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        try {
            Future<Empresa> fut1 = pool.submit(() -> { largada.await(); return service.aplicar(id1, req1); });
            Future<Empresa> fut2 = pool.submit(() -> { largada.await(); return service.aplicar(id2, req2); });
            largada.countDown();
            // Timeout curto: se o lock de uma empresa indevidamente bloqueasse a outra, isso estouraria aqui.
            fut1.get(10, TimeUnit.SECONDS);
            fut2.get(10, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        assertEquals("Fantasia Empresa 1", lerLinha(id1).nomeFantasia());
        assertEquals("Fantasia Empresa 2", lerLinha(id2).nomeFantasia());
    }

    // =========================================================================================
    // 4) Exceção depois do lock, antes do UPDATE -- rollback real, sem fault injection
    // =========================================================================================

    @Test
    @DisplayName("exceção depois do SELECT FOR UPDATE e antes do UPDATE (CNPJ imutável, caminho real de validação) -> "
            + "rollback deixa a linha byte a byte igual E libera o lock (próxima atualização válida completa sem travar)")
    void excecaoAposLockAntesDoUpdate_rollbackLiberaLockSemAlteracao() throws Exception {
        limpar();
        long id = inserirEmpresa();
        LinhaEmpresa antes = lerLinha(id);

        EmpresaAtualizacaoService service = construirServiceReal();

        EmpresaAtualizacaoRequest reqInvalido = new EmpresaAtualizacaoRequest();
        reqInvalido.setCnpj("00000000000000"); // diferente do persistido -> BusinessException DEPOIS do lock, ANTES do UPDATE

        BusinessException ex = assertThrows(BusinessException.class, () -> service.aplicar(id, reqInvalido));
        assertEquals("EMPRESA_CNPJ_IMUTAVEL", ex.getErrorCode());

        assertEquals(antes, lerLinha(id), "rollback precisa deixar a linha byte a byte igual -- nenhum efeito da tentativa que falhou");

        EmpresaAtualizacaoRequest reqValido = new EmpresaAtualizacaoRequest();
        reqValido.setNomeFantasia("Depois Do Rollback");
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> service.aplicar(id, reqValido),
                "se o rollback não tivesse liberado o lock, esta segunda chamada travaria/expiraria");

        assertEquals("Depois Do Rollback", lerLinha(id).nomeFantasia());
    }
}
