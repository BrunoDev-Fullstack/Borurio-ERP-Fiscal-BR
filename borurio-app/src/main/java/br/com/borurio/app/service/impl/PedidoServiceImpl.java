package br.com.borurio.app.service.impl;

import br.com.borurio.app.context.EmpresaContextHolder;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.entity.Produto;
import br.com.borurio.app.mapper.PedidoItemMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.app.mapper.ProdutoMapper;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.service.PedidoService;
import br.com.borurio.core.mvc.api.PageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class PedidoServiceImpl implements PedidoService {

    private static final Logger log = LoggerFactory.getLogger(PedidoServiceImpl.class);

    private final PedidoMapper pedidoMapper;
    private final PedidoItemMapper pedidoItemMapper;
    private final ProdutoMapper produtoMapper;

    public PedidoServiceImpl(PedidoMapper pedidoMapper,
                             PedidoItemMapper pedidoItemMapper,
                             ProdutoMapper produtoMapper) {
        this.pedidoMapper     = pedidoMapper;
        this.pedidoItemMapper = pedidoItemMapper;
        this.produtoMapper    = produtoMapper;
    }

    @Override
    @Transactional
    public Pedido criar(Pedido pedido, List<PedidoItem> itens) {
        validarCabecalho(pedido, itens);

        log.info("[PedidoService] Criando pedido | empresaId={} | externalOrderId={} | itens={}",
                pedido.getEmpresaId(), pedido.getExternalOrderId(),
                itens != null ? itens.size() : 0);

        if (pedido.getExternalOrderId() != null && !pedido.getExternalOrderId().isBlank()
                && pedido.getEmpresaId() != null) {
            Pedido existente = pedidoMapper.buscarPorExternalOrderIdEEmpresa(
                    pedido.getExternalOrderId(), pedido.getEmpresaId());
            if (existente != null) {
                log.info("[PedidoService] Idempotência — pedido existente retornado | externalOrderId={} | id={}",
                        pedido.getExternalOrderId(), existente.getId());
                existente.setItens(pedidoItemMapper.listarPorPedido(existente.getId()));
                return existente;
            }
        }

        // serie_nfe é NOT NULL no schema (V014) — precisa de algum valor no INSERT, mas deixou
        // de ser resolvida a partir de Empresa.serieNfePadrao AQUI (20-07-2026). O que for
        // gravado abaixo é só placeholder de schema (mesmo padrão já usado para "numero" logo
        // adiante) — nunca é lido para decidir a série de emissão. ReservaFiscalService resolve
        // a série de verdade a partir de Empresa.serieNfePadrao no INÍCIO de cada tentativa de
        // emissão e sobrescreve esta coluna via PedidoMapper.atualizarSerieReservada — resolver
        // aqui congelaria a série antiga em pedidos criados antes de uma sincronização OMS.
        if (pedido.getSerieNfe() == null || pedido.getSerieNfe().isBlank()) pedido.setSerieNfe("1");
        if (pedido.getNaturezaOperacao() == null || pedido.getNaturezaOperacao().isBlank()) {
            pedido.setNaturezaOperacao("VENDA DE MERCADORIA");
        }
        pedido.setStatus("RASCUNHO");
        // Placeholder garantirá unicidade enquanto o id ainda não existe.
        pedido.setNumero("INIT-" + System.currentTimeMillis());

        pedidoMapper.inserir(pedido);

        String numero     = "PED-" + String.format("%08d", pedido.getId());
        BigDecimal total  = BigDecimal.ZERO;

        for (PedidoItem item : itens) {
            resolverProduto(item, pedido.getEmpresaId());
            validarSnapshotFiscal(item);

            item.setPedidoId(pedido.getId());
            item.setValorTotal(item.getQuantidade().multiply(item.getValorUnitario()));
            total = total.add(item.getValorTotal());

            pedidoItemMapper.inserir(item);
        }

        pedidoMapper.atualizarPosInsercao(pedido.getId(), numero, total);
        pedido.setNumero(numero);
        pedido.setValorTotal(total);
        pedido.setItens(itens);

        log.info("[PedidoService] Pedido criado | id={} | numero={} | empresaId={} | total={}",
                pedido.getId(), numero, pedido.getEmpresaId(), total);

        return pedido;
    }

    @Override
    public Pedido buscarPorExternalOrderIdEEmpresa(String externalOrderId, Long empresaId) {
        if (externalOrderId == null || externalOrderId.isBlank() || empresaId == null) {
            return null;
        }
        return pedidoMapper.buscarPorExternalOrderIdEEmpresa(externalOrderId, empresaId);
    }

    @Override
    public Pedido buscarComItens(Long id) {
        Pedido pedido = buscarPorId(id);
        pedido.setItens(pedidoItemMapper.listarPorPedido(id));
        return pedido;
    }

    @Override
    public Pedido buscarComItensEEmpresa(Long id, Long empresaId) {
        Pedido pedido = pedidoMapper.buscarPorIdEEmpresa(id, empresaId);
        if (pedido == null) throw new NoSuchElementException("Pedido não encontrado: id=" + id);
        pedido.setItens(pedidoItemMapper.listarPorPedido(id));
        return pedido;
    }

    @Override
    public Pedido buscarPorId(Long id) {
        Pedido pedido = pedidoMapper.buscarPorId(id);
        if (pedido == null) throw new NoSuchElementException("Pedido não encontrado: id=" + id);
        return pedido;
    }

    @Override
    public Pedido buscarPorIdEEmpresa(Long id, Long empresaId) {
        Pedido pedido = pedidoMapper.buscarPorIdEEmpresa(id, empresaId);
        if (pedido == null) throw new NoSuchElementException("Pedido não encontrado: id=" + id);
        return pedido;
    }

    @Override
    public Pedido buscarComItensDoTenanteAtual(Long id) {
        Long empresaId = EmpresaContextHolder.get();
        return empresaId != null ? buscarComItensEEmpresa(id, empresaId) : buscarComItens(id);
    }

    @Override
    public Pedido buscarPorIdDoTenanteAtual(Long id) {
        Long empresaId = EmpresaContextHolder.get();
        return empresaId != null ? buscarPorIdEEmpresa(id, empresaId) : buscarPorId(id);
    }

    @Override
    public List<Pedido> listarTodos() {
        return pedidoMapper.listarTodos();
    }

    @Override
    public List<Pedido> listarPorEmpresa(Long empresaId) {
        return pedidoMapper.listarPorEmpresa(empresaId);
    }

    @Override
    public PageResponse<Pedido> listarPaginado(Long empresaId, int page, int size) {
        int offset = page * size;
        List<Pedido> content;
        long total;
        if (empresaId != null) {
            content = pedidoMapper.listarPorEmpresaPaginado(empresaId, size, offset);
            total   = pedidoMapper.countPorEmpresa(empresaId);
        } else {
            content = pedidoMapper.listarTodosPaginado(size, offset);
            total   = pedidoMapper.countTodos();
        }
        return PageResponse.of(content, page, size, total);
    }

    @Override
    public void atualizarStatus(Long id, String status, String chaveNfe) {
        buscarPorId(id);
        pedidoMapper.atualizarStatus(id, status, chaveNfe);
    }

    @Override
    public boolean reivindicarParaEmissao(Long id) {
        return pedidoMapper.reivindicarParaEmissao(id) == 1;
    }

    // -------------------------------------------------------------------------
    // Privado
    // -------------------------------------------------------------------------

    private Produto resolverProduto(PedidoItem item, Long empresaId) {
        if (item.getProdutoId() == null) {
            throw new IllegalArgumentException("produtoId é obrigatório em todos os itens.");
        }
        Produto produto = empresaId != null
                ? produtoMapper.buscarPorIdEEmpresa(item.getProdutoId(), empresaId)
                : produtoMapper.buscarPorId(item.getProdutoId());
        if (produto == null) {
            throw BusinessException.productNotFound(item.getProdutoId());
        }
        if (Integer.valueOf(0).equals(produto.getEstado())) {
            throw BusinessException.productInactive(item.getProdutoId(), produto.getCodigo());
        }
        return produto;
    }

    /** Valida que o OMS enviou todos os campos fiscais obrigatórios no item. */
    private void validarSnapshotFiscal(PedidoItem item) {
        String ref = "produtoId=" + item.getProdutoId();
        if (item.getCodigoProduto() == null || item.getCodigoProduto().isBlank())
            throw new IllegalArgumentException("codigoProduto é obrigatório no item " + ref);
        if (item.getDescricao() == null || item.getDescricao().isBlank())
            throw new IllegalArgumentException("descricao é obrigatória no item " + ref);
        if (item.getNcm() == null || item.getNcm().isBlank())
            throw new IllegalArgumentException("ncm é obrigatório no item " + ref);
        if (item.getCfop() == null || item.getCfop().isBlank())
            throw new IllegalArgumentException("cfop é obrigatório no item " + ref);
        if (item.getUnidade() == null || item.getUnidade().isBlank())
            throw new IllegalArgumentException("unidade é obrigatória no item " + ref);
        if (item.getOrigem() == null)
            throw new IllegalArgumentException("origem é obrigatória no item " + ref);
        if (item.getCsosn() == null || item.getCsosn().isBlank())
            throw new IllegalArgumentException("csosn é obrigatório no item " + ref);
    }

    private void validarCabecalho(Pedido pedido, List<PedidoItem> itens) {
        if (pedido.getCnpjEmitente() == null || pedido.getCnpjEmitente().isBlank()) {
            throw new IllegalArgumentException("CNPJ do emitente é obrigatório.");
        }
        if (pedido.getDestCnpjCpf() == null || pedido.getDestCnpjCpf().isBlank()) {
            throw new IllegalArgumentException("CNPJ/CPF do destinatário é obrigatório.");
        }
        if (pedido.getDestRazaoSocial() == null || pedido.getDestRazaoSocial().isBlank()) {
            throw new IllegalArgumentException("Razão social do destinatário é obrigatória.");
        }
        if (itens == null || itens.isEmpty()) {
            throw new IllegalArgumentException("O pedido deve ter ao menos um item.");
        }
        for (PedidoItem item : itens) {
            if (item.getQuantidade() == null || item.getQuantidade().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(
                        "Quantidade inválida no item produtoId=" + item.getProdutoId());
            }
            if (item.getValorUnitario() == null || item.getValorUnitario().compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalArgumentException(
                        "Valor unitário inválido no item produtoId=" + item.getProdutoId());
            }
        }
    }
}
