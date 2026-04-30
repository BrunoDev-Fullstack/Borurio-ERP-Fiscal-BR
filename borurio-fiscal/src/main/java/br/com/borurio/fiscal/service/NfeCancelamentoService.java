package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.dto.NfeCancelamentoRequest;

/**
 * Serviço responsável pelo cancelamento de NF-e junto à SEFAZ.
 * Implementa o Evento 110111 (Cancelamento de NF-e) conforme NT 2019.001.
 *
 * Fluxo: buildXML → assinar infEvento → SOAP → recepcaoEvento → log auditoria
 */
public interface NfeCancelamentoService {

    /**
     * Cancela uma NF-e previamente autorizada.
     *
     * @param req dados do cancelamento (chave, protocolo, justificativa)
     * @return XML de resposta da SEFAZ (retEnvEvento)
     * @throws Exception validação ou falha de comunicação com SEFAZ
     */
    String cancelar(NfeCancelamentoRequest req) throws Exception;
}
