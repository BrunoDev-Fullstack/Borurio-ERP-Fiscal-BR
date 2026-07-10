package br.com.borurio.web.controller.app;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.service.EmpresaService;
import br.com.borurio.app.service.PedidoService;
import br.com.borurio.core.mvc.api.PageResponse;
import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.dto.NfeGeracaoResult;
import br.com.borurio.web.dto.PedidoResponse;
import br.com.borurio.web.service.OmsCertificadoService;
import br.com.borurio.web.service.PedidoEmissaoService;
import br.com.borurio.web.service.PedidoOperacaoService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
    private final EmpresaService empresaService;
    private final OmsCertificadoService omsCertificadoService;

    public PedidoController(PedidoService pedidoService,
                            PedidoEmissaoService pedidoEmissaoService,
                            PedidoOperacaoService pedidoOperacaoService,
                            EmitenteProperties emitente,
                            EmpresaService empresaService,
                            OmsCertificadoService omsCertificadoService) {
        this.pedidoService           = pedidoService;
        this.pedidoEmissaoService    = pedidoEmissaoService;
        this.pedidoOperacaoService   = pedidoOperacaoService;
        this.emitente                = emitente;
        this.empresaService          = empresaService;
        this.omsCertificadoService   = omsCertificadoService;
    }

    @GetMapping
    @Operation(summary = "Lista pedidos da empresa autenticada (paginado)")
    public Result<PageResponse<PedidoResponse>> listar(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        size = Math.min(size, 100);
        Long empresaId = EmpresaContextHolder.get();
        PageResponse<Pedido> pagina = pedidoService.listarPaginado(empresaId, page, size);
        PageResponse<PedidoResponse> resposta = PageResponse.of(
                pagina.getContent().stream().map(PedidoResponse::from).toList(),
                pagina.getPage(), pagina.getSize(), pagina.getTotalElements());
        return ResultUtil.success(resposta);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Busca pedido por ID — valida pertencimento à empresa")
    public Result<PedidoResponse> buscarPorId(@PathVariable Long id) {
        Long empresaId = EmpresaContextHolder.get();
        Pedido pedido = empresaId != null
                ? pedidoService.buscarComItensEEmpresa(id, empresaId)
                : pedidoService.buscarComItens(id);
        return ResultUtil.success(PedidoResponse.from(pedido));
    }

    /**
     * Cria pedido em RASCUNHO. CNPJ do emitente resolvido da empresa autenticada via JWT.
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
    public Result<PedidoResponse> criar(@Valid @RequestBody Pedido pedido) {
        Long empresaId = EmpresaContextHolder.get();
        pedido.setEmpresaId(empresaId);

        String jtiOms = EmpresaContextHolder.getJtiAuth();
        boolean cnpjEnviadoPeloOms = jtiOms != null
                && pedido.getCnpjEmitente() != null
                && !pedido.getCnpjEmitente().isBlank();

        Empresa empresaParaEndereco = null;

        if (cnpjEnviadoPeloOms) {
            // Valida antecipadamente que o CNPJ está autorizado para este cliente OMS.
            // Falha rápida: evita criar pedido com CNPJ sem certificado ativo.
            if (!omsCertificadoService.cnpjAutorizadoParaJti(jtiOms, pedido.getCnpjEmitente())) {
                throw BusinessException.cnpjNotAuthorizedForOmsClient(pedido.getCnpjEmitente());
            }
            empresaParaEndereco = empresaService.buscarPorCnpj(pedido.getCnpjEmitente());
        } else {
            if (empresaId != null) {
                Empresa empresa = empresaService.buscarPorId(empresaId);
                pedido.setCnpjEmitente(empresa.getCnpj().replaceAll("\\D", ""));
                empresaParaEndereco = empresa;
            } else {
                pedido.setCnpjEmitente(emitente.getCnpj().replaceAll("\\D", ""));
            }
        }

        List<PedidoItem> itens = pedido.getItens();
        pedido.setItens(null);
        Pedido criado = pedidoService.criar(pedido, itens != null ? itens : List.of());

        // Só atualiza o cadastro da empresa DEPOIS que o pedido foi criado com sucesso —
        // evita mutar a empresa numa requisição cuja criação do pedido falha em seguida.
        atualizarEnderecoEmitenteSeNecessario(empresaParaEndereco, pedido);

        return ResultUtil.success(PedidoResponse.from(criado));
    }

    /**
     * Preenche o endereço da empresa emitente a partir do payload do pedido, quando o cadastro
     * estiver incompleto. Só completa campos ausentes — nunca sobrescreve endereço já cadastrado.
     */
    private void atualizarEnderecoEmitenteSeNecessario(Empresa empresa, Pedido pedido) {
        if (empresa == null) return;

        boolean enderecoIncompleto = isBlank(empresa.getLogradouro())
                || isBlank(empresa.getNumero())
                || isBlank(empresa.getBairro())
                || isBlank(empresa.getCodigoMunicipio())
                || isBlank(empresa.getMunicipio())
                || isBlank(empresa.getCep());

        boolean enderecoRecebido = !isBlank(pedido.getEmitLogradouro())
                || !isBlank(pedido.getEmitNumero())
                || !isBlank(pedido.getEmitBairro())
                || !isBlank(pedido.getEmitCodigoMunicipio())
                || !isBlank(pedido.getEmitMunicipio())
                || !isBlank(pedido.getEmitCep());

        if (!enderecoIncompleto || !enderecoRecebido) return;

        if (isBlank(empresa.getLogradouro()))      empresa.setLogradouro(pedido.getEmitLogradouro());
        if (isBlank(empresa.getNumero()))          empresa.setNumero(pedido.getEmitNumero());
        if (isBlank(empresa.getBairro()))          empresa.setBairro(pedido.getEmitBairro());
        if (isBlank(empresa.getCodigoMunicipio())) empresa.setCodigoMunicipio(pedido.getEmitCodigoMunicipio());
        if (isBlank(empresa.getMunicipio()))       empresa.setMunicipio(pedido.getEmitMunicipio());
        if (isBlank(empresa.getCep()))              empresa.setCep(pedido.getEmitCep());

        empresaService.atualizar(empresa.getId(), empresa);
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    @PostMapping("/{id}/emitir")
    @Operation(
            summary = "Emite NF-e — pedido muda para AUTORIZADO, AGUARDANDO ou REJEITADO",
            description = "Transmite a NF-e 4.00 à SEFAZ a partir do snapshot fiscal dos itens. Não requer body. " +
                          "HTTP 200 só ocorre quando a SEFAZ aceita (`AUTORIZADO`) ou ainda está processando (`AGUARDANDO`) — " +
                          "retorna `chaveNfe` com 44 dígitos e `soapRetorno` com a resposta SOAP bruta. " +
                          "**Rejeição da SEFAZ não é HTTP 200**: retorna HTTP 422 com `errorCode=SEFAZ_REJECTED`, " +
                          "`retryable=false` (geralmente é dado incorreto, não falha transitória) e `data.cStat`/`data.xMotivo` com o motivo real. " +
                          "Falhas de rede retornam `SEFAZ_TIMEOUT`/`SEFAZ_UNAVAILABLE` (retryable=true, HTTP 503); " +
                          "schema local inválido retorna `XML_SCHEMA_INVALID` (retryable=false, HTTP 422); " +
                          "cadastro do emitente incompleto retorna `EMITTER_ADDRESS_INCOMPLETE` (retryable=false, HTTP 422) sem chamar a SEFAZ. " +
                          "Precondição: pedido deve estar em `RASCUNHO`, `REJEITADO` ou `ERRO`. Cada nova tentativa gera `chaveNfe` nova. " +
                          "Qualquer outro estado (`AUTORIZADO`, `AGUARDANDO`, `CANCELADO`) retorna HTTP 422 com `errorCode=INVALID_ORDER_STATUS`."
    )
    public Result<Map<String, String>> emitir(@PathVariable Long id) throws Exception {
        NfeGeracaoResult result = pedidoEmissaoService.emitir(id);
        return ResultUtil.success(Map.of(
                "chaveNfe",    result.getChaveNfe() != null ? result.getChaveNfe() : "",
                "soapRetorno", result.getSoapRetorno()
        ));
    }

    @GetMapping("/{id}/situacao")
    @Operation(
            summary = "Consulta situação fiscal do pedido na SEFAZ (consSitNFe)",
            description = "Executa consulta em tempo real à SEFAZ (`consSitNFe`). " +
                          "O campo `consultaSefaz` (XML bruto) está **sempre presente**. " +
                          "Os campos `cStat`, `xMotivo`, `nProt` e `dhRecbto` são condicionais — presentes apenas se o documento foi registrado internamente. " +
                          "Máquina de estados: `RASCUNHO` → `AGUARDANDO` → `AUTORIZADO` | `REJEITADO` | `ERRO` | `CANCELADO`. " +
                          "`REJEITADO` e `ERRO` podem chamar `/emitir` novamente (reemissão); `AUTORIZADO` e `CANCELADO` são terminais. " +
                          "Precondição: pedido deve ter `chaveNfe` definida (ter passado por `/emitir`). Retorna HTTP 422 caso contrário."
    )
    public Result<Object> situacao(@PathVariable Long id) throws Exception {
        return ResultUtil.success(pedidoOperacaoService.consultarSituacao(id));
    }

    /**
     * Body: { "justificativa": "Motivo com no mínimo 15 caracteres" }
     */
    @PostMapping("/{id}/cancelar")
    @Operation(summary = "Cancela NF-e do pedido — pedido muda para CANCELADO")
    public Result<String> cancelar(@PathVariable Long id,
                                   @RequestBody Map<String, String> body) throws Exception {
        return ResultUtil.success(pedidoOperacaoService.cancelar(id, body.get("justificativa")));
    }

    /**
     * Body: { "correcao": "Texto com no mínimo 15 caracteres" }
     */
    @PostMapping("/{id}/cce")
    @Operation(summary = "Emite CC-e para a NF-e do pedido (Evento 110110)")
    public Result<String> cce(@PathVariable Long id,
                              @RequestBody Map<String, String> body) throws Exception {
        return ResultUtil.success(pedidoOperacaoService.emitirCce(id, body.get("correcao")));
    }
}
