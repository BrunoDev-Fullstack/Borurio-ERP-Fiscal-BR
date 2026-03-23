package br.com.borurio.web.controller.fiscal;

import br.com.borurio.fiscal.service.NfeTransmitService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/fiscal/nfe")
@RequiredArgsConstructor
public class NfeFiscalController {

    private final NfeTransmitService nfeTransmitService;

    // ---------------------------------------------------------------------
    // STATUS SEFAZ
    // ---------------------------------------------------------------------

    @GetMapping("/status")
    public ResponseEntity<ApiResponse> status() {

        log.info("Consulta status SEFAZ");

        try {

            String status = nfeTransmitService.consultarStatus();

            return ResponseEntity.ok(
                    new ApiResponse(200, status, null)
            );

        } catch (Exception e) {

            log.error("Erro ao consultar status SEFAZ", e);

            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new ApiResponse(503, "Serviço SEFAZ indisponível", null));
        }
    }

    // ---------------------------------------------------------------------
    // ENVIO NF-E
    // ---------------------------------------------------------------------

    @PostMapping(
            value = "/enviar",
            consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_XML_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse> enviar(

            @RequestBody String xml,
            @RequestHeader(value = "CNPJ-Emitente", required = true) String cnpj
    ) {

        log.info("Envio NF-e | CNPJ {}", cnpj);

        try {

            String resposta = nfeTransmitService.transmitirXml(xml, cnpj);

            return ResponseEntity.ok(
                    new ApiResponse(200, "NF-e enviada com sucesso", resposta)
            );

        } catch (Exception e) {

            log.error("Erro envio NF-e", e);

            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new ApiResponse(500, "Erro ao transmitir NF-e", null));
        }
    }

    // ---------------------------------------------------------------------

    private record ApiResponse(int code, String message, Object data) {}

}