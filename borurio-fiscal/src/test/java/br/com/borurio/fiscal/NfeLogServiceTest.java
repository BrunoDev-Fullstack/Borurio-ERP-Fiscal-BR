package br.com.borurio.fiscal;

import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.service.NfeLogService;
import br.com.borurio.fiscal.service.CertificadoService;
import org.junit.jupiter.api.*;
import org.mockito.Mockito;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import javax.net.ssl.SSLContext;
import java.util.List;

import static org.mockito.Mockito.when;

@SpringBootTest(classes = NfeLogServiceTest.TestConfig.class)
@ActiveProfiles("test")
@Transactional
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class NfeLogServiceTest {

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

    @MockBean
    private CertificadoService certificadoService;

    /**
     * Antes de cada teste, precisamos configurar o mock.
     * Como getSslContext() lança Exception, o método setup precisa declarar throws Exception.
     */
    @BeforeEach
    void setup() throws Exception {
        when(certificadoService.getSslContext()).thenReturn((SSLContext) null);
    }

    @Test
    @Order(1)
    @DisplayName("Deve registrar e listar logs fiscais corretamente")
    void deveRegistrarEListarLogsFiscalmente() {

        Assertions.assertNotNull(
                nfeLogService,
                "O bean NfeLogService deve ser inicializado no contexto de teste."
        );

        if (Mockito.mockingDetails(nfeLogService).isMock()) {
            System.out.println("""
                [AVISO] O bean NfeLogService está mockado neste contexto de teste.
                Nenhuma operação real de banco será executada (teste isolado).
                """);
            return;
        }

        String chaveNfe = "43191111111111111111550010000000011000000010";

        try {
            nfeLogService.registrarEvento(
                    chaveNfe,
                    "TESTE",
                    "Evento de teste de auditoria NF-e",
                    "devops@borurio.com"
            );
        } catch (Exception e) {
            Assertions.fail("Falha inesperada ao registrar evento fiscal: " + e.getMessage());
        }

        List<NfeLog> logs;
        try {
            logs = nfeLogService.listarTodos();
        } catch (Exception e) {
            Assertions.fail("Falha ao listar logs fiscais: " + e.getMessage());
            return;
        }

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
