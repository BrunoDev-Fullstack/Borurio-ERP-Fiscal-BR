package br.com.borurio.app.service;

import br.com.borurio.app.entity.Produto;

import java.util.List;

public interface ProdutoService {

    List<Produto> listarTodos();

    List<Produto> listarAtivos();

    Produto buscarPorId(Long id);

    Produto buscarPorCodigo(String codigo);

    Produto salvar(Produto produto);

    Produto atualizar(Long id, Produto produto);

    void desativar(Long id);
}
