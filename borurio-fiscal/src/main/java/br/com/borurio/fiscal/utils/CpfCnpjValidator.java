package br.com.borurio.fiscal.utils;

public final class CpfCnpjValidator {

    private CpfCnpjValidator() {}

    public static void validar(String valor) {
        if (valor == null || valor.isBlank())
            throw new IllegalArgumentException("CNPJ/CPF do destinatário é obrigatório.");

        String digits = valor.replaceAll("\\D", "");

        if (digits.length() == 14) {
            validarCnpj(digits);
        } else if (digits.length() == 11) {
            validarCpf(digits);
        } else {
            throw new IllegalArgumentException(
                    "CNPJ/CPF inválido: deve ter 11 dígitos (CPF) ou 14 dígitos (CNPJ), recebido: " + digits.length());
        }
    }

    // -------------------------------------------------------------------------
    // CNPJ — Receita Federal, pesos módulo 11
    // 1º DV: pesos 5-4-3-2-9-8-7-6-5-4-3-2
    // 2º DV: pesos 6-5-4-3-2-9-8-7-6-5-4-3-2
    // -------------------------------------------------------------------------
    private static void validarCnpj(String cnpj) {
        if (todosIguais(cnpj))
            throw new IllegalArgumentException("CNPJ inválido.");

        int[] p1 = {5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};
        int[] p2 = {6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2};

        int soma = 0;
        for (int i = 0; i < 12; i++) soma += digit(cnpj, i) * p1[i];
        int dv1 = (soma % 11 < 2) ? 0 : 11 - (soma % 11);

        soma = 0;
        for (int i = 0; i < 13; i++) soma += digit(cnpj, i) * p2[i];
        int dv2 = (soma % 11 < 2) ? 0 : 11 - (soma % 11);

        if (digit(cnpj, 12) != dv1 || digit(cnpj, 13) != dv2)
            throw new IllegalArgumentException("CNPJ inválido: dígitos verificadores incorretos.");
    }

    // -------------------------------------------------------------------------
    // CPF — Receita Federal, pesos módulo 11
    // 1º DV: pesos 10-9-8-7-6-5-4-3-2
    // 2º DV: pesos 11-10-9-8-7-6-5-4-3-2
    // -------------------------------------------------------------------------
    private static void validarCpf(String cpf) {
        if (todosIguais(cpf))
            throw new IllegalArgumentException("CPF inválido.");

        int soma = 0;
        for (int i = 0; i < 9; i++) soma += digit(cpf, i) * (10 - i);
        int dv1 = (soma % 11 < 2) ? 0 : 11 - (soma % 11);
        if (digit(cpf, 9) != dv1)
            throw new IllegalArgumentException("CPF inválido: dígitos verificadores incorretos.");

        soma = 0;
        for (int i = 0; i < 10; i++) soma += digit(cpf, i) * (11 - i);
        int dv2 = (soma % 11 < 2) ? 0 : 11 - (soma % 11);
        if (digit(cpf, 10) != dv2)
            throw new IllegalArgumentException("CPF inválido: dígitos verificadores incorretos.");
    }

    private static int digit(String s, int pos) {
        return Character.getNumericValue(s.charAt(pos));
    }

    private static boolean todosIguais(String s) {
        return s.chars().distinct().count() == 1;
    }
}
