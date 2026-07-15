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
     * Cancela uma NF-e previamente autorizada usando o emitente global (EmitenteProperties).
     * Mantido para o endpoint interno cru (/api/fiscal/nfe/cancelar), sem contexto de pedido.
     *
     * @param req dados do cancelamento (chave, protocolo, justificativa)
     * @return XML de resposta da SEFAZ (retEnvEvento)
     * @throws Exception validação ou falha de comunicação com SEFAZ
     */
    String cancelar(NfeCancelamentoRequest req) throws Exception;

    /**
     * Cancela uma NF-e usando o contexto fiscal explícito da empresa emitente — multi-CNPJ.
     * Se cnpjEmitente/uf/certContexto forem null, cai no comportamento legado
     * (EmitenteProperties global) — usar sempre que o cancelamento estiver vinculado a um
     * pedido/documento fiscal com CNPJ conhecido, pra nunca assinar/transmitir com o
     * certificado errado.
     *
     * @param req dados do cancelamento (chave, protocolo, justificativa)
     * @param cnpjEmitente CNPJ da empresa que emitiu a NF-e (só dígitos); null → usa o global
     * @param uf UF da empresa emitente; null → usa o global
     * @param certContexto certificado da empresa emitente; null → usa o certificado global
     * @return XML de resposta da SEFAZ (retEnvEvento)
     * @throws Exception validação ou falha de comunicação com SEFAZ
     */
    String cancelar(NfeCancelamentoRequest req, String cnpjEmitente, String uf,
                     CertificadoContexto certContexto) throws Exception;
}
