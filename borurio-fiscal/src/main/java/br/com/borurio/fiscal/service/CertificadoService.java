package br.com.borurio.fiscal.service;

import javax.net.ssl.SSLContext;

/**
 * =============================================================================
 * INTERFACE: CertificadoService
 * -----------------------------------------------------------------------------
 * Responsável por gerenciar o certificado digital A1 (PKCS12) utilizado
 * na comunicação segura com a SEFAZ-SP (NF-e 4.00).
 *
 * Importante:
 * - O certificado digital A1 NÃO é fonte de CNPJ da aplicação.
 * - O CNPJ do emitente é extraído do XML da NF-e.
 * - O certificado é utilizado exclusivamente para:
 *   • Autenticação mTLS
 *   • Assinatura e validação criptográfica
 *
 * Autor: Bruno Ribeiro — Fullstack / DevSecOps
 * Projeto: Borurio ERP Fiscal BR
 * =============================================================================
 */
public interface CertificadoService {

    /**
     * Retorna o SSLContext configurado com o certificado A1.
     * Utilizado nas conexões mTLS com a SEFAZ-SP.
     *
     * @return SSLContext configurado ou null se não carregado.
     */
    SSLContext getSslContext();

    /**
     * Retorna o status textual do serviço de certificado.
     *
     * @return Status atual do certificado A1.
     */
    String getStatus();
}
