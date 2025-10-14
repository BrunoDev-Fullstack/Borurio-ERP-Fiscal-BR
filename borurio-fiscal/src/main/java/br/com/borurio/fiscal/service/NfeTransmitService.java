package br.com.borurio.fiscal.service;

/**
 * Serviço responsável pela transmissão de NF-e (Nota Fiscal Eletrônica)
 * ao WebService da SEFAZ-SP, versão 4.00.
 *
 * Esta interface define o contrato para componentes de comunicação fiscal,
 * abrangendo envio, auditoria e retentativas automáticas de autorização.
 *
 * Padrões e boas práticas aplicadas:
 * - Java 17 / Spring Boot 3.3.x
 * - ISP (Interface Segregation Principle)
 * - Comunicação SOAP/HTTPS conforme layout NF-e 4.00
 * - Compatível com os Schemas PL009 NT2025
 * - Padrão UTF-8 sem BOM
 *
 * Implementações concretas devem garantir:
 * 1. Assinatura digital (XMLDSig) válida.
 * 2. Envio SOAP seguro via TLS 1.2 ou superior.
 * 3. Tratamento de falhas e auditoria fiscal (logs e reprocessamentos).
 */
public interface NfeTransmitService {

    /**
     * Transmite o XML assinado da NF-e para o WebService SEFAZ-SP.
     *
     * Este método deve:
     * - Enviar o XML da NF-e já assinado digitalmente.
     * - Retornar a resposta SOAP completa da SEFAZ (autorização, rejeição etc.).
     * - Realizar log e persistência da operação em banco de dados fiscal.
     *
     * @param xmlAssinado  Conteúdo completo do XML assinado digitalmente.
     * @param cnpjEmitente CNPJ do emitente da NF-e.
     * @return XML SOAP de resposta retornado pela SEFAZ-SP.
     */
    String transmitirXml(String xmlAssinado, String cnpjEmitente);

    /**
     * Consulta o status do serviço de autorização da SEFAZ.
     *
     * Deve ser usado para validação de disponibilidade antes da transmissão
     * e em rotinas de monitoramento (health check fiscal).
     *
     * @return Mensagem de status do serviço SEFAZ-SP.
     */
    String consultarStatus();
}
