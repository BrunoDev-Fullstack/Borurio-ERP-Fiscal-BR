package br.com.borurio.web.controller.fiscal;

import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.fiscal.dto.NfeCceRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@Slf4j
@RestController
@RequestMapping("/api/fiscal/nfe")
@Tag(name = "NF-e", description = "Transmissão e consulta de NF-e junto à SEFAZ")
public class NfeCceController {

    /**
     * DESABILITADO (Gate CC-e, 12-08-2026): este endpoint nunca teve contexto de pedido, então não
     * tem como participar do gate de sequência/idempotência (nfe_evento_sequencia/
     * nfe_evento_idempotencia) que passa a proteger toda transmissão de CC-e (110110). Deixar essa
     * rota transmitir por fora criaria um segundo caminho capaz de desalinhar
     * nfe_evento_sequencia.ultimo_nseq_registrado sem o Borurio saber.
     *
     * A rota continua existindo (nunca 404 silencioso — HTTP 410 explícito) pra detectar qualquer
     * consumidor interno antigo que ainda a chame; migre para POST /api/app/pedidos/{id}/cce, que é
     * o único caminho capaz de transmitir 110110 para a SEFAZ a partir deste gate. Nunca chama
     * NfeCceService, nunca reserva sequência, nunca cria nfe_evento, nunca toca SEFAZ ou
     * Pedido/NfeEmissao/NfeDocumento.
     */
    @PostMapping("/cce")
    @Operation(
            summary = "[DESABILITADO] Endpoint legado de CC-e — não transmite mais para a SEFAZ",
            description = "Retorna sempre HTTP 410. Use POST /api/app/pedidos/{id}/cce (Gate CC-e, 12-08-2026)."
    )
    public Result<String> corrigir(@RequestBody NfeCceRequest request) {
        log.warn("[CC-e] Chamada ao endpoint legado desabilitado | chave={}", request.getChaveNfe());
        throw BusinessException.cceEndpointLegadoDesabilitado();
    }
}
