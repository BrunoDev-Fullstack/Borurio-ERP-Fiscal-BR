package br.com.borurio.app.service.impl;

import br.com.borurio.app.entity.Produto;
import br.com.borurio.app.mapper.ProdutoMapper;
import br.com.borurio.app.service.ProdutoService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ProdutoServiceImpl implements ProdutoService {

    private final ProdutoMapper produtoMapper;

    public ProdutoServiceImpl(ProdutoMapper produtoMapper) {
        this.produtoMapper = produtoMapper;
    }

    @Override
    public List<Produto> listarTodos() {
        return produtoMapper.listarTodos();
    }

    @Override
    public List<Produto> listarAtivos() {
        return produtoMapper.listarAtivos();
    }

    @Override
    public Produto buscarPorId(Long id) {
        Produto produto = produtoMapper.buscarPorId(id);
        if (produto == null) {
            throw new IllegalArgumentException("Produto não encontrado: id=" + id);
        }
        return produto;
    }

    @Override
    public Produto buscarPorCodigo(String codigo) {
        Produto produto = produtoMapper.buscarPorCodigo(codigo);
        if (produto == null) {
            throw new IllegalArgumentException("Produto não encontrado: codigo=" + codigo);
        }
        return produto;
    }

    @Override
    public Produto salvar(Produto produto) {
        validar(produto);
        if (produtoMapper.buscarPorCodigo(produto.getCodigo()) != null) {
            throw new IllegalArgumentException("Já existe um produto com o código: " + produto.getCodigo());
        }
        produto.setEstado(1);
        aplicarDefaultsFiscais(produto);
        produtoMapper.inserir(produto);
        return produto;
    }

    @Override
    public Produto atualizar(Long id, Produto produto) {
        Produto existente = buscarPorId(id);
        if (!existente.getCodigo().equals(produto.getCodigo())) {
            Produto comMesmoCodigo = produtoMapper.buscarPorCodigo(produto.getCodigo());
            if (comMesmoCodigo != null && !comMesmoCodigo.getId().equals(id)) {
                throw new IllegalArgumentException("Já existe outro produto com o código: " + produto.getCodigo());
            }
        }
        validar(produto);
        produto.setId(id);
        produto.setEstado(existente.getEstado());
        aplicarDefaultsFiscais(produto);
        produtoMapper.atualizar(produto);
        return produtoMapper.buscarPorId(id);
    }

    @Override
    public void desativar(Long id) {
        buscarPorId(id);
        produtoMapper.desativar(id);
    }

    private void aplicarDefaultsFiscais(Produto p) {
        if (p.getOrigem() == null) p.setOrigem(0);
        if (p.getCsosn() == null || p.getCsosn().isBlank()) p.setCsosn("400");
        if (p.getEstoque() == null) p.setEstoque(java.math.BigDecimal.ZERO);
    }

    private void validar(Produto p) {
        if (p.getCodigo() == null || p.getCodigo().isBlank()) {
            throw new IllegalArgumentException("Código do produto é obrigatório.");
        }
        if (p.getDescricao() == null || p.getDescricao().isBlank()) {
            throw new IllegalArgumentException("Descrição do produto é obrigatória.");
        }
        if (p.getNcm() == null || !p.getNcm().matches("\\d{8}")) {
            throw new IllegalArgumentException("NCM deve ter exatamente 8 dígitos numéricos.");
        }
        if (p.getCfop() == null || !p.getCfop().matches("\\d{4}")) {
            throw new IllegalArgumentException("CFOP deve ter exatamente 4 dígitos numéricos.");
        }
        if (p.getUnidade() == null || p.getUnidade().isBlank()) {
            throw new IllegalArgumentException("Unidade comercial é obrigatória.");
        }
        if (p.getPreco() == null || p.getPreco().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Preço deve ser maior que zero.");
        }
    }
}
