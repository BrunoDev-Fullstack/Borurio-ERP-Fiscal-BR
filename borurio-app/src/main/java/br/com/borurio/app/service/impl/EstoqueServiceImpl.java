package br.com.borurio.app.service.impl;

import br.com.borurio.app.entity.EstoqueMovimento;
import br.com.borurio.app.entity.EstoqueSaldo;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.entity.Produto;
import br.com.borurio.app.mapper.EstoqueMovimentoMapper;
import br.com.borurio.app.mapper.ProdutoMapper;
import br.com.borurio.app.service.EstoqueService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

@Service
public class EstoqueServiceImpl implements EstoqueService {

    private final ProdutoMapper produtoMapper;
    private final EstoqueMovimentoMapper movimentoMapper;

    public EstoqueServiceImpl(ProdutoMapper produtoMapper,
                               EstoqueMovimentoMapper movimentoMapper) {
        this.produtoMapper   = produtoMapper;
        this.movimentoMapper = movimentoMapper;
    }

    @Override
    @Transactional
    public void reservarItens(List<PedidoItem> itens, Long empresaId, Long pedidoId, String criadoPor) {
        for (PedidoItem item : itens) {
            int linhas = produtoMapper.reservarEstoque(item.getProdutoId(), item.getQuantidade(), empresaId);
            if (linhas == 0) {
                Produto p = produtoMapper.buscarPorIdEEmpresa(item.getProdutoId(), empresaId);
                String msg = p == null
                        ? "Produto não encontrado: id=" + item.getProdutoId()
                        : "Estoque insuficiente para \"" + p.getDescricao() + "\""
                          + " (disponível: " + disponivel(p) + ", solicitado: " + item.getQuantidade() + ")";
                throw new IllegalStateException(msg);
            }
            movimentoMapper.inserir(novoMovimento("RESERVA", item, empresaId, pedidoId, criadoPor));
        }
    }

    @Override
    @Transactional
    public void baixaDefinitivaItens(List<PedidoItem> itens, Long empresaId, Long pedidoId, String criadoPor) {
        for (PedidoItem item : itens) {
            // NF-e já autorizada — nunca reverter; apenas registrar se falhar
            produtoMapper.baixaDefinitiva(item.getProdutoId(), item.getQuantidade(), empresaId);
            movimentoMapper.inserir(novoMovimento("BAIXA", item, empresaId, pedidoId, criadoPor));
        }
    }

    @Override
    @Transactional
    public void desfazerReservaItens(List<PedidoItem> itens, Long empresaId, Long pedidoId, String criadoPor) {
        for (PedidoItem item : itens) {
            produtoMapper.desfazerReserva(item.getProdutoId(), item.getQuantidade(), empresaId);
            movimentoMapper.inserir(novoMovimento("DESFAZER_RESERVA", item, empresaId, pedidoId, criadoPor));
        }
    }

    @Override
    @Transactional
    public void estornarBaixaItens(List<PedidoItem> itens, Long empresaId, Long pedidoId, String criadoPor) {
        for (PedidoItem item : itens) {
            produtoMapper.estornarBaixa(item.getProdutoId(), item.getQuantidade(), empresaId);
            movimentoMapper.inserir(novoMovimento("ESTORNO", item, empresaId, pedidoId, criadoPor));
        }
    }

    @Override
    @Transactional
    public EstoqueMovimento entrada(Long produtoId, Long empresaId, BigDecimal quantidade,
                                    String criadoPor, String observacao) {
        if (quantidade == null || quantidade.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Quantidade de entrada deve ser maior que zero.");
        }
        Produto p = produtoMapper.buscarPorIdEEmpresa(produtoId, empresaId);
        if (p == null) {
            throw new NoSuchElementException("Produto não encontrado: id=" + produtoId);
        }
        produtoMapper.estornarBaixa(produtoId, quantidade, empresaId);

        EstoqueMovimento mov = new EstoqueMovimento();
        mov.setProdutoId(produtoId);
        mov.setEmpresaId(empresaId);
        mov.setTipo("ENTRADA");
        mov.setQuantidade(quantidade);
        mov.setReferenciaTipo("MANUAL");
        mov.setObservacao(observacao);
        mov.setCriadoPor(criadoPor);
        movimentoMapper.inserir(mov);
        return mov;
    }

    @Override
    public EstoqueSaldo consultarSaldo(Long produtoId, Long empresaId) {
        Produto p = produtoMapper.buscarPorIdEEmpresa(produtoId, empresaId);
        if (p == null) {
            throw new NoSuchElementException("Produto não encontrado: id=" + produtoId);
        }
        BigDecimal total     = p.getEstoque() != null ? p.getEstoque() : BigDecimal.ZERO;
        BigDecimal reservado = p.getEstoqueReservado() != null ? p.getEstoqueReservado() : BigDecimal.ZERO;

        EstoqueSaldo saldo = new EstoqueSaldo();
        saldo.setProdutoId(produtoId);
        saldo.setEmpresaId(empresaId);
        saldo.setEstoqueTotal(total);
        saldo.setEstoqueReservado(reservado);
        saldo.setEstoqueDisponivel(total.subtract(reservado));
        return saldo;
    }

    // -------------------------------------------------------------------------

    private BigDecimal disponivel(Produto p) {
        BigDecimal total     = p.getEstoque() != null ? p.getEstoque() : BigDecimal.ZERO;
        BigDecimal reservado = p.getEstoqueReservado() != null ? p.getEstoqueReservado() : BigDecimal.ZERO;
        return total.subtract(reservado);
    }

    private EstoqueMovimento novoMovimento(String tipo, PedidoItem item, Long empresaId,
                                           Long pedidoId, String criadoPor) {
        EstoqueMovimento m = new EstoqueMovimento();
        m.setProdutoId(item.getProdutoId());
        m.setEmpresaId(empresaId);
        m.setTipo(tipo);
        m.setQuantidade(item.getQuantidade());
        m.setReferenciaTipo("PEDIDO");
        m.setReferenciaId(pedidoId);
        m.setCriadoPor(criadoPor);
        return m;
    }
}
