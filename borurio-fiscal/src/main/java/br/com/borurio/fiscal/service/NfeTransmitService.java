package br.com.borurio.fiscal.service;

/**
 * =============================================================================
 * SERVIÇO FISCAL: NfeTransmitService
 * =============================================================================
 * Responsável pela comunicação com a SEFAZ para:
 *  - Envio de NF-e (lote)
 *  - Consulta de status do serviço
 *  - Futuramente: consulta de recibo
 *
 * Padrões:
 *  - SOAP 1.2
 *  - TLS 1.2+
 *  - XML assinado (XMLDSig)
 * =============================================================================
 */
public interface NfeTransmitService {

    /**
     * Transmite um XML de NF-e já assinado para SEFAZ.
     *
     * @param xmlAssinado XML completo da NF-e (já assinado)
     * @param cnpjEmitente CNPJ do emitente
     * @param uf Unidade Federativa (ex: "SP")
     * @param ambiente 1=Produção, 2=Homologação
     * @return XML SOAP de resposta da SEFAZ
     */
    String transmitirXml(String xmlAssinado,
                         String cnpjEmitente,
                         String uf,
                         int ambiente);

    /**
     * Consulta status do serviço SEFAZ.
     *
     * @param uf Unidade Federativa
     * @param ambiente 1=Produção, 2=Homologação
     * @return XML SOAP de resposta (cStat esperado: 107)
     */
    String consultarStatus(String uf, int ambiente);
}