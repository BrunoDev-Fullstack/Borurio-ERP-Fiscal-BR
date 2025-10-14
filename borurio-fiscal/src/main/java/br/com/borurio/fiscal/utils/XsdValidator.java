package br.com.borurio.fiscal.utils;

import org.springframework.core.io.ClassPathResource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;

/**
 * Utilitário responsável por validar XMLs fiscais (NF-e) contra os esquemas XSD da SEFAZ.
 *
 * Garante conformidade técnica antes do envio e assinatura.
 * Aplicado no pipeline fiscal como primeira etapa de verificação.
 */
public class XsdValidator {

    /**
     * Valida o conteúdo XML de uma NF-e em relação ao arquivo XSD informado.
     *
     * @param xmlConteudo Conteúdo XML em formato String
     * @param xsdPath Caminho do arquivo XSD no classpath (ex: schemas/nfe_v4.00.xsd)
     * @throws RuntimeException caso o XML não esteja em conformidade com o XSD
     */
    public static void validar(String xmlConteudo, String xsdPath) {
        try {
            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            File xsdFile = new ClassPathResource(xsdPath).getFile();

            Schema schema = schemaFactory.newSchema(xsdFile);
            Validator validator = schema.newValidator();

            validator.validate(new StreamSource(new StringReader(xmlConteudo)));

        } catch (SAXException e) {
            throw new RuntimeException("XML inválido conforme o XSD SEFAZ: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new RuntimeException("Erro ao acessar o arquivo XSD: " + xsdPath, e);
        }
    }
}
