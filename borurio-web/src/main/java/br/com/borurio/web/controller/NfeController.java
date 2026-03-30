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
 * CONTROLADOR NF-E (BORURIO FISCAL)
 * =============================================================================
 */
@Slf4j
@RestController
@RequestMapping("/nfe")
@RequiredArgsConstructor
public class NfeController {

    private final NfeTransmitService nfeTransmitService;

    // =========================================================================
    // ENVIO NF-E
    // =========================================================================

    @PostMapping(
            value = "/enviar",
            consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_XML_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse> enviarNfe(
            @RequestBody String xmlAssinado,
            @RequestHeader(value = "CNPJ-Emitente") String cnpjEmitente) {

        log.info("Requisição recebida | Operação: Envio NF-e | CNPJ: {}", cnpjEmitente);

        if (xmlAssinado == null || xmlAssinado.isBlank()) {
            return buildResponse(HttpStatus.BAD_REQUEST,
                    "O XML da NF-e não pode estar vazio.", null);
        }

        try {

            // CORREÇÃO: nova assinatura do método
            String resposta = nfeTransmitService.transmitirXml(
                    xmlAssinado,
                    cnpjEmitente,
                    "SP",   // UF
                    2       // ambiente homologação
            );

            if (resposta == null) {
                return buildResponse(HttpStatus.BAD_GATEWAY,
                        "Falha ao comunicar com a SEFAZ-SP.", null);
            }

            return buildResponse(HttpStatus.OK,
                    "NF-e transmitida com sucesso.", resposta);

        } catch (IllegalArgumentException ex) {

            log.error("Erro de validação: {}", ex.getMessage());

            return buildResponse(HttpStatus.BAD_REQUEST,
                    ex.getMessage(), null);

        } catch (Exception ex) {

            log.error("Erro interno ao transmitir NF-e", ex);

            return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Erro interno ao transmitir NF-e.", null);
        }
    }

    // =========================================================================
    // STATUS SEFAZ
    // =========================================================================

    @GetMapping("/status")
    public ResponseEntity<ApiResponse> status() {

        log.info("Verificando status do serviço NF-e");

        try {

            // CORREÇÃO: nova assinatura
            String status = nfeTransmitService.consultarStatus("SP", 2);

            return buildResponse(HttpStatus.OK, status, null);

        } catch (Exception ex) {

            log.error("Erro ao consultar status SEFAZ", ex);

            return buildResponse(HttpStatus.SERVICE_UNAVAILABLE,
                    "Serviço SEFAZ indisponível.", null);
        }
    }

    // =========================================================================
    // RESPONSE PADRÃO
    // =========================================================================

    private ResponseEntity<ApiResponse> buildResponse(HttpStatus status, String message, Object data) {

        return ResponseEntity
                .status(status)
                .body(new ApiResponse(status.value(), message, data));
    }

    private record ApiResponse(int code, String message, Object data) {}
}