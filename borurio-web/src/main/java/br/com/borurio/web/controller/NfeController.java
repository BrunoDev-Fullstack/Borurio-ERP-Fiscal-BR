package br.com.borurio.web.controller;

import br.com.borurio.fiscal.service.NfeTransmitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * =============================================================================
 * CONTROLADOR NF-E (BORURIO ERP FISCAL BR)
 * -----------------------------------------------------------------------------
 * Responsável por expor endpoints REST para operações fiscais da NF-e.
 *
 * Regras importantes:
 * - O CNPJ do emitente NÃO é recebido por header.
 * - O CNPJ oficial é extraído exclusivamente do certificado digital A1.
 * - O XML é validado contra o certificado antes do envio à SEFAZ.
 *
 * Arquitetura:
 * Controller → Service → Integração SEFAZ (SOAP)
 *
 * Compatibilidade:
 * - Java 17
 * - Spring Boot 3.3.x
 * =============================================================================
 */
@Slf4j
@RestController
@RequestMapping("/nfe")
@RequiredArgsConstructor
public class NfeController {

    private final NfeTransmitService nfeTransmitService;

    // =========================================================================
    // ENDPOINT: Envio de NF-e
    // =========================================================================
    @PostMapping(
            value = "/enviar",
            consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_XML_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse> enviarNfe(@RequestBody String xmlAssinado) {

        log.info("Requisição recebida | Operação: Envio NF-e");

        if (xmlAssinado == null || xmlAssinado.isBlank()) {
            log.warn("XML vazio ou ausente na requisição");
            return buildResponse(
                    HttpStatus.BAD_REQUEST,
                    "O corpo da requisição (XML) não pode estar vazio.",
                    null
            );
        }

        try {
            String respostaSefaz = nfeTransmitService.transmitirXml(xmlAssinado);

            if (respostaSefaz == null) {
                log.error("Falha ao comunicar com a SEFAZ-SP");
                return buildResponse(
                        HttpStatus.BAD_GATEWAY,
                        "Falha ao comunicar com a SEFAZ-SP",
                        null
                );
            }

            log.info("NF-e transmitida com sucesso");
            return buildResponse(
                    HttpStatus.OK,
                    "NF-e enviada com sucesso à SEFAZ-SP",
                    respostaSefaz
            );

        } catch (IllegalArgumentException ex) {
            log.error("Erro de validação fiscal: {}", ex.getMessage());
            return buildResponse(
                    HttpStatus.BAD_REQUEST,
                    "Erro de validação fiscal: " + ex.getMessage(),
                    null
            );

        } catch (Exception ex) {
            log.error("Erro interno ao transmitir NF-e", ex);
            return buildResponse(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Erro interno ao transmitir NF-e",
                    null
            );
        }
    }

    // =========================================================================
    // ENDPOINT: Status da SEFAZ
    // =========================================================================
    @GetMapping("/status")
    public ResponseEntity<ApiResponse> status() {
        log.info("Verificando status do serviço NF-e (SEFAZ-SP)");
        try {
            String status = nfeTransmitService.consultarStatus();
            return buildResponse(HttpStatus.OK, status, null);
        } catch (Exception ex) {
            log.error("Falha ao consultar status da SEFAZ-SP", ex);
            return buildResponse(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Serviço NF-e indisponível",
                    null
            );
        }
    }

    // =========================================================================
    // MÉTODOS AUXILIARES
    // =========================================================================
    private ResponseEntity<ApiResponse> buildResponse(
            HttpStatus status,
            String message,
            Object data
    ) {
        return ResponseEntity
                .status(status)
                .body(new ApiResponse(status.value(), message, data));
    }

    /**
     * Estrutura padrão de resposta JSON (corporativo Borurio).
     */
    public record ApiResponse(int code, String message, Object data) {}
}
