package br.com.borurio.app.service;

import br.com.borurio.app.entity.Produto;
import br.com.borurio.core.mvc.api.PageResponse;

import java.util.List;

public interface ProdutoService {

    List<Produto> listarTodos();

    List<Produto> listarAtivos();

    List<Produto> listarPorEmpresa(Long empresaId);

    List<Produto> listarAtivosPorEmpresa(Long empresaId);

    Produto buscarPorId(Long id);

    Produto buscarPorIdEEmpresa(Long id, Long empresaId);

    Produto buscarPorCodigo(String codigo);

    Produto salvar(Produto produto);

    Produto atualizar(Long id, Produto produto);

    void desativar(Long id);

    PageResponse<Produto> listarPaginado(Long empresaId, int page, int size);
}
