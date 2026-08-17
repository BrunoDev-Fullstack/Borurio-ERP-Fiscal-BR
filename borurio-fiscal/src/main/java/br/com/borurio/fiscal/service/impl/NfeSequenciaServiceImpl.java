package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.dto.AtualizacaoSequenciaResultado;
import br.com.borurio.fiscal.entity.NfeSequencia;
import br.com.borurio.fiscal.exception.SequenciaComEmissaoAtivaException;
import br.com.borurio.fiscal.mapper.NfeSequenciaMapper;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class NfeSequenciaServiceImpl implements NfeSequenciaService {

    private final NfeSequenciaMapper mapper;

    public NfeSequenciaServiceImpl(NfeSequenciaMapper mapper) {
        this.mapper = mapper;
    }

    // SELECT ... FOR UPDATE garante exclusão mútua na linha;
    // SERIALIZABLE evita fantasmas caso outra thread insira a mesma série simultaneamente.
    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public int proximoNumero(String cnpjEmitente, String serie) {
        NfeSequencia seq = mapper.buscarParaAtualizar(cnpjEmitente, serie);

        if (seq == null) {
            NfeSequencia novo = new NfeSequencia();
            novo.setCnpjEmitente(cnpjEmitente);
            novo.setSerie(serie);
            novo.setUltimoNumero(1);
            mapper.inserir(novo);
            return 1;
        }

        int proximo = seq.getUltimoNumero() + 1;
        seq.setUltimoNumero(proximo);
        mapper.atualizarNumero(seq);
        return proximo;
    }

    // Mesmo lock de linha e mesmo isolamento de proximoNumero() — a leitura abaixo já
    // serializa contra qualquer outra chamada concorrente (inicialização ou alocação normal)
    // no mesmo CNPJ+série.
    //
    // Regra estrita: este método é só pra PRIMEIRA configuração de uma sequência (onboarding).
    // Se já existe uma sequência ativa com valor diferente do informado — maior OU menor —
    // a chamada falha explicitamente. Nunca avança nem regride silenciosamente. Realinhar uma
    // sequência já ativa é decisão administrativa separada, auditável, fora deste método.
    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void inicializarBaseline(String cnpjEmitente, String serie, int ultimoNumeroConhecido) {
        if (cnpjEmitente == null || cnpjEmitente.isBlank()) {
            throw new IllegalArgumentException("cnpjEmitente é obrigatório.");
        }
        if (serie == null || serie.isBlank()) {
            throw new IllegalArgumentException("serie é obrigatória.");
        }
        if (ultimoNumeroConhecido < 0) {
            throw new IllegalArgumentException(
                    "ultimoNumeroConhecido não pode ser negativo: " + ultimoNumeroConhecido);
        }

        NfeSequencia seq = mapper.buscarParaAtualizar(cnpjEmitente, serie);

        if (seq == null) {
            NfeSequencia novo = new NfeSequencia();
            novo.setCnpjEmitente(cnpjEmitente);
            novo.setSerie(serie);
            novo.setUltimoNumero(ultimoNumeroConhecido);
            mapper.inserir(novo);
            return;
        }

        if (seq.getUltimoNumero() == ultimoNumeroConhecido) {
            return; // idempotente — já está exatamente nesse valor
        }

        throw new IllegalStateException(
                "Não é possível inicializar CNPJ=" + cnpjEmitente + " série=" + serie
                        + " com baseline " + ultimoNumeroConhecido + ": a sequência já existe com valor "
                        + seq.getUltimoNumero() + ". inicializarBaseline() só cobre a primeira configuração "
                        + "de uma sequência nova — realinhar uma sequência já ativa é operação administrativa "
                        + "separada, auditável, e não é feita por este método.");
    }

    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public AtualizacaoSequenciaResultado atualizarSequencia(String cnpjEmitente, String serie, int proximoNumero) {
        if (cnpjEmitente == null || cnpjEmitente.isBlank()) {
            throw new IllegalArgumentException("cnpjEmitente é obrigatório.");
        }
        if (serie == null || serie.isBlank()) {
            throw new IllegalArgumentException("serie é obrigatória.");
        }
        if (proximoNumero < 1) {
            throw new IllegalArgumentException("proximoNumero deve ser maior ou igual a 1: " + proximoNumero);
        }

        int ultimoNumeroAlvo = proximoNumero - 1;

        NfeSequencia seq = mapper.buscarParaAtualizar(cnpjEmitente, serie);

        if (seq == null) {
            NfeSequencia novo = new NfeSequencia();
            novo.setCnpjEmitente(cnpjEmitente);
            novo.setSerie(serie);
            novo.setUltimoNumero(ultimoNumeroAlvo);
            try {
                mapper.inserir(novo);
            } catch (DuplicateKeyException e) {
                // Defesa em profundidade: duas chamadas concorrentes tentando criar a mesma
                // sequência pela primeira vez ao mesmo tempo. A constraint uk_emitente_serie
                // (V011) garante que só uma vence o INSERT — a outra relê o registro já
                // criado (agora bloqueado por FOR UPDATE, pois estamos em SERIALIZABLE) e
                // reaplica a mesma validação abaixo, em vez de vazar a exceção de SQL.
                seq = mapper.buscarParaAtualizar(cnpjEmitente, serie);
                if (seq == null) {
                    // Nunca deveria acontecer: uma DuplicateKeyException implica que a linha
                    // existe. Se ainda assim vier null, falha explícita em vez de NPE silencioso
                    // em aplicarOuValidar() — indica corrupção de estado ou bug em outra camada.
                    throw new IllegalStateException(
                            "Chave duplicada relatada para CNPJ=" + cnpjEmitente + " série=" + serie
                                    + ", mas releitura não encontrou o registro. Estado inconsistente.");
                }
                return aplicarOuValidar(seq, cnpjEmitente, serie, ultimoNumeroAlvo);
            }
            return new AtualizacaoSequenciaResultado(
                    cnpjEmitente, serie, null, proximoNumero, true, LocalDateTime.now());
        }

        return aplicarOuValidar(seq, cnpjEmitente, serie, ultimoNumeroAlvo);
    }

    private AtualizacaoSequenciaResultado aplicarOuValidar(NfeSequencia seq, String cnpjEmitente,
                                                            String serie, int ultimoNumeroAlvo) {
        int ultimoNumeroAtual = seq.getUltimoNumero();

        if (ultimoNumeroAlvo == ultimoNumeroAtual) {
            return new AtualizacaoSequenciaResultado(
                    cnpjEmitente, serie, ultimoNumeroAtual + 1, ultimoNumeroAtual + 1, false, LocalDateTime.now());
        }

        if (ultimoNumeroAlvo < ultimoNumeroAtual) {
            throw new IllegalStateException(
                    "Não é possível atualizar CNPJ=" + cnpjEmitente + " série=" + serie
                            + " para próximo número " + (ultimoNumeroAlvo + 1)
                            + ": o sequenciador já está em " + (ultimoNumeroAtual + 1)
                            + ". Regressão de numeração não é permitida.");
        }

        // Gate 1 / P0-1 (07-08-2026, hardening pós-banca): uma sincronização externa nunca pode
        // avançar ultimo_numero enquanto existe um ciclo fiscal em voo (emissao_ativa_id não
        // nulo) para este CNPJ+série — faria nfe_sequencia divergir do número que a nfe_emissao
        // ativa está prestes a consumir, e uma autorização real da SEFAZ chegaria sem conseguir
        // ser consolidada (consumirNumero() rejeitaria por mismatch). Só bloqueia quando há
        // avanço de fato — o branch de idempotência acima já retornou antes de chegar aqui.
        if (seq.getEmissaoAtivaId() != null) {
            throw new SequenciaComEmissaoAtivaException(cnpjEmitente, serie, seq.getEmissaoAtivaId());
        }

        seq.setUltimoNumero(ultimoNumeroAlvo);
        mapper.atualizarNumero(seq);
        return new AtualizacaoSequenciaResultado(
                cnpjEmitente, serie, ultimoNumeroAtual + 1, ultimoNumeroAlvo + 1, true, LocalDateTime.now());
    }

    // -------------------------------------------------------------------------
    // Gate 1 — ciclo do nNF (NfeEmissaoService)
    // -------------------------------------------------------------------------

    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public NfeSequencia buscarOuCriarParaAtualizar(String cnpjEmitente, String serie) {
        if (cnpjEmitente == null || cnpjEmitente.isBlank()) {
            throw new IllegalArgumentException("cnpjEmitente é obrigatório.");
        }
        if (serie == null || serie.isBlank()) {
            throw new IllegalArgumentException("serie é obrigatória.");
        }

        NfeSequencia seq = mapper.buscarParaAtualizar(cnpjEmitente, serie);
        if (seq != null) {
            return seq;
        }

        NfeSequencia novo = new NfeSequencia();
        novo.setCnpjEmitente(cnpjEmitente);
        novo.setSerie(serie);
        novo.setUltimoNumero(0);
        mapper.inserir(novo);
        return novo;
    }

    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public NfeSequencia buscarSeExistirParaAtualizar(String cnpjEmitente, String serie) {
        if (cnpjEmitente == null || cnpjEmitente.isBlank()) {
            throw new IllegalArgumentException("cnpjEmitente é obrigatório.");
        }
        if (serie == null || serie.isBlank()) {
            throw new IllegalArgumentException("serie é obrigatória.");
        }
        return mapper.buscarParaAtualizar(cnpjEmitente, serie);
    }

    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public int peekProximoNumero(String cnpjEmitente, String serie) {
        NfeSequencia seq = buscarOuCriarParaAtualizar(cnpjEmitente, serie);
        return seq.getUltimoNumero() + 1;
    }

    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void ocuparGate(String cnpjEmitente, String serie, Long emissaoAtivaId) {
        mapper.ocuparGate(cnpjEmitente, serie, emissaoAtivaId);
    }

    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void liberarGate(String cnpjEmitente, String serie) {
        mapper.liberarGate(cnpjEmitente, serie);
    }

    @Override
    @Transactional(isolation = Isolation.SERIALIZABLE)
    public void consumirNumero(String cnpjEmitente, String serie, int numero) {
        NfeSequencia seq = mapper.buscarParaAtualizar(cnpjEmitente, serie);
        if (seq == null) {
            throw new IllegalStateException(
                    "Sequência não encontrada para CNPJ=" + cnpjEmitente + " série=" + serie
                            + " ao tentar consumir número=" + numero + ".");
        }
        int esperado = seq.getUltimoNumero() + 1;
        if (numero != esperado) {
            throw new IllegalStateException(
                    "Número a consumir (" + numero + ") não corresponde ao próximo esperado ("
                            + esperado + ") para CNPJ=" + cnpjEmitente + " série=" + serie
                            + " — possível desvio entre a máquina de estados do ciclo do nNF e o contador real.");
        }
        seq.setUltimoNumero(numero);
        mapper.atualizarNumero(seq);
    }

    // -------------------------------------------------------------------------
    // Fase 1 SVC (17-08-2026) — persistência/ciclo de substituição, sem transporte.
    // -------------------------------------------------------------------------

    // Propagation.MANDATORY (não SERIALIZABLE isolado, como os métodos do Gate 1 acima): este
    // método nunca pode ser o início da própria transação — a unidade atômica real é consolidar +
    // reservar nNF da filha + inserir filha + trocar o gate, sempre dentro da transação
    // REPEATABLE_READ já aberta por NfeContingenciaService.abrirContingencia. Chamar isto fora de
    // uma transação externa lança IllegalTransactionStateException do próprio Spring — nunca é
    // possível consolidar o número da NORMAL sem, na mesma transação, também completar o resto.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int consolidarNumeroParaContingencia(String cnpjEmitente, String serie, int numero) {
        NfeSequencia seq = mapper.buscarParaAtualizar(cnpjEmitente, serie);
        if (seq == null) {
            throw new IllegalStateException(
                    "Sequência não encontrada para CNPJ=" + cnpjEmitente + " série=" + serie
                            + " ao tentar consolidar número=" + numero + " para contingência.");
        }
        int esperado = seq.getUltimoNumero() + 1;
        if (numero != esperado) {
            throw new IllegalStateException(
                    "Número a consolidar para contingência (" + numero + ") não corresponde ao "
                            + "próximo esperado (" + esperado + ") para CNPJ=" + cnpjEmitente
                            + " série=" + serie + ".");
        }
        seq.setUltimoNumero(numero);
        mapper.atualizarNumero(seq);
        return seq.getUltimoNumero();
    }

    // Propagation.MANDATORY pelo mesmo motivo de consolidarNumeroParaContingencia — trocar o gate
    // isoladamente, sem ter consolidado/inserido a filha na mesma transação, é exatamente o
    // estado parcial que a Fase 1 existe para impedir.
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void substituirGateParaContingencia(String cnpjEmitente, String serie,
                                                Long emissaoNormalEsperadaId, Long emissaoSvcNovaId) {
        int affected = mapper.substituirGateParaContingencia(
                cnpjEmitente, serie, emissaoNormalEsperadaId, emissaoSvcNovaId);
        if (affected != 1) {
            throw new IllegalStateException(
                    "Troca de gate NORMAL->SVC falhou para CNPJ=" + cnpjEmitente + " série=" + serie
                            + ": esperava emissao_ativa_id=" + emissaoNormalEsperadaId + ", mas o gate já "
                            + "não corresponde (corrida real) — contingência abortada, rollback da transação "
                            + "externa inteira garante que nenhum estado parcial fica visível.");
        }
    }
}
