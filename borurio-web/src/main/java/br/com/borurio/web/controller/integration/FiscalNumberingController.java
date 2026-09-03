package br.com.borurio.web.controller.integration;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.web.dto.FiscalNumberingSyncRequest;
import br.com.borurio.web.dto.FiscalNumberingSyncResponse;
import br.com.borurio.web.service.FiscalNumberingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.MDC;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integration")
@Tag(name = "Integração OMS", description = "Sincronização de série e numeração NF-e")
public class FiscalNumberingController {

    private final FiscalNumberingService service;

    public FiscalNumberingController(FiscalNumberingService service) {
        this.service = service;
    }

    /**
     * Sincroniza série e próximo número de NF-e para o CNPJ — chamado pela OMS toda vez que o
     * cliente muda série/numeração no lado deles. Idempotente: reenviar o mesmo valor não altera
     * nada. Rejeita regressão de numeração (NUMERACAO_INFERIOR_A_ATUAL).
     *
     * CNPJ, identidade do cliente OMS e requestId nunca vêm do body — resolvidos pela autorização
     * autenticada (mesmo token de /pedidos) e pelo RequestIdFilter/MDC.
     */
    @PutMapping("/fiscal-numbering/{cnpj}")
    @Operation(summary = "Sincroniza série e numeração NF-e para o CNPJ autorizado")
    public FiscalNumberingSyncResponse sincronizar(@PathVariable String cnpj,
                                                     @RequestBody FiscalNumberingSyncRequest req) {
        String jtiOms = EmpresaContextHolder.getJtiAuth();
        String requestId = MDC.get("requestId");
        return service.sincronizar(cnpj, req.getSerie(), req.getProximoNumero(), jtiOms, requestId);
    }
}
