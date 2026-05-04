package br.com.borurio.fiscal.service;

public interface NfeSequenciaService {

    /**
     * Retorna o próximo número de NF-e para o par (cnpjEmitente, serie),
     * incrementando o contador de forma atômica.
     * Se não existir registro para a série, inicializa em 1.
     */
    int proximoNumero(String cnpjEmitente, String serie);
}
