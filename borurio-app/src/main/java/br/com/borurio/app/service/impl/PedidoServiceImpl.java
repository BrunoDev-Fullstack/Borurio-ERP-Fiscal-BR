package br.com.borurio.app.service.impl;

import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.entity.Produto;
import br.com.borurio.app.mapper.PedidoItemMapper;
import br.com.borurio.app.mapper.PedidoMapper;
import br.com.borurio.app.mapper.ProdutoMapper;
import br.com.borurio.app.service.PedidoService;
import br.com.borurio.core.mvc.api.PageResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class PedidoServiceImpl implements PedidoService {

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
            Produto produto = resolverProduto(item, pedido.getEmpresaId());
            preencherSnapshot(item, produto);

            item.setPedidoId(pedido.getId());
            item.setValorTotal(item.getQuantidade().multiply(item.getValorUnitario()));
            total = total.add(item.getValorTotal());

            pedidoItemMapper.inserir(item);
        }

        pedidoMapper.atualizarPosInsercao(pedido.getId(), numero, total);
        pedido.setNumero(numero);
        pedido.setValorTotal(total);
        pedido.setItens(itens);

        return pedido;
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
            throw new IllegalArgumentException("Produto não encontrado: id=" + item.getProdutoId());
        }
        if (Integer.valueOf(0).equals(produto.getEstado())) {
            throw new IllegalArgumentException(
                    "Produto inativo não pode ser adicionado ao pedido: id=" + item.getProdutoId()
                    + " código=" + produto.getCodigo());
        }
        return produto;
    }

    /** Congela os dados fiscais do produto no item. Imutável após criação do pedido. */
    private void preencherSnapshot(PedidoItem item, Produto produto) {
        item.setCodigoProduto(produto.getCodigo());
        item.setDescricao(produto.getDescricao());
        item.setNcm(produto.getNcm());
        item.setCfop(produto.getCfop());
        item.setUnidade(produto.getUnidade());
        item.setOrigem(produto.getOrigem() != null ? produto.getOrigem() : 0);
        item.setCsosn(produto.getCsosn() != null ? produto.getCsosn() : "400");
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
