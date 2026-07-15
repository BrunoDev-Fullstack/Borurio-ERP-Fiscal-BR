package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.entity.NfeSequencia;
import br.com.borurio.fiscal.mapper.NfeSequenciaMapper;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

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
}
