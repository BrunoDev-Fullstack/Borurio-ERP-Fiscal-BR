package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.fiscal.builder.NfeXmlBuilder;
import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.domain.nfe.Ide;
import br.com.borurio.fiscal.domain.nfe.InfNFe;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
    @Mock NfeEmissaoService nfeEmissaoService;

    NfeGeracaoService service;

    @BeforeEach
    void setUp() {
        service = new NfeGeracaoService(emitente, nfeXmlBuilder, nfeOrquestradorService,
                nfeLogService, ncmService, sequenciaService, retornoParser,
                documentoService, empresaCertificadoService, omsCertificadoService, nfeEmissaoService);
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

    @Test
    @DisplayName("Achado de code review 14-08-2026: Empresa.uf em branco (string vazia, não nula) "
            + "falha cedo via validação de endereço, antes de tocar nNF/chave/SEFAZ")
    void gerar_empresaComUfEmBranco_lancaBusinessExceptionSemTocarCicloFiscal() {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));

        Empresa empresa = empresaValida(41L, "1");
        empresa.setUf(""); // em branco, não nula — o bug era só checar != null

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS));

        assertEquals("EMITTER_ADDRESS_INCOMPLETE", ex.getErrorCode());
        assertFalse(ex.isRetryable());
        verifyNoInteractions(nfeOrquestradorService);
        verifyNoInteractions(nfeXmlBuilder);
        verifyNoInteractions(sequenciaService);
        verifyNoInteractions(nfeEmissaoService);
    }

    @Test
    @DisplayName("Banca 14-08-2026 (3ª rodada): Empresa.uf=null (nunca setada) falha cedo via "
            + "validação de endereço, mesmo tratamento do caso vazio")
    void gerar_empresaComUfNula_lancaBusinessExceptionSemTocarCicloFiscal() {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));

        Empresa empresa = empresaValida(42L, "1");
        empresa.setUf(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS));

        assertEquals("EMITTER_ADDRESS_INCOMPLETE", ex.getErrorCode());
        assertFalse(ex.isRetryable());
        verifyNoInteractions(nfeOrquestradorService);
        verifyNoInteractions(nfeXmlBuilder);
    }

    @Test
    @DisplayName("Banca 14-08-2026 (3ª rodada): Empresa.uf=\"   \" (só espaços) falha cedo, mesmo "
            + "tratamento do caso vazio — isBlank cobre espaço em branco, não só string vazia")
    void gerar_empresaComUfSoEspacos_lancaBusinessExceptionSemTocarCicloFiscal() {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));

        Empresa empresa = empresaValida(43L, "1");
        empresa.setUf("   ");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS));

        assertEquals("EMITTER_ADDRESS_INCOMPLETE", ex.getErrorCode());
        assertFalse(ex.isRetryable());
        verifyNoInteractions(nfeOrquestradorService);
        verifyNoInteractions(nfeXmlBuilder);
    }

    @Test
    @DisplayName("Banca 14-08-2026 (3ª rodada): Empresa.uf=\" sp \" (espaços + minúscula) canonicaliza "
            + "para SP de ponta a ponta — mesmo valor no endereço do emitente do XML e no transporte, "
            + "nunca dois caminhos de normalização divergentes")
    void gerar_empresaComUfComEspacosEMinuscula_canonicalizaSpDePontaAPonta() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());

        Empresa empresa = empresaValida(44L, "1");
        empresa.setUf(" sp ");

        service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS);

        ArgumentCaptor<NFe> captor = ArgumentCaptor.forClass(NFe.class);
        verify(nfeXmlBuilder).build(captor.capture(), eq(ModalidadeFrete.CONTA_TERCEIROS));
        assertEquals("SP", captor.getValue().getInfNFe().getEmit().getEnderEmit().getUF(),
                "endereço do emitente no XML precisa receber a mesma UF canonicalizada");
        verify(nfeOrquestradorService).processar(any(), any(), eq("SP"), any());
    }

    @Test
    @DisplayName("Banca 14-08-2026 (3ª rodada): EmitenteProperties.uf válida NÃO mascara UF ausente "
            + "de uma Empresa real — o fallback de configuração global só existe no caminho "
            + "administrativo legado (empresa==null), nunca quando há Empresa resolvida")
    void gerar_emitentePropertiesComUfValida_naoMascaraUfInvalidaDeEmpresaReal() {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        lenient().when(emitente.getUf()).thenReturn("RJ"); // configuração global válida, deliberadamente

        Empresa empresa = empresaValida(45L, "1");
        empresa.setUf(""); // Empresa real, mas com cadastro de UF incompleto

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS));

        assertEquals("EMITTER_ADDRESS_INCOMPLETE", ex.getErrorCode());
        verifyNoInteractions(nfeOrquestradorService);
        verifyNoInteractions(nfeXmlBuilder);
    }

    // -------------------------------------------------------------------------
    // Fase 0 do Gate SVC (14-08-2026) — UF real da Empresa chega ao transporte, nunca mais
    // recalculada por EmitenteProperties (achado da auditoria: NfeOrquestradorService perdia a
    // UF resolvida por NfeGeracaoService e a recalculava de configuração global).
    // -------------------------------------------------------------------------

    @Test
    void gerar_empresaComUfDiferenteDeEmitenteProperties_transmiteComUfDaEmpresa() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());
        lenient().when(emitente.getUf()).thenReturn("RJ"); // config global deliberadamente divergente

        Empresa empresa = empresaValida(40L, "1");
        empresa.setUf("SP"); // UF real da empresa emitente desta emissão

        service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS);

        verify(nfeOrquestradorService).processar(any(), any(), eq("SP"), any());
        verify(nfeOrquestradorService, never()).processar(any(), any(), eq("RJ"), any());
    }

    @Test
    @DisplayName("Achado de code review 14-08-2026 (2ª revisão): caminho legado (empresa=null) com "
            + "EmitenteProperties.uf em branco cai em SP, mesmo fallback do outro endpoint legado "
            + "(NfeOrquestradorService.processar 2 args) — não deve virar IllegalArgumentException")
    void gerar_empresaNulaEEmitentePropertiesUfEmBranco_caiEmSpComoOutroCaminhoLegado() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());
        lenient().when(emitente.getUf()).thenReturn("");
        lenient().when(emitente.getCnpj()).thenReturn("12345678000195");

        service.gerar(requestValido(), null, ModalidadeFrete.SEM_OCORRENCIA_TRANSPORTE);

        verify(nfeOrquestradorService).processar(any(), any(), eq("SP"), any());
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
                documentoService, empresaCertificadoService, omsCertificadoService, nfeEmissaoService);
        org.springframework.test.util.ReflectionTestUtils.setField(servicoLocal, "tpAmb", tpAmbValor);

        NfeEmissaoRequest req = requestValido();
        req.setDestRazaoSocial(nomeDestinatario);
        Empresa empresa = empresaValida(30L + tpAmbValor, "1");

        ArgumentCaptor<String> xmlCaptor = ArgumentCaptor.forClass(String.class);
        servicoLocal.gerar(req, empresa, ModalidadeFrete.CONTA_TERCEIROS);
        verify(orquestradorLocal).processar(xmlCaptor.capture(), anyString(), anyString(), any());

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

    // -------------------------------------------------------------------------
    // Gate 1 — persistência da chave em nfe_emissao ANTES da chamada à SEFAZ
    // -------------------------------------------------------------------------

    /**
     * Linha nfe_emissao mínima para os testes de SVC Fase 2 — número/série/CNPJ batem com
     * requestValido()/empresaValida(...) de propósito, para não disparar as validações de
     * divergência (cobertas em testes dedicados abaixo).
     */
    private br.com.borurio.fiscal.entity.NfeEmissao emissaoValida(Long id, String tpEmis, String dhCont, String xJust) {
        br.com.borurio.fiscal.entity.NfeEmissao e = new br.com.borurio.fiscal.entity.NfeEmissao();
        e.setId(id);
        e.setEstado(br.com.borurio.fiscal.entity.NfeEmissao.Estados.RESERVADO);
        e.setNumeroNfe(1);
        e.setSerie("1");
        e.setCnpjEmitente("22418179000134");
        e.setTpEmis(tpEmis);
        e.setDhCont(dhCont);
        e.setXJustContingencia(xJust);
        return e;
    }

    @Test
    void gerar_comEmissaoId_persisteChaveAntesDeChamarOrquestrador() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());
        when(nfeEmissaoService.buscarPorId(501L)).thenReturn(emissaoValida(501L, "1", null, null));
        Empresa empresa = empresaValida(12L, "1");

        service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 501L);

        ArgumentCaptor<String> chaveCaptor = ArgumentCaptor.forClass(String.class);
        InOrder ordem = inOrder(nfeEmissaoService, nfeOrquestradorService);
        // A chave precisa estar congelada em nfe_emissao ANTES de qualquer chamada de rede —
        // sem isso, uma reconciliação futura (Gate 3) não saberia qual chave consultar em
        // caso de timeout.
        ordem.verify(nfeEmissaoService).marcarTransmitido(eq(501L), chaveCaptor.capture());
        ordem.verify(nfeOrquestradorService).processar(any(), any(), any(), any());
        assertEquals(44, chaveCaptor.getValue().length(), "chave de acesso NF-e tem 44 dígitos");
    }

    // -------------------------------------------------------------------------
    // SVC Fase 2 (18-08-2026) — origem única de tpEmis/dhCont/xJust e de numeroNfe/serie/CNPJ,
    // fail-closed (plano v2, itens 4/4b/7/8).
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("SVC Fase 2: emissaoId presente com linha inexistente falha explícito, nunca cai em NORMAL")
    void gerar_emissaoIdSemLinha_lancaIllegalStateExceptionSemChamarSefaz() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(nfeEmissaoService.buscarPorId(999L)).thenReturn(null);
        Empresa empresa = empresaValida(50L, "1");

        assertThrows(IllegalStateException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 999L));

        verifyNoInteractions(nfeOrquestradorService);
        verifyNoInteractions(nfeXmlBuilder);
    }

    @Test
    @DisplayName("SVC Fase 2: emissaoId presente com tpEmis nulo na linha falha explícito, nunca cai em NORMAL")
    void gerar_emissaoComTpEmisNulo_lancaIllegalStateException() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(nfeEmissaoService.buscarPorId(502L)).thenReturn(emissaoValida(502L, null, null, null));
        Empresa empresa = empresaValida(51L, "1");

        assertThrows(IllegalStateException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 502L));

        verifyNoInteractions(nfeOrquestradorService);
        verifyNoInteractions(nfeXmlBuilder);
    }

    @Test
    @DisplayName("SVC Fase 2: tpEmis fora de {1,6,7} na linha falha explícito")
    void gerar_emissaoComTpEmisInvalido_lancaIllegalStateException() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(nfeEmissaoService.buscarPorId(503L)).thenReturn(emissaoValida(503L, "4", null, null));
        Empresa empresa = empresaValida(52L, "1");

        assertThrows(IllegalStateException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 503L));

        verifyNoInteractions(nfeOrquestradorService);
    }

    @Test
    @DisplayName("SVC Fase 2: número do request divergente do reservado falha explícito, XML nunca gerado com número errado")
    void gerar_numeroDivergenteDaLinhaReservada_lancaIllegalStateException() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(nfeEmissaoService.buscarPorId(504L)).thenReturn(emissaoValida(504L, "1", null, null));
        Empresa empresa = empresaValida(53L, "1");

        NfeEmissaoRequest req = requestValido();
        req.setNumero("999"); // diverge do numeroNfe=1 da linha reservada

        assertThrows(IllegalStateException.class,
                () -> service.gerar(req, empresa, ModalidadeFrete.CONTA_TERCEIROS, 504L));

        verifyNoInteractions(nfeOrquestradorService);
        verifyNoInteractions(nfeXmlBuilder);
    }

    @Test
    @DisplayName("SVC Fase 2: série do request divergente da reservada falha explícito")
    void gerar_serieDivergenteDaLinhaReservada_lancaIllegalStateException() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(nfeEmissaoService.buscarPorId(505L)).thenReturn(emissaoValida(505L, "1", null, null));
        Empresa empresa = empresaValida(54L, "1");

        NfeEmissaoRequest req = requestValido();
        req.setSerie("2"); // diverge da serie="1" da linha reservada

        assertThrows(IllegalStateException.class,
                () -> service.gerar(req, empresa, ModalidadeFrete.CONTA_TERCEIROS, 505L));

        verifyNoInteractions(nfeOrquestradorService);
    }

    @Test
    @DisplayName("SVC Fase 2: CNPJ resolvido divergente do reservado falha explícito")
    void gerar_cnpjDivergenteDaLinhaReservada_lancaIllegalStateException() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        // linha reservada aponta para outro CNPJ, nunca o da Empresa resolvida abaixo
        br.com.borurio.fiscal.entity.NfeEmissao emissao = emissaoValida(506L, "1", null, null);
        emissao.setCnpjEmitente("99999999000191");
        when(nfeEmissaoService.buscarPorId(506L)).thenReturn(emissao);
        Empresa empresa = empresaValida(55L, "1"); // CNPJ real: 22418179000134

        assertThrows(IllegalStateException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 506L));

        verifyNoInteractions(nfeOrquestradorService);
    }

    @Test
    @DisplayName("SVC Fase 2: dhCont sem xJust (linha inconsistente) falha explícito")
    void gerar_dhContSemXJust_lancaIllegalStateException() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(nfeEmissaoService.buscarPorId(507L))
                .thenReturn(emissaoValida(507L, "6", "2026-08-18T10:05:00-03:00", null));
        Empresa empresa = empresaValida(56L, "1");

        assertThrows(IllegalStateException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 507L));

        verifyNoInteractions(nfeOrquestradorService);
    }

    @Test
    @DisplayName("SVC Fase 2: tpEmis=NORMAL com dhCont residual (linha inconsistente) falha explícito")
    void gerar_normalComDhContResidual_lancaIllegalStateException() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(nfeEmissaoService.buscarPorId(508L))
                .thenReturn(emissaoValida(508L, "1", "2026-08-18T10:05:00-03:00", "Justificativa residual invalida."));
        Empresa empresa = empresaValida(57L, "1");

        assertThrows(IllegalStateException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 508L));

        verifyNoInteractions(nfeOrquestradorService);
    }

    // -------------------------------------------------------------------------
    // Banca 19-08-2026 — gate de estado, cláusula SVC obrigatória, fail-closed explícito e
    // política de identidade fiscal por caso (achados confirmados em 18-08-2026, corrigidos aqui).
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Banca 19-08: SVC-AN com dhCont/xJust ambos ausentes falha explícito (achado 2, antes passava despercebido)")
    void gerar_svcAnSemDhContNemXJust_lancaIllegalStateException() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(nfeEmissaoService.buscarPorId(530L)).thenReturn(emissaoValida(530L, "6", null, null));
        Empresa empresa = empresaValida(70L, "1");

        assertThrows(IllegalStateException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 530L));

        verifyNoInteractions(nfeOrquestradorService);
        verifyNoInteractions(nfeXmlBuilder);
    }

    @Test
    @DisplayName("Banca 19-08: SVC-RS com dhCont/xJust ambos ausentes falha explícito (achado 2)")
    void gerar_svcRsSemDhContNemXJust_lancaIllegalStateException() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(nfeEmissaoService.buscarPorId(531L)).thenReturn(emissaoValida(531L, "7", null, null));
        Empresa empresa = empresaValida(71L, "1");

        assertThrows(IllegalStateException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 531L));

        verifyNoInteractions(nfeOrquestradorService);
    }

    @Test
    @DisplayName("Banca 19-08: gate de estado — RESERVADO é o único estado que permite gerar()")
    void gerar_comEstadoReservado_permiteGeracao() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());
        when(nfeEmissaoService.buscarPorId(540L)).thenReturn(emissaoValida(540L, "1", null, null));
        Empresa empresa = empresaValida(72L, "1");

        assertDoesNotThrow(() -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 540L));

        verify(nfeOrquestradorService).processar(any(), any(), any(), any());
    }

    @Test
    @DisplayName("Banca 19-08: gate de estado — TRANSMITIDO/AUTORIZADO/etc. nunca geram chave/XML nova")
    void gerar_comEstadoDiferenteDeReservado_lancaIllegalStateException() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        br.com.borurio.fiscal.entity.NfeEmissao emissao = emissaoValida(541L, "1", null, null);
        emissao.setEstado(br.com.borurio.fiscal.entity.NfeEmissao.Estados.AUTORIZADO);
        when(nfeEmissaoService.buscarPorId(541L)).thenReturn(emissao);
        Empresa empresa = empresaValida(73L, "1");

        assertThrows(IllegalStateException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 541L));

        verifyNoInteractions(nfeOrquestradorService);
        verifyNoInteractions(nfeXmlBuilder);
    }

    @Test
    @DisplayName("Banca 19-08: gate de estado — estado nulo na linha falha explícito, nunca cai em RESERVADO por omissão")
    void gerar_comEstadoNulo_lancaIllegalStateException() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        br.com.borurio.fiscal.entity.NfeEmissao emissao = emissaoValida(542L, "1", null, null);
        emissao.setEstado(null);
        when(nfeEmissaoService.buscarPorId(542L)).thenReturn(emissao);
        Empresa empresa = empresaValida(74L, "1");

        assertThrows(IllegalStateException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 542L));

        verifyNoInteractions(nfeOrquestradorService);
    }

    @Test
    @DisplayName("Banca 19-08: fail-closed — série em branco na linha reservada falha explícito, nunca NPE")
    void gerar_comSerieEmBrancoNaLinha_lancaIllegalStateException() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        br.com.borurio.fiscal.entity.NfeEmissao emissao = emissaoValida(543L, "1", null, null);
        emissao.setSerie("   ");
        when(nfeEmissaoService.buscarPorId(543L)).thenReturn(emissao);
        Empresa empresa = empresaValida(75L, "1");

        // assertThrows já prova que é IllegalStateException, não NullPointerException incidental —
        // é exatamente essa distinção que a correção de fail-closed garante (achado 3).
        assertThrows(IllegalStateException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 543L));
        verifyNoInteractions(nfeOrquestradorService);
    }

    @Test
    @DisplayName("Banca 19-08: fail-closed — cnpjEmitente nulo na linha reservada falha explícito, nunca NPE")
    void gerar_comCnpjNuloNaLinha_lancaIllegalStateException() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        br.com.borurio.fiscal.entity.NfeEmissao emissao = emissaoValida(544L, "1", null, null);
        emissao.setCnpjEmitente(null);
        when(nfeEmissaoService.buscarPorId(544L)).thenReturn(emissao);
        Empresa empresa = empresaValida(76L, "1");

        assertThrows(IllegalStateException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 544L));

        verifyNoInteractions(nfeOrquestradorService);
    }

    @Test
    @DisplayName("Banca 19-08: fail-closed — numeroNfe <= 0 na linha reservada falha explícito")
    void gerar_comNumeroNfeInvalidoNaLinha_lancaIllegalStateException() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        br.com.borurio.fiscal.entity.NfeEmissao emissao = emissaoValida(545L, "1", null, null);
        emissao.setNumeroNfe(0);
        when(nfeEmissaoService.buscarPorId(545L)).thenReturn(emissao);
        Empresa empresa = empresaValida(77L, "1");

        assertThrows(IllegalStateException.class,
                () -> service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 545L));

        verifyNoInteractions(nfeOrquestradorService);
    }

    /**
     * Teste permanente da POLÍTICA decidida em banca (19-08-2026) — nunca remover, mesmo espírito
     * do teste diagnóstico de 18-08 que provou o bug (removido após capturar a evidência), mas
     * este documenta o comportamento CORRETO e decidido, não uma falha. Os 3 cenários que chegam a
     * gerar() com estado==RESERVADO SEMPRE produzem uma chave nova — nunca reaproveitam
     * nfe_emissao.chave_nfe, mesmo quando ela já existe (casos 2 e 3). Ver comentário em
     * NfeGeracaoService.gerar() (banca 19-08-2026) para a justificativa fiscal de cada caso.
     */
    @Test
    @DisplayName("Banca 19-08 — POLÍTICA PERMANENTE: caso 1 (nunca tentado) sempre gera chave nova")
    void politicaChave_caso1_chaveNfeNulaCstatNulo_geraChaveNova() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());
        br.com.borurio.fiscal.entity.NfeEmissao emissao = emissaoValida(550L, "1", null, null);
        assertNull(emissao.getChaveNfe());
        assertNull(emissao.getCstat());
        when(nfeEmissaoService.buscarPorId(550L)).thenReturn(emissao);
        Empresa empresa = empresaValida(80L, "1");

        service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 550L);

        String chaveNova = capturarChaveTransmitida(550L);
        assertEquals(44, chaveNova.length());
    }

    @Test
    @DisplayName("Banca 19-08 — POLÍTICA PERMANENTE: caso 2 (falha local, chave anterior nunca chegou à SEFAZ) gera chave nova, diferente da anterior")
    void politicaChave_caso2_chaveNfePresenteCstatNulo_geraChaveNovaDiferenteDaAnterior() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());
        br.com.borurio.fiscal.entity.NfeEmissao emissao = emissaoValida(551L, "1", null, null);
        // reverterParaReservadoPorFalhaLocal preserva chave_nfe da tentativa que nunca saiu do
        // Borurio (cstat continua null — nunca houve resposta da SEFAZ, ver
        // NfeEmissaoMapper.reverterTransmitidoParaReservado).
        String chaveAnteriorNuncaTransmitida = "35260822418179000134550001000000019876543210";
        emissao.setChaveNfe(chaveAnteriorNuncaTransmitida);
        assertNull(emissao.getCstat());
        when(nfeEmissaoService.buscarPorId(551L)).thenReturn(emissao);
        Empresa empresa = empresaValida(81L, "1");

        service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 551L);

        String chaveNova = capturarChaveTransmitida(551L);
        assertEquals(44, chaveNova.length());
        assertNotEquals(chaveAnteriorNuncaTransmitida, chaveNova,
                "chave nunca transmitida à SEFAZ é descartada — gerar() sempre calcula uma nova, nunca reaproveita string persistida");
    }

    @Test
    @DisplayName("Banca 19-08 — POLÍTICA PERMANENTE: caso 3 (retomada após rejeição SEFAZ, cStat presente) NUNCA reutiliza a chave já processada")
    void politicaChave_caso3_chaveNfePresenteCstatPresente_nuncaReutilizaChaveProcessada() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());
        br.com.borurio.fiscal.entity.NfeEmissao emissao = emissaoValida(552L, "1", null, null);
        // retomarComoReservado preserva chave_nfe/cstat/xmotivo/nprot da tentativa que a SEFAZ
        // efetivamente processou e rejeitou — reenviar essa MESMA chave arrisca cStat=204
        // (duplicidade). cstat NOT NULL é o sinal real que distingue este caso do caso 2.
        String chaveJaProcessadaPelaSefaz = "35260822418179000134550001000000019876543210";
        emissao.setChaveNfe(chaveJaProcessadaPelaSefaz);
        emissao.setCstat(598);
        emissao.setXmotivo("Rejeicao: Razao Social do destinatario incompativel");
        when(nfeEmissaoService.buscarPorId(552L)).thenReturn(emissao);
        Empresa empresa = empresaValida(82L, "1");

        service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 552L);

        String chaveNova = capturarChaveTransmitida(552L);
        assertEquals(44, chaveNova.length());
        assertNotEquals(chaveJaProcessadaPelaSefaz, chaveNova,
                "chave já processada (cstat presente) NUNCA pode ser reenviada — risco real de cStat=204 duplicidade");
    }

    @Test
    @DisplayName("SVC Fase 2: NORMAL com emissaoId — XML estruturalmente idêntico ao pré-Fase-2 (sem dhCont/xJust)")
    void gerar_normalComEmissaoId_xmlSemDhContEXJust() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());
        when(nfeEmissaoService.buscarPorId(509L)).thenReturn(emissaoValida(509L, "1", null, null));
        Empresa empresa = empresaValida(58L, "1");

        service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 509L);

        ArgumentCaptor<NFe> captor = ArgumentCaptor.forClass(NFe.class);
        verify(nfeXmlBuilder).build(captor.capture(), eq(ModalidadeFrete.CONTA_TERCEIROS));
        Ide ide = captor.getValue().getInfNFe().getIde();
        assertEquals("1", ide.getTpEmis());
        assertNull(ide.getDhCont());
        assertNull(ide.getXJust());
    }

    @Test
    @DisplayName("SVC Fase 2: SVC-AN — tpEmis/dhCont/xJust corretos no XML, número da chave == numeroNfe reservado")
    void gerar_svcAn_xmlComTpEmisDhContEXJustCorretos() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());
        when(nfeEmissaoService.buscarPorId(510L)).thenReturn(
                emissaoValida(510L, "6", "2026-08-18T10:05:00-03:00", "Justificativa de contingencia SVC-AN valida."));
        Empresa empresa = empresaValida(59L, "1");

        service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 510L);

        ArgumentCaptor<NFe> captor = ArgumentCaptor.forClass(NFe.class);
        verify(nfeXmlBuilder).build(captor.capture(), eq(ModalidadeFrete.CONTA_TERCEIROS));
        Ide ide = captor.getValue().getInfNFe().getIde();
        assertEquals("6", ide.getTpEmis());
        assertEquals("2026-08-18T10:05:00-03:00", ide.getDhCont());
        assertEquals("Justificativa de contingencia SVC-AN valida.", ide.getXJust());
        assertEquals("1", ide.getNNF(), "nNF do XML deve bater com numeroNfe da linha reservada");
    }

    @Test
    @DisplayName("SVC Fase 2: SVC-RS — tpEmis/dhCont/xJust corretos no XML")
    void gerar_svcRs_xmlComTpEmisDhContEXJustCorretos() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());
        when(nfeEmissaoService.buscarPorId(511L)).thenReturn(
                emissaoValida(511L, "7", "2026-08-18T10:05:00-03:00", "Justificativa de contingencia SVC-RS valida."));
        Empresa empresa = empresaValida(60L, "1");

        service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 511L);

        ArgumentCaptor<NFe> captor = ArgumentCaptor.forClass(NFe.class);
        verify(nfeXmlBuilder).build(captor.capture(), eq(ModalidadeFrete.CONTA_TERCEIROS));
        Ide ide = captor.getValue().getInfNFe().getIde();
        assertEquals("7", ide.getTpEmis());
        assertEquals("2026-08-18T10:05:00-03:00", ide.getDhCont());
    }

    /**
     * Recalcula o DV de forma independente de NfeGeracaoService.calcularCDV() — mod-11 ponderado
     * clássico da chave de acesso NF-e, implementado à parte para nunca reutilizar (e
     * potencialmente mascarar um bug de) o mesmo método de produção usado como oráculo do teste.
     */
    private String calcularCdvIndependente(String chave43) {
        int[] pesos = {2, 3, 4, 5, 6, 7, 8, 9};
        int soma = 0;
        int pesoIdx = 0;
        for (int i = chave43.length() - 1; i >= 0; i--) {
            soma += (chave43.charAt(i) - '0') * pesos[pesoIdx];
            pesoIdx = (pesoIdx + 1) % pesos.length;
        }
        int resto = soma % 11;
        return String.valueOf(resto < 2 ? 0 : 11 - resto);
    }

    private String capturarChaveTransmitida(Long emissaoId) throws Exception {
        ArgumentCaptor<String> chaveCaptor = ArgumentCaptor.forClass(String.class);
        verify(nfeEmissaoService).marcarTransmitido(eq(emissaoId), chaveCaptor.capture());
        return chaveCaptor.getValue();
    }

    @Test
    @DisplayName("SVC Fase 2 — TESTES DA CHAVE: NORMAL — 44 posições, tpEmis na posição 35, nNF, DV independente, Id, <tpEmis> coerentes")
    void chave_normal_estruturalmenteCorreta() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());
        when(nfeEmissaoService.buscarPorId(520L)).thenReturn(emissaoValida(520L, "1", null, null));
        Empresa empresa = empresaValida(61L, "1");

        service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 520L);

        String chave = capturarChaveTransmitida(520L);
        verificarChaveEstrutural(chave, "1", "1", 520L);
    }

    @Test
    @DisplayName("SVC Fase 2 — TESTES DA CHAVE: SVC-AN — 44 posições, tpEmis=6 na posição 35, nNF, DV independente, Id, <tpEmis> coerentes")
    void chave_svcAn_estruturalmenteCorreta() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());
        when(nfeEmissaoService.buscarPorId(521L)).thenReturn(
                emissaoValida(521L, "6", "2026-08-18T10:05:00-03:00", "Justificativa de contingencia SVC-AN valida."));
        Empresa empresa = empresaValida(62L, "1");

        service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 521L);

        String chave = capturarChaveTransmitida(521L);
        verificarChaveEstrutural(chave, "6", "1", 521L);
    }

    @Test
    @DisplayName("SVC Fase 2 — TESTES DA CHAVE: SVC-RS — 44 posições, tpEmis=7 na posição 35, nNF, DV independente, Id, <tpEmis> coerentes")
    void chave_svcRs_estruturalmenteCorreta() throws Exception {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());
        when(nfeEmissaoService.buscarPorId(522L)).thenReturn(
                emissaoValida(522L, "7", "2026-08-18T10:05:00-03:00", "Justificativa de contingencia SVC-RS valida."));
        Empresa empresa = empresaValida(63L, "1");

        service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS, 522L);

        String chave = capturarChaveTransmitida(522L);
        verificarChaveEstrutural(chave, "7", "1", 522L);
    }

    private void verificarChaveEstrutural(String chave, String tpEmisEsperado, String nNFEsperado, Long emissaoId) {
        assertEquals(44, chave.length(), "chave de acesso NF-e deve ter 44 posições");

        String chave43 = chave.substring(0, 43);
        char tpEmisNaChave = chave.charAt(34); // posição 35 (índice humano) = índice 34
        assertEquals(tpEmisEsperado.charAt(0), tpEmisNaChave,
                "posição 35 da chave deve ser o tpEmis resolvido para esta emissão");

        // nNF ocupa as posições 26-34 (índice 25 a 33, 9 dígitos) — comparação numérica ignora zeros à esquerda.
        String nNFNaChave = chave43.substring(25, 34);
        assertEquals(Integer.parseInt(nNFEsperado), Integer.parseInt(nNFNaChave),
                "nNF da chave deve bater com numeroNfe da linha reservada");

        String cdvEsperado = calcularCdvIndependente(chave43);
        assertEquals(cdvEsperado, chave.substring(43),
                "DV recalculado de forma independente deve bater com o DV de produção");

        ArgumentCaptor<NFe> captor = ArgumentCaptor.forClass(NFe.class);
        verify(nfeXmlBuilder).build(captor.capture(), eq(ModalidadeFrete.CONTA_TERCEIROS));
        InfNFe inf = captor.getValue().getInfNFe();
        assertEquals("NFe" + chave, inf.getId(), "Id da infNFe deve ser \"NFe\" + chave completa");
        assertEquals(tpEmisEsperado, inf.getIde().getTpEmis(),
                "<tpEmis> do XML deve ser igual ao dígito 35 da chave");
    }

    @Test
    void gerar_semEmissaoId_naoChamaMarcarTransmitido() throws Exception {
        // Overload de 3 argumentos — caminho legado (NfeEnvioController) e testes que não
        // passam pelo ciclo de nfe_emissao continuam funcionando exatamente como antes.
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));
        when(retornoParser.parse(any())).thenReturn(retornoAutorizado());
        Empresa empresa = empresaValida(13L, "1");

        service.gerar(requestValido(), empresa, ModalidadeFrete.CONTA_TERCEIROS);

        verifyNoInteractions(nfeEmissaoService);
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
