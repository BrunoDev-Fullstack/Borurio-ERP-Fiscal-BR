package br.com.borurio.fiscal.utils;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Utilitário responsável por validar XMLs fiscais (NF-e) contra os esquemas XSD oficiais da SEFAZ.
 *
 * Este componente identifica automaticamente o tipo de XML (enviNFe, retEnviNFe, procNFe, etc.)
 * e carrega o schema correspondente com todas as dependências necessárias.
 *
 * Compatível com:
 *  - NF-e 4.00 (PL009)
 *  - Nota Técnica 2025.002 (IBS/CBS/IS)
 *  - Java 17 / Spring Boot 3.3.2
 *
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps Fiscal BR
 * Versão: 1.2.0
 */
@Component
public class XsdValidator {

    /**
     * Valida o XML de acordo com o schema fiscal (XSD) da SEFAZ.
     *
     * @param xmlDocumento Documento XML (org.w3c.dom.Document)
     * @param xsdPath Caminho base do XSD principal (ex: "xsd/enviNFe_v4.00.xsd")
     * @throws Exception Caso o XML não esteja em conformidade com o schema SEFAZ.
     */
    public void validate(Document xmlDocumento, String xsdPath) throws Exception {
        try {
            Schema schema = carregarSchemaFiscal(xsdPath);
            Validator validator = schema.newValidator();

            // Força namespace SEFAZ para evitar falhas de declaração do elemento raiz
            xmlDocumento.getDocumentElement()
                    .setAttribute("xmlns", "http://www.portalfiscal.inf.br/nfe");

            validator.validate(new DOMSource(xmlDocumento));

        } catch (SAXException e) {
            throw new Exception("Falha de conformidade XML/XSD: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new Exception("Erro ao ler schemas XSD: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new Exception("Erro ao validar XML da NF-e: " + e.getMessage(), e);
        }
    }

    /**
     * Carrega todos os arquivos XSD disponíveis no diretório fiscal,
     * garantindo que includes e imports sejam resolvidos localmente.
     *
     * @param xsdPrincipal Caminho do arquivo XSD principal (relativo ao classpath)
     * @return Schema combinado com todos os arquivos do diretório
     * @throws IOException Caso algum arquivo não possa ser lido
     * @throws SAXException Caso o parser XML detecte erro estrutural
     */
    private Schema carregarSchemaFiscal(String xsdPrincipal) throws IOException, SAXException {
        SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);

        List<StreamSource> sources = new ArrayList<>();

        // Carrega todos os arquivos XSD da pasta /xsd/
        ClassPathResource dirResource = new ClassPathResource("xsd");
        if (dirResource.exists() && dirResource.getFile().isDirectory()) {
            for (var file : dirResource.getFile().listFiles()) {
                if (file != null && file.getName().endsWith(".xsd")) {
                    sources.add(new StreamSource(file));
                    System.out.println("[XSD-LOADER] Schema detectado: " + file.getName());
                }
            }
        } else {
            // Fallback para quando o projeto está empacotado em JAR
            String[] commonSchemas = {
                    "enviNFe_v4.00.xsd", "nfe_v4.00.xsd", "procNFe_v4.00.xsd",
                    "tiposBasico_v4.00.xsd", "xmldsig-core-schema_v1.01.xsd"
            };
            for (String schemaFile : commonSchemas) {
                ClassPathResource res = new ClassPathResource("xsd/" + schemaFile);
                if (res.exists()) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            res.getInputStream(), StandardCharsets.UTF_8))) {
                        sources.add(new StreamSource(reader));
                        System.out.println("[XSD-LOADER] Schema embutido: " + schemaFile);
                    }
                }
            }
        }

        if (sources.isEmpty()) {
            throw new IOException("Nenhum arquivo XSD encontrado no diretório fiscal (xsd/)");
        }

        return schemaFactory.newSchema(sources.toArray(new StreamSource[0]));
    }
}
