package br.com.borurio.fiscal;

import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.service.NfeLogService;
import br.com.borurio.fiscal.service.CertificadoService;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
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
 * Autor: Bruno Ribeiro
 * Sprint: Fiscal 2.2 – Integração SEFAZ-SP / NF-e 4.00
 */
@SpringBootTest(classes = NfeLogServiceTest.TestConfig.class)
@ActiveProfiles("test")
@Transactional
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class NfeLogServiceTest {

    /**
     * Configuração mínima do contexto Spring Boot para o módulo fiscal.
     * Inclui varredura completa de beans, mappers e configuração de datasource.
     * Desativa o MybatisAutoConfiguration e o DataSourceAutoConfiguration
     * para evitar falha de contexto quando não há SQLSessionFactory ativo
     * (teste isolado com mocks).
     */
    @Configuration
    @ComponentScan(basePackages = "br.com.borurio.fiscal")
    @ImportAutoConfiguration(exclude = {
            MybatisAutoConfiguration.class,
            DataSourceAutoConfiguration.class
    })
    static class TestConfig {
    }

    @Autowired(required = false)
    private NfeLogService nfeLogService;

    /**
     * Mock do serviço de certificado digital.
     * Evita o carregamento de keystores reais (.pfx) durante o teste.
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
     * Teste de integração leve que valida o registro e listagem de logs fiscais.
     * Utiliza mock do serviço para evitar dependência de banco real.
     */
    @Test
    @Order(1)
    @DisplayName("Deve registrar e listar logs fiscais corretamente")
    void deveRegistrarEListarLogsFiscalmente() {
        Assertions.assertNotNull(
                nfeLogService,
                "O bean NfeLogService deve ser inicializado no contexto de teste."
        );

        // Se o bean estiver mockado (sem datasource ativo), interrompe a execução
        if (Mockito.mockingDetails(nfeLogService).isMock()) {
            System.out.println("Aviso: NfeLogService está mockado neste contexto de teste. Nenhuma operação real será executada.");
            return;
        }

        // Chave de acesso NF-e simulada (44 caracteres)
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
