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
}
