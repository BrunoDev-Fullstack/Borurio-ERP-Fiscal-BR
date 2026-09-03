package br.com.borurio.web.service;

import br.com.borurio.fiscal.config.SefazReconciliacaoProperties;
import br.com.borurio.fiscal.dto.NfeCceEventoPreparado;
import br.com.borurio.fiscal.dto.NfeEventoRetorno;
import br.com.borurio.fiscal.entity.NfeEvento;
import br.com.borurio.fiscal.mapper.NfeEventoIdempotenciaMapper;
import br.com.borurio.fiscal.mapper.NfeEventoMapper;
import br.com.borurio.fiscal.mapper.NfeEventoSequenciaMapper;
import br.com.borurio.fiscal.service.CertificadoContexto;
import br.com.borurio.fiscal.service.NfeCceClassificador;
import br.com.borurio.fiscal.service.NfeCceService;
import br.com.borurio.fiscal.service.NfeConsultaSituacaoService;
import br.com.borurio.fiscal.service.NfeEventoRetornoParser;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Gate CC-e (evento 110110, 12-08-2026) — prova contra MySQL real dos dois pontos de concorrência
 * do desenho aprovado que os testes unitários (mapeadores mockados) não conseguem provar sozinhos:
 *   1) UNIQUE KEY (idempotency_key) em nfe_evento_idempotencia — N chamadas concorrentes com a
 *      MESMA Idempotency-Key nunca criam duas linhas de operação nem transmitem duas vezes.
 *   2) FOR UPDATE em nfe_evento_sequencia — N chamadas concorrentes com Idempotency-Keys
 *      DIFERENTES para a MESMA chave de NF-e nunca reservam a mesma sequência duas vezes; só uma
 *      vence, as demais recebem CCE_EM_ANDAMENTO.
 *
 * Diferente de {@link NfeEventoConcorrenciaRealMySqlIT} (cancelamento): o orquestrador de CC-e
 * gerencia suas PRÓPRIAS fronteiras transacionais internamente via {@code TransactionTemplate}
 * (múltiplas transações por chamada a corrigir()), não uma única transação externa controlada pelo
 * chamador — por isso este IT usa um {@link DataSourceTransactionManager} real +
 * {@link SpringManagedTransactionFactory} (mesma integração Spring+MyBatis de produção), em vez de
 * uma {@code SqlSession} bruta com commit/rollback manual.
 *
 * *IT (não *Test): roda só quando executada explicitamente, contra um MySQL efêmero/descartável
 * -- reutiliza {@link Gate3ReconciliacaoTestProperties} (mesmas variáveis GATE3_DB_*, credenciais de
 * infraestrutura de teste, não específicas de nenhum gate). Nunca toca borurio-mysql-dev/hom.
 */
class NfeCceGateConcorrenciaRealMySqlIT {

    private static final String CNPJ = "99887755000144";
    private static final String UF = "SP";
    private static final Long EMPRESA_ID = 1L;
    private static SqlSessionFactory sqlSessionFactory;
    private static DataSourceTransactionManager transactionManager;

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

        // SpringManagedTransactionFactory (não JdbcTransactionFactory): as sessões MyBatis
        // precisam participar da MESMA transação Spring que o TransactionTemplate interno do
        // orquestrador abre/fecha -- é essa integração que replica o comportamento de produção.
        Environment env = new Environment("nfe-cce-concorrencia-it", new SpringManagedTransactionFactory(), dataSource);
        Configuration config = new Configuration(env);
        config.addMapper(NfeEventoMapper.class);
        config.addMapper(NfeEventoSequenciaMapper.class);
        config.addMapper(NfeEventoIdempotenciaMapper.class);
        sqlSessionFactory = new SqlSessionFactoryBuilder().build(config);

        limpar();
    }

    @AfterAll
    static void limparFinal() throws Exception {
        limpar();
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
            conn.createStatement().execute("DELETE FROM nfe_evento_idempotencia WHERE empresa_id = 1");
            conn.createStatement().execute("DELETE FROM nfe_evento WHERE cnpj_emitente = '" + CNPJ + "'");
            conn.createStatement().execute("DELETE FROM nfe_evento_sequencia WHERE chave_nfe LIKE '352608990000%'");
        }
    }

    /** Bootstrap local já resolvido (fast path de garantirGateExiste) -- sem chamada de rede nesta prova. */
    private void inserirGateBootstrapped(String chave, int ultimoNSeq) throws SQLException {
        try (Connection conn = novaConexao();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO nfe_evento_sequencia (chave_nfe, tipo_evento, ultimo_nseq_registrado) "
                             + "VALUES (?, '110110', ?)")) {
            ps.setString(1, chave);
            ps.setInt(2, ultimoNSeq);
            ps.executeUpdate();
        }
    }

    private int contar(String sql, Object... params) throws SQLException {
        try (Connection conn = novaConexao(); PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) ps.setObject(i + 1, params[i]);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                return rs.getInt(1);
            }
        }
    }

    private NfeCceOrquestradorService construirServiceReal(NfeCceService cceService,
                                                             NfeEventoRetornoParser eventoRetornoParser,
                                                             NfeConsultaSituacaoService consultaSituacaoService) {
        SqlSessionTemplate sessionTemplate = new SqlSessionTemplate(sqlSessionFactory);
        NfeEventoMapper eventoMapper = sessionTemplate.getMapper(NfeEventoMapper.class);
        NfeEventoSequenciaMapper sequenciaMapper = sessionTemplate.getMapper(NfeEventoSequenciaMapper.class);
        NfeEventoIdempotenciaMapper idempotenciaMapper = sessionTemplate.getMapper(NfeEventoIdempotenciaMapper.class);
        return new NfeCceOrquestradorService(eventoMapper, sequenciaMapper, idempotenciaMapper, cceService,
                eventoRetornoParser, new NfeCceClassificador(), consultaSituacaoService,
                new SefazReconciliacaoProperties(), transactionManager);
    }

    private NfeEventoRetorno retornoRegistrado(String nProt) {
        NfeEventoRetorno r = new NfeEventoRetorno();
        r.setCStatLote(128);
        r.setInfEventoPresente(true);
        r.setCStatEvento(135);
        r.setXMotivoEvento("Evento registrado e vinculado a NF-e");
        r.setNProtEvento(nProt);
        return r;
    }

    private NfeCceService cceServiceQueSempreRegistra() throws Exception {
        NfeCceService cceService = mock(NfeCceService.class);
        when(cceService.prepararEvento(any(), eq(CNPJ), eq(UF), any(), anyInt())).thenAnswer(inv -> {
            int nSeq = inv.getArgument(4);
            return new NfeCceEventoPreparado("ID110110" + nSeq, "2026-08-12T10:00:00-03:00",
                    "<evento-assinado/>", "hash-xyz", "chave-nao-usada-neste-mock", CNPJ, nSeq);
        });
        when(cceService.transmitirEvento(any(), any())).thenReturn("<retEnvEvento/>");
        return cceService;
    }

    // =========================================================================================
    // UNIQUE KEY (idempotency_key) sob concorrência real
    // =========================================================================================

    @Test
    @DisplayName("10 chamadas concorrentes com a MESMA Idempotency-Key — nunca duas linhas de idempotência, transmite exatamente uma vez")
    void mesmaIdempotencyKey_dezChamadasConcorrentes_transmiteUmaVez() throws Exception {
        limpar();
        String chave = "35260899000000000191550020000000091000000029";
        String idemKey = java.util.UUID.randomUUID().toString();
        inserirGateBootstrapped(chave, 0);

        NfeCceService cceService = cceServiceQueSempreRegistra();
        NfeEventoRetornoParser eventoRetornoParser = mock(NfeEventoRetornoParser.class);
        when(eventoRetornoParser.parse(any())).thenReturn(retornoRegistrado("135260000009000"));
        NfeConsultaSituacaoService consultaSituacaoService = mock(NfeConsultaSituacaoService.class);

        NfeCceOrquestradorService service = construirServiceReal(cceService, eventoRetornoParser, consultaSituacaoService);
        CertificadoContexto cert = mock(CertificadoContexto.class);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<String>> futuros = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            long pedidoId = 9100L + i; // mesma chave, mesma idemKey; pedidoId varia só de rótulo
            futuros.add(pool.submit((Callable<String>) () -> {
                largada.await();
                return service.corrigir(pedidoId, EMPRESA_ID, CNPJ, UF, chave,
                        "Correção do endereço do destinatário", idemKey, cert);
            }));
        }
        largada.countDown();

        int sucessos = 0, emAndamentoOuConflito = 0;
        for (Future<String> f : futuros) {
            try {
                f.get(20, TimeUnit.SECONDS);
                sucessos++;
            } catch (java.util.concurrent.ExecutionException e) {
                emAndamentoOuConflito++;
            }
        }
        pool.shutdown();

        assertTrue(sucessos >= 1, "pelo menos uma chamada precisa ter sucesso");
        assertEquals(threads, sucessos + emAndamentoOuConflito);
        assertEquals(1, contar("SELECT COUNT(*) FROM nfe_evento_idempotencia WHERE idempotency_key = ?", idemKey),
                "10 chamadas concorrentes com a MESMA Idempotency-Key nunca podem criar mais de uma linha de operação");
        assertEquals(1, contar("SELECT COUNT(*) FROM nfe_evento WHERE chave_nfe = ? AND tipo_evento = '110110'", chave),
                "nunca mais de uma linha de identidade fiscal para a mesma chave+sequência");
        verify(cceService, times(1)).transmitirEvento(any(), any());
        assertEquals(1, contar("SELECT ultimo_nseq_registrado FROM nfe_evento_sequencia WHERE chave_nfe = ?", chave));
    }

    // =========================================================================================
    // FOR UPDATE em nfe_evento_sequencia sob concorrência real
    // =========================================================================================

    @Test
    @DisplayName("10 chamadas concorrentes com Idempotency-Keys DIFERENTES para a MESMA chave nova — só uma reserva nSeq=1, as demais recebem CCE_EM_ANDAMENTO")
    void idempotencyKeysDiferentes_mesmaChaveNova_soUmaReserva() throws Exception {
        limpar();
        String chave = "35260899000000000191550020000000092000000028";
        inserirGateBootstrapped(chave, 0);

        NfeCceService cceService = cceServiceQueSempreRegistra();
        NfeEventoRetornoParser eventoRetornoParser = mock(NfeEventoRetornoParser.class);
        when(eventoRetornoParser.parse(any())).thenReturn(retornoRegistrado("135260000009001"));
        NfeConsultaSituacaoService consultaSituacaoService = mock(NfeConsultaSituacaoService.class);

        NfeCceOrquestradorService service = construirServiceReal(cceService, eventoRetornoParser, consultaSituacaoService);
        CertificadoContexto cert = mock(CertificadoContexto.class);

        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<String>> futuros = new ArrayList<>();
        Set<String> chavesIdempotencyUsadas = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < threads; i++) {
            long pedidoId = 9200L + i;
            String idemKey = java.util.UUID.randomUUID().toString();
            chavesIdempotencyUsadas.add(idemKey);
            futuros.add(pool.submit((Callable<String>) () -> {
                largada.await();
                return service.corrigir(pedidoId, EMPRESA_ID, CNPJ, UF, chave,
                        "Correção do endereço do destinatário", idemKey, cert);
            }));
        }
        largada.countDown();

        int sucessos = 0, bloqueados = 0;
        for (Future<String> f : futuros) {
            try {
                f.get(20, TimeUnit.SECONDS);
                sucessos++;
            } catch (java.util.concurrent.ExecutionException e) {
                assertTrue(e.getCause().getMessage() != null
                                && (e.getCause().getMessage().contains("andamento")
                                    || e.getCause().getMessage().contains("EM_ANDAMENTO")
                                    || e.getCause() instanceof br.com.borurio.app.exception.BusinessException),
                        "chamada bloqueada precisa falhar com CCE_EM_ANDAMENTO, nunca com erro genérico: " + e.getCause());
                bloqueados++;
            }
        }
        pool.shutdown();

        assertEquals(1, sucessos, "exatamente uma Idempotency-Key nova consegue reservar a sequência 1 desta chave");
        assertEquals(threads - 1, bloqueados);
        assertEquals(1, contar("SELECT COUNT(*) FROM nfe_evento WHERE chave_nfe = ? AND tipo_evento = '110110'", chave),
                "10 chamadas concorrentes para identidades NOVAS na mesma chave nunca podem reservar a mesma sequência duas vezes");
        assertEquals(1, contar("SELECT COUNT(*) FROM nfe_evento WHERE chave_nfe = ? AND n_seq_evento = 1", chave));
        verify(cceService, times(1)).transmitirEvento(any(), any());
    }
}
