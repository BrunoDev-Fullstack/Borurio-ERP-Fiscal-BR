package br.com.borurio.fiscal.service;

import javax.net.ssl.SSLContext;

/**
 * Comunicação com a SEFAZ: envio de NF-e, consulta de status e recibo.
 * SOAP 1.2 / TLS 1.2+ / XML assinado (XMLDSig).
 */
public interface NfeTransmitService {

    /**
     * Transmite um lote síncrono contendo uma NF-e já assinada.
     * Envelopa o XML em enviNFe (versao=4.00, indSinc=1) antes de enviar.
     *
     * @param xmlAssinado XML completo da NFe já assinado (inclui Signature)
     * @param cnpjEmitente CNPJ do emitente (usado no log fiscal)
     * @param uf Unidade Federativa sigla (ex: "SP")
     * @param ambiente 1=Produção, 2=Homologação
     * @return XML SOAP de resposta da SEFAZ (retEnviNFe)
     */
    String transmitirXml(String xmlAssinado,
                         String cnpjEmitente,
                         String uf,
                         int ambiente);

    /** Transmite usando SSLContext de empresa específica (Fase 8B). Fallback para cert global se sslContextEmpresa=null. */
    String transmitirXml(String xmlAssinado,
                         String cnpjEmitente,
                         String uf,
                         int ambiente,
                         SSLContext sslContextEmpresa);

    /**
     * Consulta status do serviço SEFAZ para a UF informada.
     *
     * @param uf Unidade Federativa sigla (ex: "SP")
     * @param ambiente 1=Produção, 2=Homologação
     * @return XML SOAP de resposta (cStat esperado: 107 = Serviço em Operação)
     */
    String consultarStatus(String uf, int ambiente);

    /**
     * Consulta o resultado de um lote enviado de forma assíncrona (indSinc=0).
     * Envia consReciNFe para o endpoint NFeRetAutorizacao4.
     *
     * @param nRec Número do recibo retornado pela SEFAZ no enviNFe assíncrono
     * @param uf Unidade Federativa sigla (ex: "SP")
     * @param ambiente 1=Produção, 2=Homologação
     * @return XML SOAP de resposta (retConsReciNFe)
     */
    String consultarRecibo(String nRec, String uf, int ambiente);

    /**
     * Consulta situação de uma NF-e pela chave de acesso (consSitNFe).
     * Endpoint: NFeConsultaProtocolo4.
     *
     * @param chaveNfe Chave de acesso NF-e (44 dígitos numéricos)
     * @param uf Unidade Federativa sigla (ex: "SP")
     * @param ambiente 1=Produção, 2=Homologação
     * @return XML SOAP de resposta SEFAZ (retConsSitNFe) com cStat e xMotivo
     */
    String consultarNfe(String chaveNfe, String uf, int ambiente);
}
