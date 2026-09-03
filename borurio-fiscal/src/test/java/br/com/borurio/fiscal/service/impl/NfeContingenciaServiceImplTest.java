package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.entity.NfeEmissao;
import br.com.borurio.fiscal.entity.NfeSequencia;
import br.com.borurio.fiscal.exception.ContingenciaInvalidaException;
import br.com.borurio.fiscal.mapper.NfeEmissaoMapper;
import br.com.borurio.fiscal.service.NfeContingenciaService;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Fase 1 SVC (17-08-2026) — Caminho B (persistência/ciclo de substituição, sem transporte). Ver
 * NfeContingenciaConcorrenciaRealMySqlIT para as corridas reais (RACE A/B/C) contra MySQL.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("NfeContingenciaService — abertura de contingência (Caminho B)")
class NfeContingenciaServiceImplTest {

    @Mock NfeSequenciaService sequenciaService;
    @Mock NfeEmissaoMapper nfeEmissaoMapper;

    NfeContingenciaService service;

    private static final String CNPJ = "22418179000134";
    private static final String SERIE = "1";
    private static final Long NORMAL_ID = 501L;
    private static final String X_JUST_VALIDO = "Falha de conectividade com a SEFAZ";
    private static final OffsetDateTime DH_CONT = OffsetDateTime.of(2026, 8, 17, 14, 30, 0, 0, ZoneOffset.of("-03:00"));

    @BeforeEach
    void setUp() {
        service = new NfeContingenciaServiceImpl(sequenciaService, nfeEmissaoMapper);
    }

    private NfeEmissao normal(String estado, int numero) {
        NfeEmissao e = new NfeEmissao();
        e.setId(NORMAL_ID);
        e.setPedidoId(1L);
        e.setEmpresaId(10L);
        e.setCnpjEmitente(CNPJ);
        e.setModelo("55");
        e.setSerie(SERIE);
        e.setNumeroNfe(numero);
        e.setEstado(estado);
        e.setTentativas(1);
        return e;
    }

    private NfeSequencia sequencia(int ultimoNumero, Long emissaoAtivaId) {
        NfeSequencia seq = new NfeSequencia();
        seq.setCnpjEmitente(CNPJ);
        seq.setSerie(SERIE);
        seq.setUltimoNumero(ultimoNumero);
        seq.setEmissaoAtivaId(emissaoAtivaId);
        return seq;
    }

    private void stubGateNaNormal(String estado, int numero, int ultimoNumero) {
        NfeEmissao n = normal(estado, numero);
        when(nfeEmissaoMapper.buscarPorId(NORMAL_ID)).thenReturn(n);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, SERIE)).thenReturn(sequencia(ultimoNumero, NORMAL_ID));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(NORMAL_ID)).thenReturn(n);
    }

    // -------------------------------------------------------------------------
    // Caminho B — feliz
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Caminho B feliz: consolida, número da filha vem do RETORNO da consolidação, insere filha e troca o gate via CAS")
    void abrirContingencia_caminhoB_feliz_numeroFilhaDoRetornoDaConsolidacao() {
        stubGateNaNormal(NfeEmissao.Estados.TRANSMITIDO, 100, 99);
        when(sequenciaService.consolidarNumeroParaContingencia(CNPJ, SERIE, 100)).thenReturn(100);
        doAnswer(inv -> {
            NfeEmissao filha = inv.getArgument(0);
            filha.setId(900L);
            return 1;
        }).when(nfeEmissaoMapper).inserirContingencia(any(NfeEmissao.class));

        NfeEmissao filha = service.abrirContingencia(NORMAL_ID, NfeEmissao.TpEmis.SVC_AN, X_JUST_VALIDO, DH_CONT);

        assertEquals(101, filha.getNumeroNfe(), "numeroFilha = retorno da consolidação (100) + 1, nunca NORMAL.numeroNfe direto");
        assertEquals(NfeEmissao.Estados.RESERVADO, filha.getEstado());
        assertEquals(NfeEmissao.TpEmis.SVC_AN, filha.getTpEmis());
        assertEquals(NfeEmissao.AutorizadorDestino.SVC_AN, filha.getAutorizadorDestino());
        assertEquals(NORMAL_ID, filha.getEmissaoOrigemId());
        assertEquals("2026-08-17T14:30:00-03:00", filha.getDhCont());
        assertEquals(X_JUST_VALIDO, filha.getXJustContingencia());

        verify(sequenciaService).consolidarNumeroParaContingencia(CNPJ, SERIE, 100);
        verify(sequenciaService).substituirGateParaContingencia(CNPJ, SERIE, NORMAL_ID, 900L);
        ArgumentCaptor<NfeEmissao> captor = ArgumentCaptor.forClass(NfeEmissao.class);
        verify(nfeEmissaoMapper).inserirContingencia(captor.capture());
        assertEquals(101, captor.getValue().getNumeroNfe());
    }

    @Test
    @DisplayName("Caminho B a partir de PENDENTE_CONFIRMACAO também é aceito")
    void abrirContingencia_caminhoB_pendenteConfirmacao_aceito() {
        stubGateNaNormal(NfeEmissao.Estados.PENDENTE_CONFIRMACAO, 50, 49);
        when(sequenciaService.consolidarNumeroParaContingencia(CNPJ, SERIE, 50)).thenReturn(50);
        doAnswer(inv -> {
            ((NfeEmissao) inv.getArgument(0)).setId(700L);
            return 1;
        }).when(nfeEmissaoMapper).inserirContingencia(any(NfeEmissao.class));

        NfeEmissao filha = service.abrirContingencia(NORMAL_ID, NfeEmissao.TpEmis.SVC_RS, X_JUST_VALIDO, DH_CONT);

        assertEquals(51, filha.getNumeroNfe());
        assertEquals(NfeEmissao.AutorizadorDestino.SVC_RS, filha.getAutorizadorDestino());
    }

    // -------------------------------------------------------------------------
    // Caminho A — não implementado nesta fase
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Caminho A (NORMAL ainda RESERVADO) é rejeitado explicitamente — não implementado nesta fase")
    void abrirContingencia_normalReservado_rejeitaCaminhoANaoImplementado() {
        stubGateNaNormal(NfeEmissao.Estados.RESERVADO, 100, 99);

        ContingenciaInvalidaException ex = assertThrows(ContingenciaInvalidaException.class,
                () -> service.abrirContingencia(NORMAL_ID, NfeEmissao.TpEmis.SVC_AN, X_JUST_VALIDO, DH_CONT));

        assertTrue(ex.getMessage().contains("Caminho A"));
        verify(sequenciaService, never()).consolidarNumeroParaContingencia(any(), any(), anyInt());
        verify(nfeEmissaoMapper, never()).inserirContingencia(any());
        verify(sequenciaService, never()).substituirGateParaContingencia(any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Rejeições — estado terminal / AGUARDANDO_CORRECAO / gate não corresponde
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("NORMAL já AUTORIZADA (terminal): contingência recusada, nada consolidado/inserido")
    void abrirContingencia_normalAutorizada_rejeita() {
        // Terminal -> gate já liberado (emissaoAtivaId != NORMAL_ID) -- reproduz RACE A.
        NfeEmissao n = normal(NfeEmissao.Estados.AUTORIZADO, 100);
        when(nfeEmissaoMapper.buscarPorId(NORMAL_ID)).thenReturn(n);
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, SERIE)).thenReturn(sequencia(100, null));
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(NORMAL_ID)).thenReturn(n);

        assertThrows(ContingenciaInvalidaException.class,
                () -> service.abrirContingencia(NORMAL_ID, NfeEmissao.TpEmis.SVC_AN, X_JUST_VALIDO, DH_CONT));

        verify(sequenciaService, never()).consolidarNumeroParaContingencia(any(), any(), anyInt());
        verify(nfeEmissaoMapper, never()).inserirContingencia(any());
    }

    @Test
    @DisplayName("NORMAL em AGUARDANDO_CORRECAO: contingência recusada — SEFAZ já respondeu, sem ambiguidade")
    void abrirContingencia_normalAguardandoCorrecao_rejeita() {
        stubGateNaNormal(NfeEmissao.Estados.AGUARDANDO_CORRECAO, 100, 99);

        assertThrows(ContingenciaInvalidaException.class,
                () -> service.abrirContingencia(NORMAL_ID, NfeEmissao.TpEmis.SVC_AN, X_JUST_VALIDO, DH_CONT));

        verify(sequenciaService, never()).consolidarNumeroParaContingencia(any(), any(), anyInt());
    }

    @Test
    @DisplayName("Gate não aponta para a NORMAL informada: contingência recusada (não é o ciclo ativo)")
    void abrirContingencia_gateNaoCorresponde_rejeita() {
        NfeEmissao n = normal(NfeEmissao.Estados.TRANSMITIDO, 100);
        when(nfeEmissaoMapper.buscarPorId(NORMAL_ID)).thenReturn(n);
        when(nfeEmissaoMapper.buscarPorIdParaAtualizar(NORMAL_ID)).thenReturn(n);
        // Gate aponta para outro ciclo (999L), não para NORMAL_ID.
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, SERIE)).thenReturn(sequencia(99, 999L));

        assertThrows(ContingenciaInvalidaException.class,
                () -> service.abrirContingencia(NORMAL_ID, NfeEmissao.TpEmis.SVC_AN, X_JUST_VALIDO, DH_CONT));

        verify(sequenciaService, never()).consolidarNumeroParaContingencia(any(), any(), anyInt());
    }

    // -------------------------------------------------------------------------
    // tpEmis -> autorizadorDestino sempre derivado, nunca recebido separado
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("tpEmisContingencia inválido (fora de SVC_AN/SVC_RS) é rejeitado antes de tocar qualquer mapper")
    void abrirContingencia_tpEmisInvalido_rejeitadoAntesDeQualquerLock() {
        assertThrows(IllegalArgumentException.class,
                () -> service.abrirContingencia(NORMAL_ID, "4" /* EPEC, fora de escopo */, X_JUST_VALIDO, DH_CONT));
        assertThrows(IllegalArgumentException.class,
                () -> service.abrirContingencia(NORMAL_ID, NfeEmissao.TpEmis.NORMAL, X_JUST_VALIDO, DH_CONT));

        verifyNoInteractions(nfeEmissaoMapper, sequenciaService);
    }

    // -------------------------------------------------------------------------
    // xJust — trim, 15..256 chars no valor normalizado, só-espaços rejeitado
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("xJust é trimado antes de validar/persistir")
    void abrirContingencia_xJustComEspacos_trimAntesDePersistir() {
        stubGateNaNormal(NfeEmissao.Estados.TRANSMITIDO, 100, 99);
        when(sequenciaService.consolidarNumeroParaContingencia(CNPJ, SERIE, 100)).thenReturn(100);
        doAnswer(inv -> {
            ((NfeEmissao) inv.getArgument(0)).setId(900L);
            return 1;
        }).when(nfeEmissaoMapper).inserirContingencia(any(NfeEmissao.class));

        NfeEmissao filha = service.abrirContingencia(NORMAL_ID, NfeEmissao.TpEmis.SVC_AN,
                "   " + X_JUST_VALIDO + "   ", DH_CONT);

        assertEquals(X_JUST_VALIDO, filha.getXJustContingencia());
    }

    @Test
    @DisplayName("xJust menor que 15 chars após trim é rejeitado")
    void abrirContingencia_xJustCurto_rejeitado() {
        assertThrows(IllegalArgumentException.class,
                () -> service.abrirContingencia(NORMAL_ID, NfeEmissao.TpEmis.SVC_AN, "curto", DH_CONT));
        verifyNoInteractions(nfeEmissaoMapper, sequenciaService);
    }

    @Test
    @DisplayName("xJust maior que 256 chars após trim é rejeitado")
    void abrirContingencia_xJustLongo_rejeitado() {
        String longo = "a".repeat(257);
        assertThrows(IllegalArgumentException.class,
                () -> service.abrirContingencia(NORMAL_ID, NfeEmissao.TpEmis.SVC_AN, longo, DH_CONT));
    }

    @Test
    @DisplayName("xJust só com espaços é rejeitado (trim vira vazio, falha no mínimo de 15)")
    void abrirContingencia_xJustSoEspacos_rejeitado() {
        assertThrows(IllegalArgumentException.class,
                () -> service.abrirContingencia(NORMAL_ID, NfeEmissao.TpEmis.SVC_AN, "                              ", DH_CONT));
        verifyNoInteractions(nfeEmissaoMapper, sequenciaService);
    }

    // -------------------------------------------------------------------------
    // Double-substitute — defesa em profundidade via DuplicateKeyException
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Double-substitute (uk_nfe_emissao_origem) é traduzido para ContingenciaInvalidaException")
    void abrirContingencia_duplicateKeyNoInsert_traduzParaContingenciaInvalida() {
        stubGateNaNormal(NfeEmissao.Estados.TRANSMITIDO, 100, 99);
        when(sequenciaService.consolidarNumeroParaContingencia(CNPJ, SERIE, 100)).thenReturn(100);
        doThrow(new DuplicateKeyException("uk_nfe_emissao_origem"))
                .when(nfeEmissaoMapper).inserirContingencia(any(NfeEmissao.class));

        assertThrows(ContingenciaInvalidaException.class,
                () -> service.abrirContingencia(NORMAL_ID, NfeEmissao.TpEmis.SVC_AN, X_JUST_VALIDO, DH_CONT));

        verify(sequenciaService, never()).substituirGateParaContingencia(any(), any(), any(), any());
    }
}
