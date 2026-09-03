package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.exception.SefazRotaNaoConfiguradaException;
import br.com.borurio.fiscal.exception.SefazTransmissaoIncertaException;
import br.com.borurio.fiscal.utils.XsdValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/**
 * =============================================================================
 * SERVIÇO: NfeOrquestradorService
 * =============================================================================
 * Fluxo:
 * XML → Converter → Validar XSD → Assinar XMLDSIG → Transmitir → Retornar
 * =============================================================================
 */
@Service
public class NfeOrquestradorService {

    private final XsdValidator xsdValidator;
    private final AssinaturaXmlService assinaturaXmlService;
    private final NfeTransmitService nfeTransmitService;
    private final EmitenteProperties emitente;

    @Value("${sefaz.tpAmb:2}")
    private int tpAmb;

    @Autowired
    public NfeOrquestradorService(XsdValidator xsdValidator,
                                  AssinaturaXmlService assinaturaXmlService,
                                  NfeTransmitService nfeTransmitService,
                                  EmitenteProperties emitente) {
        this.xsdValidator = xsdValidator;
        this.assinaturaXmlService = assinaturaXmlService;
        this.nfeTransmitService = nfeTransmitService;
        this.emitente = emitente;
    }

    /**
     * Caminho legado (endpoint administrativo deprecated, {@code NfeEnvioController.enviarNfe}) —
     * sem Empresa resolvida, então sem UF real disponível. Único lugar que ainda usa
     * {@link EmitenteProperties} para UF, explicitamente, e só aqui — nunca no caminho real
     * (ver {@link #processar(String, String, String, CertificadoContexto)}).
     */
    public String processar(String xmlNfe, String cnpjEmitente) throws Exception {
        String ufLegado = (emitente.getUf() != null && !emitente.getUf().isBlank()) ? emitente.getUf() : "SP";
        return processar(xmlNfe, cnpjEmitente, ufLegado, null);
    }

    /**
     * Processa NF-e usando o certificado de uma empresa específica e a UF real dessa empresa —
     * Fase 0 do Gate SVC (14-08-2026): a UF nunca mais é recalculada aqui a partir de
     * configuração global. Quem chama (hoje só {@code NfeGeracaoService.gerar}) já resolveu a UF
     * certa a partir da {@code Empresa} da emissão e precisa propagá-la explicitamente.
     *
     * @param ufEmitente UF real da empresa emitente — obrigatória, nunca inferida aqui.
     * @param ctx certificado da empresa; se {@code null}, usa o certificado global (CertificadoService).
     */
    public String processar(String xmlNfe, String cnpjEmitente, String ufEmitente,
                            CertificadoContexto ctx) throws Exception {
        if (ufEmitente == null || ufEmitente.isBlank()) {
            throw new IllegalArgumentException(
                    "ufEmitente é obrigatória para processar a NF-e — nunca inferida de configuração global.");
        }

        // 1. Converter XML para Document
        Document document = converterParaDocument(xmlNfe);

        // Contrato de input: apenas <NFe> bare é aceito como corpo da requisição.
        // O lote <enviNFe> é responsabilidade exclusiva de NfeTransmitServiceImpl.
        String rootElement = document.getDocumentElement().getLocalName();
        if (!"NFe".equals(rootElement)) {
            throw new IllegalArgumentException(
                    "XML inválido: o elemento raiz deve ser <NFe>. " +
                    "O lote <enviNFe> é montado internamente pelo sistema."
            );
        }

        // 2. Validar XSD antes de assinar
        xsdValidator.validate(document, "xsd/custom/nfe_v4.00_consolidado.xsd");

        // 3. Assinar XML (XMLDSIG RSA-SHA1 — conforme schema oficial xmldsig-core-schema_v1.01.xsd)
        String xmlAssinado = ctx != null
                ? assinaturaXmlService.assinar(xmlNfe, ctx)
                : assinaturaXmlService.assinar(xmlNfe);

        // 4. Transmitir para SEFAZ com XML assinado — ÚNICO ponto deste método que efetivamente
        // toca a rede. Tudo acima (parse, XSD, assinatura) é local e propaga com o tipo de
        // exceção que já lança hoje. Envolver só esta chamada torna a fronteira local/transmissão
        // comprovável pela fase de execução, não por uma lista de tipos de exceção reconhecidos
        // (que sempre ficaria incompleta) — ver SefazTransmissaoIncertaException.
        // UF vem do parâmetro (Fase 0, 14-08-2026) — nunca mais recalculada aqui a partir de
        // configuração global; NfeTransmitServiceImpl é quem falha fechado se a UF não tiver rota
        // (ou tiver rota incompleta).
        try {
            return ctx != null
                    ? nfeTransmitService.transmitirXml(xmlAssinado, cnpjEmitente, ufEmitente, tpAmb, ctx.sslContext())
                    : nfeTransmitService.transmitirXml(xmlAssinado, cnpjEmitente, ufEmitente, tpAmb);
        } catch (SefazRotaNaoConfiguradaException e) {
            // Achado de code review 14-08-2026: erro de CONFIGURAÇÃO determinístico (nunca se
            // resolve sozinho numa nova tentativa) — precisa propagar como tal, nunca ser
            // reclassificado como transmissão incerta/retryable pelo catch genérico abaixo.
            throw e;
        } catch (Exception e) {
            throw new SefazTransmissaoIncertaException(e);
        }
    }

    private Document converterParaDocument(String xml) throws Exception {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();

        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

        return factory.newDocumentBuilder().parse(
                new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))
        );
    }
}