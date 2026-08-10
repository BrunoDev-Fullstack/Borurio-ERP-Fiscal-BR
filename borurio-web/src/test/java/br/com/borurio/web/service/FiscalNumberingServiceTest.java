package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.OmsFiscalAuthorization;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.OmsFiscalAuthorizationMapper;
import br.com.borurio.fiscal.dto.AtualizacaoSequenciaResultado;
import br.com.borurio.fiscal.entity.NfeSequencia;
import br.com.borurio.fiscal.entity.NfeSequenciaAuditoria;
import br.com.borurio.fiscal.exception.SequenciaComEmissaoAtivaException;
import br.com.borurio.fiscal.mapper.NfeSequenciaAuditoriaMapper;
import br.com.borurio.fiscal.service.NfeSequenciaService;
import br.com.borurio.web.dto.FiscalNumberingSyncResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("FiscalNumberingService — PUT /api/integration/fiscal-numbering/{cnpj}")
class FiscalNumberingServiceTest {

    @Mock EmpresaMapper empresaMapper;
    @Mock NfeSequenciaService sequenciaService;
    @Mock NfeSequenciaAuditoriaMapper auditoriaMapper;
    @Mock OmsCertificadoService omsCertificadoService;
    @Mock OmsFiscalAuthorizationMapper omsAuthMapper;

    FiscalNumberingService service;

    private static final String CNPJ = "22418179000134";
    private static final String JTI  = "jti-oms-teste";

    @BeforeEach
    void setUp() {
        service = new FiscalNumberingService(
                empresaMapper, sequenciaService, auditoriaMapper, omsCertificadoService, omsAuthMapper);
        lenient().when(omsCertificadoService.cnpjAutorizadoParaJti(JTI, CNPJ)).thenReturn(true);
    }

    private Empresa empresaComSerie(String serie) {
        Empresa e = new Empresa();
        e.setId(8L);
        e.setCnpj(CNPJ);
        e.setSerieNfePadrao(serie);
        return e;
    }

    @Test
    @DisplayName("Sincronização normal (mesma série) grava auditoria e não toca Empresa.serieNfePadrao")
    void sincronizar_mesmaSerie_naoAtualizaEmpresa() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresaComSerie("1"));
        when(sequenciaService.atualizarSequencia(CNPJ, "1", 101))
                .thenReturn(new AtualizacaoSequenciaResultado(CNPJ, "1", 100, 101, true, LocalDateTime.now()));

        FiscalNumberingSyncResponse resp = service.sincronizar(CNPJ, "1", 101, JTI, "req-1");

        assertEquals("1", resp.serieAnterior());
        assertEquals("1", resp.serieAtual());
        assertEquals(100, resp.proximoNumeroAnterior());
        assertEquals(101, resp.proximoNumeroAtual());
        assertTrue(resp.aplicado());
        verify(empresaMapper, never()).atualizarSerieNfePadrao(anyLong(), anyString());
        verify(auditoriaMapper).inserir(any());
    }

    @Test
    @DisplayName("Troca de série atualiza Empresa.serieNfePadrao na mesma chamada")
    void sincronizar_serieDiferente_atualizaEmpresa() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresaComSerie("1"));
        when(sequenciaService.atualizarSequencia(CNPJ, "2", 1))
                .thenReturn(new AtualizacaoSequenciaResultado(CNPJ, "2", 0, 1, true, LocalDateTime.now()));

        FiscalNumberingSyncResponse resp = service.sincronizar(CNPJ, "2", 1, JTI, "req-2");

        assertEquals("1", resp.serieAnterior());
        assertEquals("2", resp.serieAtual());
        assertTrue(resp.aplicado());
        verify(empresaMapper).atualizarSerieNfePadrao(8L, "2");
    }

    @Test
    @DisplayName("Idempotente: mesmo valor da última sincronização não atualiza Empresa nem marca aplicado")
    void sincronizar_idempotente_naoAtualizaEmpresa() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresaComSerie("1"));
        when(sequenciaService.atualizarSequencia(CNPJ, "1", 101))
                .thenReturn(new AtualizacaoSequenciaResultado(CNPJ, "1", 101, 101, false, LocalDateTime.now()));

        FiscalNumberingSyncResponse resp = service.sincronizar(CNPJ, "1", 101, JTI, "req-3");

        assertFalse(resp.aplicado());
        verify(empresaMapper, never()).atualizarSerieNfePadrao(anyLong(), anyString());
    }

    @Test
    @DisplayName("Regressão de numeração vira BusinessException NUMERACAO_INFERIOR_A_ATUAL / 422")
    void sincronizar_numeracaoInferior_rejeitada() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresaComSerie("1"));
        when(sequenciaService.atualizarSequencia(CNPJ, "1", 5))
                .thenThrow(new IllegalStateException("sequenciador já está em 101"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sincronizar(CNPJ, "1", 5, JTI, "req-4"));

        assertEquals("NUMERACAO_INFERIOR_A_ATUAL", ex.getErrorCode());
        assertEquals(422, ex.getHttpStatus());
        verify(auditoriaMapper, never()).inserir(any());
        verify(empresaMapper, never()).atualizarSerieNfePadrao(anyLong(), anyString());
    }

    @Test
    @DisplayName("CNPJ não autorizado pro token retorna CNPJ_NOT_AUTHORIZED / 403 — nunca toca Empresa/sequência")
    void sincronizar_cnpjNaoAutorizado_rejeitado() {
        when(omsCertificadoService.cnpjAutorizadoParaJti(JTI, CNPJ)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sincronizar(CNPJ, "1", 101, JTI, "req-5"));

        assertEquals("CNPJ_NOT_AUTHORIZED", ex.getErrorCode());
        assertEquals(403, ex.getHttpStatus());
        verifyNoInteractions(empresaMapper, sequenciaService, auditoriaMapper);
    }

    @Test
    @DisplayName("jti nulo (sem autenticação OMS) é tratado como não autorizado, nunca NPE")
    void sincronizar_jtiNulo_rejeitado() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sincronizar(CNPJ, "1", 101, null, "req-6"));

        assertEquals("CNPJ_NOT_AUTHORIZED", ex.getErrorCode());
    }

    @Test
    @DisplayName("Série vazia retorna SERIE_INVALIDA / 422 antes de tocar qualquer mapper")
    void sincronizar_serieInvalida_rejeitada() {
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sincronizar(CNPJ, "  ", 101, JTI, "req-7"));

        assertEquals("SERIE_INVALIDA", ex.getErrorCode());
        assertEquals(422, ex.getHttpStatus());
        verifyNoInteractions(empresaMapper, sequenciaService, omsCertificadoService, auditoriaMapper);
    }

    @Test
    @DisplayName("proximoNumero nulo ou menor que 1 retorna NUMERACAO_INVALIDA / 422 antes de tocar qualquer mapper")
    void sincronizar_numeroInvalido_rejeitado() {
        BusinessException ex1 = assertThrows(BusinessException.class,
                () -> service.sincronizar(CNPJ, "1", 0, JTI, "req-8"));
        assertEquals("NUMERACAO_INVALIDA", ex1.getErrorCode());

        BusinessException ex2 = assertThrows(BusinessException.class,
                () -> service.sincronizar(CNPJ, "1", null, JTI, "req-9"));
        assertEquals("NUMERACAO_INVALIDA", ex2.getErrorCode());

        verifyNoInteractions(empresaMapper, sequenciaService, omsCertificadoService, auditoriaMapper);
    }

    @Test
    @DisplayName("Empresa não encontrada apesar de token autorizado retorna COMPANY_NOT_FOUND — estado inconsistente, não presumido")
    void sincronizar_empresaNaoEncontrada_rejeitada() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sincronizar(CNPJ, "1", 101, JTI, "req-10"));

        assertEquals("COMPANY_NOT_FOUND", ex.getErrorCode());
        verifyNoInteractions(sequenciaService, auditoriaMapper);
    }

    @Test
    @DisplayName("Auditoria grava id interno da autorização, nunca o jti completo")
    void sincronizar_auditoria_gravaIdInternoNaoJti() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresaComSerie("1"));
        when(sequenciaService.atualizarSequencia(CNPJ, "1", 101))
                .thenReturn(new AtualizacaoSequenciaResultado(CNPJ, "1", 100, 101, true, LocalDateTime.now()));
        OmsFiscalAuthorization auth = new OmsFiscalAuthorization();
        auth.setId(42L);
        auth.setJti(JTI);
        when(omsAuthMapper.buscarPorJti(JTI)).thenReturn(auth);

        service.sincronizar(CNPJ, "1", 101, JTI, "req-11");

        ArgumentCaptor<NfeSequenciaAuditoria> captor = ArgumentCaptor.forClass(NfeSequenciaAuditoria.class);
        verify(auditoriaMapper).inserir(captor.capture());
        NfeSequenciaAuditoria auditoria = captor.getValue();
        assertEquals("42", auditoria.getClienteOms());
        assertEquals("OMS_SYNC", auditoria.getOrigem());
        assertEquals("req-11", auditoria.getRequestId());
        assertEquals(CNPJ, auditoria.getCnpjEmitente());
    }

    @Test
    @DisplayName("CNPJ recebido no path é normalizado (remove máscara) antes de qualquer consulta")
    void sincronizar_cnpjComMascara_normalizado() {
        String cnpjMascarado = "22.418.179/0001-34";
        when(omsCertificadoService.cnpjAutorizadoParaJti(JTI, CNPJ)).thenReturn(true);
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresaComSerie("1"));
        when(sequenciaService.atualizarSequencia(CNPJ, "1", 101))
                .thenReturn(new AtualizacaoSequenciaResultado(CNPJ, "1", 100, 101, true, LocalDateTime.now()));

        FiscalNumberingSyncResponse resp = service.sincronizar(cnpjMascarado, "1", 101, JTI, "req-12");

        assertEquals(CNPJ, resp.cnpjEmitente());
        verify(empresaMapper).buscarPorCnpjParaAtualizar(CNPJ);
    }

    // -------------------------------------------------------------------------
    // P0-1 (07-08-2026, hardening pós-banca) — sincronização x gate do Gate 1.
    // Cenários A-G conforme revisão.
    // -------------------------------------------------------------------------

    private NfeSequencia seqComGate(String serie, int ultimoNumero, Long emissaoAtivaId) {
        NfeSequencia seq = new NfeSequencia();
        seq.setCnpjEmitente(CNPJ);
        seq.setSerie(serie);
        seq.setUltimoNumero(ultimoNumero);
        seq.setEmissaoAtivaId(emissaoAtivaId);
        return seq;
    }

    @Test
    @DisplayName("A) Mesma série + avanço de número + gate ocupado -> 409 NUMERACAO_COM_EMISSAO_EM_ANDAMENTO, nada gravado")
    void sincronizar_mesmaSerieAvancoComGateOcupado_rejeitada409() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresaComSerie("1"));
        when(sequenciaService.atualizarSequencia(CNPJ, "1", 105))
                .thenThrow(new SequenciaComEmissaoAtivaException(CNPJ, "1", 501L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sincronizar(CNPJ, "1", 105, JTI, "req-A"));

        assertEquals("NUMERACAO_COM_EMISSAO_EM_ANDAMENTO", ex.getErrorCode());
        assertEquals(409, ex.getHttpStatus());
        assertTrue(ex.isRetryable());
        assertFalse(ex.getMessage().contains("501"), "id interno da emissão não pode vazar na mensagem ao OMS");
        verify(empresaMapper, never()).atualizarSerieNfePadrao(anyLong(), anyString());
        verify(auditoriaMapper, never()).inserir(any());
    }

    @Test
    @DisplayName("B) Troca de série + emissão ativa na série ATUAL -> 409, Empresa.serieNfePadrao não muda")
    void sincronizar_trocaSerieComGateNaSerieAtual_rejeitada409() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresaComSerie("1"));
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1"))
                .thenReturn(seqComGate("1", 100, 501L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sincronizar(CNPJ, "2", 1, JTI, "req-B"));

        assertEquals("NUMERACAO_COM_EMISSAO_EM_ANDAMENTO", ex.getErrorCode());
        assertFalse(ex.getMessage().contains("501"), "id interno da emissão não pode vazar na mensagem ao OMS");
        verify(empresaMapper, never()).atualizarSerieNfePadrao(anyLong(), anyString());
        verify(sequenciaService, never()).atualizarSequencia(any(), any(), anyInt());
        verify(auditoriaMapper, never()).inserir(any());
    }

    @Test
    @DisplayName("C) Troca de série para destino OCUPADO -> 409, nenhuma alteração")
    void sincronizar_trocaSerieParaDestinoOcupado_rejeitada409() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresaComSerie("1"));
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(seqComGate("1", 100, null));
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "2")).thenReturn(seqComGate("2", 4, 777L));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sincronizar(CNPJ, "2", 5, JTI, "req-C"));

        assertEquals("NUMERACAO_COM_EMISSAO_EM_ANDAMENTO", ex.getErrorCode());
        assertFalse(ex.getMessage().contains("777"), "id interno da emissão não pode vazar na mensagem ao OMS");
        verify(empresaMapper, never()).atualizarSerieNfePadrao(anyLong(), anyString());
        verify(sequenciaService, never()).atualizarSequencia(any(), any(), anyInt());
    }

    @Test
    @DisplayName("D) Mesma série + mesmo próximo número (idempotente) permanece aceita mesmo com gate ocupado — regra 1")
    void sincronizar_idempotenteComGateOcupado_permaneceAceita() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresaComSerie("1"));
        // Idempotente: aplicarOuValidar retorna aplicado=false ANTES de checar o gate — o mock
        // aqui reflete exatamente esse contrato (o teste de unidade em NfeSequenciaServiceTest
        // prova o comportamento real dentro de aplicarOuValidar).
        when(sequenciaService.atualizarSequencia(CNPJ, "1", 101))
                .thenReturn(new AtualizacaoSequenciaResultado(CNPJ, "1", 101, 101, false, LocalDateTime.now()));

        FiscalNumberingSyncResponse resp = service.sincronizar(CNPJ, "1", 101, JTI, "req-D");

        assertFalse(resp.aplicado());
        verify(sequenciaService, never()).buscarSeExistirParaAtualizar(any(), any());
    }

    @Test
    @DisplayName("E) Gate livre + avanço válido de número — comportamento homologado preservado")
    void sincronizar_gateLivreAvancoValido_comportamentoPreservado() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresaComSerie("1"));
        when(sequenciaService.atualizarSequencia(CNPJ, "1", 105))
                .thenReturn(new AtualizacaoSequenciaResultado(CNPJ, "1", 100, 105, true, LocalDateTime.now()));

        FiscalNumberingSyncResponse resp = service.sincronizar(CNPJ, "1", 105, JTI, "req-E");

        assertTrue(resp.aplicado());
    }

    @Test
    @DisplayName("F) Gate livre + troca válida de série — comportamento homologado preservado")
    void sincronizar_gateLivreTrocaSerie_comportamentoPreservado() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresaComSerie("1"));
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "1")).thenReturn(seqComGate("1", 100, null));
        when(sequenciaService.buscarSeExistirParaAtualizar(CNPJ, "2")).thenReturn(null); // série nova nunca usada
        when(sequenciaService.atualizarSequencia(CNPJ, "2", 1))
                .thenReturn(new AtualizacaoSequenciaResultado(CNPJ, "2", 0, 1, true, LocalDateTime.now()));

        FiscalNumberingSyncResponse resp = service.sincronizar(CNPJ, "2", 1, JTI, "req-F");

        assertTrue(resp.aplicado());
        verify(empresaMapper).atualizarSerieNfePadrao(8L, "2");
    }

    @Test
    @DisplayName("G) Após o ciclo ativo ser resolvido, a mesma sincronização antes bloqueada é aplicada normalmente")
    void sincronizar_apoisCicloResolvido_sincronizacaoAnteriorBloqueadaPassaAAplicar() {
        when(empresaMapper.buscarPorCnpjParaAtualizar(CNPJ)).thenReturn(empresaComSerie("1"));

        // Primeira tentativa: gate ocupado, bloqueada.
        when(sequenciaService.atualizarSequencia(CNPJ, "1", 105))
                .thenThrow(new SequenciaComEmissaoAtivaException(CNPJ, "1", 501L));
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.sincronizar(CNPJ, "1", 105, JTI, "req-G1"));
        assertEquals("NUMERACAO_COM_EMISSAO_EM_ANDAMENTO", ex.getErrorCode());

        // Ciclo resolvido (AUTORIZADO/DENEGADO) — resolverCiclo() já teria liberado o gate
        // (emissao_ativa_id=NULL) e avançado ultimo_numero via consumirNumero(), fora deste
        // teste. Reflete isso remockando atualizarSequencia para o comportamento pós-resolução.
        reset(sequenciaService);
        when(sequenciaService.atualizarSequencia(CNPJ, "1", 105))
                .thenReturn(new AtualizacaoSequenciaResultado(CNPJ, "1", 100, 105, true, LocalDateTime.now()));

        FiscalNumberingSyncResponse resp = service.sincronizar(CNPJ, "1", 105, JTI, "req-G2");

        assertTrue(resp.aplicado());
    }
}
