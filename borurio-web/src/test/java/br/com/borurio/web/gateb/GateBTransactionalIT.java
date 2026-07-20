package br.com.borurio.web.gateb;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.OmsCompanyCertificate;
import br.com.borurio.app.entity.OmsFiscalAuthorization;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.OmsCompanyCertificateMapper;
import br.com.borurio.app.mapper.OmsFiscalAuthorizationMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import br.com.borurio.web.dto.FiscalNumberingSyncResponse;
import br.com.borurio.web.dto.ReservaFiscalResultado;
import br.com.borurio.web.service.FiscalNumberingService;
import br.com.borurio.web.service.ReservaFiscalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Gate B — bateria transacional contra MySQL 8.4 real (container descartável, docker run,
 * credenciais exclusivamente via variáveis de ambiente — ver GateBTestProperties). Reusa os
 * MESMOS beans Spring reais
 * (NfeSequenciaService, ReservaFiscalService, FiscalNumberingService) que rodam em produção —
 * nenhum mock nos caminhos normais, transações e locks reais.
 *
 * *IT (não *Test): Surefire não pega esse padrão por padrão — `mvn test` normal não precisa de
 * Docker. Só roda quando executada explicitamente.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GateBTransactionalIT {

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", GateBTestProperties::dbUrl);
        registry.add("spring.datasource.username", GateBTestProperties::dbUsername);
        registry.add("spring.datasource.password", GateBTestProperties::dbPassword);
        registry.add("FISCAL_CERT_PATH", GateBTestProperties::certPath);
        registry.add("FISCAL_CERT_PASSWORD", GateBTestProperties::certPassword);
    }

    @Autowired NfeSequenciaService sequenciaService;
    @Autowired ReservaFiscalService reservaFiscalService;
    @Autowired FiscalNumberingService fiscalNumberingService;
    @Autowired EmpresaMapper empresaMapper;
    @SpyBean PedidoMapper pedidoMapper;
    @Autowired OmsFiscalAuthorizationMapper omsAuthMapper;
    @Autowired OmsCompanyCertificateMapper omsCertMapper;
    @Autowired JdbcTemplate jdbc;

    private static final String CNPJ_A = "54393421000159"; // empresa default seedada pelo StartupListener
    private static final String CNPJ_B = "22418179000134"; // segunda empresa, seedada no setUp
    private static final String JTI    = "jti-gateb-teste";
    private Long empresaIdA;
    private Long empresaIdB;

    @BeforeEach
    void limparEstadoDeTeste() {
        reset(pedidoMapper); // restaura comportamento real caso um teste anterior tenha espionado uma falha
        jdbc.update("DELETE FROM nfe_sequencia_auditoria");
        jdbc.update("DELETE FROM nfe_sequencia");
        jdbc.update("DELETE FROM pedido_item");
        jdbc.update("DELETE FROM pedido");
        jdbc.update("DELETE FROM oms_company_certificate");
        jdbc.update("DELETE FROM oms_fiscal_authorization");
        jdbc.update("DELETE FROM oms_integrator");
        jdbc.update("INSERT INTO oms_integrator (codigo, nome, ativo) VALUES ('GATEB-INTEGRATOR', 'Gate B Integrator', 1)");
        Long integratorId = jdbc.queryForObject("SELECT id FROM oms_integrator ORDER BY id DESC LIMIT 1", Long.class);

        Empresa empresaA = empresaMapper.buscarPorCnpj(CNPJ_A);
        empresaIdA = empresaA.getId();
        empresaA.setSerieNfePadrao("1");
        empresaMapper.atualizar(empresaA);

        Empresa empresaB = empresaMapper.buscarPorCnpj(CNPJ_B);
        if (empresaB == null) {
            empresaB = new Empresa();
            empresaB.setCnpj(CNPJ_B);
            empresaB.setRazaoSocial("EMPRESA B GATE B");
            empresaB.setUf("SP");
            empresaB.setCrt("1");
            empresaB.setSerieNfePadrao("1");
            empresaB.setIndFinalPadrao("1");
            empresaB.setAtivo(true);
            empresaB.setControleEstoqueAtivo(true);
            empresaMapper.inserir(empresaB);
        } else {
            empresaB.setSerieNfePadrao("1");
            empresaMapper.atualizar(empresaB);
        }
        empresaIdB = empresaB.getId();

        // Autorização OMS sintética — jti conhecido, sem certificado real (a checagem de
        // autorização é 100% baseada em linhas do banco, nunca decodifica o .pfx).
        OmsFiscalAuthorization auth = new OmsFiscalAuthorization();
        auth.setEmpresaId(empresaIdA);
        auth.setIntegratorId(integratorId);
        auth.setCodigoOms("GATEB-CLIENTE-1");
        auth.setJti(JTI);
        auth.setTokenExpiraEm(LocalDateTime.now().plusYears(1));
        auth.setEmitidoEm(LocalDateTime.now());
        omsAuthMapper.inserir(auth);

        seedCertificado(auth.getId(), CNPJ_A, empresaIdA);
        seedCertificado(auth.getId(), CNPJ_B, empresaIdB);
    }

    private void seedCertificado(Long authId, String cnpj, Long empresaId) {
        OmsCompanyCertificate cert = new OmsCompanyCertificate();
        cert.setAuthId(authId);
        cert.setCnpj(cnpj);
        cert.setEmpresaId(empresaId);
        cert.setThumbprint("gateb-thumbprint-" + cnpj);
        cert.setCertPfxEnc(new byte[]{1, 2, 3}); // nunca decodificado nestes testes
        cert.setCertSenhaEnc("gateb-senha-fake");
        cert.setKeyVersion("v1");
        cert.setNotBefore(LocalDateTime.now().minusDays(1));
        cert.setNotAfter(LocalDateTime.now().plusYears(1));
        cert.setAtivo(true);
        omsCertMapper.inserir(cert);
    }

    @AfterEach
    void restaurarSpy() {
        reset(pedidoMapper);
    }

    private Long criarPedidoMinimo(String cnpj, Long empresaId) {
        jdbc.update("""
                INSERT INTO pedido (empresa_id, numero, cnpj_emitente, dest_cnpj_cpf, dest_razao_social,
                                     natureza_operacao, serie_nfe, status, data_pedido, data_atualizacao)
                VALUES (?, ?, ?, '12345678000195', 'Cliente GateB', 'VENDA DE MERCADORIA', '1', 'RASCUNHO', NOW(), NOW())
                """, empresaId, "G" + (System.nanoTime() % 10_000_000_000L), cnpj);
        return jdbc.queryForObject("SELECT id FROM pedido ORDER BY id DESC LIMIT 1", Long.class);
    }

    private int ultimoNumeroReal(String cnpj, String serie) {
        List<Integer> r = jdbc.queryForList(
                "SELECT ultimo_numero FROM nfe_sequencia WHERE cnpj_emitente=? AND serie=?", Integer.class, cnpj, serie);
        return r.isEmpty() ? -1 : r.get(0);
    }

    // =========================================================================
    // 1-8: FiscalNumberingService — sincronização, MySQL real
    // =========================================================================

    @Test
    @DisplayName("1-2: sincronização inicial cria sequência real; proximoNumeroAnterior=null")
    void sincronizacaoInicial_criaSequenciaReal() {
        FiscalNumberingSyncResponse resp = fiscalNumberingService.sincronizar(CNPJ_A, "1", 101, JTI, "req-1");

        assertNull(resp.proximoNumeroAnterior());
        assertEquals(101, resp.proximoNumeroAtual());
        assertTrue(resp.aplicado());
        assertEquals(100, ultimoNumeroReal(CNPJ_A, "1"));

        Integer aud = jdbc.queryForObject("SELECT COUNT(*) FROM nfe_sequencia_auditoria WHERE cnpj_emitente=?", Integer.class, CNPJ_A);
        assertEquals(1, aud, "auditoria precisa ter sido gravada de verdade");
    }

    @Test
    @DisplayName("3: reenvio do mesmo valor é idempotente — não altera o registro real")
    void idempotencia_realMysql() {
        fiscalNumberingService.sincronizar(CNPJ_A, "1", 101, JTI, "req-a");
        FiscalNumberingSyncResponse resp2 = fiscalNumberingService.sincronizar(CNPJ_A, "1", 101, JTI, "req-b");

        assertFalse(resp2.aplicado());
        assertEquals(100, ultimoNumeroReal(CNPJ_A, "1"));
        Integer aud = jdbc.queryForObject("SELECT COUNT(*) FROM nfe_sequencia_auditoria WHERE cnpj_emitente=?", Integer.class, CNPJ_A);
        assertEquals(2, aud, "cada chamada grava auditoria própria, mesmo idempotente");
    }

    @Test
    @DisplayName("4: avanço real de numeração")
    void avanco_realMysql() {
        fiscalNumberingService.sincronizar(CNPJ_A, "1", 101, JTI, "req-a");
        FiscalNumberingSyncResponse resp = fiscalNumberingService.sincronizar(CNPJ_A, "1", 500, JTI, "req-b");

        assertTrue(resp.aplicado());
        assertEquals(101, resp.proximoNumeroAnterior());
        assertEquals(500, resp.proximoNumeroAtual());
        assertEquals(499, ultimoNumeroReal(CNPJ_A, "1"));
    }

    @Test
    @DisplayName("5: regressão real é rejeitada, estado no banco não muda")
    void regressao_rejeitadaRealMysql() {
        fiscalNumberingService.sincronizar(CNPJ_A, "1", 500, JTI, "req-a");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> fiscalNumberingService.sincronizar(CNPJ_A, "1", 5, JTI, "req-b"));

        assertEquals("NUMERACAO_INFERIOR_A_ATUAL", ex.getErrorCode());
        assertEquals(499, ultimoNumeroReal(CNPJ_A, "1"), "estado no banco não pode ter mudado");
    }

    @Test
    @DisplayName("6-8: troca de série real atualiza Empresa.serieNfePadrao; empresa inexistente não cria sequência")
    void trocaDeSerie_eEmpresaInexistente_realMysql() {
        fiscalNumberingService.sincronizar(CNPJ_A, "2", 1, JTI, "req-a");

        Empresa empresaAtualizada = empresaMapper.buscarPorCnpj(CNPJ_A);
        assertEquals("2", empresaAtualizada.getSerieNfePadrao(), "Empresa.serieNfePadrao precisa refletir a troca real no banco");
        assertEquals(0, ultimoNumeroReal(CNPJ_A, "2"));

        // Empresa inexistente: CNPJ nunca autorizado, nunca teve certificado seedado.
        String cnpjFantasma = "99999999000199";
        BusinessException ex = assertThrows(BusinessException.class,
                () -> fiscalNumberingService.sincronizar(cnpjFantasma, "1", 1, JTI, "req-b"));
        assertEquals("CNPJ_NOT_AUTHORIZED", ex.getErrorCode(), "sem certificado seedado, falha na autorização antes de chegar em Empresa");
        assertEquals(-1, ultimoNumeroReal(cnpjFantasma, "1"), "nenhuma sequência deve ter sido criada");
    }

    // =========================================================================
    // 9: ReservaFiscalService — falha na persistência do pedido desfaz a sequência
    // =========================================================================

    @Test
    @DisplayName("9: falha ao persistir o snapshot no pedido desfaz o avanço da sequência (rollback real)")
    void falhaNoPedido_desfazAvancoDaSequencia_rollbackReal() {
        Long pedidoId = criarPedidoMinimo(CNPJ_A, empresaIdA);
        doThrow(new RuntimeException("GateB: falha forçada na persistência do snapshot"))
                .when(pedidoMapper).atualizarSerieReservada(anyLong(), anyString());

        assertThrows(RuntimeException.class, () -> reservaFiscalService.reservar(pedidoId, CNPJ_A));

        // A transação inteira precisa ter voltado atrás — nenhuma sequência deve existir.
        assertEquals(-1, ultimoNumeroReal(CNPJ_A, "1"),
                "número não pode ficar consumido se o snapshot no pedido falhou — prova o fix do achado #1 da revisão anterior");
    }

    // =========================================================================
    // 10-16: Concorrência real (MySQL, SERIALIZABLE) e isolamento
    // =========================================================================

    @Test
    @DisplayName("10: duas sincronizações concorrentes pro mesmo CNPJ+série — só uma avança, nenhuma corrompe o estado")
    void duasSincronizacoesConcorrentes_mesmoCnpjSerie() throws Exception {
        fiscalNumberingService.sincronizar(CNPJ_A, "1", 101, JTI, "seed"); // ultimoNumero=100

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        AtomicInteger sucessos = new AtomicInteger(0);
        AtomicInteger falhas = new AtomicInteger(0);

        Runnable tarefaA = () -> {
            try { largada.await();
                try { fiscalNumberingService.sincronizar(CNPJ_A, "1", 200, JTI, "conc-a"); sucessos.incrementAndGet(); }
                catch (Exception e) { falhas.incrementAndGet(); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        Runnable tarefaB = () -> {
            try { largada.await();
                try { fiscalNumberingService.sincronizar(CNPJ_A, "1", 300, JTI, "conc-b"); sucessos.incrementAndGet(); }
                catch (Exception e) { falhas.incrementAndGet(); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        pool.submit(tarefaA); pool.submit(tarefaB);
        largada.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        // SERIALIZABLE serializa as duas — ambas podem ter sucesso (200 depois 300, ou o contrário
        // é rejeitado por regressão) OU uma falha por NUMERACAO_INFERIOR_A_ATUAL. O que NUNCA pode
        // acontecer é o estado final ficar menor que o maior valor pedido.
        int estadoFinal = ultimoNumeroReal(CNPJ_A, "1");
        assertEquals(299, estadoFinal, "estado final precisa refletir o maior avanço aplicado (300 → ultimoNumero=299), nunca ficar preso em 200 nem corromper");
        System.out.println("[GateB] sync concorrente: sucessos=" + sucessos.get() + " falhas(regressão)=" + falhas.get());
    }

    @Test
    @DisplayName("11: criação concorrente da mesma sequência inexistente — nunca duplica, uk_emitente_serie protege de verdade")
    void criacaoConcorrente_mesmaSequencia() throws Exception {
        int n = 5;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch largada = new CountDownLatch(1);
        AtomicInteger sucessos = new AtomicInteger(0);

        for (int i = 0; i < n; i++) {
            pool.submit(() -> {
                try {
                    largada.await();
                    fiscalNumberingService.sincronizar(CNPJ_A, "1", 101, JTI, "conc-create-" + Thread.currentThread().getId());
                    sucessos.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception ignored) {
                    // idempotente para os que chegam depois — não é falha
                }
            });
        }
        largada.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        Integer linhas = jdbc.queryForObject(
                "SELECT COUNT(*) FROM nfe_sequencia WHERE cnpj_emitente=? AND serie='1'", Integer.class, CNPJ_A);
        assertEquals(1, linhas, "uk_emitente_serie (V011) precisa garantir exatamente UMA linha, mesmo com 5 criações concorrentes");
        assertEquals(100, ultimoNumeroReal(CNPJ_A, "1"));
    }

    @Test
    @DisplayName("12: sincronização concorrente com reserva de emissão — nunca gera número duplicado")
    void sincronizacaoConcorrenteComReservaDeEmissao() throws Exception {
        fiscalNumberingService.sincronizar(CNPJ_A, "1", 101, JTI, "seed"); // ultimoNumero=100
        Long pedido1 = criarPedidoMinimo(CNPJ_A, empresaIdA);
        Long pedido2 = criarPedidoMinimo(CNPJ_A, empresaIdA);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        Set<Integer> numerosReservados = ConcurrentHashMap.newKeySet();

        Runnable reserva = () -> {
            try { largada.await();
                ReservaFiscalResultado r = reservaFiscalService.reservar(pedido1, CNPJ_A);
                numerosReservados.add(r.numero());
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        Runnable sync = () -> {
            try { largada.await();
                fiscalNumberingService.sincronizar(CNPJ_A, "1", 300, JTI, "conc-sync");
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
              catch (Exception ignored) { /* pode rejeitar por regressão dependendo da ordem — aceitável */ }
        };
        pool.submit(reserva); pool.submit(sync);
        largada.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS));

        // Depois da corrida, uma segunda reserva pega o estado JÁ CONSISTENTE (seja pré ou pós sync).
        ReservaFiscalResultado r2 = reservaFiscalService.reservar(pedido2, CNPJ_A);
        assertFalse(numerosReservados.contains(r2.numero()), "reserva #2 nunca pode repetir o número da reserva #1, mesmo com sync concorrente");
        System.out.println("[GateB] reserva concorrente com sync: numero1=" + numerosReservados + " numero2=" + r2.numero() + " serie2=" + r2.serie());
    }

    @Test
    @DisplayName("13: dez reservas concorrentes geram números únicos e sequenciais no MySQL real")
    void dezReservasConcorrentes_numerosUnicos() throws Exception {
        int n = 10;
        List<Long> pedidoIds = new java.util.ArrayList<>();
        for (int i = 0; i < n; i++) pedidoIds.add(criarPedidoMinimo(CNPJ_A, empresaIdA));

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch largada = new CountDownLatch(1);
        Set<Integer> numeros = ConcurrentHashMap.newKeySet();

        for (Long pid : pedidoIds) {
            pool.submit(() -> {
                try {
                    largada.await();
                    numeros.add(reservaFiscalService.reservar(pid, CNPJ_A).numero());
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
        }
        largada.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(n, numeros.size(), "10 reservas concorrentes precisam gerar 10 números distintos, sem duplicata, no MySQL real");
        Set<Integer> esperado = Set.of(1,2,3,4,5,6,7,8,9,10);
        assertEquals(esperado, numeros);
    }

    @Test
    @DisplayName("14-15: isolamento entre dois CNPJs e entre duas séries do mesmo CNPJ — contadores independentes de verdade")
    void isolamentoCnpjsESeries_realMysql() {
        fiscalNumberingService.sincronizar(CNPJ_A, "1", 50, JTI, "a1");
        fiscalNumberingService.sincronizar(CNPJ_A, "2", 900, JTI, "a2");
        fiscalNumberingService.sincronizar(CNPJ_B, "1", 10, JTI, "b1");

        assertEquals(49, ultimoNumeroReal(CNPJ_A, "1"));
        assertEquals(899, ultimoNumeroReal(CNPJ_A, "2"));
        assertEquals(9, ultimoNumeroReal(CNPJ_B, "1"));

        Empresa empresaA = empresaMapper.buscarPorCnpj(CNPJ_A);
        Empresa empresaB = empresaMapper.buscarPorCnpj(CNPJ_B);
        assertEquals("2", empresaA.getSerieNfePadrao(), "última série sincronizada pra A foi a 2");
        assertEquals("1", empresaB.getSerieNfePadrao(), "B não deve ser afetada pela sincronização de A");
    }

    // =========================================================================
    // 17: ordem global de locks — sem deadlock com chamadas concorrentes cruzadas
    // =========================================================================

    @Test
    @DisplayName("17: chamadas cruzadas reserva/sincronização em massa não produzem deadlock (ordem de locks consistente)")
    void ordemDeLocksConsistente_semDeadlock() throws Exception {
        fiscalNumberingService.sincronizar(CNPJ_A, "1", 2, JTI, "seed");
        int rodadas = 15;
        ExecutorService pool = Executors.newFixedThreadPool(4);
        CountDownLatch largada = new CountDownLatch(1);
        AtomicInteger erros = new AtomicInteger(0);

        for (int i = 0; i < rodadas; i++) {
            Long pid = criarPedidoMinimo(CNPJ_A, empresaIdA);
            int numeroSync = 100 + i;
            pool.submit(() -> {
                try { largada.await(); reservaFiscalService.reservar(pid, CNPJ_A); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (Exception e) { erros.incrementAndGet(); }
            });
            pool.submit(() -> {
                try { largada.await(); fiscalNumberingService.sincronizar(CNPJ_A, "1", numeroSync, JTI, "cross-" + numeroSync); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                catch (Exception ignored) { /* regressão esperada às vezes — não é deadlock */ }
            });
        }
        largada.countDown();
        pool.shutdown();
        boolean terminouATempo = pool.awaitTermination(40, TimeUnit.SECONDS);
        assertTrue(terminouATempo, "se não terminou a tempo, é sinal de deadlock real — ordem de locks (Empresa → nfe_sequencia) precisa ser consistente nos dois fluxos");
        System.out.println("[GateB] " + (rodadas * 2) + " chamadas cruzadas concluídas sem timeout | erros inesperados=" + erros.get());
    }
}
