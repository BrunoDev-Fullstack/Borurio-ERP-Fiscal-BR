package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.service.NfeAuthorizeService;
import br.com.borurio.fiscal.service.NfeLogService;
import br.com.borurio.fiscal.utils.XsdValidator;
import jakarta.xml.bind.DatatypeConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;

/**
 * Serviço responsável por simular o processo de autorização de NF-e (mock SEFAZ).
 *
 * Este componente executa o fluxo completo:
 *  1. Validação do XML contra os schemas oficiais (PL009 / XSD 4.00);
 *  2. Simulação da autorização e geração de protocolo (<protNFe>);
 *  3. Registro de evento fiscal em nfe_log (auditoria);
 *  4. Retorno do XML de resposta com status 100 (Autorizado o uso da NF-e).
 *
 * Padrões aplicados:
 *  - Arquitetura em camadas (Controller → Service → Mapper)
 *  - Componentização e injeção via Spring Boot 3.3.2
 *  - Validação fiscal conforme layout SEFAZ
 *  - Logs e auditoria via NfeLogService
 *  - Codificação UTF-8 e segurança XML
 *
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Versão: 1.0.0
 * Módulo: borurio-fiscal
 */
@Service
public class NfeAuthorizeServiceImpl implements NfeAuthorizeService {

    private final NfeLogService nfeLogService;
    private final XsdValidator xsdValidator;

    @Autowired
    public NfeAuthorizeServiceImpl(NfeLogService nfeLogService, XsdValidator xsdValidator) {
        this.nfeLogService = nfeLogService;
        this.xsdValidator = xsdValidator;
    }

    /**
     * Executa o fluxo completo de autorização mock SEFAZ.
     *
     * @param xmlDocumento Documento XML da NF-e.
     * @return Documento XML de resposta (retEnviNFe) com protocolo simulado.
     * @throws Exception Em caso de falha de validação, parsing ou persistência.
     */
    @Override
    public Document autorizarNFe(Document xmlDocumento) throws Exception {
        // Etapa 1: Validação do XML conforme layout oficial
        validarXML(xmlDocumento);

        // Etapa 2: Geração do protocolo de autorização mock
        Document xmlAutorizado = gerarProtocolo(xmlDocumento);

        // Etapa 3: Registro do evento fiscal em nfe_log (auditoria)
        String chaveNFe = extrairChave(xmlDocumento);
        String xmlString = documentToString(xmlAutorizado);

        nfeLogService.registrarEvento(
                "AUTORIZACAO_MOCK",
                "NF-e autorizada localmente (mock SEFAZ)",
                chaveNFe,
                xmlString
        );

        return xmlAutorizado;
    }

    /**
     * Valida o XML da NF-e contra os schemas oficiais (leiauteNFe_v4.00.xsd).
     */
    @Override
    public void validarXML(Document xmlDocumento) throws Exception {
        try {
            xsdValidator.validate(xmlDocumento, "/xsd/leiauteNFe_v4.00.xsd");
        } catch (Exception e) {
            throw new Exception("Falha na validação do XML da NF-e: " + e.getMessage(), e);
        }
    }

    /**
     * Gera o XML de resposta (retEnviNFe) com protocolo de autorização (mock SEFAZ).
     */
    @Override
    public Document gerarProtocolo(Document xmlDocumento) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document retEnviNFe = builder.newDocument();

        // Elemento raiz <retEnviNFe>
        Element root = retEnviNFe.createElementNS("http://www.portalfiscal.inf.br/nfe", "retEnviNFe");
        root.setAttribute("versao", "4.00");
        retEnviNFe.appendChild(root);

        // Cabeçalho
        appendElement(retEnviNFe, root, "tpAmb", "2"); // 2 = Homologação
        appendElement(retEnviNFe, root, "verAplic", "Borurio-ERP-DEV");
        appendElement(retEnviNFe, root, "cStat", "100");
        appendElement(retEnviNFe, root, "xMotivo", "Autorizado o uso da NF-e");
        appendElement(retEnviNFe, root, "cUF", "35"); // São Paulo

        // Protocolo <protNFe>
        Element protNFe = retEnviNFe.createElement("protNFe");
        protNFe.setAttribute("versao", "4.00");
        root.appendChild(protNFe);

        Element infProt = retEnviNFe.createElement("infProt");
        protNFe.appendChild(infProt);

        appendElement(retEnviNFe, infProt, "tpAmb", "2");
        appendElement(retEnviNFe, infProt, "verAplic", "Borurio-ERP-DEV");

        String chaveNFe = extrairChave(xmlDocumento);
        appendElement(retEnviNFe, infProt, "chNFe", chaveNFe);

        String dataHora = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX").format(new Date());
        appendElement(retEnviNFe, infProt, "dhRecbto", dataHora);

        appendElement(retEnviNFe, infProt, "nProt", gerarNumeroProtocolo());
        appendElement(retEnviNFe, infProt, "digVal", gerarHashAssinatura(xmlDocumento));
        appendElement(retEnviNFe, infProt, "cStat", "100");
        appendElement(retEnviNFe, infProt, "xMotivo", "Autorizado o uso da NF-e");

        return retEnviNFe;
    }

    /**
     * Extrai a chave da NF-e (atributo Id do elemento <infNFe>).
     */
    private String extrairChave(Document xmlDocumento) {
        try {
            Element infNFe = (Element) xmlDocumento.getElementsByTagName("infNFe").item(0);
            if (infNFe != null && infNFe.hasAttribute("Id")) {
                return infNFe.getAttribute("Id").replace("NFe", "");
            }
        } catch (Exception ignored) {
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 44);
    }

    /**
     * Gera número de protocolo SEFAZ simulado (15 dígitos).
     */
    private String gerarNumeroProtocolo() {
        String base = "13525" + System.currentTimeMillis();
        return base.substring(0, Math.min(base.length(), 15));
    }

    /**
     * Gera hash SHA-1 simulado da NF-e (mock da assinatura digital).
     */
    private String gerarHashAssinatura(Document xmlDocumento) {
        try {
            String xmlString = documentToString(xmlDocumento);
            byte[] bytes = xmlString.getBytes(StandardCharsets.UTF_8);
            byte[] digest = java.security.MessageDigest.getInstance("SHA-1").digest(bytes);
            return DatatypeConverter.printBase64Binary(digest);
        } catch (Exception e) {
            return Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes());
        }
    }

    /**
     * Converte um Document XML em String UTF-8 formatada.
     */
    private String documentToString(Document doc) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transformer.transform(new DOMSource(doc), new StreamResult(out));
        return out.toString(StandardCharsets.UTF_8);
    }

    /**
     * Cria e adiciona um elemento XML de forma segura.
     */
    private void appendElement(Document doc, Element parent, String tag, String value) {
        Element element = doc.createElement(tag);
        element.setTextContent(value);
        parent.appendChild(element);
    }
}
