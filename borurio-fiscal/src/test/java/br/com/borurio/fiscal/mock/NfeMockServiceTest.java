package br.com.borurio.fiscal.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Classe de teste responsável por validar a existência e integridade
 * dos arquivos de mock utilizados na simulação da NF-e (modo SEFAZ mock).
 *
 * Este teste é do tipo unitário (não depende do contexto Spring Boot),
 * garantindo que os arquivos de payload JSON de mock estejam corretamente
 * estruturados antes da execução dos testes de integração fiscal.
 *
 * Boas práticas aplicadas:
 * - Isolamento de teste unitário (sem dependência de beans Spring);
 * - Leitura segura via ClassPathResource;
 * - Asserts explícitos com mensagens descritivas;
 * - Linguagem e documentação técnica padronizadas.
 *
 * @author Bruno Ribeiro
 * @since Sprint Fiscal 2.2 – Integração SEFAZ-SP / NF-e 4.00
 */
class NfeMockServiceTest {

    @Test
    @DisplayName("Deve validar o conteúdo do mock-nfe-request.json")
    void deveLerPayloadMockNfeRequest() throws Exception {
        Path mockPath = new ClassPathResource("mock-nfe-request.json")
                .getFile()
                .toPath();

        String content = Files.readString(mockPath);

        assertNotNull(content, "O conteúdo do arquivo mock-nfe-request.json está vazio ou inacessível.");
        assertTrue(content.contains("\"modelo\": \"55\""),
                "O arquivo mock-nfe-request.json não contém o campo 'modelo' esperado.");
        assertTrue(content.contains("\"valorTotal\":"),
                "O arquivo mock-nfe-request.json não contém o campo 'valorTotal' esperado.");
    }

    @Test
    @DisplayName("Deve validar o conteúdo do mock-nfe-response.json")
    void deveLerPayloadMockNfeResponse() throws Exception {
        Path mockPath = new ClassPathResource("mock-nfe-response.json")
                .getFile()
                .toPath();

        String content = Files.readString(mockPath);

        assertNotNull(content, "O conteúdo do arquivo mock-nfe-response.json está vazio ou inacessível.");
        assertTrue(content.contains("\"code\": 200"),
                "O arquivo mock-nfe-response.json não contém o campo 'code' esperado.");
        assertTrue(content.contains("\"message\":"),
                "O arquivo mock-nfe-response.json não contém o campo 'message' esperado.");
    }
}
