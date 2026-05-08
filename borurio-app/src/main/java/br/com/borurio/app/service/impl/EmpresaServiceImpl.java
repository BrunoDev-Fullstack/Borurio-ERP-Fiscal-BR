package br.com.borurio.app.service.impl;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.service.EmpresaService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class EmpresaServiceImpl implements EmpresaService {

    private final EmpresaMapper empresaMapper;

    public EmpresaServiceImpl(EmpresaMapper empresaMapper) {
        this.empresaMapper = empresaMapper;
    }

    @Override
    public List<Empresa> listarTodas() {
        return empresaMapper.listarTodas();
    }

    @Override
    public Empresa buscarPorId(Long id) {
        Empresa empresa = empresaMapper.buscarPorId(id);
        if (empresa == null) throw new IllegalArgumentException("Empresa não encontrada: id=" + id);
        return empresa;
    }

    @Override
    public Empresa buscarPorCnpj(String cnpj) {
        String apenasDigitos = cnpj != null ? cnpj.replaceAll("\\D", "") : "";
        Empresa empresa = empresaMapper.buscarPorCnpj(apenasDigitos);
        if (empresa == null) throw new IllegalArgumentException("Empresa não encontrada: cnpj=" + cnpj);
        return empresa;
    }

    @Override
    public Empresa salvar(Empresa empresa) {
        validar(empresa);
        empresa.setCnpj(empresa.getCnpj().replaceAll("\\D", ""));
        if (empresa.getCrt() == null) empresa.setCrt("1");
        if (empresa.getSerieNfePadrao() == null) empresa.setSerieNfePadrao("1");
        if (empresa.getAtivo() == null) empresa.setAtivo(true);
        empresaMapper.inserir(empresa);
        return empresa;
    }

    @Override
    public Empresa atualizar(Long id, Empresa empresa) {
        buscarPorId(id);
        validar(empresa);
        empresa.setId(id);
        empresa.setCnpj(empresa.getCnpj().replaceAll("\\D", ""));
        empresaMapper.atualizar(empresa);
        return empresaMapper.buscarPorId(id);
    }

    private void validar(Empresa empresa) {
        if (empresa.getCnpj() == null || empresa.getCnpj().isBlank())
            throw new IllegalArgumentException("CNPJ da empresa é obrigatório.");
        if (empresa.getRazaoSocial() == null || empresa.getRazaoSocial().isBlank())
            throw new IllegalArgumentException("Razão social da empresa é obrigatória.");
        if (empresa.getUf() == null || empresa.getUf().isBlank())
            throw new IllegalArgumentException("UF da empresa é obrigatória.");
    }
}
