package br.com.borurio.web.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;

/**
 * =============================================================================
 * Controlador de monitoramento básico da aplicação.
 *
 * Responsável por validar a disponibilidade da API principal
 * (módulo web do ERP Fiscal Borurio Brasil).
 *
 * Utilizado em ambientes de:
 * - Desenvolvimento (smoke tests)
 * - Homologação (CI/CD pipelines)
 * - Produção (monitoramento e observabilidade)
 *
 * Compatibilidade:
 * - Java 17
 * - Spring Boot 3.3.x
 * - Docker Compose / Kubernetes Healthchecks
 * =============================================================================
 */
@Slf4j
@RestController
public class PingController {

    /**
     * Endpoint de verificação básica da aplicação.
     * <p>
     * Retorna uma resposta JSON simples confirmando
     * que a API está operacional e registrando o timestamp.
     *
     * Exemplo:
     * <pre>
     * GET /ping
     * </pre>
     *
     * @return JSON indicando que a API está online.
     */
    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        log.debug("Verificação de disponibilidade /ping acionada.");

        Map<String, Object> response = new HashMap<>();
        response.put("code", 200);
        response.put("message", "API Borurio ERP Fiscal BR está online.");
        response.put("timestamp", OffsetDateTime.now(ZoneId.of("America/Sao_Paulo")).toString());

        return ResponseEntity.ok(response);
    }
}
