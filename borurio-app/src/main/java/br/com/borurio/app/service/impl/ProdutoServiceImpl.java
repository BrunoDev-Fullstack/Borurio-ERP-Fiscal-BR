package br.com.borurio.app.service.impl;

import br.com.borurio.app.entity.Produto;
import br.com.borurio.app.mapper.ProdutoMapper;
import br.com.borurio.app.service.ProdutoService;
import br.com.borurio.core.mvc.api.PageResponse;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.NoSuchElementException;

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
    public List<Produto> listarPorEmpresa(Long empresaId) {
        return produtoMapper.listarPorEmpresa(empresaId);
    }

    @Override
    public List<Produto> listarAtivosPorEmpresa(Long empresaId) {
        return produtoMapper.listarAtivosPorEmpresa(empresaId);
    }

    @Override
    public Produto buscarPorId(Long id) {
        Produto produto = produtoMapper.buscarPorId(id);
        if (produto == null) {
            throw new NoSuchElementException("Produto não encontrado: id=" + id);
        }
        return produto;
    }

    @Override
    public Produto buscarPorIdEEmpresa(Long id, Long empresaId) {
        Produto produto = produtoMapper.buscarPorIdEEmpresa(id, empresaId);
        if (produto == null) {
            throw new NoSuchElementException("Produto não encontrado: id=" + id);
        }
        return produto;
    }

    @Override
    public Produto buscarPorCodigo(String codigo) {
        Produto produto = produtoMapper.buscarPorCodigo(codigo);
        if (produto == null) {
            throw new NoSuchElementException("Produto não encontrado: codigo=" + codigo);
        }
        return produto;
    }

    @Override
    public Produto salvar(Produto produto) {
        aplicarDefaultsFiscais(produto);
        validar(produto);
        Produto existenteCodigo = produto.getEmpresaId() != null
                ? produtoMapper.buscarPorCodigoEEmpresa(produto.getCodigo(), produto.getEmpresaId())
                : produtoMapper.buscarPorCodigo(produto.getCodigo());
        if (existenteCodigo != null) {
            throw new IllegalArgumentException("Já existe um produto com o código: " + produto.getCodigo());
        }
        produto.setEstado(1);
        produtoMapper.inserir(produto);
        return produto;
    }

    @Override
    public Produto atualizar(Long id, Produto produto) {
        Produto existente = buscarPorId(id);
        if (!existente.getCodigo().equals(produto.getCodigo())) {
            Produto comMesmoCodigo = existente.getEmpresaId() != null
                    ? produtoMapper.buscarPorCodigoEEmpresa(produto.getCodigo(), existente.getEmpresaId())
                    : produtoMapper.buscarPorCodigo(produto.getCodigo());
            if (comMesmoCodigo != null && !comMesmoCodigo.getId().equals(id)) {
                throw new IllegalArgumentException("Já existe outro produto com o código: " + produto.getCodigo());
            }
        }
        aplicarDefaultsFiscais(produto);
        validar(produto);
        produto.setId(id);
        produto.setEmpresaId(existente.getEmpresaId());
        produto.setEstado(existente.getEstado());
        produtoMapper.atualizar(produto);
        return produtoMapper.buscarPorId(id);
    }

    @Override
    public PageResponse<Produto> listarPaginado(Long empresaId, int page, int size) {
        int offset = page * size;
        List<Produto> content;
        long total;
        if (empresaId != null) {
            content = produtoMapper.listarPorEmpresaPaginado(empresaId, size, offset);
            total   = produtoMapper.countPorEmpresa(empresaId);
        } else {
            content = produtoMapper.listarTodosPaginado(size, offset);
            total   = produtoMapper.countTodos();
        }
        return PageResponse.of(content, page, size, total);
    }

    @Override
    public void desativar(Long id) {
        buscarPorId(id);
        produtoMapper.desativar(id);
    }

    private void aplicarDefaultsFiscais(Produto p) {
        if (p.getOrigem() == null) p.setOrigem(0);
        if (p.getCsosn() == null || p.getCsosn().isBlank()) p.setCsosn("400");
        if (p.getCfop() == null || p.getCfop().isBlank()) p.setCfop("5102");
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
