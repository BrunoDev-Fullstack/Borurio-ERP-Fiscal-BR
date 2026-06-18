package br.com.borurio.web.controller.integration;

import br.com.borurio.web.dto.OmsFiscalAuthorizationRequest;
import br.com.borurio.web.dto.OmsFiscalAuthorizationResponse;
import br.com.borurio.web.service.OmsFiscalAuthorizationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/integration")
public class OmsFiscalAuthorizationController {

    private final OmsFiscalAuthorizationService service;

    public OmsFiscalAuthorizationController(OmsFiscalAuthorizationService service) {
        this.service = service;
    }

    /**
     * Autoriza um sistema OMS a emitir NF-e em nome de uma empresa emitente cadastrada.
     *
     * Requer header X-Api-Key com a chave técnica do integrador.
     * O CNPJ enviado deve corresponder ao CNPJ embutido no certificado A1.
     * A empresa deve estar pré-cadastrada pelo ADMIN via POST /api/app/empresas.
     *
     * Retorna um token JWT de longa duração vinculado à empresa.
     * Em caso de reautorização, o token anterior é invalidado e um novo é emitido.
     */
    @PostMapping("/fiscal-authorizations")
    public ResponseEntity<OmsFiscalAuthorizationResponse> autorizar(
            @RequestHeader(value = "X-Api-Key", required = false) String apiKey,
            @Valid @RequestBody OmsFiscalAuthorizationRequest req) {

        OmsFiscalAuthorizationResponse resp = service.autorizar(apiKey, req);
        return ResponseEntity.ok(resp);
    }
}
