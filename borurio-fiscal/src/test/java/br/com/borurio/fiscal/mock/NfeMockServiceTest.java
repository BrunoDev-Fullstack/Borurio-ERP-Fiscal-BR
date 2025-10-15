package br.com.borurio.fiscal.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Classe de teste responsável por validar a existência e integridade
 * dos arquivos de mock utilizados na simulação da NF-e (modo SEFAZ mock).
 *
 * Este teste é do tipo unitário (sem dependência do contexto Spring Boot),
 * garantindo que os arquivos de payload JSON estejam disponíveis e válidos
 * antes da execução dos testes de integração fiscal (autorização e retorno).
 *
 * Boas práticas aplicadas:
 * - Isolamento completo (sem inicialização de ApplicationContext);
 * - Leitura segura via ClassPathResource e UTF-8;
 * - Validação semântica mínima (campos esperados nos mocks);
 * - Assertivas explícitas e mensagens descritivas;
 * - Padrão nacionalizado de documentação técnica.
 *
 * @author Bruno Ribeiro
 * @version 1.0.0
 * @since Sprint Fiscal 2.5 – Mock SEFAZ / NF-e 4.00
 */
class NfeMockServiceTest {

    @Test
    @DisplayName("Deve validar o conteúdo do arquivo mock-nfe-request.json")
    void deveValidarMockRequestJson() throws Exception {
        ClassPathResource resource = new ClassPathResource("mock-nfe-request.json");
        assertTrue(resource.exists(), "O arquivo mock-nfe-request.json não foi encontrado no classpath.");

        try (InputStream inputStream = resource.getInputStream()) {
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

            assertNotNull(content, "O conteúdo do arquivo mock-nfe-request.json está nulo ou inacessível.");
            assertFalse(content.isBlank(), "O conteúdo do arquivo mock-nfe-request.json está vazio.");

            // Campos essenciais esperados no payload de envio NF-e
            assertTrue(content.contains("\"modelo\": \"55\""),
                    "O campo 'modelo' (modelo 55 - NF-e) não foi encontrado no mock-nfe-request.json.");
            assertTrue(content.contains("\"valorTotal\""),
                    "O campo 'valorTotal' não foi encontrado no mock-nfe-request.json.");
        }
    }

    @Test
    @DisplayName("Deve validar o conteúdo do arquivo mock-nfe-response.json")
    void deveValidarMockResponseJson() throws Exception {
        ClassPathResource resource = new ClassPathResource("mock-nfe-response.json");
        assertTrue(resource.exists(), "O arquivo mock-nfe-response.json não foi encontrado no classpath.");

        try (InputStream inputStream = resource.getInputStream()) {
            String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);

            assertNotNull(content, "O conteúdo do arquivo mock-nfe-response.json está nulo ou inacessível.");
            assertFalse(content.isBlank(), "O conteúdo do arquivo mock-nfe-response.json está vazio.");

            // Campos esperados no payload de resposta NF-e mock
            assertTrue(content.contains("\"code\": 200"),
                    "O campo 'code' (HTTP 200) não foi encontrado no mock-nfe-response.json.");
            assertTrue(content.contains("\"message\""),
                    "O campo 'message' não foi encontrado no mock-nfe-response.json.");
        }
    }
}
