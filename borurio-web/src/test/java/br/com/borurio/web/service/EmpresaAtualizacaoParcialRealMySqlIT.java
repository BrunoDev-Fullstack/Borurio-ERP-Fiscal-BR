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

import static org.junit.jupiter.api.Assertions.*;

/**
 * Prova, contra MySQL real, que {@link EmpresaAtualizacaoService} persiste a atualização PARCIAL
 * de {@code PUT /api/app/empresas/{id}} corretamente: campos omitidos preservados no banco de
 * verdade, campos obrigatórios com null explícito rejeitados SEM nenhuma UPDATE (linha inteira
 * inalterada), CNPJ protegido, isolamento entre empresas real.
 *
 * Renomeado em 14-08-2026 (era {@code EmpresaAtualizarOmiteControleEstoqueRealMySqlIT}): o nome
 * antigo descrevia só o primeiro achado (omissão de {@code controleEstoqueAtivo}); o escopo real
 * do arquivo sempre foi mais amplo — todo o contrato de atualização parcial, ponta a ponta, contra
 * banco real, usando {@link EmpresaAtualizacaoService} de produção (não mock).
 *
 * Infra de MyBatis trocada em 14-08-2026 (correção do lost update, banca de concorrência):
 * {@link SpringManagedTransactionFactory} + {@link SqlSessionTemplate} + {@link DataSourceTransactionManager}
 * reais, no lugar do {@code JdbcTransactionFactory}+{@code SqlSession} manual anterior — necessário
 * porque {@link EmpresaAtualizacaoService#aplicar} agora abre sua própria fronteira transacional via
 * {@code TransactionTemplate} (leitura {@code FOR UPDATE} + merge + UPDATE), e só participa
 * corretamente da mesma transação/conexão quando os mappers vêm de um {@code SqlSessionTemplate}
 * ligado ao MESMO {@link org.springframework.transaction.PlatformTransactionManager} injetado no
 * service — a mesma integração Spring+MyBatis de produção (ver
 * {@code EmpresaAtualizacaoLostUpdateRealMySqlIT} para a prova de concorrência propriamente dita).
 *
 * *IT (não *Test): roda só quando executada explicitamente, contra um MySQL efêmero/descartável
 * (nunca borurio-mysql-dev/hom — ver {@link Gate3ReconciliacaoTestProperties}). Valores sintéticos
 * em todos os campos, inclusive certificado.
 */
class EmpresaAtualizacaoParcialRealMySqlIT {

    private static SqlSessionFactory sqlSessionFactory;
    private static DataSourceTransactionManager transactionManager;
    private static final String CNPJ = "66554433000122";
    private static final String CNPJ_OUTRA_EMPRESA = "77889900000111";

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

        Environment env = new Environment("empresa-atualizacao-parcial-it", new SpringManagedTransactionFactory(), dataSource);
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

    private long inserirEmpresa(String cnpj, boolean controleEstoqueAtivo) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO empresa (cnpj, razao_social, uf, crt, serie_nfe_padrao, ind_final_padrao, ativo, "
                             + "controle_estoque_ativo, nome_fantasia, ie, logradouro, cert_path, cert_senha, cert_tipo) "
                             + "VALUES (?, 'Empresa Teste PUT Parcial', 'SP', '1', '1', '1', 1, ?, "
                             + "'Fantasia Original', 'IE-ORIGINAL', 'Rua Original, 100', '/antigo.pfx', 'senha-antiga-sintetica', 'PKCS12')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, cnpj);
            ps.setBoolean(2, controleEstoqueAtivo);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                assertTrue(rs.next());
                return rs.getLong(1);
            }
        }
    }

    private record LinhaEmpresa(String cnpj, String razaoSocial, String crt, String uf, String serieNfePadrao,
                                 String indFinalPadrao, boolean ativo, boolean controleEstoqueAtivo,
                                 String nomeFantasia, String ie, String logradouro,
                                 String certPath, String certSenha, String certTipo) {
    }

    private LinhaEmpresa lerLinha(long id) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT cnpj, razao_social, crt, uf, serie_nfe_padrao, ind_final_padrao, ativo, "
                             + "controle_estoque_ativo, nome_fantasia, ie, logradouro, cert_path, cert_senha, cert_tipo "
                             + "FROM empresa WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return new LinhaEmpresa(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getBoolean(7), rs.getBoolean(8),
                        rs.getString(9), rs.getString(10), rs.getString(11), rs.getString(12), rs.getString(13), rs.getString(14));
            }
        }
    }

    /** Mappers via SqlSessionTemplate -- participam do mesmo PlatformTransactionManager do service (produção real). */
    private EmpresaAtualizacaoService construirServiceReal() {
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(sqlSessionFactory);
        EmpresaMapper mapper = sessionTemplate.getMapper(EmpresaMapper.class);
        EmpresaService empresaService = new EmpresaServiceImpl(mapper);
        CertSenhaEncryptor encryptor = new CertSenhaEncryptor();
        encryptor.init(); // sem CERT_ENCRYPTION_KEY -- modo passthrough, adequado para valores sintéticos de teste
        return new EmpresaAtualizacaoService(empresaService, mapper, encryptor, transactionManager);
    }

    // =========================================================================================
    // Payload só de certificado -- o caso documentado
    // =========================================================================================

    @Test
    @DisplayName("payload só de certificado (documentado): persiste certPath/certSenha/certTipo e preserva "
            + "TODOS os demais campos no banco real, incluindo os 8 obrigatórios")
    void aplicar_soCertificado_persisteSoCertificadoEPreservaResto() throws Exception {
        limpar();
        long id = inserirEmpresa(CNPJ, false); // controleEstoqueAtivo=false no estado inicial

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setCertPath("/novo/caminho-sintetico.pfx");
        req.setCertSenha("nova-senha-sintetica");
        req.setCertTipo("PKCS12");

        construirServiceReal().aplicar(id, req);

        LinhaEmpresa linha = lerLinha(id);
        assertEquals("/novo/caminho-sintetico.pfx", linha.certPath());
        assertEquals("nova-senha-sintetica", linha.certSenha()); // passthrough (sem CERT_ENCRYPTION_KEY) -- valor sintético, não é senha real
        assertEquals("PKCS12", linha.certTipo());

        // Todos os 8 obrigatórios e os demais nullable inalterados.
        assertEquals(CNPJ, linha.cnpj());
        assertEquals("Empresa Teste PUT Parcial", linha.razaoSocial());
        assertEquals("1", linha.crt());
        assertEquals("SP", linha.uf());
        assertEquals("1", linha.serieNfePadrao());
        assertEquals("1", linha.indFinalPadrao());
        assertTrue(linha.ativo());
        assertFalse(linha.controleEstoqueAtivo(), "controleEstoqueAtivo=false precisa permanecer false após atualizar só certificado");
        assertEquals("Fantasia Original", linha.nomeFantasia());
        assertEquals("IE-ORIGINAL", linha.ie());
        assertEquals("Rua Original, 100", linha.logradouro());
    }

    @Test
    @DisplayName("controleEstoqueAtivo=true permanece true após atualizar somente certificado (cenário espelhado)")
    void aplicar_soCertificado_controleEstoqueAtivoTrue_permaneceTrue() throws Exception {
        limpar();
        long id = inserirEmpresa(CNPJ, true);

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setCertPath("/x.pfx");

        construirServiceReal().aplicar(id, req);

        assertTrue(lerLinha(id).controleEstoqueAtivo());
    }

    @Test
    @DisplayName("alteração explícita de controleEstoqueAtivo é persistida de verdade")
    void aplicar_controleEstoqueAtivoExplicito_persisteAlteracao() throws Exception {
        limpar();
        long id = inserirEmpresa(CNPJ, true);

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setControleEstoqueAtivo(false);

        construirServiceReal().aplicar(id, req);

        assertFalse(lerLinha(id).controleEstoqueAtivo());
    }

    // =========================================================================================
    // Falha de validação -- NENHUMA UPDATE, linha inteira inalterada
    // =========================================================================================

    @Test
    @DisplayName("null explícito em campo obrigatório (razaoSocial) -> BusinessException 422, "
            + "linha permanece BYTE A BYTE igual (nenhuma UPDATE parcial nem completa)")
    void aplicar_razaoSocialNullExplicito_naoGravaNada() throws Exception {
        limpar();
        long id = inserirEmpresa(CNPJ, true);
        LinhaEmpresa antes = lerLinha(id);

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setRazaoSocial(null);
        req.setCertPath("/nao-deveria-persistir.pfx"); // mesmo tendo outro campo válido no request, nada pode gravar

        EmpresaAtualizacaoService service = construirServiceReal();
        BusinessException ex = assertThrows(BusinessException.class, () -> service.aplicar(id, req));
        assertEquals("EMPRESA_CAMPO_OBRIGATORIO_NULO", ex.getErrorCode());

        assertEquals(antes, lerLinha(id), "linha precisa estar EXATAMENTE igual a antes da tentativa -- a exceção é lançada antes de qualquer UPDATE ser executada");
    }

    @Test
    @DisplayName("tentativa de trocar CNPJ -> BusinessException 422, linha permanece inalterada")
    void aplicar_cnpjDiferente_naoGravaNada() throws Exception {
        limpar();
        long id = inserirEmpresa(CNPJ, true);
        LinhaEmpresa antes = lerLinha(id);

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setCnpj("99988877000166");

        EmpresaAtualizacaoService service = construirServiceReal();
        BusinessException ex = assertThrows(BusinessException.class, () -> service.aplicar(id, req));
        assertEquals("EMPRESA_CNPJ_IMUTAVEL", ex.getErrorCode());

        assertEquals(antes, lerLinha(id));
    }

    @Test
    @DisplayName("mesmo CNPJ informado (idempotente) -> aceito, sem efeito colateral nos demais campos")
    void aplicar_mesmoCnpj_semEfeitoColateral() throws Exception {
        limpar();
        long id = inserirEmpresa(CNPJ, true);

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setCnpj(CNPJ);

        construirServiceReal().aplicar(id, req);

        LinhaEmpresa linha = lerLinha(id);
        assertEquals(CNPJ, linha.cnpj());
        assertEquals("Fantasia Original", linha.nomeFantasia(), "reenviar o mesmo CNPJ não pode alterar nenhum outro campo");
    }

    // =========================================================================================
    // Campos nullable -- omitido preserva, null explícito limpa
    // =========================================================================================

    @Test
    @DisplayName("null explícito em campo nullable (nomeFantasia) -> limpa de verdade no banco")
    void aplicar_nomeFantasiaNullExplicito_limpaNoBanco() throws Exception {
        limpar();
        long id = inserirEmpresa(CNPJ, true);

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setNomeFantasia(null);

        construirServiceReal().aplicar(id, req);

        assertNull(lerLinha(id).nomeFantasia());
    }

    @Test
    @DisplayName("campo nullable omitido preserva o valor persistido no banco")
    void aplicar_ieOmitido_preservaNoBanco() throws Exception {
        limpar();
        long id = inserirEmpresa(CNPJ, true);

        EmpresaAtualizacaoRequest req = new EmpresaAtualizacaoRequest();
        req.setCertPath("/x.pfx"); // único campo presente

        construirServiceReal().aplicar(id, req);

        assertEquals("IE-ORIGINAL", lerLinha(id).ie());
    }

    // =========================================================================================
    // Isolamento entre empresas -- prova real, duas linhas
    // =========================================================================================

    @Test
    @DisplayName("isolamento: atualizar a empresa 1 nunca altera a linha da empresa 2 no banco real")
    void aplicar_duasEmpresas_isolamentoRealNoBanco() throws Exception {
        limpar();
        long id1 = inserirEmpresa(CNPJ, false);
        long id2 = inserirEmpresa(CNPJ_OUTRA_EMPRESA, true);
        LinhaEmpresa empresa2Antes = lerLinha(id2);

        EmpresaAtualizacaoRequest req1 = new EmpresaAtualizacaoRequest();
        req1.setControleEstoqueAtivo(true);
        req1.setNomeFantasia("Fantasia Nova Empresa 1");

        construirServiceReal().aplicar(id1, req1);

        LinhaEmpresa empresa1Depois = lerLinha(id1);
        LinhaEmpresa empresa2Depois = lerLinha(id2);

        assertTrue(empresa1Depois.controleEstoqueAtivo());
        assertEquals("Fantasia Nova Empresa 1", empresa1Depois.nomeFantasia());
        assertEquals(empresa2Antes, empresa2Depois, "empresa 2 precisa estar byte a byte igual -- nunca tocada pela atualização da empresa 1");
    }
}
