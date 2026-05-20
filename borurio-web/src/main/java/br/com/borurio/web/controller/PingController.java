package br.com.borurio.web.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * =============================================================================
 * CONTROLADOR: PingController
 * -----------------------------------------------------------------------------
 * Responsável por verificar a disponibilidade da API principal do ERP Fiscal.
 *
 * Padrão técnico:
 * - Java 17
 * - Spring Boot 3.3.x
 * - Docker / DevSecOps / Observabilidade
 *
 * Utilização:
 * - /api/test/ping → utilizado em smoke tests, CI/CD e health checks externos.
 *
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * =============================================================================
 */
@Slf4j
@RestController
@RequestMapping("/api/test")
@Tag(name = "Utilitários", description = "Healthcheck e endpoints auxiliares de diagnóstico")
public class PingController {

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    /**
     * Endpoint de verificação básica de disponibilidade da API.
     * Retorna status, mensagem e timestamp atual.
     *
     * @return JSON contendo informações básicas do ambiente ativo.
     */
    @Operation(
            summary = "Healthcheck — verifica disponibilidade da API",
            description = "Endpoint público, sem autenticação. Retorna `status: UP` quando a API está operacional. " +
                          "O campo `environment` indica o perfil Spring ativo (`dev`, `hom` ou `prd`). " +
                          "Use este endpoint como primeiro passo do smoke test antes de autenticar."
    )
    @GetMapping("/ping")
    public ResponseEntity<Map<String, Object>> ping() {
        log.debug("Verificação de disponibilidade /api/test/ping acionada.");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("code", HttpStatus.OK.value());
        body.put("message", "API Borurio ERP Fiscal BR está operacional.");
        body.put("environment", activeProfile);
        body.put("timestamp", OffsetDateTime.now(ZoneId.of("America/Sao_Paulo")).toString());

        return ResponseEntity.ok(body);
    }
}
