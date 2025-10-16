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
 * -----------------------------------------------------------------------------
 * Responsável por expor endpoints REST para operações fiscais da NF-e.
 * Integra o módulo Web com o módulo Fiscal (borurio-fiscal), permitindo
 * a transmissão de XMLs assinados e a consulta de status da SEFAZ-SP.
 *
 * Padrões aplicados:
 * - Arquitetura em camadas (Controller → Service → Mapper)
 * - Boas práticas RESTful com retorno padronizado {code, message, data}
 * - Logging estruturado via SLF4J
 * - Tratamento resiliente de exceções
 *
 * Compatibilidade:
 * - Java 17
 * - Spring Boot 3.3.x
 * - Maven 3.9.x
 * =============================================================================
 */
@Slf4j
@RestController
@RequestMapping("/nfe")
@RequiredArgsConstructor
public class NfeController {

    /** Serviço responsável pela transmissão e status da NF-e */
    private final NfeTransmitService nfeTransmitService;

    // =========================================================================
    // ENDPOINT: Envio de NF-e
    // =========================================================================

    /**
     * Transmite o XML assinado da NF-e para o WebService da SEFAZ-SP
     * (Homologação ou Produção), retornando a resposta fiscal mock/real.
     *
     * Exemplo:
     * <pre>
     * POST /nfe/enviar
     * Header: CNPJ-Emitente: 12345678000199
     * Content-Type: text/plain ou application/xml
     * Body: XML assinado da NF-e (versão 4.00)
     * </pre>
     *
     * @param xmlAssinado   Conteúdo XML assinado digitalmente.
     * @param cnpjEmitente  CNPJ do emitente (header obrigatório).
     * @return Resposta JSON padronizada contendo o retorno SEFAZ.
     */
    @PostMapping(
            value = "/enviar",
            consumes = {MediaType.TEXT_PLAIN_VALUE, MediaType.APPLICATION_XML_VALUE},
            produces = MediaType.APPLICATION_JSON_VALUE
    )
    public ResponseEntity<ApiResponse> enviarNfe(
            @RequestBody String xmlAssinado,
            @RequestHeader("CNPJ-Emitente") String cnpjEmitente) {

        log.info("Requisição recebida | Operação: Envio NF-e | CNPJ: {}", cnpjEmitente);

        if (xmlAssinado == null || xmlAssinado.isBlank()) {
            log.warn("XML vazio ou ausente | CNPJ: {}", cnpjEmitente);
            return buildResponse(HttpStatus.BAD_REQUEST,
                    "O corpo da requisição (XML) não pode estar vazio.", null);
        }

        try {
            String respostaSefaz = nfeTransmitService.transmitirXml(xmlAssinado, cnpjEmitente);

            if (respostaSefaz == null) {
                log.error("Falha ao transmitir NF-e | CNPJ: {}", cnpjEmitente);
                return buildResponse(HttpStatus.BAD_GATEWAY,
                        "Falha ao comunicar com a SEFAZ-SP", null);
            }

            log.info("NF-e transmitida com sucesso | CNPJ: {}", cnpjEmitente);
            return buildResponse(HttpStatus.OK,
                    "NF-e enviada com sucesso à SEFAZ-SP", respostaSefaz);

        } catch (IllegalArgumentException ex) {
            log.error("Parâmetros inválidos | CNPJ: {} | Erro: {}", cnpjEmitente, ex.getMessage());
            return buildResponse(HttpStatus.BAD_REQUEST,
                    "Parâmetros inválidos: " + ex.getMessage(), null);

        } catch (Exception ex) {
            log.error("Erro interno ao transmitir NF-e | CNPJ: {} | Erro: {}", cnpjEmitente, ex.getMessage(), ex);
            return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Erro interno ao transmitir NF-e: " + ex.getMessage(), null);
        }
    }

    // =========================================================================
    // ENDPOINT: Status da SEFAZ
    // =========================================================================

    /**
     * Verifica a disponibilidade do serviço fiscal (mock SEFAZ-SP).
     *
     * Exemplo:
     * <pre>
     * GET /nfe/status
     * </pre>
     *
     * @return Status atual do serviço NF-e.
     */
    @GetMapping("/status")
    public ResponseEntity<ApiResponse> status() {
        log.info("Verificando status do serviço NF-e (módulo fiscal)");
        try {
            String status = nfeTransmitService.consultarStatus();
            return buildResponse(HttpStatus.OK, status, null);
        } catch (Exception e) {
            log.error("Falha ao consultar status da SEFAZ-SP: {}", e.getMessage(), e);
            return buildResponse(HttpStatus.SERVICE_UNAVAILABLE,
                    "Serviço NF-e indisponível", null);
        }
    }

    // =========================================================================
    // MÉTODOS AUXILIARES
    // =========================================================================

    /**
     * Cria um {@link ResponseEntity} padronizado com a estrutura JSON:
     * <pre>
     * {
     *   "code": 200,
     *   "message": "OK",
     *   "data": { ... }
     * }
     * </pre>
     *
     * @param status  Código HTTP
     * @param message Mensagem descritiva
     * @param data    Objeto de retorno
     * @return ResponseEntity com padrão corporativo Borurio
     */
    private ResponseEntity<ApiResponse> buildResponse(HttpStatus status, String message, Object data) {
        return ResponseEntity.status(status)
                .body(new ApiResponse(status.value(), message, data));
    }

    /**
     * Estrutura padrão de resposta JSON para os endpoints fiscais.
     */
    private record ApiResponse(int code, String message, Object data) {}
}
