package br.com.borurio.fiscal.service;

/**
 * =============================================================================
 * SERVIÇO: NfeTransmitService
 * =============================================================================
 * Responsável pela transmissão de Notas Fiscais Eletrônicas (NF-e)
 * para os WebServices oficiais da SEFAZ-SP (versão 4.00).
 *
 * Este contrato define as operações essenciais para comunicação fiscal:
 * - Transmissão segura de NF-e assinadas digitalmente;
 * - Consulta de status de autorização e disponibilidade da SEFAZ;
 * - Auditoria e persistência dos eventos fiscais.
 *
 * =============================================================================
 * Padrões técnicos e práticas aplicadas:
 * -----------------------------------------------------------------------------
 * • Java 17 / Spring Boot 3.3.x / UTF-8 sem BOM
 * • ISP — Interface Segregation Principle
 * • Comunicação SOAP/HTTPS (TLS 1.2+) conforme layout NF-e 4.00
 * • Compatível com schemas PL_010b / NT 2025.002 / v1.30
 * • Princípios DevSecOps (segurança, rastreabilidade, automação)
 * =============================================================================
 *
 * Requisitos das implementações concretas:
 * -----------------------------------------------------------------------------
 * 1. Assinatura digital (XMLDSig / ICP-Brasil A1 ou A3).
 * 2. Envio via HTTPS (TLS >= 1.2) e SOAPAction válida.
 * 3. Tratamento robusto de exceções e logs fiscais (auditoria e retentativas).
 * 4. Persistência do log fiscal no banco (tabela nfe_log via MyBatis).
 *
 * =============================================================================
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Módulo: borurio-fiscal
 * Versão: 1.0.0
 * =============================================================================
 */
public interface NfeTransmitService {

    /**
     * Transmite o XML assinado da NF-e para o WebService SEFAZ-SP.
     *
     * Fluxo de responsabilidade:
     * 1. Assinar o XML conforme o certificado digital configurado;
     * 2. Enviar o XML via SOAP para o endpoint de autorização (tpAmb=2 ou 1);
     * 3. Capturar e interpretar o XML de resposta (autorizado, rejeitado, erro);
     * 4. Registrar log fiscal e salvar o resultado no banco de dados.
     *
     * @param xmlAssinado  Conteúdo integral do XML assinado digitalmente.
     * @param cnpjEmitente CNPJ do emitente da NF-e.
     * @return XML SOAP de resposta retornado pela SEFAZ-SP.
     */
    String transmitirXml(String xmlAssinado, String cnpjEmitente);

    /**
     * Consulta o status de disponibilidade do serviço SEFAZ-SP.
     *
     * Este método deve ser utilizado em:
     * - Rotinas de monitoramento fiscal (health check);
     * - Validação antes de transmissões em lote;
     * - Diagnóstico de conectividade ou indisponibilidade.
     *
     * @return Mensagem textual com o status atual do serviço SEFAZ-SP.
     */
    String consultarStatus();
}
