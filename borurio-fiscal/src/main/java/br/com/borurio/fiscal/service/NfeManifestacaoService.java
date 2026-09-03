package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.NfeManifestacaoRequest;

public interface NfeManifestacaoService {

    /**
     * Envia evento de Manifestação do Destinatário para a SEFAZ.
     * Eventos suportados: 210200, 210210, 210220, 210240.
     */
    String manifestar(NfeManifestacaoRequest req) throws Exception;
}
