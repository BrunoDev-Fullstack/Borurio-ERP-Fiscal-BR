package br.com.borurio.app.service;

import br.com.borurio.app.entity.Empresa;

import java.util.List;

public interface EmpresaService {
    List<Empresa> listarTodas();
    Empresa buscarPorId(Long id);
    Empresa buscarPorCnpj(String cnpj);
    Empresa salvar(Empresa empresa);
    Empresa atualizar(Long id, Empresa empresa);
}
