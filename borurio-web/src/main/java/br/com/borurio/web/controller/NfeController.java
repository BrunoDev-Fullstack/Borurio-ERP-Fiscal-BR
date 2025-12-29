package br.com.borurio.web.controller;

import br.com.borurio.fiscal.service.NfeTransmitService;
import br.com.borurio.fiscal.service.NfeStatusService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * =============================================================================
 * CONTROLADOR NF-E — BORURIO FISCAL BR
 * -----------------------------------------------------------------------------
 * Integra o módulo Web com o módulo Fiscal.
 * ============================================================================
 */
@Slf4j
@RestController
@RequestMapping("/nfe")
@RequiredArgsConstructor
public class NfeController {

    private final NfeTransmitService nfeTransmitService;
    private final NfeStatusService nfeStatusService;

    // =========================================================================
    // POST /nfe/enviar — Envio real da NF-e
    // =========================================================================
    @PostMapping(
            value = "/enviar",
            consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_XML_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse> enviarNfe(
            @RequestBody String xmlAssinado,
            @RequestHeader("CNPJ-Emitente") String cnpjEmitente) {

        log.info("Requisição recebida | Envio NF-e | CNPJ: {}", cnpjEmitente);

        if (xmlAssinado == null || xmlAssinado.isBlank()) {
            return buildResponse(HttpStatus.BAD_REQUEST,
                    "O corpo da requisição (XML) não pode estar vazio.", null);
        }

        try {
            String retorno = nfeTransmitService.transmitirXml(xmlAssinado, cnpjEmitente);
            return buildResponse(HttpStatus.OK,
                    "NF-e enviada com sucesso à SEFAZ-SP", retorno);

        } catch (Exception ex) {
            log.error("Erro ao transmitir NF-e: {}", ex.getMessage(), ex);
            return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Falha ao transmitir NF-e: " + ex.getMessage(), null);
        }
    }

    // =========================================================================
    // GET /nfe/status — Status real SEFAZ via módulo fiscal
    // =========================================================================
    @GetMapping("/status")
    public ResponseEntity<ApiResponse> status() {
        try {
            String xmlStatus = nfeStatusService.consultarStatusServico();
            return buildResponse(HttpStatus.OK, "Status SEFAZ obtido com sucesso", xmlStatus);
        } catch (Exception e) {
            log.error("Erro ao consultar status da SEFAZ: {}", e.getMessage(), e);
            return buildResponse(HttpStatus.SERVICE_UNAVAILABLE,
                    "Serviço NF-e indisponível", null);
        }
    }

    // =========================================================================
    // Auxiliares
    // =========================================================================
    private ResponseEntity<ApiResponse> buildResponse(HttpStatus status, String message, Object data) {
        return ResponseEntity.status(status)
                .body(new ApiResponse(status.value(), message, data));
    }

    private record ApiResponse(int code, String message, Object data) {}
}
