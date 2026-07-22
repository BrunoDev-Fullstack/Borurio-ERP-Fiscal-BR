package br.com.borurio.web.controller.admin;

import br.com.borurio.web.dto.OmsAuthorizationAdminResponse;
import br.com.borurio.web.dto.OmsAuthorizationRevogarRequest;
import br.com.borurio.web.dto.OmsAuthorizationRotacionarRequest;
import br.com.borurio.web.service.OmsAuthorizationAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.MDC;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Revogação e rotação administrativa de autorização OMS (ROLE_ADMIN, ver SecurityConfig
 * /api/admin/**). Gate 7H — hotfix de segurança para a ausência de checagem de revogado_em
 * em JwtFilter. @PreAuthorize é defesa adicional em profundidade — a proteção primária já
 * vem do matcher /api/admin/** em SecurityConfig (method security já habilitada via
 * @EnableMethodSecurity nessa mesma classe).
 */
@RestController
@RequestMapping("/api/admin/oms-authorizations")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin — Autorização OMS", description = "Revogação e rotação administrativa de token OMS")
public class OmsAuthorizationAdminController {

    private final OmsAuthorizationAdminService service;

    public OmsAuthorizationAdminController(OmsAuthorizationAdminService service) {
        this.service = service;
    }

    /**
     * Revoga imediatamente a autorização — idempotente, sempre 200. Idempotency-Key obrigatório
     * (mesmo contrato de /rotacionar, ainda que a revogação não dependa dele para ser segura).
     */
    @PostMapping("/{id}/revogar")
    @Operation(summary = "Revoga imediatamente uma autorização OMS")
    public ResponseEntity<OmsAuthorizationAdminResponse> revogar(
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OmsAuthorizationRevogarRequest req) {

        OmsAuthorizationAdminResponse resp = service.revogar(id, req, idempotencyKey, MDC.get("requestId"));
        return semCache(resp);
    }

    /**
     * Rotaciona o jti da autorização (novo token). expectedVersion obrigatório — concorrência
     * otimista. Permite reativar autorização revogada quando expectedVersion corresponder à
     * versao pós-revogação. Idempotency-Key obrigatório: replay da mesma chave regenera o
     * mesmo JWT sem nova escrita.
     */
    @PostMapping("/{id}/rotacionar")
    @Operation(summary = "Rotaciona o jti de uma autorização OMS, emitindo novo token")
    public ResponseEntity<OmsAuthorizationAdminResponse> rotacionar(
            @PathVariable Long id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OmsAuthorizationRotacionarRequest req) {

        OmsAuthorizationAdminResponse resp = service.rotacionar(id, req, idempotencyKey, MDC.get("requestId"));
        return semCache(resp);
    }

    /** Resposta contém um JWT — nunca pode ser armazenada por proxy/browser/cache intermediário. */
    private ResponseEntity<OmsAuthorizationAdminResponse> semCache(OmsAuthorizationAdminResponse resp) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(resp);
    }
}
