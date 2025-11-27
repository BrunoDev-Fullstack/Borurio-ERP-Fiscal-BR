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
 * =============================================================================
 * Integração entre o módulo WEB e o módulo FISCAL para operações da NF-e.
 *
 * Funções principais:
 *  - Envio de XML assinado para SEFAZ (Hom/Prod)
 *  - Verificação de status do serviço NF-e
 *
 * Boas práticas aplicadas:
 *  - Arquitetura em camadas (Controller → Service → Mapper → SEFAZ)
 *  - Respostas padronizadas {code, message, data}
 *  - Logging orientado a auditoria fiscal
 *  - Segurança compatível com Spring Boot 3.3.x
 *
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Revisão: 26/11/2025
 * =============================================================================
 */
@Slf4j
@RestController
@RequestMapping("/nfe")
@RequiredArgsConstructor
public class NfeController {

    /** Serviço responsável pelo envio real da NF-e para a SEFAZ */
    private final NfeTransmitService nfeTransmitService;

    // =========================================================================
    // ENDPOINT — Envio de NF-e
    // =========================================================================

    /**
     * Endpoint principal para transmissão do XML assinado da NF-e para a SEFAZ-SP.
     *
     * Exemplo de uso:
     *  POST /nfe/enviar
     *  Header: CNPJ-Emitente: 12345678000199
     *  Body: (XML assinado)
     */
    @PostMapping(
            value = "/enviar",
            consumes = {MediaType.APPLICATION_XML_VALUE, MediaType.TEXT_XML_VALUE, MediaType.TEXT_PLAIN_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse> enviarNfe(
            @RequestBody String xmlAssinado,
            @RequestHeader("CNPJ-Emitente") String cnpjEmitente
    ) {

        log.info("[NF-e] Requisição recebida | operação=ENVIO | cnpj={} | tamanhoXML={}",
                cnpjEmitente, xmlAssinado != null ? xmlAssinado.length() : 0);

        // Validação XML
        if (xmlAssinado == null || xmlAssinado.isBlank()) {
            return buildResponse(
                    HttpStatus.BAD_REQUEST,
                    "O corpo da requisição (XML) está vazio.",
                    null
            );
        }

        try {
            String resposta = nfeTransmitService.transmitirXml(xmlAssinado, cnpjEmitente);

            if (resposta == null) {
                return buildResponse(
                        HttpStatus.BAD_GATEWAY,
                        "Falha na comunicação com a SEFAZ-SP.",
                        null
                );
            }

            log.info("[NF-e] Envio concluído com sucesso | cnpj={}", cnpjEmitente);

            return buildResponse(
                    HttpStatus.OK,
                    "NF-e enviada com sucesso à SEFAZ-SP.",
                    resposta
            );

        } catch (IllegalArgumentException e) {
            log.warn("[NF-e] Erro de parâmetros | cnpj={} | motivo={}", cnpjEmitente, e.getMessage());
            return buildResponse(HttpStatus.BAD_REQUEST, e.getMessage(), null);

        } catch (Exception e) {
            log.error("[NF-e] ERRO INTERNO | cnpj={} | erro={}", cnpjEmitente, e.getMessage(), e);
            return buildResponse(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Erro interno durante a transmissão: " + e.getMessage(),
                    null
            );
        }
    }

    // =========================================================================
    // ENDPOINT — Status NF-e
    // =========================================================================

    /**
     * Endpoint para consulta de status do serviço NF-e.
     * No DEV: retorna MOCK.
     * No HOM/PRD: retorna real.
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse> status() {
        log.info("[NF-e] Verificando status do serviço NF-e");

        try {
            String status = nfeTransmitService.consultarStatus();
            return buildResponse(HttpStatus.OK, status, null);

        } catch (Exception e) {
            log.error("[NF-e] Falha ao consultar status | erro={}", e.getMessage(), e);
            return buildResponse(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Serviço NF-e indisponível.",
                    null
            );
        }
    }

    // =========================================================================
    // UTIL — Resposta padrão da API Borurio (JSON)
    // =========================================================================

    private ResponseEntity<ApiResponse> buildResponse(HttpStatus status, String message, Object data) {
        return ResponseEntity.status(status).body(new ApiResponse(status.value(), message, data));
    }

    /** DTO padrão de saída */
    private record ApiResponse(int code, String message, Object data) {}
}
