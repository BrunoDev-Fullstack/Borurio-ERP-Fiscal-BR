package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.fiscal.builder.NfeXmlBuilder;
import br.com.borurio.fiscal.config.EmitenteProperties;
import br.com.borurio.fiscal.dto.NfeEmissaoItem;
import br.com.borurio.fiscal.dto.NfeEmissaoRequest;
import br.com.borurio.fiscal.service.NcmService;
import br.com.borurio.fiscal.service.NfeDocumentoService;
import br.com.borurio.fiscal.service.NfeLogService;
import br.com.borurio.fiscal.service.NfeOrquestradorService;
import br.com.borurio.fiscal.service.NfeSefazRetornoParser;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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

    @Test
    void gerar_empresaComEnderecoIncompleto_lancaBusinessExceptionSemChamarSefaz() {
        when(ncmService.buscarPorCodigo("84715011")).thenReturn(mock(br.com.borurio.fiscal.entity.Ncm.class));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.gerar(requestValido(), empresaComEnderecoIncompleto()));

        assertEquals("EMITTER_ADDRESS_INCOMPLETE", ex.getErrorCode());
        assertFalse(ex.isRetryable());
        verifyNoInteractions(nfeOrquestradorService);
        verifyNoInteractions(nfeXmlBuilder);
    }
}
