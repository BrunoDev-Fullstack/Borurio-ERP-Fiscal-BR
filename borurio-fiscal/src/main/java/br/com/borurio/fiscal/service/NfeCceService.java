package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.NfeCceRequest;

public interface NfeCceService {

    /**
     * Emite Carta de Correção Eletrônica (CC-e / Evento 110110) para a NF-e informada.
     *
     * @param req DTO com chaveNfe, correcao e sequencia opcional
     * @return XML de resposta SEFAZ (retEnvEvento)
     */
    String corrigir(NfeCceRequest req) throws Exception;
}
