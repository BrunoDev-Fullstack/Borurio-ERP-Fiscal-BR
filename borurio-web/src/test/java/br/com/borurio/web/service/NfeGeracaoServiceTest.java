package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.fiscal.builder.NfeXmlBuilder;
import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.domain.nfe.ModalidadeFrete;
import br.com.borurio.fiscal.domain.nfe.NFe;
import br.com.borurio.fiscal.dto.NfeEmissaoItem;
import br.com.borurio.fiscal.dto.NfeEmissaoRequest;
import br.com.borurio.fiscal.dto.NfeSefazRetorno;
import br.com.borurio.fiscal.service.NcmService;
import br.com.borurio.fiscal.service.NfeDocumentoService;
import br.com.borurio.fiscal.service.NfeLogService;
import br.com.borurio.fiscal.service.NfeOrquestradorService;
import br.com.borurio.fiscal.service.NfeSefazRetornoParser;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import br.com.borurio.fiscal.utils.XsdValidator;
import org.w3c.dom.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Cobre a validação prévia do endereço do emitente (Requisito 4): evita chamar a SEFAZ
 * quando o cadastro está incompleto, em vez de deixar a rejeição de schema acontecer lá.
 */
@ExtendWith(MockitoExtension.class)
class NfeGeracaoServiceTest {

    @Mock EmitenteProperties emitente;
    @Mock NfeXmlBuilder nfeXmlBuilder;
    @Mock NfeOrquestradorService nfeOrquestradorService;
    @Mock NfeLogService nfeLogService;
    @Mock NcmService ncmService;
    @Mock NfeSequenciaService sequenciaService;
    @Mock NfeSefazRetornoParser retornoParser;
    @Mock NfeDocumentoService documentoService;
    @Mock EmpresaCertificadoService empresaCertificadoService;
    @Mock OmsCertificadoService omsCertificadoService;

    NfeGeracaoService service;

    @BeforeEach
    void setUp() {
        service = new NfeGeracaoService(emitente, nfeXmlBuilder, nfeOrquestradorService,
                nfeLogService, ncmService, sequenciaService, retornoParser,
                documentoService, empresaCertificadoService, omsCertificadoService);
    }

    private NfeEmissaoRequest requestValido() {
        NfeEmissaoRequest req = new NfeEmissaoRequest();
        req.setSerie("1");
        req.setDestCnpjCpf("12345678000195");
        req.setDestRazaoSocial("Cliente Teste");

        NfeEmissaoItem item = new NfeEmissaoItem();
        item.setCodigoProduto("SKU-1");
        item.setNcm("84715011");
        item.setQuantidade(new BigDecimal("1"));
        item.setValorUnitario(new BigDecimal("10.00"));
        req.setItens(List.of(item));
        return req;
    }

    private Empresa empresaComEnderecoIncompleto() {
        Empresa e = new Empresa();
        e.setId(8L);
        e.setCnpj("22418179000134");
        e.setRazaoSocial("J ZHENG BIJOUTERIAS");
        e.setUf("SP");
        // logradouro/numero/bairro/codigoMunicipio/municipio/cep ausentes de propósito
        return e;
    }

    private Empresa empresaValida(Long id, String indFinalPadrao) {
        Empresa e = new Empresa();
        e.setId(id);
        e.setCnpj("22418179000134");
        e.setRazaoSocial("J ZHENG BIJOUTERIAS");
        e.setUf("SP");
        e.setLogradouro("Rua Teste");
        e.setNumero("100");
        e.setBairro("Centro");
        e.setCodigoMunicipio("3550308");
        e.setMunicipio("São Paulo");
        e.setCep("01000000");
        e.setIndFinalPadrao(indFinalPadrao);
        return e;
    }

    private NfeSefazRetorno retornoAutorizado() {
        NfeSefazRetorno r = new NfeSefazRetorno();
        r.setCStat(100);
        r.setXMotivo("Autorizado o uso da NF-e");
        r.setNProt("135260000000000");
        return r;
    }

    @Test
    void gerar_empresaComEnderecoIncompleto_lancaBusinessExceptionSemChamarSefaz() {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.gerar(requestValido(), empresaComEnderecoIncompleto(), ModalidadeFrete.CONTA_TERCEIROS));

        assertEquals("EMITTER_ADDRESS_INCOMPLETE", ex.getErrorCode());
        assertFalse(ex.isRetryable());
        verifyNoInteractions(nfeOrquestradorService);
        verifyNoInteractions(nfeXmlBuilder);
    }

    // -------------------------------------------------------------------------
    // P0.4 — indFinal como padrão configurável da empresa emitente (não mais heurística CPF/CNPJ)
    // -------------------------------------------------------------------------

    @Test
    void resolverIndFinalPadrao_empresaComPadrao1_retorna1() {
        assertEquals("1", service.resolverIndFinalPadrao(empresaValida(1L, "1")));
    }

    @Test
    void resolverIndFinalPadrao_empresaComPadrao0_retorna0() {
        assertEquals("0", service.resolverIndFinalPadrao(empresaValida(2L, "0")));
    }

    @Test
    void resolverIndFinalPadrao_valorNulo_fallback1DeCompatibilidade() {
        assertEquals("1", service.resolverIndFinalPadrao(empresaValida(3L, null)));
    }

    @Test
    void resolverIndFinalPadrao_valorVazio_fallback1DeCompatibilidade() {
        assertEquals("1", service.resolverIndFinalPadrao(empresaValida(4L, "")));
    }

    @Test
    void resolverIndFinalPadrao_empresaNula_fallback1DoFluxoLegado() {
        assertEquals("1", service.resolverIndFinalPadrao(null));
    }

    @Test
    void resolverIndFinalPadrao_valorInvalido_lancaBusinessExceptionSemNormalizar() {
        Empresa empresa = empresaValida(5L, "2");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.resolverIndFinalPadrao(empresa));

        assertEquals("IND_FINAL_PADRAO_INVALIDO", ex.getErrorCode());
        assertFalse(ex.isRetryable());
    }

    @Test
    void resolverIndFinalPadrao_empresasIndependentes_naoInterferemEntreSi() {
        Empresa empresaRevenda = empresaValida(6L, "0");
        Empresa empresaConsumidorFinal = empresaValida(7L, "1");

        assertEquals("0", service.resolverIndFinalPadrao(empresaRevenda));
        assertEquals("1", service.resolverIndFinalPadrao(empresaConsumidorFinal));
        // Repetir na ordem inversa garante que não há estado compartilhado entre resoluções.
        assertEquals("1", service.resolverIndFinalPadrao(empresaConsumidorFinal));
        assertEquals("0", service.resolverIndFinalPadrao(empresaRevenda));
    }

    @Test
    void gerar_valorInvalido_falhaAntesDeMontarOuTransmitirXml() {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        Empresa empresa = empresaValida(9L, "X");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS));

        assertEquals("IND_FINAL_PADRAO_INVALIDO", ex.getErrorCode());
        verifyNoInteractions(nfeOrquestradorService);
        verifyNoInteractions(nfeXmlBuilder);
    }

    @Test
    void gerar_indFinalNaoDependeMaisDoDocumentoDestinatario() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());

        Empresa empresaRevenda = empresaValida(10L, "0");

        NfeEmissaoRequest reqCpf = requestValido();
        reqCpf.setDestCnpjCpf("52998224725"); // CPF (11 dígitos) — antes da correção forçava indFinal="1"
        service.gerar(reqCpf, empresaRevenda, ModalidadeFrete.CONTA_TERCEIROS);

        NfeEmissaoRequest reqCnpj = requestValido();
        reqCnpj.setDestCnpjCpf("12345678000195"); // CNPJ (14 dígitos)
        service.gerar(reqCnpj, empresaRevenda, ModalidadeFrete.CONTA_TERCEIROS);

        ArgumentCaptor<NFe> captor = ArgumentCaptor.forClass(NFe.class);
        verify(nfeXmlBuilder, times(2)).build(captor.capture(), eq(ModalidadeFrete.CONTA_TERCEIROS));
        for (NFe nfe : captor.getAllValues()) {
            assertEquals("0", nfe.getInfNFe().getIde().getIndFinal(),
                    "indFinal deve vir de Empresa.indFinalPadrao, independentemente do CPF/CNPJ do destinatário");
        }
    }

    @Test
    void gerar_xmlFinalContemIndFinalConfiguradoNaEmpresa() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());

        Empresa empresaConsumidorFinal = empresaValida(11L, "1");

        service.gerar(requestValido(), empresaConsumidorFinal, ModalidadeFrete.CONTA_TERCEIROS);

        ArgumentCaptor<NFe> captor = ArgumentCaptor.forClass(NFe.class);
        verify(nfeXmlBuilder).build(captor.capture(), eq(ModalidadeFrete.CONTA_TERCEIROS));
        assertEquals("1", captor.getValue().getInfNFe().getIde().getIndFinal());
    }

    // -------------------------------------------------------------------------
    // P0.5 — indIntermed presente no XML final para o fluxo atual (venda direta)
    // -------------------------------------------------------------------------

    @Test
    void gerar_xmlFinalContemIndIntermedNoValorAtualDeVendaDireta() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());

        Empresa empresa = empresaValida(12L, "1");

        service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS);

        ArgumentCaptor<NFe> captor = ArgumentCaptor.forClass(NFe.class);
        verify(nfeXmlBuilder).build(captor.capture(), eq(ModalidadeFrete.CONTA_TERCEIROS));
        assertEquals("0", captor.getValue().getInfNFe().getIde().getIndIntermed(),
                "indIntermed deve estar presente no XML com o valor provisório atual (\"0\" = venda direta) — "
                        + "esse valor não representa regra de negócio fechada, só o comportamento vigente do fluxo atual");
    }

    // -------------------------------------------------------------------------
    // Rejeição 598 — dest/xNome fixo em homologação (tpAmb=2), nome real preservado em
    // produção (tpAmb=1). Achado real do Gate 7B: emissão do pedido 22 (J.ZHENG) rejeitada
    // pela SEFAZ-SP por usar a razão social real do destinatário em homologação.
    // -------------------------------------------------------------------------

    @Test
    void resolverNomeDestinatario_homologacao_retornaTextoFixoDaSefaz() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "tpAmb", 2);

        assertEquals("NF-E EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL",
                service.resolverNomeDestinatario("Cliente Real Ltda"));
    }

    @Test
    void resolverNomeDestinatario_producao_preservaNomeReal() {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "tpAmb", 1);

        assertEquals("Cliente Real Ltda", service.resolverNomeDestinatario("Cliente Real Ltda"));
    }

    @Test
    void gerar_homologacao_xmlFinalContemXNomeFixoDaSefaz() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "tpAmb", 2);
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());

        NfeEmissaoRequest req = requestValido();
        req.setDestRazaoSocial("Cliente Sintetico Teste"); // nome real, nunca deve ir ao XML em homologação
        Empresa empresa = empresaValida(20L, "1");

        service.gerar(req, empresa, ModalidadeFrete.CONTA_TERCEIROS);

        ArgumentCaptor<NFe> captor = ArgumentCaptor.forClass(NFe.class);
        verify(nfeXmlBuilder).build(captor.capture(), eq(ModalidadeFrete.CONTA_TERCEIROS));
        assertEquals("NF-E EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL",
                captor.getValue().getInfNFe().getDest().getXNome(),
                "Em tpAmb=2, dest/xNome deve ser exatamente o texto obrigatório da SEFAZ, nunca o nome real.");

        // texto aparece somente em dest/xNome, nunca no emitente
        assertEquals("J ZHENG BIJOUTERIAS", captor.getValue().getInfNFe().getEmit().getXNome(),
                "emit/xNome deve continuar com a razão social real do emitente, mesmo em homologação.");

        // o nome real informado na requisição não é alterado pela geração do XML
        assertEquals("Cliente Sintetico Teste", req.getDestRazaoSocial(),
                "O nome real do destinatário no request/pedido não pode ser modificado — a substituição é só no XML.");
    }

    @Test
    void gerar_producao_xmlFinalContemRazaoSocialRealDoDestinatario() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "tpAmb", 1);
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());

        NfeEmissaoRequest req = requestValido();
        req.setDestRazaoSocial("Cliente Sintetico Teste");
        Empresa empresa = empresaValida(21L, "1");

        service.gerar(req, empresa, ModalidadeFrete.CONTA_TERCEIROS);

        ArgumentCaptor<NFe> captor = ArgumentCaptor.forClass(NFe.class);
        verify(nfeXmlBuilder).build(captor.capture(), eq(ModalidadeFrete.CONTA_TERCEIROS));
        assertEquals("Cliente Sintetico Teste", captor.getValue().getInfNFe().getDest().getXNome(),
                "Em tpAmb=1 (produção), dest/xNome deve conter a razão social real do destinatário.");
    }

    @Test
    void gerar_homologacao_xNomeSemAcentoESemEspacosExtras() throws Exception {
        org.springframework.test.util.ReflectionTestUtils.setField(service, "tpAmb", 2);
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());

        service.gerar(requestValido(), empresaValida(22L, "1"), ModalidadeFrete.CONTA_TERCEIROS);

        ArgumentCaptor<NFe> captor = ArgumentCaptor.forClass(NFe.class);
        verify(nfeXmlBuilder).build(captor.capture(), eq(ModalidadeFrete.CONTA_TERCEIROS));
        String xNome = captor.getValue().getInfNFe().getDest().getXNome();

        assertEquals(xNome.trim(), xNome, "xNome não pode ter espaços extras no início/fim.");
        assertFalse(xNome.contains("HOMOLOGAÇÃO"), "O texto exigido pela SEFAZ usa \"HOMOLOGACAO\" sem cedilha/acento.");
        assertTrue(xNome.contains("HOMOLOGACAO"), "O texto deve conter \"HOMOLOGACAO\" sem acento, conforme exigido pela SEFAZ.");
    }

    /**
     * Constrói o XML real (NfeXmlBuilder real, não mockado) para validar contra o XSD oficial
     * da NF-e 4.00 — mesmo schema usado em NfePipelineLocalTest (borurio-fiscal). Confirma que
     * a substituição de dest/xNome não quebra a validade estrutural do XML em nenhum ambiente.
     */
    private String gerarXmlReal(int tpAmbValor, String nomeDestinatario) throws Exception {
        NfeXmlBuilder builderReal = new NfeXmlBuilder();
        NcmService ncmServiceLocal = mock(NcmService.class);
        when(ncmServiceLocal.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        NfeOrquestradorService orquestradorLocal = mock(NfeOrquestradorService.class);
        NfeSefazRetornoParser retornoParserLocal = mock(NfeSefazRetornoParser.class);
        when(retornoParserLocal.parse(any())).thenReturn(retornoAutorizado());

        NfeGeracaoService servicoLocal = new NfeGeracaoService(emitente, builderReal, orquestradorLocal,
                nfeLogService, ncmServiceLocal, sequenciaService, retornoParserLocal,
                documentoService, empresaCertificadoService, omsCertificadoService);
        org.springframework.test.util.ReflectionTestUtils.setField(servicoLocal, "tpAmb", tpAmbValor);

        NfeEmissaoRequest req = requestValido();
        req.setDestRazaoSocial(nomeDestinatario);
        Empresa empresa = empresaValida(30L + tpAmbValor, "1");

        ArgumentCaptor<String> xmlCaptor = ArgumentCaptor.forClass(String.class);
        servicoLocal.gerar(req, empresa, ModalidadeFrete.CONTA_TERCEIROS);
        verify(orquestradorLocal).processar(xmlCaptor.capture(), anyString(), any());

        return xmlCaptor.getValue();
    }

    @Test
    void gerar_homologacao_xmlRealValidoContraXsdOficial() throws Exception {
        String xml = gerarXmlReal(2, "Cliente Sintetico Teste");

        Document doc = parseXml(xml);
        new XsdValidator().validate(doc, "xsd/custom/nfe_v4.00_consolidado.xsd");

        assertEquals("NF-E EMITIDA EM AMBIENTE DE HOMOLOGACAO - SEM VALOR FISCAL",
                extrairTextoDestXNome(doc));
    }

    @Test
    void gerar_producao_xmlRealValidoContraXsdOficial() throws Exception {
        String xml = gerarXmlReal(1, "Cliente Sintetico Teste");

        Document doc = parseXml(xml);
        new XsdValidator().validate(doc, "xsd/custom/nfe_v4.00_consolidado.xsd");

        assertEquals("Cliente Sintetico Teste", extrairTextoDestXNome(doc));
    }

    @Test
    void gerar_fluxoOms_xmlRealContemModFreteTerceiros() throws Exception {
        String xml = gerarXmlReal(1, "Cliente Sintetico Teste");

        assertTrue(xml.contains("<modFrete>2</modFrete>"),
                "Fluxo chamado com ModalidadeFrete.CONTA_TERCEIROS (equivalente ao usado por PedidoEmissaoService) "
                        + "deve gravar modFrete=2 no XML real gerado por NfeGeracaoService.");
    }

    private Document parseXml(String xml) throws Exception {
        javax.xml.parsers.DocumentBuilderFactory factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try (var input = new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private String extrairTextoDestXNome(Document doc) {
        org.w3c.dom.NodeList destList = doc.getElementsByTagNameNS(
                "http://www.portalfiscal.inf.br/nfe", "dest");
        org.w3c.dom.Element destEl = (org.w3c.dom.Element) destList.item(0);
        org.w3c.dom.NodeList xNomeList = destEl.getElementsByTagNameNS(
                "http://www.portalfiscal.inf.br/nfe", "xNome");
        return xNomeList.item(0).getTextContent();
    }
}
