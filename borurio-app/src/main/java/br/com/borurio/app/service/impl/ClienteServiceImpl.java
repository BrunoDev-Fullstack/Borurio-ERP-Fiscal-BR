package br.com.borurio.app.service.impl;

import br.com.borurio.app.entity.Cliente;
import br.com.borurio.app.mapper.ClienteMapper;
import br.com.borurio.app.service.ClienteService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ClienteServiceImpl implements ClienteService {

    private final ClienteMapper clienteMapper;

    public ClienteServiceImpl(ClienteMapper clienteMapper) {
        this.clienteMapper = clienteMapper;
    }

    @Override
    public List<Cliente> listarTodos() {
        return clienteMapper.listarTodos();
    }

    @Override
    public Cliente buscarPorId(Long id) {
        return clienteMapper.buscarPorId(id);
    }

    @Override
    public Cliente salvar(Cliente cliente) {
        clienteMapper.inserir(cliente);
        return cliente;
    }

    @Override
    public Cliente atualizar(Long id, Cliente cliente) {
        cliente.setId(id);
        clienteMapper.atualizar(cliente);
        return cliente;
    }

    @Override
    public boolean desativar(Long id) {
        return clienteMapper.desativar(id) > 0;
    }
}

