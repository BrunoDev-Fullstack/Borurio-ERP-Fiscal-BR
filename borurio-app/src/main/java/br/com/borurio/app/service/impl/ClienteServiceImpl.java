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
    public Cliente buscarPorCnpj(String cnpj) {
        if (cnpj == null || cnpj.isBlank()) {
            throw new IllegalArgumentException("CNPJ não pode ser vazio.");
        }
        return clienteMapper.buscarPorCnpj(cnpj.replaceAll("\\D", ""));
    }

    @Override
    public Cliente salvar(Cliente cliente) {
        normalizar(cliente);
        validar(cliente);
        if ("PJ".equals(cliente.getTipoPessoa())
                && clienteMapper.buscarPorCnpj(cliente.getCnpj()) != null) {
            throw new IllegalArgumentException("CNPJ já cadastrado.");
        }
        clienteMapper.inserir(cliente);
        return cliente;
    }

    @Override
    public Cliente atualizar(Long id, Cliente cliente) {
        normalizar(cliente);
        validar(cliente);
        cliente.setId(id);
        int rows = clienteMapper.atualizar(cliente);
        if (rows == 0) {
            return null;
        }
        return cliente;
    }

    @Override
    public boolean desativar(Long id) {
        return clienteMapper.desativar(id) > 0;
    }

    // =========================================================================
    // NORMALIZAÇÃO — remove pontuação antes de qualquer validação ou persistência
    // =========================================================================

    private void normalizar(Cliente cliente) {
        if (cliente.getCnpj() != null) {
            cliente.setCnpj(cliente.getCnpj().replaceAll("\\D", ""));
        }
        if (cliente.getCpf() != null) {
            cliente.setCpf(cliente.getCpf().replaceAll("\\D", ""));
        }
    }

    // =========================================================================
    // VALIDAÇÃO
    // =========================================================================

    private void validar(Cliente cliente) {
        validarTipoPessoa(cliente);
        validarIdentificador(cliente);
        validarRazaoSocial(cliente);
    }

    private void validarTipoPessoa(Cliente cliente) {
        String tipo = cliente.getTipoPessoa();
        if (tipo == null || (!tipo.equals("PJ") && !tipo.equals("PF"))) {
            throw new IllegalArgumentException("tipoPessoa deve ser 'PJ' ou 'PF'.");
        }
    }

    private void validarIdentificador(Cliente cliente) {
        if ("PJ".equals(cliente.getTipoPessoa())) {
            validarCnpj(cliente.getCnpj());
        } else {
            validarCpf(cliente.getCpf());
        }
    }

    private void validarCnpj(String cnpj) {
        if (cnpj == null || cnpj.isBlank()) {
            throw new IllegalArgumentException("CNPJ é obrigatório para Pessoa Jurídica.");
        }
        if (cnpj.length() != 14) {
            throw new IllegalArgumentException("CNPJ deve conter 14 dígitos.");
        }
        if (todosDigitosIguais(cnpj)) {
            throw new IllegalArgumentException("CNPJ inválido.");
        }
        if (!cnpjDigitosVerificadoresValidos(cnpj)) {
            throw new IllegalArgumentException("CNPJ inválido.");
        }
    }

    private void validarCpf(String cpf) {
        if (cpf == null || cpf.isBlank()) {
            throw new IllegalArgumentException("CPF é obrigatório para Pessoa Física.");
        }
        if (cpf.length() != 11) {
            throw new IllegalArgumentException("CPF deve conter 11 dígitos.");
        }
        if (todosDigitosIguais(cpf)) {
            throw new IllegalArgumentException("CPF inválido.");
        }
        if (!cpfDigitosVerificadoresValidos(cpf)) {
            throw new IllegalArgumentException("CPF inválido.");
        }
    }

    private void validarRazaoSocial(Cliente cliente) {
        if (cliente.getRazaoSocial() == null || cliente.getRazaoSocial().isBlank()) {
            throw new IllegalArgumentException("Razão social / nome completo é obrigatório.");
        }
    }

    // =========================================================================
    // ALGORITMOS DE DÍGITOS VERIFICADORES — Receita Federal (módulo 11)
    // =========================================================================

    // Pesos: 5-4-3-2-9-8-7-6-5-4-3-2 (1º DV) e 6-5-4-3-2-9-8-7-6-5-4-3-2 (2º DV)
    private boolean cnpjDigitosVerificadoresValidos(String cnpj) {
        int[] pesos1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] pesos2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

        int soma = 0;
        for (int i = 0; i < 12; i++) {
            soma += Character.getNumericValue(cnpj.charAt(i)) * pesos1[i];
        }
        int resto = soma % 11;
        int dig1 = (resto < 2) ? 0 : 11 - resto;

        soma = 0;
        for (int i = 0; i < 13; i++) {
            soma += Character.getNumericValue(cnpj.charAt(i)) * pesos2[i];
        }
        resto = soma % 11;
        int dig2 = (resto < 2) ? 0 : 11 - resto;

        return (cnpj.charAt(12) - '0') == dig1 && (cnpj.charAt(13) - '0') == dig2;
    }

    // Pesos: 10-9-8-7-6-5-4-3-2 (1º DV) e 11-10-9-8-7-6-5-4-3-2 (2º DV)
    private boolean cpfDigitosVerificadoresValidos(String cpf) {
        int soma = 0;
        for (int i = 0; i < 9; i++) {
            soma += Character.getNumericValue(cpf.charAt(i)) * (10 - i);
        }
        int resto = soma % 11;
        int dig1 = (resto < 2) ? 0 : 11 - resto;
        if ((cpf.charAt(9) - '0') != dig1) return false;

        soma = 0;
        for (int i = 0; i < 10; i++) {
            soma += Character.getNumericValue(cpf.charAt(i)) * (11 - i);
        }
        resto = soma % 11;
        int dig2 = (resto < 2) ? 0 : 11 - resto;
        return (cpf.charAt(10) - '0') == dig2;
    }

    private boolean todosDigitosIguais(String digits) {
        return digits.chars().distinct().count() == 1;
    }
}
