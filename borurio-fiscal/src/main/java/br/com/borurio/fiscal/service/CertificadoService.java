package br.com.borurio.fiscal.service;

import javax.net.ssl.SSLContext;

/**
 * =============================================================================
 * INTERFACE: CertificadoService
 * -----------------------------------------------------------------------------
 * Responsável por definir o contrato para gerenciamento e obtenção do
 * certificado digital A1 (.pfx) utilizado na comunicação segura (mTLS)
 * com os webservices da SEFAZ-SP (NF-e 4.00).
 *
 * Implementações:
 *   - dev / hom : podem retornar SSLContext null (modo simulado)
 *   - prd       : carrega o certificado A1 e inicia SSLContext real
 *
 * Observação:
 *   - Nenhum método desta interface deve lançar Exception checked.
 *     Todas as falhas devem ser tratadas internamente e expostas
 *     via RuntimeException ou via "getStatus()".
 *
 * Autor: Bruno Ribeiro – Desenvolvedor Fullstack / DevSecOps
 * Projeto: Borurio ERP Fiscal BR
 * Revisão: 26/11/2025
 * =============================================================================
 */
public interface CertificadoService {

    /**
     * Retorna o contexto SSL configurado com base no certificado A1.
     *
     * @return SSLContext configurado (PRD) ou null (DEV / HOM sem certificado real)
     */
    SSLContext getSslContext();

    /**
     * Retorna uma descrição do estado atual do serviço, útil para diagnósticos.
     *
     * @return texto amigável indicando o status do certificado.
     */
    String getStatus();
}
