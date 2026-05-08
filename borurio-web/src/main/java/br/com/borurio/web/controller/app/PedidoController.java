package br.com.borurio.web.controller.app;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.service.PedidoService;
import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.dto.NfeGeracaoResult;
import br.com.borurio.web.service.PedidoEmissaoService;
import br.com.borurio.web.service.PedidoOperacaoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/app/pedidos")
@Tag(name = "Pedidos", description = "Gestão de pedidos de venda e operações fiscais vinculadas")
public class PedidoController {

    private final PedidoService pedidoService;
    private final PedidoEmissaoService pedidoEmissaoService;
    private final PedidoOperacaoService pedidoOperacaoService;
    private final EmitenteProperties emitente;

    public PedidoController(PedidoService pedidoService,
                            PedidoEmissaoService pedidoEmissaoService,
                            PedidoOperacaoService pedidoOperacaoService,
                            EmitenteProperties emitente) {
        this.pedidoService         = pedidoService;
        this.pedidoEmissaoService  = pedidoEmissaoService;
        this.pedidoOperacaoService = pedidoOperacaoService;
        this.emitente              = emitente;
    }

    // =========================================================================
    // CRUD
    // =========================================================================

    @GetMapping
    @Operation(summary = "Lista todos os pedidos")
    public Result<List<Pedido>> listar() {
        return ResultUtil.success(pedidoService.listarTodos());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Busca pedido por ID com itens e snapshot fiscal")
    public Result<Pedido> buscarPorId(@PathVariable Long id) {
        try {
            return ResultUtil.success(pedidoService.buscarComItens(id));
        } catch (IllegalArgumentException e) {
            return ResultUtil.error(e.getMessage());
        }
    }

    /**
     * Cria pedido em RASCUNHO. O CNPJ do emitente é injetado automaticamente
     * a partir das propriedades da aplicação — não precisa ser enviado no body.
     *
     * Body mínimo:
     * <pre>
     * {
     *   "destCnpjCpf": "12345678000195",
     *   "destRazaoSocial": "Cliente",
     *   "destUf": "SP",
     *   "itens": [{ "produtoId": 1, "quantidade": 2, "valorUnitario": 45.90 }]
     * }
     * </pre>
     */
    @PostMapping
    @Operation(summary = "Cria pedido em RASCUNHO com snapshot fiscal congelado nos itens")
    public Result<?> criar(@RequestBody Pedido pedido) {
        try {
            pedido.setCnpjEmitente(emitente.getCnpj().replaceAll("\\D", ""));
            pedido.setEmpresaId(EmpresaContextHolder.get());
            List<PedidoItem> itens = pedido.getItens();
            pedido.setItens(null);
            return ResultUtil.success(pedidoService.criar(pedido, itens != null ? itens : List.of()));
        } catch (IllegalArgumentException e) {
            return ResultUtil.error(e.getMessage());
        }
    }

    // =========================================================================
    // EMISSÃO
    // =========================================================================

    /**
     * Transmite NF-e para o pedido. Pedido deve estar em RASCUNHO.
     * Atualiza status para: AUTORIZADO | AGUARDANDO | REJEITADO | ERRO.
     * Em caso de AUTORIZADO, realiza baixa de estoque dos itens.
     */
    @PostMapping("/{id}/emitir")
    @Operation(summary = "Emite NF-e — pedido muda para AUTORIZADO, AGUARDANDO ou REJEITADO")
    public Result<?> emitir(@PathVariable Long id) {
        try {
            NfeGeracaoResult result = pedidoEmissaoService.emitir(id);
            return ResultUtil.success(Map.of(
                    "chaveNfe",    result.getChaveNfe() != null ? result.getChaveNfe() : "",
                    "soapRetorno", result.getSoapRetorno()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResultUtil.error(e.getMessage());
        } catch (Exception e) {
            return ResultUtil.error("Erro ao emitir NF-e: " + e.getMessage());
        }
    }

    // =========================================================================
    // OPERAÇÕES FISCAIS POR PEDIDO
    // =========================================================================

    /**
     * Consulta situação fiscal do pedido: estado local (nfe_documento) + consulta live SEFAZ.
     * Requer chaveNfe preenchida (pedido já emitido).
     */
    @GetMapping("/{id}/situacao")
    @Operation(summary = "Consulta situação fiscal do pedido na SEFAZ (consSitNFe)")
    public Result<?> situacao(@PathVariable Long id) {
        try {
            return ResultUtil.success(pedidoOperacaoService.consultarSituacao(id));
        } catch (IllegalStateException e) {
            return ResultUtil.error(e.getMessage());
        } catch (Exception e) {
            return ResultUtil.error("Falha ao consultar situação: " + e.getMessage());
        }
    }

    /**
     * Cancela a NF-e vinculada ao pedido.
     * Pedido deve estar em status AUTORIZADO e ter nProt gravado.
     *
     * Body: { "justificativa": "Motivo com no minimo 15 caracteres" }
     */
    @PostMapping("/{id}/cancelar")
    @Operation(summary = "Cancela NF-e do pedido — pedido muda para CANCELADO")
    public Result<?> cancelar(@PathVariable Long id,
                               @RequestBody Map<String, String> body) {
        try {
            String retorno = pedidoOperacaoService.cancelar(id, body.get("justificativa"));
            return ResultUtil.success(retorno);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResultUtil.error(e.getMessage());
        } catch (Exception e) {
            return ResultUtil.error("Falha ao cancelar NF-e: " + e.getMessage());
        }
    }

    /**
     * Emite Carta de Correção Eletrônica (CC-e) vinculada ao pedido.
     * Pedido deve estar em status AUTORIZADO.
     *
     * Body: { "correcao": "Texto da correcao com no minimo 15 caracteres" }
     */
    @PostMapping("/{id}/cce")
    @Operation(summary = "Emite CC-e para a NF-e do pedido (Evento 110110)")
    public Result<?> cce(@PathVariable Long id,
                          @RequestBody Map<String, String> body) {
        try {
            String retorno = pedidoOperacaoService.emitirCce(id, body.get("correcao"));
            return ResultUtil.success(retorno);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResultUtil.error(e.getMessage());
        } catch (Exception e) {
            return ResultUtil.error("Falha ao emitir CC-e: " + e.getMessage());
        }
    }
}
