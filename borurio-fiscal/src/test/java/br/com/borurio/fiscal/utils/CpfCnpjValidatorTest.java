package br.com.borurio.fiscal.utils;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CpfCnpjValidator — módulo 11 Receita Federal")
public class CpfCnpjValidatorTest {

    // -------------------------------------------------------------------------
    // CNPJ válidos
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Deve aceitar CNPJ válido (apenas dígitos)")
    void cnpjValidoDigitos() {
        assertDoesNotThrow(() -> CpfCnpjValidator.validar("54393421000159"));
    }

    @Test
    @DisplayName("Deve aceitar CNPJ válido com pontuação")
    void cnpjValidoPontuacao() {
        assertDoesNotThrow(() -> CpfCnpjValidator.validar("54.393.421/0001-59"));
    }

    // -------------------------------------------------------------------------
    // CNPJ inválidos
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Deve rejeitar CNPJ com DV incorreto")
    void cnpjDvIncorreto() {
        // último dígito alterado
        var ex = assertThrows(IllegalArgumentException.class,
                () -> CpfCnpjValidator.validar("54393421000158"));
        assertTrue(ex.getMessage().contains("CNPJ inválido"));
    }

    @Test
    @DisplayName("Deve rejeitar CNPJ com todos os dígitos iguais")
    void cnpjTodosIguais() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> CpfCnpjValidator.validar("11111111111111"));
        assertTrue(ex.getMessage().contains("CNPJ inválido"));
    }

    @Test
    @DisplayName("Deve rejeitar CNPJ com tamanho inválido (13 dígitos)")
    void cnpjTamanhoInvalido() {
        assertThrows(IllegalArgumentException.class,
                () -> CpfCnpjValidator.validar("1234567890123"));
    }

    // -------------------------------------------------------------------------
    // CPF válidos
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Deve aceitar CPF válido (apenas dígitos)")
    void cpfValidoDigitos() {
        // CPF gerado válido para testes
        assertDoesNotThrow(() -> CpfCnpjValidator.validar("52998224725"));
    }

    @Test
    @DisplayName("Deve aceitar CPF válido com pontuação")
    void cpfValidoPontuacao() {
        assertDoesNotThrow(() -> CpfCnpjValidator.validar("529.982.247-25"));
    }

    // -------------------------------------------------------------------------
    // CPF inválidos
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Deve rejeitar CPF com DV incorreto")
    void cpfDvIncorreto() {
        // último dígito alterado
        var ex = assertThrows(IllegalArgumentException.class,
                () -> CpfCnpjValidator.validar("52998224726"));
        assertTrue(ex.getMessage().contains("CPF inválido"));
    }

    @Test
    @DisplayName("Deve rejeitar CPF com todos os dígitos iguais")
    void cpfTodosIguais() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> CpfCnpjValidator.validar("00000000000"));
        assertTrue(ex.getMessage().contains("CPF inválido"));
    }

    // -------------------------------------------------------------------------
    // Nulo / vazio
    // -------------------------------------------------------------------------
    @Test
    @DisplayName("Deve rejeitar valor nulo")
    void nulo() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> CpfCnpjValidator.validar(null));
        assertTrue(ex.getMessage().contains("obrigatório"));
    }

    @Test
    @DisplayName("Deve rejeitar valor em branco")
    void vazio() {
        var ex = assertThrows(IllegalArgumentException.class,
                () -> CpfCnpjValidator.validar("   "));
        assertTrue(ex.getMessage().contains("obrigatório"));
    }
}
