package br.com.borurio.app.service;

import br.com.borurio.app.entity.Cliente;

import java.util.List;

public interface ClienteService {

    List<Cliente> listarTodos();

    List<Cliente> listarPorEmpresa(Long empresaId);

    Cliente buscarPorId(Long id);

    Cliente buscarPorCnpj(String cnpj);

    Cliente salvar(Cliente cliente);

    Cliente atualizar(Long id, Cliente cliente);

    boolean desativar(Long id);
}
