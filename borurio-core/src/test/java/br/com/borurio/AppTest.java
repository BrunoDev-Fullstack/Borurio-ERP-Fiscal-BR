package br.com.borurio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste básico de inicialização do módulo borurio-core.
 *
 * <p>Este teste tem como objetivo validar a configuração
 * do ambiente de testes com JUnit 5 e garantir que o
 * pipeline de build (Maven + CI/CD) reconheça o módulo</p>
 *
 * <p>Boas práticas aplicadas:
 * - Padrão JUnit 5 (org.junit.jupiter.api)
 * - Uso de @DisplayName para descrição legível
 * - Assertions modernas do pacote org.junit.jupiter.api.Assertions
 * - Compatível com Maven Surefire Plugin e pipelines DevSecOps</p>
 */
public class AppTest {

    @Test
    @DisplayName("Ambiente de testes do módulo borurio-core inicializado com sucesso")
    void testApp() {
        assertTrue(true, "O ambiente de teste está operacional.");
    }
}
