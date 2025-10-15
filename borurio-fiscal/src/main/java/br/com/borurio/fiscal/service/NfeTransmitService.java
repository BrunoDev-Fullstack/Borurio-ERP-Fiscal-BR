package br.com.borurio.fiscal.service;

/**
 * Serviço responsável pela transmissão de NF-e (Nota Fiscal Eletrônica)
 * ao WebService da SEFAZ-SP (versão 4.00).
 *
 * Esta interface define o contrato para componentes de comunicação fiscal,
 * abrangendo envio, consulta, auditoria e retentativas automáticas
 * de autorização junto à Secretaria da Fazenda.
 *
 * Padrões e boas práticas aplicadas:
 * <ul>
 *     <li>Java 17 / Spring Boot 3.3.x</li>
 *     <li>ISP — Interface Segregation Principle</li>
 *     <li>Comunicação SOAP/HTTPS conforme layout NF-e 4.00</li>
 *     <li>Compatível com Schemas PL009 / NT2025</li>
 *     <li>Padrão de codificação UTF-8 (sem BOM)</li>
 * </ul>
 *
 * Requisitos das implementações concretas:
 * <ol>
 *     <li>Assinatura digital válida (XMLDSig / padrão ICP-Brasil).</li>
 *     <li>Envio SOAP seguro via TLS 1.2 ou superior.</li>
 *     <li>Tratamento robusto de exceções e auditoria fiscal (logs, persistência e retentativas).</li>
 * </ol>
 *
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Módulo: Fiscal (NF-e / SEFAZ-SP / Auditoria)
 * Versão: 1.0.0
 */
public interface NfeTransmitService {

    /**
     * Transmite o XML assinado da NF-e para o WebService SEFAZ-SP.
     *
     * Este método deve:
     * <ul>
     *     <li>Enviar o XML da NF-e devidamente assinado digitalmente.</li>
     *     <li>Retornar a resposta SOAP completa da SEFAZ (autorização, rejeição ou erro).</li>
     *     <li>Registrar o evento fiscal no log e persistir o resultado no banco de dados.</li>
     * </ul>
     *
     * @param xmlAssinado  Conteúdo integral do XML assinado digitalmente.
     * @param cnpjEmitente CNPJ do emitente da NF-e.
     * @return XML SOAP de resposta retornado pela SEFAZ-SP.
     */
    String transmitirXml(String xmlAssinado, String cnpjEmitente);

    /**
     * Consulta o status de disponibilidade do serviço de autorização da SEFAZ.
     *
     * Este método é utilizado para:
     * <ul>
     *     <li>Verificar a conectividade e operação do serviço SEFAZ antes de transmitir NF-e.</li>
     *     <li>Rotinas automáticas de monitoramento e health check fiscal.</li>
     * </ul>
     *
     * @return Mensagem textual com o status atual do serviço SEFAZ-SP.
     */
    String consultarStatus();
}
