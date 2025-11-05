package br.com.borurio.fiscal.service;

/**
 * =============================================================================
 * SERVIÇO FISCAL: NfeTransmitService
 * -----------------------------------------------------------------------------
 * Responsável pela transmissão de NF-e (Nota Fiscal Eletrônica) para o
 * WebService da SEFAZ-SP (versão 4.00 - SOAP/XMLDSig).
 *
 * Define o contrato para implementações concretas (NfeTransmitServiceImpl),
 * garantindo compatibilidade entre ambientes DEV, HOM e PRD.
 * -----------------------------------------------------------------------------
 * Padrões aplicados:
 *  - Java 17 / Spring Boot 3.3.x
 *  - Comunicação SOAP 1.2 / HTTPS (TLS 1.2+)
 *  - Assinatura digital XMLDSig conforme ICP-Brasil
 *  - ISP (Interface Segregation Principle)
 * -----------------------------------------------------------------------------
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Módulo: borurio-fiscal
 * Versão: 1.0.0
 * =============================================================================
 */
public interface NfeTransmitService {

    /**
     * Transmite o XML assinado da NF-e para o WebService SEFAZ-SP.
     *
     * @param xmlAssinado  Conteúdo integral do XML assinado digitalmente.
     * @param cnpjEmitente CNPJ do emitente da NF-e.
     * @return XML SOAP de resposta retornado pela SEFAZ-SP.
     */
    String transmitirXml(String xmlAssinado, String cnpjEmitente);

    /**
     * Sobrecarga simplificada — usada em testes locais e ambiente de homologação.
     * Internamente delega para transmitirXml() com um CNPJ genérico.
     *
     * @param xmlAssinado Conteúdo integral do XML assinado digitalmente.
     * @return XML SOAP de resposta retornado pela SEFAZ-SP.
     */
    default String transmitir(String xmlAssinado) {
        return transmitirXml(xmlAssinado, "00000000000000"); // CNPJ genérico (mock)
    }

    /**
     * Consulta o status de disponibilidade do serviço SEFAZ-SP.
     *
     * Utilizado para monitoramento e rotinas automáticas de health check fiscal.
     *
     * @return Mensagem textual com o status atual do serviço SEFAZ-SP.
     */
    String consultarStatus();
}
