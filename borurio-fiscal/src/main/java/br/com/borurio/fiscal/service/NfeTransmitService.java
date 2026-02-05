package br.com.borurio.fiscal.service;

/**
 * =============================================================================
 * INTERFACE: NfeTransmitService
 * -----------------------------------------------------------------------------
 * Contrato oficial para processamento e transmissão de NF-e 4.00 à SEFAZ-SP.
 *
 * RESPONSABILIDADES DESTE SERVIÇO:
 * - Receber o XML BASE da NF-e (não assinado)
 * - Validar estrutura e dados obrigatórios
 * - Assinar digitalmente o XML (certificado A1)
 * - Transmitir via SOAP (mTLS) para a SEFAZ-SP
 *
 * REGRAS:
 * - O CNPJ do emitente é extraído EXCLUSIVAMENTE do XML
 * - Nenhum controller fornece CNPJ
 * - O certificado A1 é usado para assinatura e mTLS
 *
 * Arquitetura:
 * Controller (Web) → Fiscal → SEFAZ
 *
 * =============================================================================
 * Autor: Bruno Ribeiro — Fullstack / DevSecOps
 * =============================================================================
 */
public interface NfeTransmitService {

    /**
     * Processa e transmite uma NF-e para a SEFAZ-SP.
     *
     * Fluxo interno esperado:
     * 1. Validação do XML base
     * 2. Assinatura digital da NF-e
     * 3. Transmissão para a SEFAZ-SP
     * 4. Persistência de logs e retorno
     *
     * @param xmlBase XML base da NF-e (modelo 55, versão 4.00)
     * @return XML SOAP de resposta da SEFAZ
     */
    String transmitirXml(String xmlBase);

    /**
     * Consulta o status do serviço SEFAZ-SP.
     *
     * @return Status textual do serviço
     */
    String consultarStatus();
}
