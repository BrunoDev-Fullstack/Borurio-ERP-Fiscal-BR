package br.com.borurio.fiscal.mock;

import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.service.NfeAuthorizeService;
import br.com.borurio.fiscal.service.NfeLogService;
import br.com.borurio.fiscal.service.impl.NfeAuthorizeServiceImpl;
import br.com.borurio.fiscal.utils.XsdValidator;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;

/**
 * Teste de integração leve responsável por validar o fluxo completo
 * de autorização mock SEFAZ no ambiente fiscal do ERP Borurio Brasil.
 *
 * Este teste:
 *  - NÃO inicializa banco nem MyBatis;
 *  - Valida XML contra o XSD oficial (PL009 - NF-e 4.00);
 *  - Simula protocolo SEFAZ local com cStat=100;
 *  - Registra o evento via mock de NfeLogService;
 *  - Garante o comportamento esperado do serviço de autorização.
 *
 * Boas práticas aplicadas:
 *  - Isolamento completo (sem dependência externa);
 *  - Beans configurados manualmente via @TestConfiguration;
 *  - Logging detalhado de cada etapa do fluxo;
 *  - Linguagem técnica nacionalizada e alinhada à SEFAZ-SP.
 *
 * Autor: Bruno Ribeiro — DevSecOps Fiscal BR
 * Versão: Sprint 2.5 — Mock SEFAZ NF-e 4.00
 */
@SpringJUnitConfig(classes = NfeAuthorizeServiceTest.Config.class)
public class NfeAuthorizeServiceTest {

    /**
     * Configuração interna de beans para o contexto de teste isolado.
     * Não depende de nenhum outro módulo ou datasource real.
     */
    @TestConfiguration
    static class Config {

        @Bean
        public XsdValidator xsdValidator() {
            return new XsdValidator();
        }

        @Bean
        public NfeLogService nfeLogService() {
            // Mock completo do serviço de log fiscal (sem dependência de banco)
            return new NfeLogService() {

                @Override
                public void registrarEvento(String tipo, String descricao, String chave, String xml) {
                    System.out.println("[MOCK-LOG] Evento registrado:");
                    System.out.println("  Tipo: " + tipo);
                    System.out.println("  Descrição: " + descricao);
                    System.out.println("  Chave: " + chave);
                }

                @Override
                public void salvar(NfeLog log) {
                    System.out.println("[MOCK-LOG] Salvando objeto NfeLog (simulação).");
                }

                @Override
                public List<NfeLog> listarTodos() {
                    System.out.println("[MOCK-LOG] listando logs (simulação).");
                    return Collections.emptyList();
                }

                @Override
                public List<NfeLog> buscarPorChave(String chave) {
                    System.out.println("[MOCK-LOG] buscando logs pela chave: " + chave);
                    return Collections.emptyList();
                }

                @Override
                public int contarEventos(String chaveNfe, String tipoEvento) {
                    return 0;
                }

                @Override
                public br.com.borurio.core.mvc.api.PageResponse<NfeLog> listarPaginado(Long empresaId, int page, int size) {
                    return br.com.borurio.core.mvc.api.PageResponse.of(Collections.emptyList(), page, size, 0L);
                }
            };
        }

        @Bean
        public NfeAuthorizeService nfeAuthorizeService() {
            return new NfeAuthorizeServiceImpl(nfeLogService(), xsdValidator());
        }
    }

    // Injeção direta pelo contexto de teste configurado
    @Autowired
    private NfeAuthorizeService nfeAuthorizeService;

    /**
     * Teste principal: executa o fluxo mock SEFAZ completo e
     * valida o XML de resposta com status de autorização 100.
     */
    @Test
    @DisplayName("Deve autorizar NF-e mock com status 100 e xMotivo válido (sem banco)")
    public void deveAutorizarNFeMockComSucesso() throws Exception {
        // Arrange
        InputStream xmlInput = new ClassPathResource("xml/mockEnviNFe.xml").getInputStream();
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document xmlDocumento = builder.parse(xmlInput);

        // Act
        Document resposta = nfeAuthorizeService.autorizarNFe(xmlDocumento);

        // Assert
        Element cStat = (Element) resposta.getElementsByTagName("cStat").item(0);
        Element xMotivo = (Element) resposta.getElementsByTagName("xMotivo").item(0);

        Assertions.assertNotNull(cStat, "Elemento <cStat> deve estar presente.");
        Assertions.assertNotNull(xMotivo, "Elemento <xMotivo> deve estar presente.");
        Assertions.assertEquals("100", cStat.getTextContent().trim(),
                "O código de status deve ser 100 (Autorizado o uso da NF-e).");
        Assertions.assertTrue(xMotivo.getTextContent().contains("Autorizado o uso da NF-e"),
                "O motivo deve indicar autorização do uso da NF-e.");

        System.out.println("[TESTE OK] NF-e mock autorizada com sucesso — status 100.");
    }
}
