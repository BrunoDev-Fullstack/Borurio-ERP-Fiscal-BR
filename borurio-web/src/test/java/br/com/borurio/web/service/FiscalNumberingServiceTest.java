package br.com.borurio.web.service;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.entity.OmsFiscalAuthorization;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.app.mapper.OmsFiscalAuthorizationMapper;
import br.com.borurio.fiscal.dto.AtualizacaoSequenciaResultado;
import br.com.borurio.fiscal.entity.NfeSequenciaAuditoria;
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
}
