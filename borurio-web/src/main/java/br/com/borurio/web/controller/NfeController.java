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
 * CONTROLADOR NF-E — BORURIO ERP FISCAL BR
 * =============================================================================
 * Camada REST que integra o módulo WEB com o módulo FISCAL (NF-e Real).
 *
 * Funcionalidades:
 *  - Envio de XML assinado para SEFAZ (Autorização)
 *  - Consulta de Status do Serviço NF-e (StatusServico4)
 *
 * =============================================================================
 * Autor: Bruno Ribeiro — Fullstack / DevSecOps
 * Revisão: 03/12/2025
 * =============================================================================
 */
@Slf4j
@RestController
@RequestMapping("/nfe")
@RequiredArgsConstructor
public class NfeController {

    /** Serviço oficial para transmissão e consulta NF-e (módulo fiscal) */
    private final NfeTransmitService nfeTransmitService;

    // =========================================================================
    // ENDPOINT — Envio da NF-e
    // =========================================================================

    /**
     * Envia XML assinado da NF-e para o WebService SEFAZ-SP.
     *
     * @param xmlAssinado    XML assinado (versão 4.00)
     * @param cnpjEmitente   CNPJ emissor
     */
    @PostMapping(
            value = "/enviar",
            consumes = {
                    MediaType.APPLICATION_XML_VALUE,
                    MediaType.TEXT_XML_VALUE,
                    MediaType.TEXT_PLAIN_VALUE
            },
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse> enviarNfe(
            @RequestBody String xmlAssinado,
            @RequestHeader("CNPJ-Emitente") String cnpjEmitente
    ) {

        log.info("[NF-e] Requisição recebida | operação=ENVIO | cnpj={} | tamXML={}",
                cnpjEmitente,
                xmlAssinado != null ? xmlAssinado.length() : 0
        );

        if (xmlAssinado == null || xmlAssinado.isBlank()) {
            return buildResponse(
                    HttpStatus.BAD_REQUEST,
                    "XML assinado não informado.",
                    null
            );
        }

        try {
            String resposta = nfeTransmitService.transmitirXml(xmlAssinado, cnpjEmitente);

            return buildResponse(
                    HttpStatus.OK,
                    "NF-e enviada para SEFAZ-SP com sucesso.",
                    resposta
            );

        } catch (IllegalArgumentException e) {
            return buildResponse(HttpStatus.BAD_REQUEST, e.getMessage(), null);

        } catch (Exception e) {
            log.error("[NF-e] Falha no envio | erro={}", e.getMessage(), e);
            return buildResponse(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Erro interno durante a transmissão: " + e.getMessage(),
                    null
            );
        }
    }

    // =========================================================================
    // ENDPOINT — Consulta de Status do Serviço
    // =========================================================================

    /**
     * Consulta o status do serviço NF-e (SEFAZ-SP).
     *
     * DEV: Mock
     * HOM/PRD: Real
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse> status(
            @RequestHeader(value = "CNPJ-Emitente", required = false, defaultValue = "00000000000000")
            String cnpjEmitente
    ) {
        log.info("[NF-e] Consultando status do serviço NF-e | cnpj={}", cnpjEmitente);

        try {
            // CHAMADA REAL AO CONTRATO
            String resposta = nfeTransmitService.consultarStatusServico(cnpjEmitente);

            return buildResponse(HttpStatus.OK, "Status obtido com sucesso.", resposta);

        } catch (Exception e) {
            log.error("[NF-e] Falha ao consultar status | erro={}", e.getMessage(), e);

            return buildResponse(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Serviço SEFAZ-SP indisponível. " + e.getMessage(),
                    null
            );
        }
    }

    // =========================================================================
    // UTIL — Resposta Padrão Borurio
    // =========================================================================

    private ResponseEntity<ApiResponse> buildResponse(HttpStatus status, String message, Object data) {
        return ResponseEntity.status(status).body(new ApiResponse(status.value(), message, data));
    }

    /** DTO padrão para respostas JSON */
    private record ApiResponse(int code, String message, Object data) {}
}
