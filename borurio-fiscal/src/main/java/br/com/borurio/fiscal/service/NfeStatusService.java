package br.com.borurio.fiscal.service;

/**
 * =============================================================================
 * SERVIÇO FISCAL — CONSULTA STATUS SEFAZ (NF-e 4.00)
 * =============================================================================
 * Responsável por consultar o Status do Serviço da NF-e junto à SEFAZ-SP,
 * utilizando o WebService NFeStatusServico4.
 *
 * Características:
 * - Compatível com ambientes de Homologação e Produção
 * - Utiliza certificado digital A1 (ICP-Brasil)
 * - Retorna XML bruto conforme padrão SEFAZ
 *
 * Observação:
 * O tratamento de transporte (SSL, SOAP, HTTP) deve ser responsabilidade
 * da camada de infraestrutura/cliente, não deste contrato.
 *
 * Projeto: Borurio ERP Fiscal BR
 * Módulo: borurio-fiscal
 * =============================================================================
 */
public interface NfeStatusService {

    /**
     * Consulta o Status do Serviço da NF-e na SEFAZ-SP.
     *
     * @return XML bruto retornado pela SEFAZ (StatusServicoResponse).
     */
    String consultarStatusServico();
}
