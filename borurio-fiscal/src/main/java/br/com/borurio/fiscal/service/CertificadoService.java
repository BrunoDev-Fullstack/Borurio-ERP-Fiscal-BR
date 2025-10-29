package br.com.borurio.fiscal.service;

import javax.net.ssl.SSLContext;

/**
 * =============================================================================
 * INTERFACE: CertificadoService
 * -----------------------------------------------------------------------------
 * Responsável por definir o contrato para o gerenciamento e obtenção do
 * certificado digital A1 (arquivo .pfx) utilizado na comunicação segura
 * com a SEFAZ-SP (NF-e 4.00).
 *
 * Perfis de implementação:
 *   - dev / hom: modo simulado (mock SEFAZ, sem carga real de certificado)
 *   - prd: modo real (carrega o certificado digital A1 e inicializa SSLContext)
 *
 * Utilização:
 *   Implementações concretas devem garantir que o SSLContext esteja
 *   devidamente configurado para autenticação mútua via TLS 1.2+.
 *
 * Autor: Bruno Ribeiro – Desenvolvedor Fullstack / DevSecOps
 * Projeto: Borurio ERP Fiscal BR
 * Data: 29/10/2025
 * =============================================================================
 */
public interface CertificadoService {

    /**
     * Retorna o contexto SSL configurado com base no certificado A1.
     * Deve ser utilizado para conexões seguras com a SEFAZ-SP.
     *
     * Em ambientes de desenvolvimento ou homologação (mock SEFAZ),
     * este método pode retornar null.
     *
     * @return SSLContext configurado (PRD) ou null (DEV/HOM)
     * @throws Exception caso ocorra falha no carregamento do certificado.
     */
    SSLContext getSslContext() throws Exception;

    /**
     * Retorna uma descrição textual do estado atual do serviço de certificado.
     * Pode ser utilizada em endpoints de diagnóstico ou logs de auditoria.
     *
     * @return Descrição do status atual (ex.: "Certificado A1 carregado com sucesso").
     */
    String getStatus();
}
