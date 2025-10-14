package br.com.borurio.fiscal;

import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.service.NfeLogService;
import br.com.borurio.fiscal.service.CertificadoService;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.net.ssl.SSLContext;
import java.util.List;

import static org.mockito.Mockito.when;

/**
 * Teste de integração do serviço de auditoria fiscal (NfeLogService).
 *
 * Este teste executa em um ambiente de teste isolado ("test"),
 * validando o registro e a listagem de logs fiscais no banco de dados.
 *
 * Boas práticas aplicadas:
 * - Execução em transação com rollback automático;
 * - Mock do CertificadoService para evitar carga do PFX real;
 * - Perfil "test" ativo (application-test.yml);
 * - Varredura de componentes e mapeamentos MyBatis no pacote fiscal;
 * - Assertivas descritivas e rastreáveis.
 *
 * @author Bruno Ribeiro
 * @since Sprint Fiscal 2.2 – Integração SEFAZ-SP / NF-e 4.00
 */
@SpringBootTest(classes = NfeLogServiceTest.TestConfig.class)
@ActiveProfiles("test")
@Transactional
public class NfeLogServiceTest {

    /**
     * Configuração mínima do contexto Spring Boot para o módulo fiscal.
     * Inclui varredura completa de beans, mappers e configuração de datasource.
     */
    @ComponentScan(basePackages = "br.com.borurio.fiscal")
    @ImportAutoConfiguration({DataSourceAutoConfiguration.class})
    static class TestConfig {
    }

    @Autowired
    private NfeLogService nfeLogService;

    /**
     * Mock para o serviço de certificado digital, evitando falha
     * de contexto durante a inicialização dos testes.
     */
    @MockBean
    private CertificadoService certificadoService;

    /**
     * Configuração prévia antes de cada teste.
     * Simula o certificado como nulo (não necessário neste cenário).
     */
    @BeforeEach
    void setup() {
        when(certificadoService.getSslContext()).thenReturn((SSLContext) null);
    }

    /**
     * Valida o registro e a listagem de logs fiscais.
     * Espera-se que, após a inserção, a lista contenha ao menos um log.
     */
    @Test
    @DisplayName("Deve registrar e listar logs fiscais corretamente")
    void deveRegistrarEListarLogsFiscalmente() {
        // Chave de acesso NF-e simulada (44 caracteres numéricos)
        String chaveNfe = "43191111111111111111550010000000011000000010";

        nfeLogService.registrarEvento(
                chaveNfe,
                "TESTE",
                "Evento de teste de auditoria NF-e",
                "devops@borurio.com"
        );

        List<NfeLog> logs = nfeLogService.listarTodos();

        Assertions.assertFalse(
                logs.isEmpty(),
                "A lista de logs não deve estar vazia após a inserção de um evento."
        );

        Assertions.assertTrue(
                logs.stream().anyMatch(l -> chaveNfe.equals(l.getChaveNfe())),
                "Deve existir um log associado à chave NF-e inserida."
        );
    }
}
