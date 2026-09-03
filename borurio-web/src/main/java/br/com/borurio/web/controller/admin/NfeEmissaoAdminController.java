package br.com.borurio.web.controller.admin;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.web.dto.AbandonoCicloRequest;
import br.com.borurio.web.dto.AbandonoCicloResponse;
import br.com.borurio.web.dto.AbandonoCicloResultado;
import br.com.borurio.web.dto.TransporteNaoEntregueRequest;
import br.com.borurio.web.dto.TransporteNaoEntregueResponse;
import br.com.borurio.web.dto.TransporteNaoEntregueResultado;
import br.com.borurio.web.service.NfeEmissaoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Recovery administrativo do ciclo do nNF (Gate 1) — ROLE_ADMIN, ver matcher {@code /api/admin/**}
 * em SecurityConfig. {@code @PreAuthorize} é defesa em profundidade (method security já habilitada
 * via {@code @EnableMethodSecurity} na config).
 *
 * Endpoints:
 *   - {@code /abandonar} — encerra um ciclo em AGUARDANDO_CORRECAO cujo dado de origem não pode
 *     mais ser corrigido. Ver {@link NfeEmissaoService#abandonarCiclo}.
 *   - {@code /marcar-transporte-nao-entregue} — encerra um ciclo em TRANSMITIDO/
 *     PENDENTE_CONFIRMACAO cuja transmissão foi comprovadamente rejeitada no transporte/gateway
 *     antes de chegar ao autorizador. Ver {@link NfeEmissaoService#marcarTransporteNaoEntregue}.
 *
 * Ambos liberam o gate da série SEM consumir o número e nunca chamam a SEFAZ.
 */
@RestController
@RequestMapping("/api/admin/nfe-emissoes")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin — Ciclo de emissão NF-e", description = "Recovery administrativo do ciclo do nNF (Gate 1)")
public class NfeEmissaoAdminController {

    private final NfeEmissaoService nfeEmissaoService;

    public NfeEmissaoAdminController(NfeEmissaoService nfeEmissaoService) {
        this.nfeEmissaoService = nfeEmissaoService;
    }

    /**
     * Abandona um ciclo em AGUARDANDO_CORRECAO. Idempotente: chamar de novo sobre um ciclo já
     * ABANDONADO devolve 200 com {@code idempotente=true}. Recusa (422) qualquer outro estado de
     * origem, ciclo com protocolo SEFAZ, ou ciclo já substituído por contingência; 404 se a
     * emissão não existir.
     */
    @PostMapping("/{emissaoId}/abandonar")
    @Operation(summary = "Abandona um ciclo de nNF em AGUARDANDO_CORRECAO, liberando o gate sem consumir o número")
    public Result<AbandonoCicloResponse> abandonar(@PathVariable Long emissaoId,
                                                   @Valid @RequestBody AbandonoCicloRequest request) {
        AbandonoCicloResultado resultado = nfeEmissaoService.abandonarCiclo(emissaoId, request.getMotivo());
        return ResultUtil.success(AbandonoCicloResponse.from(resultado));
    }

    /**
     * Encerra um ciclo em TRANSMITIDO/PENDENTE_CONFIRMACAO cuja transmissão foi comprovadamente
     * rejeitada no transporte/gateway antes de chegar ao autorizador da SEFAZ (ex.: HTTP 403 do
     * proxy, resposta HTML). Libera o gate SEM consumir o número; devolve o pedido de origem a
     * {@code ERRO} (emissível) com a {@code chaveNfe} espúria limpa; NÃO chama a SEFAZ.
     *
     * Idempotente. Recusa (422): outro estado de origem ({@code CICLO_NAO_ELEGIVEL_TRANSPORTE}),
     * ciclo com {@code nProt} ({@code CICLO_COM_PROTOCOLO}), ciclo já reconciliado
     * ({@code CICLO_JA_RECONCILIADO}), evidência de processamento em nfe_documento
     * ({@code EVIDENCIA_DE_PROCESSAMENTO}), ciclo substituído por contingência
     * ({@code CICLO_SUBSTITUIDO}); 404 se a emissão não existir.
     */
    @PostMapping("/{emissaoId}/marcar-transporte-nao-entregue")
    @Operation(summary = "Encerra um ciclo TRANSMITIDO/PENDENTE_CONFIRMACAO cuja transmissão não chegou ao autorizador")
    public Result<TransporteNaoEntregueResponse> marcarTransporteNaoEntregue(
            @PathVariable Long emissaoId,
            @Valid @RequestBody TransporteNaoEntregueRequest request) {
        TransporteNaoEntregueResultado resultado =
                nfeEmissaoService.marcarTransporteNaoEntregue(emissaoId, request.getMotivo());
        return ResultUtil.success(TransporteNaoEntregueResponse.from(resultado));
    }
}
