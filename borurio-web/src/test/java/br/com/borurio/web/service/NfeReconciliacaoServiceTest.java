package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.Pedido;
import br.com.borurio.app.entity.PedidoItem;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.fiscal.config.SefazReconciliacaoProperties;
import br.com.borurio.fiscal.dto.NfeConsultaSituacaoRetorno;
import br.com.borurio.fiscal.entity.NfeDocumento;
import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.exception.SefazTransmissaoIncertaException;
import br.com.borurio.fiscal.service.NfeConsultaSituacaoService;
import br.com.borurio.fiscal.service.NfeDocumentoService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Gate 3 (reconciliação, 10-08-2026) — matriz de classificação da Consulta Situação, local-first
 * e claim de backoff. Nunca prova retransmissão: NfeGeracaoService não é sequer injetado aqui.
 */
@ExtendWith(MockitoExtension.class)
class NfeReconciliacaoServiceTest {

    @Mock NfeEmissaoService nfeEmissaoService;
    @Mock NfeDocumentoService documentoService;
    @Mock NfeConsultaSituacaoService consultaSituacaoService;

    NfeReconciliacaoService service;

    private static final Long PEDIDO_ID = 99L;
    private static final Long EMISSAO_ID = 501L;
    private static final Long EMPRESA_ID = 10L;
    private static final String CHAVE = "35260500000000000191550010000000011000000013";
    private static final String OUTRA_CHAVE = "35260500000000000191550010000000029000000021";

    @BeforeEach
    void setUp() {
        service = new NfeReconciliacaoService(nfeEmissaoService, documentoService, consultaSituacaoService,
                new SefazReconciliacaoProperties());
        lenient().when(nfeEmissaoService.tentarAdquirirJanelaConsulta(EMISSAO_ID)).thenReturn(true);
        lenient().when(documentoService.buscarPorChave(anyString())).thenReturn(Optional.empty());
    }

    private Pedido pedido() {
        Pedido p = new Pedido();
        p.setId(PEDIDO_ID);
        p.setEmpresaId(EMPRESA_ID);
        PedidoItem item = new PedidoItem();
        item.setProdutoId(1L);
        item.setQuantidade(new BigDecimal("2"));
        p.setItens(List.of(item));
        return p;
    }

    private NfeEmissao emissaoPendente(String estado) {
        NfeEmissao e = new NfeEmissao();
        e.setId(EMISSAO_ID);
        e.setPedidoId(PEDIDO_ID);
        e.setChaveNfe(CHAVE);
        e.setEstado(estado);
        e.setTentativasConsulta(0);
        e.setTransmitidoEm(LocalDateTime.now().minusMinutes(1));
        e.setCreatedAt(LocalDateTime.now().minusMinutes(1));
        return e;
    }

    private Empresa empresa() {
        Empresa emp = new Empresa();
        emp.setId(EMPRESA_ID);
        emp.setUf("SP");
        return emp;
    }

    private NfeConsultaSituacaoRetorno retorno(int cStat, String chNFe, String nProt, boolean protNFePresente) {
        NfeConsultaSituacaoRetorno r = new NfeConsultaSituacaoRetorno();
        r.setCStat(cStat);
        r.setXMotivo("motivo-" + cStat);
        r.setChNFe(chNFe);
        r.setNProt(nProt);
        r.setProtNFePresente(protNFePresente);
        return r;
    }

    // -------------------------------------------------------------------------
    // Claim de backoff
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Claim não vencido: nunca toca nfe_documento nem a SEFAZ")
    void claimNaoVencido_nuncaTocaRedeNemBancoLocal() {
        when(nfeEmissaoService.tentarAdquirirJanelaConsulta(EMISSAO_ID)).thenReturn(false);
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.PENDENTE_CONFIRMACAO);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reconciliar(pedido(), emissao, empresa(), true));

        assertEquals("EMISSAO_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        assertTrue(ex.isRetryable());
        verifyNoInteractions(documentoService, consultaSituacaoService);
        verify(nfeEmissaoService, never()).resolverCicloComEfeitos(
                anyLong(), anyString(), any(), any(), any(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), anyString());
    }

    // -------------------------------------------------------------------------
    // Local-first
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Local-first positivo: nfe_documento com cStat=100 e nProt válido resolve sem consultar a SEFAZ")
    void localFirst_cStat100ComProtocolo_resolveAutorizadoSemConsultarSefaz() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.TRANSMITIDO);
        NfeDocumento doc = NfeDocumento.builder().chaveNfe(CHAVE).cStat("100").nProt("135260000000001").build();
        when(documentoService.buscarPorChave(CHAVE)).thenReturn(Optional.of(doc));

        assertDoesNotThrow(() -> service.reconciliar(pedido(), emissao, empresa(), true));

        verifyNoInteractions(consultaSituacaoService);
        verify(nfeEmissaoService).resolverCicloComEfeitos(EMISSAO_ID, NfeEmissao.Estados.AUTORIZADO, 100, null, "135260000000001",
                PEDIDO_ID, "AUTORIZADO", CHAVE, true, pedido().getItens(), EMPRESA_ID, "sistema-reconciliacao");
    }

    @Test
    @DisplayName("Local-first rejeitado por protocolo insuficiente: cStat=100 sem nProt cai para consulta SEFAZ")
    void localFirst_cStat100SemProtocolo_naoResolveLocalmente() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.TRANSMITIDO);
        NfeDocumento doc = NfeDocumento.builder().chaveNfe(CHAVE).cStat("100").nProt(null).build();
        when(documentoService.buscarPorChave(CHAVE)).thenReturn(Optional.of(doc));
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(retorno(217, null, null, false));

        assertThrows(BusinessException.class, () -> service.reconciliar(pedido(), emissao, empresa(), true));

        verify(consultaSituacaoService).consultar(eq(CHAVE), anyString(), anyInt());
    }

    @Test
    @DisplayName("Local-first rejeitado por chave diferente: doc de outra chave nunca é usado")
    void localFirst_chaveDiferente_naoResolveLocalmente() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.TRANSMITIDO);
        NfeDocumento doc = NfeDocumento.builder().chaveNfe(OUTRA_CHAVE).cStat("100").nProt("prot").build();
        // buscarPorChave(CHAVE) devolvendo um doc com chave diferente simula um bug de índice —
        // o serviço precisa detectar e ignorar mesmo assim.
        when(documentoService.buscarPorChave(CHAVE)).thenReturn(Optional.of(doc));
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(retorno(217, null, null, false));

        assertThrows(BusinessException.class, () -> service.reconciliar(pedido(), emissao, empresa(), true));

        verify(consultaSituacaoService).consultar(eq(CHAVE), anyString(), anyInt());
    }

    @Test
    @DisplayName("Local-first: cStat=225 resolve AGUARDANDO_CORRECAO sem consultar a SEFAZ")
    void localFirst_cStat225_resolveAguardandoCorrecaoSemConsultarSefaz() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.PENDENTE_CONFIRMACAO);
        NfeDocumento doc = NfeDocumento.builder().chaveNfe(CHAVE).cStat("225").build();
        when(documentoService.buscarPorChave(CHAVE)).thenReturn(Optional.of(doc));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reconciliar(pedido(), emissao, empresa(), true));

        assertEquals("SEFAZ_REJECTED", ex.getErrorCode());
        verifyNoInteractions(consultaSituacaoService);
        verify(nfeEmissaoService).resolverCicloComEfeitos(EMISSAO_ID, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 225, null, null,
                PEDIDO_ID, "REJEITADO", CHAVE, true, pedido().getItens(), EMPRESA_ID, "sistema-reconciliacao");
    }

    @Test
    @DisplayName("Local-first: cStat=205 resolve NUMERO_OCUPADO sem consultar a SEFAZ")
    void localFirst_cStat205_resolveNumeroOcupadoSemConsultarSefaz() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.PENDENTE_CONFIRMACAO);
        NfeDocumento doc = NfeDocumento.builder().chaveNfe(CHAVE).cStat("205").build();
        when(documentoService.buscarPorChave(CHAVE)).thenReturn(Optional.of(doc));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reconciliar(pedido(), emissao, empresa(), true));

        assertEquals("NUMERO_FISCAL_OCUPADO", ex.getErrorCode());
        assertTrue(ex.isRetryable());
        verifyNoInteractions(consultaSituacaoService);
        verify(nfeEmissaoService).resolverCicloComEfeitos(EMISSAO_ID, NfeEmissao.Estados.NUMERO_OCUPADO, 205, null, null,
                PEDIDO_ID, "ERRO", CHAVE, true, pedido().getItens(), EMPRESA_ID, "sistema-reconciliacao");
    }

    // -------------------------------------------------------------------------
    // Consulta Situação — classificação
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Consulta: cStat=100 com chNFe igual à congelada e protocolo válido -> AUTORIZADO")
    void consulta_cStat100ComProtocoloEChaveIgual_resolveAutorizado() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.TRANSMITIDO);
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(retorno(100, CHAVE, "prot-100", true));

        assertDoesNotThrow(() -> service.reconciliar(pedido(), emissao, empresa(), true));

        verify(nfeEmissaoService).resolverCicloComEfeitos(EMISSAO_ID, NfeEmissao.Estados.AUTORIZADO, 100, "motivo-100", "prot-100",
                PEDIDO_ID, "AUTORIZADO", CHAVE, true, pedido().getItens(), EMPRESA_ID, "sistema-reconciliacao");
    }

    @Test
    @DisplayName("Consulta: cStat=150 com chNFe igual e protocolo válido -> AUTORIZADO")
    void consulta_cStat150ComProtocoloEChaveIgual_resolveAutorizado() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.TRANSMITIDO);
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(retorno(150, CHAVE, "prot-150", true));

        assertDoesNotThrow(() -> service.reconciliar(pedido(), emissao, empresa(), true));

        verify(nfeEmissaoService).resolverCicloComEfeitos(EMISSAO_ID, NfeEmissao.Estados.AUTORIZADO, 150, "motivo-150", "prot-150",
                PEDIDO_ID, "AUTORIZADO", CHAVE, true, pedido().getItens(), EMPRESA_ID, "sistema-reconciliacao");
    }

    @Test
    @DisplayName("Consulta: cStat=100 mas chNFe divergente da congelada -- nunca autoriza, continua pendente")
    void consulta_cStat100ComChaveDivergente_nuncaAutoriza() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.TRANSMITIDO);
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(retorno(100, OUTRA_CHAVE, "prot-100", true));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reconciliar(pedido(), emissao, empresa(), true));

        assertEquals("EMISSAO_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        verify(nfeEmissaoService, never()).resolverCicloComEfeitos(
                anyLong(), anyString(), any(), any(), any(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Consulta: cStat=217 (NF-e não consta na base) -- resposta fiscal válida, continua pendente, nunca vira exceção de transporte")
    void consulta_cStat217_continuaPendente() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.PENDENTE_CONFIRMACAO);
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(retorno(217, null, null, false));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reconciliar(pedido(), emissao, empresa(), true));

        assertEquals("EMISSAO_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        verify(nfeEmissaoService, never()).resolverCicloComEfeitos(
                anyLong(), anyString(), any(), any(), any(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Consulta: cStat=635 (mesma série/número já transmitidos, aguardando processamento) -- continua pendente, não retransmite")
    void consulta_cStat635_continuaPendente() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.PENDENTE_CONFIRMACAO);
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(retorno(635, null, null, false));

        assertThrows(BusinessException.class, () -> service.reconciliar(pedido(), emissao, empresa(), true));

        verify(nfeEmissaoService, never()).resolverCicloComEfeitos(
                anyLong(), anyString(), any(), any(), any(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Consulta: cStat=205 (já denegada na base) -- NUMERO_OCUPADO, consome número, nunca autoriza")
    void consulta_cStat205_resolveNumeroOcupado() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.TRANSMITIDO);
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(retorno(205, CHAVE, null, false));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reconciliar(pedido(), emissao, empresa(), true));

        assertEquals("NUMERO_FISCAL_OCUPADO", ex.getErrorCode());
        verify(nfeEmissaoService).resolverCicloComEfeitos(EMISSAO_ID, NfeEmissao.Estados.NUMERO_OCUPADO, 205, "motivo-205", null,
                PEDIDO_ID, "ERRO", CHAVE, true, pedido().getItens(), EMPRESA_ID, "sistema-reconciliacao");
    }

    @Test
    @DisplayName("Consulta: cStat=206 (número já inutilizado) -- NUMERO_OCUPADO, nunca reutiliza o número")
    void consulta_cStat206_resolveNumeroOcupado() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.TRANSMITIDO);
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(retorno(206, CHAVE, null, false));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reconciliar(pedido(), emissao, empresa(), true));

        assertEquals("NUMERO_FISCAL_OCUPADO", ex.getErrorCode());
        verify(nfeEmissaoService).resolverCicloComEfeitos(EMISSAO_ID, NfeEmissao.Estados.NUMERO_OCUPADO, 206, "motivo-206", null,
                PEDIDO_ID, "ERRO", CHAVE, true, pedido().getItens(), EMPRESA_ID, "sistema-reconciliacao");
    }

    @Test
    @DisplayName("Consulta: cStat=218 (já cancelada na base) -- NUMERO_OCUPADO")
    void consulta_cStat218_resolveNumeroOcupado() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.TRANSMITIDO);
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(retorno(218, CHAVE, null, false));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reconciliar(pedido(), emissao, empresa(), true));

        assertEquals("NUMERO_FISCAL_OCUPADO", ex.getErrorCode());
        verify(nfeEmissaoService).resolverCicloComEfeitos(EMISSAO_ID, NfeEmissao.Estados.NUMERO_OCUPADO, 218, "motivo-218", null,
                PEDIDO_ID, "ERRO", CHAVE, true, pedido().getItens(), EMPRESA_ID, "sistema-reconciliacao");
    }

    @Test
    @DisplayName("Consulta: cStat=539 com chNFe diferente da congelada -- conflito confirmado, NUMERO_OCUPADO")
    void consulta_cStat539ComChaveDivergenteConfirmada_resolveNumeroOcupado() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.PENDENTE_CONFIRMACAO);
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(retorno(539, OUTRA_CHAVE, null, false));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reconciliar(pedido(), emissao, empresa(), true));

        assertEquals("NUMERO_FISCAL_OCUPADO", ex.getErrorCode());
        verify(nfeEmissaoService).resolverCicloComEfeitos(EMISSAO_ID, NfeEmissao.Estados.NUMERO_OCUPADO, 539, "motivo-539", null,
                PEDIDO_ID, "ERRO", CHAVE, true, pedido().getItens(), EMPRESA_ID, "sistema-reconciliacao");
    }

    @Test
    @DisplayName("Consulta: cStat=539 sem chNFe informada -- nunca gera chave nova, resultado ainda inconclusivo")
    void consulta_cStat539SemChave_continuaPendente_nuncaGeraChaveNova() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.PENDENTE_CONFIRMACAO);
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(retorno(539, null, null, false));

        assertThrows(BusinessException.class, () -> service.reconciliar(pedido(), emissao, empresa(), true));

        verify(nfeEmissaoService, never()).resolverCicloComEfeitos(
                anyLong(), anyString(), any(), any(), any(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Consulta: cStat=110 (uso denegado histórico) -- fail-safe, nunca automático")
    void consulta_cStat110_continuaPendente_failSafe() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.PENDENTE_CONFIRMACAO);
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(retorno(110, CHAVE, null, false));

        assertThrows(BusinessException.class, () -> service.reconciliar(pedido(), emissao, empresa(), true));

        verify(nfeEmissaoService, never()).resolverCicloComEfeitos(
                anyLong(), anyString(), any(), any(), any(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Consulta: cStat=301 -- excepcional, nunca vira rejeição corrigível reutilizável")
    void consulta_cStat301_continuaPendente() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.PENDENTE_CONFIRMACAO);
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(retorno(301, CHAVE, null, false));

        assertThrows(BusinessException.class, () -> service.reconciliar(pedido(), emissao, empresa(), true));

        verify(nfeEmissaoService, never()).resolverCicloComEfeitos(
                anyLong(), anyString(), any(), any(), any(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Consulta: cStat=204 sem protocolo -- continua pendente, não decide idempotência sozinho")
    void consulta_cStat204SemProtocolo_continuaPendente() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.PENDENTE_CONFIRMACAO);
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(retorno(204, null, null, false));

        assertThrows(BusinessException.class, () -> service.reconciliar(pedido(), emissao, empresa(), true));

        verify(nfeEmissaoService, never()).resolverCicloComEfeitos(
                anyLong(), anyString(), any(), any(), any(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Consulta: cStat=302/303 -- AGUARDANDO_CORRECAO, mesmo nNF, gate mantido até correção")
    void consulta_cStat302_resolveAguardandoCorrecao() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.PENDENTE_CONFIRMACAO);
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(retorno(302, null, null, false));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reconciliar(pedido(), emissao, empresa(), true));

        assertEquals("SEFAZ_REJECTED", ex.getErrorCode());
        verify(nfeEmissaoService).resolverCicloComEfeitos(EMISSAO_ID, NfeEmissao.Estados.AGUARDANDO_CORRECAO, 302, "motivo-302", null,
                PEDIDO_ID, "REJEITADO", CHAVE, true, pedido().getItens(), EMPRESA_ID, "sistema-reconciliacao");
    }

    // -------------------------------------------------------------------------
    // Falhas de transporte/parse — nunca confundidas com resposta fiscal válida
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Timeout/falha de transporte na Consulta Situação -- continua pendente, nunca decide")
    void falhaTransporte_continuaPendente() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.PENDENTE_CONFIRMACAO);
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenThrow(new SefazTransmissaoIncertaException(new java.net.SocketTimeoutException("timeout")));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reconciliar(pedido(), emissao, empresa(), true));

        assertEquals("EMISSAO_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        verify(nfeEmissaoService, never()).resolverCicloComEfeitos(
                anyLong(), anyString(), any(), any(), any(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Resposta da Consulta Situação ilegível (falha de parse) -- continua pendente, nunca decide")
    void falhaParse_continuaPendente() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.PENDENTE_CONFIRMACAO);
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(NfeConsultaSituacaoRetorno.falhaParse("XML malformado"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reconciliar(pedido(), emissao, empresa(), true));

        assertEquals("EMISSAO_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        verify(nfeEmissaoService, never()).resolverCicloComEfeitos(
                anyLong(), anyString(), any(), any(), any(), anyLong(), anyString(), any(), anyBoolean(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Sem chave congelada -- nunca consulta a SEFAZ, continua pendente")
    void semChaveCongelada_nuncaConsultaSefaz() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.TRANSMITIDO);
        emissao.setChaveNfe(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.reconciliar(pedido(), emissao, empresa(), true));

        assertEquals("EMISSAO_AGUARDANDO_RECONCILIACAO", ex.getErrorCode());
        verifyNoInteractions(consultaSituacaoService);
    }

    // -------------------------------------------------------------------------
    // Estoque — controlaEstoque=false nunca aplica efeito de estoque, mas ainda resolve o ciclo
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("controlaEstoque=false: reconciliação ainda resolve o ciclo, delegando controlaEstoque=false para a finalização atômica")
    void controlaEstoqueFalse_aindaAssimResolveCiclo() {
        NfeEmissao emissao = emissaoPendente(NfeEmissao.Estados.TRANSMITIDO);
        when(consultaSituacaoService.consultar(eq(CHAVE), anyString(), anyInt()))
                .thenReturn(retorno(100, CHAVE, "prot-100", true));

        assertDoesNotThrow(() -> service.reconciliar(pedido(), emissao, empresa(), false));

        verify(nfeEmissaoService).resolverCicloComEfeitos(EMISSAO_ID, NfeEmissao.Estados.AUTORIZADO, 100, "motivo-100", "prot-100",
                PEDIDO_ID, "AUTORIZADO", CHAVE, false, pedido().getItens(), EMPRESA_ID, "sistema-reconciliacao");
    }
}
