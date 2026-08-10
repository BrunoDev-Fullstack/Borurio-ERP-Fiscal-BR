package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.AtualizacaoSequenciaResultado;
import br.com.borurio.fiscal.entity.NfeSequencia;
import br.com.borurio.fiscal.exception.SequenciaComEmissaoAtivaException;
import br.com.borurio.fiscal.mapper.NfeSequenciaMapper;
import br.com.borurio.fiscal.service.impl.NfeSequenciaServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("NfeSequenciaService — controle atômico de nNF por série")
public class NfeSequenciaServiceTest {

    private NfeSequenciaMapper mapper;
    private NfeSequenciaService service;

    private static final String CNPJ  = "54393421000159";
    private static final String SERIE = "1";

    @BeforeEach
    void setUp() {
        mapper  = mock(NfeSequenciaMapper.class);
        service = new NfeSequenciaServiceImpl(mapper);
    }

    @Test
    @DisplayName("Deve retornar 1 e inserir novo registro quando série ainda não existe")
    void primeiroNumeroInsereRegistro() {
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(null);

        int resultado = service.proximoNumero(CNPJ, SERIE);

        assertEquals(1, resultado);
        verify(mapper).inserir(argThat(seq ->
                seq.getCnpjEmitente().equals(CNPJ) &&
                seq.getSerie().equals(SERIE) &&
                seq.getUltimoNumero() == 1));
        verify(mapper, never()).atualizarNumero(any());
    }

    @Test
    @DisplayName("Deve incrementar e retornar próximo número quando série já existe (ultimo=5 → retorna 6)")
    void incrementaNumeroExistente() {
        NfeSequencia existente = new NfeSequencia();
        existente.setCnpjEmitente(CNPJ);
        existente.setSerie(SERIE);
        existente.setUltimoNumero(5);
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(existente);

        int resultado = service.proximoNumero(CNPJ, SERIE);

        assertEquals(6, resultado);
        verify(mapper).atualizarNumero(argThat(seq -> seq.getUltimoNumero() == 6));
        verify(mapper, never()).inserir(any());
    }

    @Test
    @DisplayName("Deve respeitar série diferente como contador independente")
    void seriesIndependentes() {
        NfeSequencia s1 = new NfeSequencia();
        s1.setCnpjEmitente(CNPJ);
        s1.setSerie("1");
        s1.setUltimoNumero(10);

        NfeSequencia s2 = new NfeSequencia();
        s2.setCnpjEmitente(CNPJ);
        s2.setSerie("2");
        s2.setUltimoNumero(3);

        when(mapper.buscarParaAtualizar(CNPJ, "1")).thenReturn(s1);
        when(mapper.buscarParaAtualizar(CNPJ, "2")).thenReturn(s2);

        assertEquals(11, service.proximoNumero(CNPJ, "1"));
        assertEquals(4,  service.proximoNumero(CNPJ, "2"));
    }

    // -------------------------------------------------------------------------
    // P0.3 — inicializarBaseline (onboarding de CNPJ com histórico em outro ERP)
    //
    // Regra estrita: cobre só a PRIMEIRA configuração de uma sequência nova.
    //   inexistente        → cria
    //   igual ao existente → idempotente
    //   diferente do existente (maior OU menor) → erro explícito, nunca escreve
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Baseline em sequência inexistente cria o registro com o valor informado")
    void baselineEmSequenciaInexistente_cria() {
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(null);

        service.inicializarBaseline(CNPJ, SERIE, 100);

        verify(mapper).inserir(argThat(seq ->
                seq.getCnpjEmitente().equals(CNPJ) &&
                seq.getSerie().equals(SERIE) &&
                seq.getUltimoNumero() == 100));
        verify(mapper, never()).atualizarNumero(any());
    }

    @Test
    @DisplayName("Baseline idêntico ao valor já existente é idempotente — não escreve")
    void baselineIgualAoExistente_idempotente() {
        NfeSequencia existente = new NfeSequencia();
        existente.setCnpjEmitente(CNPJ);
        existente.setSerie(SERIE);
        existente.setUltimoNumero(100);
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(existente);

        service.inicializarBaseline(CNPJ, SERIE, 100);

        verify(mapper, never()).inserir(any());
        verify(mapper, never()).atualizarNumero(any());
    }

    @Test
    @DisplayName("Baseline diferente do valor já existente lança erro explícito — nunca escreve, nunca regride, nunca avança")
    void baselineDiferenteDeSequenciaExistente_lancaErro() {
        // Caso 1: valor informado MENOR que o existente (regressão)
        NfeSequencia existente120 = new NfeSequencia();
        existente120.setCnpjEmitente(CNPJ);
        existente120.setSerie(SERIE);
        existente120.setUltimoNumero(120);
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(existente120);

        IllegalStateException ex1 = assertThrows(IllegalStateException.class,
                () -> service.inicializarBaseline(CNPJ, SERIE, 90));
        assertTrue(ex1.getMessage().contains("120"));
        assertTrue(ex1.getMessage().contains("90"));

        // Caso 2: valor informado MAIOR que o existente (avanço silencioso — também proibido)
        NfeSequencia existente50 = new NfeSequencia();
        existente50.setCnpjEmitente(CNPJ);
        existente50.setSerie(SERIE);
        existente50.setUltimoNumero(50);
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(existente50);

        IllegalStateException ex2 = assertThrows(IllegalStateException.class,
                () -> service.inicializarBaseline(CNPJ, SERIE, 100));
        assertTrue(ex2.getMessage().contains("50"));
        assertTrue(ex2.getMessage().contains("100"));

        // Nenhum dos dois casos gravou nada.
        verify(mapper, never()).inserir(any());
        verify(mapper, never()).atualizarNumero(any());
    }

    @Test
    @DisplayName("Baseline negativo é rejeitado antes de tocar o mapper")
    void baselineNegativo_rejeitado() {
        assertThrows(IllegalArgumentException.class,
                () -> service.inicializarBaseline(CNPJ, SERIE, -1));

        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("CNPJ nulo ou vazio é rejeitado antes de tocar o mapper")
    void cnpjNuloOuVazio_rejeitado() {
        assertThrows(IllegalArgumentException.class,
                () -> service.inicializarBaseline(null, SERIE, 100));
        assertThrows(IllegalArgumentException.class,
                () -> service.inicializarBaseline("  ", SERIE, 100));

        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("Série nula ou vazia é rejeitada antes de tocar o mapper")
    void serieNulaOuVazia_rejeitada() {
        assertThrows(IllegalArgumentException.class,
                () -> service.inicializarBaseline(CNPJ, null, 100));
        assertThrows(IllegalArgumentException.class,
                () -> service.inicializarBaseline(CNPJ, "  ", 100));

        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("Dois CNPJs diferentes têm baselines independentes")
    void baselineDoisCnpjsIndependentes() {
        String cnpjB = "22418179000134";
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(null);
        when(mapper.buscarParaAtualizar(cnpjB, SERIE)).thenReturn(null);

        service.inicializarBaseline(CNPJ, SERIE, 100);
        service.inicializarBaseline(cnpjB, SERIE, 50);

        verify(mapper).inserir(argThat(seq -> seq.getCnpjEmitente().equals(CNPJ) && seq.getUltimoNumero() == 100));
        verify(mapper).inserir(argThat(seq -> seq.getCnpjEmitente().equals(cnpjB) && seq.getUltimoNumero() == 50));
    }

    // -------------------------------------------------------------------------
    // Determinismo real: baseline 100 → próxima alocação real é 101
    // Usa o mapper falho com estado (definido mais abaixo) em vez de mocks sem memória,
    // pra provar a cadeia de ponta a ponta, não só o comentário de intenção.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Baseline 100 seguido de proximoNumero() retorna exatamente 101")
    void baselineSeguidoDeProximoNumero_retorna101() {
        NfeSequenciaMapperComLockDeLinha mapperReal = new NfeSequenciaMapperComLockDeLinha();
        NfeSequenciaService servicoReal = new NfeSequenciaServiceImpl(mapperReal);

        try {
            servicoReal.inicializarBaseline(CNPJ, SERIE, 100);
        } finally {
            mapperReal.liberarTransacaoDaThreadAtual();
        }

        int proximo;
        try {
            proximo = servicoReal.proximoNumero(CNPJ, SERIE);
        } finally {
            mapperReal.liberarTransacaoDaThreadAtual();
        }

        assertEquals(101, proximo);
    }

    // -------------------------------------------------------------------------
    // Concorrência real — mapper falso simulando SELECT ... FOR UPDATE
    // -------------------------------------------------------------------------

    /**
     * Simula o comportamento real do banco (SELECT ... FOR UPDATE + SERIALIZABLE): a leitura
     * bloqueia até a "transação" anterior no mesmo CNPJ+série liberar o lock. O lock só é
     * liberado quando o teste chama liberarTransacaoDaThreadAtual() no finally — mesma
     * semântica do @Transactional real do Spring, que libera o lock da linha no commit,
     * independente de ter havido escrita ou não (ex.: caminho idempotente do baseline).
     */
    static class NfeSequenciaMapperComLockDeLinha implements NfeSequenciaMapper {
        private final Map<String, NfeSequencia> dados = new ConcurrentHashMap<>();
        private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();
        private final ThreadLocal<ReentrantLock> lockDaThread = new ThreadLocal<>();
        final List<Integer> valoresGravados = new CopyOnWriteArrayList<>();

        private String chave(String cnpj, String serie) { return cnpj + "|" + serie; }

        @Override
        public NfeSequencia buscarParaAtualizar(String cnpjEmitente, String serie) {
            ReentrantLock lock = locks.computeIfAbsent(chave(cnpjEmitente, serie), k -> new ReentrantLock());
            lock.lock();
            lockDaThread.set(lock);
            NfeSequencia atual = dados.get(chave(cnpjEmitente, serie));
            if (atual == null) return null;
            NfeSequencia copia = new NfeSequencia();
            copia.setCnpjEmitente(atual.getCnpjEmitente());
            copia.setSerie(atual.getSerie());
            copia.setUltimoNumero(atual.getUltimoNumero());
            copia.setEmissaoAtivaId(atual.getEmissaoAtivaId());
            return copia;
        }

        @Override
        public void inserir(NfeSequencia seq) {
            dados.put(chave(seq.getCnpjEmitente(), seq.getSerie()), copiar(seq));
            valoresGravados.add(seq.getUltimoNumero());
        }

        @Override
        public void atualizarNumero(NfeSequencia seq) {
            dados.put(chave(seq.getCnpjEmitente(), seq.getSerie()), copiar(seq));
            valoresGravados.add(seq.getUltimoNumero());
        }

        @Override
        public void ocuparGate(String cnpjEmitente, String serie, Long emissaoAtivaId) {
            NfeSequencia atual = dados.get(chave(cnpjEmitente, serie));
            if (atual == null) return;
            atual.setEmissaoAtivaId(emissaoAtivaId);
        }

        @Override
        public void liberarGate(String cnpjEmitente, String serie) {
            NfeSequencia atual = dados.get(chave(cnpjEmitente, serie));
            if (atual == null) return;
            atual.setEmissaoAtivaId(null);
        }

        private NfeSequencia copiar(NfeSequencia seq) {
            NfeSequencia copia = new NfeSequencia();
            copia.setCnpjEmitente(seq.getCnpjEmitente());
            copia.setSerie(seq.getSerie());
            copia.setUltimoNumero(seq.getUltimoNumero());
            copia.setEmissaoAtivaId(seq.getEmissaoAtivaId());
            return copia;
        }

        /** Chamar no finally de cada "transação" simulada — equivalente ao commit do Spring. */
        void liberarTransacaoDaThreadAtual() {
            ReentrantLock lock = lockDaThread.get();
            if (lock != null) {
                lockDaThread.remove();
                lock.unlock();
            }
        }
    }

    @Test
    @DisplayName("Concorrência A: baseline concluído ANTES → 9 alocações concorrentes produzem exatamente 101..109")
    void concorrencia_baselineConcluidoAntes_novePosicoesExatas() throws Exception {
        NfeSequenciaMapperComLockDeLinha mapperReal = new NfeSequenciaMapperComLockDeLinha();
        NfeSequenciaService servicoReal = new NfeSequenciaServiceImpl(mapperReal);

        // Baseline roda de forma síncrona e completa ANTES de qualquer concorrência — sem isso,
        // nada garante que proximoNumero() não aloque a partir de 1 numa corrida com o baseline.
        try {
            servicoReal.inicializarBaseline(CNPJ, SERIE, 100);
        } finally {
            mapperReal.liberarTransacaoDaThreadAtual();
        }

        int numThreads = 9;
        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        CountDownLatch largada = new CountDownLatch(1);
        Set<Integer> numerosAlocados = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < numThreads; i++) {
            pool.submit(() -> {
                try {
                    largada.await();
                    try {
                        numerosAlocados.add(servicoReal.proximoNumero(CNPJ, SERIE));
                    } finally {
                        mapperReal.liberarTransacaoDaThreadAtual();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        largada.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        Set<Integer> esperado = Set.of(101, 102, 103, 104, 105, 106, 107, 108, 109);
        assertEquals(esperado, numerosAlocados,
                "as 9 alocações concorrentes pós-baseline devem ser exatamente 101..109, sem número abaixo de 101");
    }

    @Test
    @DisplayName("Concorrência B: duas inicializações concorrentes com o MESMO valor — idempotente, estado final único")
    void concorrencia_duasInicializacoesMesmoValor_idempotente() throws Exception {
        NfeSequenciaMapperComLockDeLinha mapperReal = new NfeSequenciaMapperComLockDeLinha();
        NfeSequenciaService servicoReal = new NfeSequenciaServiceImpl(mapperReal);

        int numThreads = 2;
        ExecutorService pool = Executors.newFixedThreadPool(numThreads);
        CountDownLatch largada = new CountDownLatch(1);
        AtomicInteger falhas = new AtomicInteger(0);

        for (int i = 0; i < numThreads; i++) {
            pool.submit(() -> {
                try {
                    largada.await();
                    try {
                        servicoReal.inicializarBaseline(CNPJ, SERIE, 100);
                    } catch (Exception e) {
                        falhas.incrementAndGet();
                    } finally {
                        mapperReal.liberarTransacaoDaThreadAtual();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        largada.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(0, falhas.get(), "duas inicializações com o mesmo valor nunca deveriam lançar erro");
        assertEquals(100, servicoReal.proximoNumero(CNPJ, SERIE) - 1, "estado final precisa ser exatamente 100");
    }

    @Test
    @DisplayName("Concorrência C: duas inicializações concorrentes com valores DIFERENTES — só uma vence, a outra falha explicitamente")
    void concorrencia_duasInicializacoesValoresDiferentes_somenteUmaVence() throws Exception {
        NfeSequenciaMapperComLockDeLinha mapperReal = new NfeSequenciaMapperComLockDeLinha();
        NfeSequenciaService servicoReal = new NfeSequenciaServiceImpl(mapperReal);

        CountDownLatch largada = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        AtomicInteger sucessos = new AtomicInteger(0);
        AtomicInteger falhas = new AtomicInteger(0);

        Runnable tarefa100 = () -> {
            try {
                largada.await();
                try {
                    servicoReal.inicializarBaseline(CNPJ, SERIE, 100);
                    sucessos.incrementAndGet();
                } catch (IllegalStateException e) {
                    falhas.incrementAndGet();
                } finally {
                    mapperReal.liberarTransacaoDaThreadAtual();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Runnable tarefa150 = () -> {
            try {
                largada.await();
                try {
                    servicoReal.inicializarBaseline(CNPJ, SERIE, 150);
                    sucessos.incrementAndGet();
                } catch (IllegalStateException e) {
                    falhas.incrementAndGet();
                } finally {
                    mapperReal.liberarTransacaoDaThreadAtual();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };

        pool.submit(tarefa100);
        pool.submit(tarefa150);
        largada.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertEquals(1, sucessos.get(), "exatamente uma das duas configurações deveria vencer");
        assertEquals(1, falhas.get(), "a outra precisa falhar explicitamente, nunca sobrescrever silenciosamente");

        // O estado final é 100 OU 150 — nunca um valor corrompido/misturado.
        int estadoFinal = servicoReal.proximoNumero(CNPJ, SERIE) - 1;
        assertTrue(estadoFinal == 100 || estadoFinal == 150,
                "estado final precisa ser exatamente um dos dois valores propostos: " + estadoFinal);
    }

    // -------------------------------------------------------------------------
    // atualizarSequencia() — sincronização recorrente vinda da OMS (20-07-2026)
    //
    // Diferente de inicializarBaseline(): cobre atualizações repetidas, não só a primeira.
    //   inexistente        → cria com ultimoNumero = proximoNumero - 1
    //   igual ao existente → idempotente, aplicado=false
    //   maior que existente → avança, aplicado=true
    //   menor que existente → IllegalStateException (regressão rejeitada)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("atualizarSequencia em sequência inexistente cria o registro com ultimoNumero = proximoNumero - 1")
    void atualizarSequencia_sequenciaInexistente_cria() {
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(null);

        AtualizacaoSequenciaResultado resultado = service.atualizarSequencia(CNPJ, SERIE, 101);

        verify(mapper).inserir(argThat(seq ->
                seq.getCnpjEmitente().equals(CNPJ) &&
                seq.getSerie().equals(SERIE) &&
                seq.getUltimoNumero() == 100));
        assertTrue(resultado.aplicado());
        assertEquals(101, resultado.proximoNumeroAtual());
        assertNull(resultado.proximoNumeroAnterior(),
                "sequência recém-criada não tem número anterior — null, nunca 0 (0 seria um nNF inválido)");
    }

    @Test
    @DisplayName("atualizarSequencia idempotente quando proximoNumero já é exatamente o próximo — não escreve")
    void atualizarSequencia_valorIgual_idempotente() {
        NfeSequencia existente = new NfeSequencia();
        existente.setCnpjEmitente(CNPJ);
        existente.setSerie(SERIE);
        existente.setUltimoNumero(100); // próximo já seria 101
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(existente);

        AtualizacaoSequenciaResultado resultado = service.atualizarSequencia(CNPJ, SERIE, 101);

        verify(mapper, never()).inserir(any());
        verify(mapper, never()).atualizarNumero(any());
        assertFalse(resultado.aplicado());
        assertEquals(101, resultado.proximoNumeroAnterior());
        assertEquals(101, resultado.proximoNumeroAtual());
    }

    @Test
    @DisplayName("atualizarSequencia avança quando proximoNumero é maior que o registrado")
    void atualizarSequencia_valorMaior_avanca() {
        NfeSequencia existente = new NfeSequencia();
        existente.setCnpjEmitente(CNPJ);
        existente.setSerie(SERIE);
        existente.setUltimoNumero(100);
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(existente);

        AtualizacaoSequenciaResultado resultado = service.atualizarSequencia(CNPJ, SERIE, 500);

        verify(mapper).atualizarNumero(argThat(seq -> seq.getUltimoNumero() == 499));
        assertTrue(resultado.aplicado());
        assertEquals(101, resultado.proximoNumeroAnterior());
        assertEquals(500, resultado.proximoNumeroAtual());
    }

    @Test
    @DisplayName("atualizarSequencia rejeita regressão — proximoNumero menor que o já registrado nunca escreve")
    void atualizarSequencia_valorMenor_rejeitado() {
        NfeSequencia existente = new NfeSequencia();
        existente.setCnpjEmitente(CNPJ);
        existente.setSerie(SERIE);
        existente.setUltimoNumero(100); // próximo já seria 101

        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(existente);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.atualizarSequencia(CNPJ, SERIE, 90));
        assertTrue(ex.getMessage().contains("101"));
        assertTrue(ex.getMessage().contains("90"));

        verify(mapper, never()).inserir(any());
        verify(mapper, never()).atualizarNumero(any());
    }

    @Test
    @DisplayName("atualizarSequencia com criação concorrente (chave duplicada) relê e reaplica a validação")
    void atualizarSequencia_criacaoConcorrente_releEReaplica() {
        NfeSequencia jaCriadaPelaOutraTransacao = new NfeSequencia();
        jaCriadaPelaOutraTransacao.setCnpjEmitente(CNPJ);
        jaCriadaPelaOutraTransacao.setSerie(SERIE);
        jaCriadaPelaOutraTransacao.setUltimoNumero(100);

        when(mapper.buscarParaAtualizar(CNPJ, SERIE))
                .thenReturn(null)                          // 1ª leitura: ainda não existe
                .thenReturn(jaCriadaPelaOutraTransacao);    // releitura após duplicate key
        doThrow(new org.springframework.dao.DuplicateKeyException("uk_emitente_serie"))
                .when(mapper).inserir(any());

        AtualizacaoSequenciaResultado resultado = service.atualizarSequencia(CNPJ, SERIE, 101);

        assertFalse(resultado.aplicado(), "outra transação já deixou no valor exato — idempotente");
        assertEquals(101, resultado.proximoNumeroAtual());
    }

    @Test
    @DisplayName("atualizarSequencia: releitura null após chave duplicada falha explicitamente, nunca NPE")
    void atualizarSequencia_criacaoConcorrente_releituraNulaFalhaExplicitamente() {
        // Cenário defensivo (não deveria ocorrer na prática — DuplicateKeyException implica que
        // a linha existe): a releitura pós-conflito retorna null de qualquer forma. Precisa
        // falhar com mensagem clara, nunca deixar aplicarOuValidar() estourar NPE em seq.getUltimoNumero().
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(null);
        doThrow(new org.springframework.dao.DuplicateKeyException("uk_emitente_serie"))
                .when(mapper).inserir(any());

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.atualizarSequencia(CNPJ, SERIE, 101));
        assertTrue(ex.getMessage().contains("inconsistente"));
    }

    @Test
    @DisplayName("atualizarSequencia rejeita proximoNumero menor que 1 antes de tocar o mapper")
    void atualizarSequencia_proximoNumeroInvalido_rejeitado() {
        assertThrows(IllegalArgumentException.class, () -> service.atualizarSequencia(CNPJ, SERIE, 0));
        assertThrows(IllegalArgumentException.class, () -> service.atualizarSequencia(CNPJ, SERIE, -5));
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("atualizarSequencia rejeita CNPJ/série nulos ou vazios antes de tocar o mapper")
    void atualizarSequencia_cnpjOuSerieInvalidos_rejeitados() {
        assertThrows(IllegalArgumentException.class, () -> service.atualizarSequencia(null, SERIE, 101));
        assertThrows(IllegalArgumentException.class, () -> service.atualizarSequencia(CNPJ, "  ", 101));
        verifyNoInteractions(mapper);
    }

    // -------------------------------------------------------------------------
    // Gate 1 — ciclo do nNF (NfeEmissaoService): peek sem persistir, gate, consumo terminal
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("buscarOuCriarParaAtualizar cria a sequência (ultimoNumero=0) quando ainda não existe, sem consumir número nenhum")
    void buscarOuCriarParaAtualizar_sequenciaInexistente_criaComZero() {
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(null);

        NfeSequencia seq = service.buscarOuCriarParaAtualizar(CNPJ, SERIE);

        assertEquals(0, seq.getUltimoNumero());
        verify(mapper).inserir(argThat(s -> s.getUltimoNumero() == 0));
    }

    @Test
    @DisplayName("buscarOuCriarParaAtualizar devolve a sequência existente sem tocar o mapper de escrita")
    void buscarOuCriarParaAtualizar_sequenciaExistente_devolveSemEscrever() {
        NfeSequencia existente = new NfeSequencia();
        existente.setCnpjEmitente(CNPJ);
        existente.setSerie(SERIE);
        existente.setUltimoNumero(7);
        existente.setEmissaoAtivaId(501L);
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(existente);

        NfeSequencia seq = service.buscarOuCriarParaAtualizar(CNPJ, SERIE);

        assertEquals(7, seq.getUltimoNumero());
        assertEquals(501L, seq.getEmissaoAtivaId());
        verify(mapper, never()).inserir(any());
        verify(mapper, never()).atualizarNumero(any());
    }

    @Test
    @DisplayName("peekProximoNumero devolve ultimoNumero+1 sem persistir o incremento — diferente de proximoNumero()")
    void peekProximoNumero_naoPersisteIncremento() {
        NfeSequencia existente = new NfeSequencia();
        existente.setCnpjEmitente(CNPJ);
        existente.setSerie(SERIE);
        existente.setUltimoNumero(9);
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(existente);

        int candidato = service.peekProximoNumero(CNPJ, SERIE);

        assertEquals(10, candidato);
        verify(mapper, never()).atualizarNumero(any());
        verify(mapper, never()).inserir(any());
    }

    @Test
    @DisplayName("ocuparGate e liberarGate delegam direto ao mapper")
    void ocuparELiberarGate_delegamAoMapper() {
        service.ocuparGate(CNPJ, SERIE, 501L);
        service.liberarGate(CNPJ, SERIE);

        verify(mapper).ocuparGate(CNPJ, SERIE, 501L);
        verify(mapper).liberarGate(CNPJ, SERIE);
    }

    @Test
    @DisplayName("consumirNumero avança ultimoNumero quando o número bate exatamente com o esperado")
    void consumirNumero_numeroEsperado_avanca() {
        NfeSequencia existente = new NfeSequencia();
        existente.setCnpjEmitente(CNPJ);
        existente.setSerie(SERIE);
        existente.setUltimoNumero(4);
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(existente);

        service.consumirNumero(CNPJ, SERIE, 5);

        verify(mapper).atualizarNumero(argThat(s -> s.getUltimoNumero() == 5));
    }

    @Test
    @DisplayName("consumirNumero com número fora do esperado falha explicitamente — proteção contra desvio da máquina de estados")
    void consumirNumero_numeroInesperado_lancaIllegalState() {
        NfeSequencia existente = new NfeSequencia();
        existente.setCnpjEmitente(CNPJ);
        existente.setSerie(SERIE);
        existente.setUltimoNumero(4);
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(existente);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.consumirNumero(CNPJ, SERIE, 7));

        assertTrue(ex.getMessage().contains("desvio"));
        verify(mapper, never()).atualizarNumero(any());
    }

    @Test
    @DisplayName("consumirNumero em sequência inexistente falha explicitamente")
    void consumirNumero_sequenciaInexistente_lancaIllegalState() {
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(null);

        assertThrows(IllegalStateException.class, () -> service.consumirNumero(CNPJ, SERIE, 1));
        verify(mapper, never()).atualizarNumero(any());
    }

    // -------------------------------------------------------------------------
    // P0-1 (07-08-2026, hardening pós-banca) — vetor 1: atualizarSequencia() nunca avança
    // ultimo_numero enquanto a linha tem emissaoAtivaId != null (gate do Gate 1 ocupado).
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("buscarSeExistirParaAtualizar devolve null quando a sequência não existe, sem criar nada")
    void buscarSeExistirParaAtualizar_inexistente_devolveNullSemCriar() {
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(null);

        NfeSequencia resultado = service.buscarSeExistirParaAtualizar(CNPJ, SERIE);

        assertNull(resultado);
        verify(mapper, never()).inserir(any());
    }

    @Test
    @DisplayName("buscarSeExistirParaAtualizar devolve a linha existente (com emissaoAtivaId) sem escrever")
    void buscarSeExistirParaAtualizar_existente_devolveComGate() {
        NfeSequencia existente = new NfeSequencia();
        existente.setCnpjEmitente(CNPJ);
        existente.setSerie(SERIE);
        existente.setUltimoNumero(4);
        existente.setEmissaoAtivaId(501L);
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(existente);

        NfeSequencia resultado = service.buscarSeExistirParaAtualizar(CNPJ, SERIE);

        assertEquals(501L, resultado.getEmissaoAtivaId());
        verify(mapper, never()).inserir(any());
        verify(mapper, never()).atualizarNumero(any());
    }

    @Test
    @DisplayName("atualizarSequencia com avanço real e gate ocupado lança SequenciaComEmissaoAtivaException, ultimo_numero não muda")
    void atualizarSequencia_avancoComGateOcupado_lancaSequenciaComEmissaoAtiva() {
        NfeSequencia existente = new NfeSequencia();
        existente.setCnpjEmitente(CNPJ);
        existente.setSerie(SERIE);
        existente.setUltimoNumero(4);
        existente.setEmissaoAtivaId(501L);
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(existente);

        SequenciaComEmissaoAtivaException ex = assertThrows(SequenciaComEmissaoAtivaException.class,
                () -> service.atualizarSequencia(CNPJ, SERIE, 6)); // proximoNumero=6 -> alvo=5 > atual=4

        assertEquals(CNPJ, ex.getCnpjEmitente());
        assertEquals(SERIE, ex.getSerie());
        assertEquals(501L, ex.getEmissaoAtivaId());
        verify(mapper, never()).atualizarNumero(any());
    }

    @Test
    @DisplayName("atualizarSequencia idempotente (mesmo valor) é permitida mesmo com gate ocupado — regra 1")
    void atualizarSequencia_idempotenteComGateOcupado_naoBloqueia() {
        NfeSequencia existente = new NfeSequencia();
        existente.setCnpjEmitente(CNPJ);
        existente.setSerie(SERIE);
        existente.setUltimoNumero(4);
        existente.setEmissaoAtivaId(501L);
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(existente);

        AtualizacaoSequenciaResultado resultado = service.atualizarSequencia(CNPJ, SERIE, 5); // alvo=4 == atual=4

        assertFalse(resultado.aplicado());
        verify(mapper, never()).atualizarNumero(any());
    }

    @Test
    @DisplayName("atualizarSequencia com avanço real e gate livre continua funcionando normalmente")
    void atualizarSequencia_avancoComGateLivre_aplicaNormalmente() {
        NfeSequencia existente = new NfeSequencia();
        existente.setCnpjEmitente(CNPJ);
        existente.setSerie(SERIE);
        existente.setUltimoNumero(4);
        existente.setEmissaoAtivaId(null);
        when(mapper.buscarParaAtualizar(CNPJ, SERIE)).thenReturn(existente);

        AtualizacaoSequenciaResultado resultado = service.atualizarSequencia(CNPJ, SERIE, 6);

        assertTrue(resultado.aplicado());
        verify(mapper).atualizarNumero(argThat(s -> s.getUltimoNumero() == 5));
    }
}
