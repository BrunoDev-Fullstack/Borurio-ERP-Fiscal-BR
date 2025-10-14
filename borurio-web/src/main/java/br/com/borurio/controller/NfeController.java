package br.com.borurio.controller;

import br.com.borurio.fiscal.service.NfeTransmitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Controlador REST responsável por expor os endpoints fiscais (NF-e).
 *
 * Este controlador integra o módulo Web com o módulo Fiscal (borurio-fiscal),
 * permitindo que a aplicação realize operações de transmissão e consulta
 * de status da SEFAZ-SP para a Nota Fiscal Eletrônica (NF-e versão 4.00).
 *
 * Boas práticas aplicadas:
 * - Arquitetura em camadas (Controller → Service → Mapper)
 * - Padrão RESTful e responses JSON padronizados
 * - Logging estruturado com SLF4J
 * - Tratamento de exceções resiliente
 *
 * Compatibilidade:
 * - Java 17
 * - Spring Boot 3.3.x
 * - Maven 3.9.x
 */
@Slf4j
@RestController
@RequestMapping("/nfe")
@RequiredArgsConstructor
public class NfeController {

    private final NfeTransmitService nfeTransmitService;

    /**
     * Endpoint responsável por transmitir o XML assinado da NF-e
     * para o WebService da SEFAZ-SP (Homologação ou Produção).
     *
     * Exemplo:
     * POST /nfe/enviar
     * Header: CNPJ-Emitente: 12345678000199
     * Body: XML assinado (text/plain ou application/xml)
     *
     * @param xmlAssinado   Conteúdo XML assinado digitalmente.
     * @param cnpjEmitente  CNPJ do emitente (header obrigatório).
     * @return Resposta SOAP retornada pela SEFAZ ou mensagem de erro padronizada.
     */
    @PostMapping(
            value = "/enviar",
            consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_XML_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse> enviarNfe(
            @RequestBody String xmlAssinado,
            @RequestHeader("CNPJ-Emitente") String cnpjEmitente) {

        log.info("Requisição recebida para transmissão NF-e | CNPJ: {}", cnpjEmitente);

        try {
            String respostaSefaz = nfeTransmitService.transmitirXml(xmlAssinado, cnpjEmitente);

            if (respostaSefaz == null) {
                log.warn("Falha ao transmitir NF-e para SEFAZ-SP | CNPJ: {}", cnpjEmitente);
                return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                        .body(new ApiResponse(502, "Falha ao comunicar com a SEFAZ-SP", null));
            }

            log.info("NF-e enviada com sucesso à SEFAZ-SP | CNPJ: {}", cnpjEmitente);
            return ResponseEntity.ok(new ApiResponse(200, "NF-e enviada com sucesso à SEFAZ-SP", respostaSefaz));

        } catch (Exception ex) {
            log.error("Erro interno ao transmitir NF-e | CNPJ: {} | Erro: {}", cnpjEmitente, ex.getMessage(), ex);
            return ResponseEntity.internalServerError()
                    .body(new ApiResponse(500, "Erro interno ao transmitir NF-e: " + ex.getMessage(), null));
        }
    }

    /**
     * Endpoint de verificação de disponibilidade do serviço fiscal (mock SEFAZ-SP).
     *
     * Exemplo:
     * GET /nfe/status
     *
     * @return Status do serviço NF-e (mock ou real).
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse> status() {
        log.info("Verificando status do serviço NF-e (módulo fiscal)");

        try {
            String status = nfeTransmitService.consultarStatus();
            return ResponseEntity.ok(new ApiResponse(200, status, null));
        } catch (Exception e) {
            log.error("Falha ao consultar status da SEFAZ-SP: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiResponse(503, "Serviço NF-e indisponível", null));
        }
    }

    /**
     * Estrutura padrão de resposta JSON.
     * Exemplo: { "code": 200, "message": "OK", "data": { ... } }
     */
    private record ApiResponse(int code, String message, Object data) {}
}
