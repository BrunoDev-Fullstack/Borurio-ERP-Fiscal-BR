package br.com.borurio.fiscal.service;

/**
 * =============================================================================
 * SERVIÇO FISCAL — NfeTransmitService
 * =============================================================================
 * Contrato oficial para comunicação com os WebServices NF-e da SEFAZ-SP
 * utilizando:
 *
 *   • SOAP 1.2 (WS-Autorização e WS-Status)
 *   • HTTPS com mTLS (Certificado A1)
 *   • XMLDSig para assinatura digital
 *   • Padrão NF-e 4.00
 *
 * Responsabilidades:
 *   - Transmitir NF-e assinada ao WebService NFeAutorizacao4
 *   - Consultar status do serviço junto ao NFeStatusServico4
 *   - Fornecer API segura para a camada de negócio (NfeServiceImpl)
 *
 * Padrões aplicados:
 *   - Java 17 / Spring Boot 3.3.x
 *   - Interface → Implementação (CLEAN Architecture)
 *   - DevSecOps (segurança, rastreabilidade e segregação de responsabilidade)
 *
 * =============================================================================
 * Autor: Bruno Ribeiro — Fullstack / DevSecOps
 * Módulo: borurio-fiscal
 * Revisão: 03/12/2025
 * =============================================================================
 */
public interface NfeTransmitService {

    /**
     * Realiza a transmissão REAL de uma NF-e assinada digitalmente para o
     * WebService NFeAutorizacao4 da SEFAZ-SP (SOAP 1.2 + mTLS).
     *
     * @param xmlAssinado  XML da NF-e já assinado (versão 4.00)
     * @param cnpjEmitente CNPJ responsável pelo envio
     * @return Envelope SOAP completo retornado pela SEFAZ
     */
    String transmitirXml(String xmlAssinado, String cnpjEmitente);

    /**
     * Atalho seguro para testes locais e validações de infraestrutura.
     * Usa CNPJ genérico "00000000000000".
     *
     * @param xmlAssinado XML da NF-e já assinado.
     * @return Resposta do WebService SEFAZ.
     */
    default String transmitir(String xmlAssinado) {
        return transmitirXml(xmlAssinado, "00000000000000");
    }

    /**
     * Consulta o Status do Serviço junto ao WebService NFeStatusServico4.
     *
     * @param cnpjEmitente CNPJ responsável pela consulta
     * @return Envelope SOAP retornado pela SEFAZ
     */
    String consultarStatusServico(String cnpjEmitente);

}
