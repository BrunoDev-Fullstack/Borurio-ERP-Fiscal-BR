package br.com.borurio.web.service;

import br.com.borurio.app.entity.DbUser;
import br.com.borurio.app.entity.MotivoAdminOms;
import br.com.borurio.app.entity.OmsFiscalAuthorization;
import br.com.borurio.app.entity.OmsFiscalAuthorizationAudit;
import br.com.borurio.app.exception.BusinessException;
import br.com.borurio.app.mapper.DbUserMapper;
import br.com.borurio.app.mapper.OmsFiscalAuthorizationAuditMapper;
import br.com.borurio.app.mapper.OmsFiscalAuthorizationMapper;
import br.com.borurio.web.auth.JwtUtil;
import br.com.borurio.web.dto.OmsAuthorizationAdminResponse;
import br.com.borurio.web.dto.OmsAuthorizationRevogarRequest;
import br.com.borurio.web.dto.OmsAuthorizationRotacionarRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OmsAuthorizationAdminServiceTest {

    private static final Long AUTH_ID = 5L;
    private static final Long OUTRO_AUTH_ID = 7L;
    private static final String ADMIN_EMAIL = "admin@borurio.com";
    private static final String IDEMPOTENCY_KEY = "22222222-2222-2222-2222-222222222222";

    @Mock private OmsFiscalAuthorizationMapper authMapper;
    @Mock private OmsFiscalAuthorizationAuditMapper auditMapper;
    @Mock private DbUserMapper dbUserMapper;
    @Mock private JwtUtil jwtUtil;
    @Mock private PlatformTransactionManager transactionManager;

    private OmsAuthorizationAdminService service;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));

        service = new OmsAuthorizationAdminService(authMapper, auditMapper, dbUserMapper, jwtUtil, transactionManager);
        ReflectionTestUtils.setField(service, "rotationMinValidityMs", 3_600_000L);

        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(ADMIN_EMAIL, null, List.of()));

        DbUser admin = new DbUser();
        admin.setId(99L);
        admin.setEmail(ADMIN_EMAIL);
        lenient().when(dbUserMapper.findByEmail(ADMIN_EMAIL)).thenReturn(admin);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private OmsFiscalAuthorization authAtiva(Long versao) {
        OmsFiscalAuthorization auth = new OmsFiscalAuthorization();
        auth.setId(AUTH_ID);
        auth.setEmpresaId(1L);
        auth.setCodigoOms("CLIENTE-OMS");
        auth.setJti("jti-atual");
        auth.setTokenExpiraEm(LocalDateTime.now().plusYears(1));
        auth.setEmitidoEm(LocalDateTime.now().minusDays(1));
        auth.setVersao(versao);
        return auth;
    }

    // =========================================================================
    // Revogação
    // =========================================================================

    @Nested
    class Revogacao {

        @Test
        void idempotencyKeyAusente_lancaInvalidIdempotencyKey() {
            OmsAuthorizationRevogarRequest req = new OmsAuthorizationRevogarRequest();
            req.setMotivoCodigo(MotivoAdminOms.ROTINA_SEGURANCA);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.revogar(AUTH_ID, req, null, "req-1"));
            assertEquals("INVALID_IDEMPOTENCY_KEY", ex.getErrorCode());
            assertEquals(400, ex.getHttpStatus());
            verifyNoInteractions(authMapper);
        }

        @Test
        void idempotencyKeyMalformada_lancaInvalidIdempotencyKey() {
            OmsAuthorizationRevogarRequest req = new OmsAuthorizationRevogarRequest();
            req.setMotivoCodigo(MotivoAdminOms.ROTINA_SEGURANCA);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.revogar(AUTH_ID, req, "nao-e-uuid", "req-1"));
            assertEquals("INVALID_IDEMPOTENCY_KEY", ex.getErrorCode());
        }

        @Test
        void motivoOutroSemDetalhe_lancaMotivoDetalheObrigatorio() {
            OmsAuthorizationRevogarRequest req = new OmsAuthorizationRevogarRequest();
            req.setMotivoCodigo(MotivoAdminOms.OUTRO);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.revogar(AUTH_ID, req, IDEMPOTENCY_KEY, "req-1"));
            assertEquals("MOTIVO_DETALHE_OBRIGATORIO", ex.getErrorCode());
            verifyNoInteractions(authMapper);
        }

        @Test
        void idempotencyKeyDeOutraAutorizacao_lancaIdempotencyKeyConflict() {
            OmsFiscalAuthorizationAudit deOutraAuth = new OmsFiscalAuthorizationAudit();
            deOutraAuth.setAuthId(OUTRO_AUTH_ID);
            deOutraAuth.setEvento("REVOGACAO");
            when(auditMapper.buscarPorIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(deOutraAuth);

            OmsAuthorizationRevogarRequest req = new OmsAuthorizationRevogarRequest();
            req.setMotivoCodigo(MotivoAdminOms.ROTINA_SEGURANCA);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.revogar(AUTH_ID, req, IDEMPOTENCY_KEY, "req-1"));
            assertEquals("IDEMPOTENCY_KEY_CONFLICT", ex.getErrorCode());
            assertEquals(409, ex.getHttpStatus());
            // nunca chega a tocar a autorização solicitada
            verifyNoInteractions(authMapper);
        }

        @Test
        void idempotencyKeyDeEventoDiferente_lancaIdempotencyKeyConflict() {
            OmsFiscalAuthorizationAudit deRotacao = new OmsFiscalAuthorizationAudit();
            deRotacao.setAuthId(AUTH_ID);
            deRotacao.setEvento("ROTACAO");
            when(auditMapper.buscarPorIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(deRotacao);

            OmsAuthorizationRevogarRequest req = new OmsAuthorizationRevogarRequest();
            req.setMotivoCodigo(MotivoAdminOms.ROTINA_SEGURANCA);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.revogar(AUTH_ID, req, IDEMPOTENCY_KEY, "req-1"));
            assertEquals("IDEMPOTENCY_KEY_CONFLICT", ex.getErrorCode());
        }

        @Test
        void adminNaoEncontradoNoBanco_lancaAdminContextInvalid() {
            when(dbUserMapper.findByEmail(ADMIN_EMAIL)).thenReturn(null);
            OmsAuthorizationRevogarRequest req = new OmsAuthorizationRevogarRequest();
            req.setMotivoCodigo(MotivoAdminOms.ROTINA_SEGURANCA);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.revogar(AUTH_ID, req, IDEMPOTENCY_KEY, "req-1"));
            assertEquals("ADMIN_CONTEXT_INVALID", ex.getErrorCode());
            assertEquals(403, ex.getHttpStatus());
        }

        @Test
        void autorizacaoInexistente_lancaOmsAuthorizationNotFound() {
            when(authMapper.buscarPorIdParaAtualizar(AUTH_ID)).thenReturn(null);
            OmsAuthorizationRevogarRequest req = new OmsAuthorizationRevogarRequest();
            req.setMotivoCodigo(MotivoAdminOms.ROTINA_SEGURANCA);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.revogar(AUTH_ID, req, IDEMPOTENCY_KEY, "req-1"));
            assertEquals("OMS_AUTHORIZATION_NOT_FOUND", ex.getErrorCode());
            assertEquals(404, ex.getHttpStatus());
        }

        @Test
        void autorizacaoAtiva_revogaComSucesso_versaoIncrementadaTokenNulo() {
            OmsFiscalAuthorization auth = authAtiva(3L);
            OmsFiscalAuthorization authApos = authAtiva(4L);
            authApos.setRevogadoEm(LocalDateTime.now());

            when(authMapper.buscarPorIdParaAtualizar(AUTH_ID)).thenReturn(auth);
            when(authMapper.revogar(eq(AUTH_ID), any(), any())).thenReturn(1);
            when(authMapper.buscarPorId(AUTH_ID)).thenReturn(authApos);

            OmsAuthorizationRevogarRequest req = new OmsAuthorizationRevogarRequest();
            req.setMotivoCodigo(MotivoAdminOms.SUSPEITA_VAZAMENTO);

            OmsAuthorizationAdminResponse resp = service.revogar(AUTH_ID, req, IDEMPOTENCY_KEY, "req-1");

            assertEquals("REVOGADA", resp.getStatus());
            assertNull(resp.getToken());
            assertNull(resp.getTokenExpiraEm());
            assertEquals(4L, resp.getVersao());
            verify(auditMapper).inserir(argThat(a ->
                    "REVOGACAO".equals(a.getEvento())
                            && a.getAuthId().equals(AUTH_ID)
                            && a.getJtiNovo() == null
                            && a.getExecutadoPorUsuarioId().equals(99L)
                            && a.getVersaoAnterior().equals(3L)
                            && a.getVersaoNova().equals(4L)));
        }

        @Test
        void autorizacaoJaRevogada_retorna200IdempotenteSemNovaEscrita() {
            OmsFiscalAuthorization auth = authAtiva(4L);
            auth.setRevogadoEm(LocalDateTime.now().minusHours(1));
            when(authMapper.buscarPorIdParaAtualizar(AUTH_ID)).thenReturn(auth);

            OmsAuthorizationRevogarRequest req = new OmsAuthorizationRevogarRequest();
            req.setMotivoCodigo(MotivoAdminOms.SUSPEITA_VAZAMENTO);

            OmsAuthorizationAdminResponse resp = service.revogar(AUTH_ID, req, IDEMPOTENCY_KEY, "req-1");

            assertEquals("REVOGADA", resp.getStatus());
            assertEquals(4L, resp.getVersao());
            verify(authMapper, never()).revogar(anyLong(), any(), any());
            verify(auditMapper, never()).inserir(any());
        }

        @Test
        void corridaDeIdempotencyKey_rollbackTotalDepoisReplayValido() {
            OmsFiscalAuthorization auth = authAtiva(3L);
            when(authMapper.buscarPorIdParaAtualizar(AUTH_ID)).thenReturn(auth);
            when(authMapper.revogar(eq(AUTH_ID), any(), any())).thenReturn(1);
            doThrow(new DuplicateKeyException("idempotency_key duplicada")).when(auditMapper).inserir(any());

            // Após o rollback (simulado — mock não reverte de verdade, mas o fluxo do serviço não
            // depende disso: ele reconsulta auditMapper por fora do bloco transacional), a
            // auditoria vencedora (gravada pela requisição concorrente) é encontrada.
            OmsFiscalAuthorizationAudit vencedora = new OmsFiscalAuthorizationAudit();
            vencedora.setAuthId(AUTH_ID);
            vencedora.setEvento("REVOGACAO");
            vencedora.setVersaoNova(4L);
            OmsFiscalAuthorization authApos = authAtiva(4L);
            authApos.setRevogadoEm(LocalDateTime.now());

            when(auditMapper.buscarPorIdempotencyKey(IDEMPOTENCY_KEY))
                    .thenReturn(null)      // checagem inicial: chave ainda não existe
                    .thenReturn(vencedora); // reconsulta após DuplicateKeyException
            when(authMapper.buscarPorId(AUTH_ID)).thenReturn(authApos);

            OmsAuthorizationRevogarRequest req = new OmsAuthorizationRevogarRequest();
            req.setMotivoCodigo(MotivoAdminOms.ROTINA_SEGURANCA);

            OmsAuthorizationAdminResponse resp = service.revogar(AUTH_ID, req, IDEMPOTENCY_KEY, "req-1");

            assertEquals("REVOGADA", resp.getStatus());
            assertEquals(4L, resp.getVersao());
            verify(transactionManager).rollback(any());
            verify(transactionManager, never()).commit(any());
        }
    }

    // =========================================================================
    // Rotação
    // =========================================================================

    @Nested
    class Rotacao {

        private OmsAuthorizationRotacionarRequest requestValido(Long expectedVersion) {
            OmsAuthorizationRotacionarRequest req = new OmsAuthorizationRotacionarRequest();
            req.setMotivoCodigo(MotivoAdminOms.ROTINA_SEGURANCA);
            req.setExpectedVersion(expectedVersion);
            return req;
        }

        @Test
        void expectedVersionDivergente_lancaAuthorizationChanged() {
            when(auditMapper.buscarPorIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(null);
            when(authMapper.buscarPorIdParaAtualizar(AUTH_ID)).thenReturn(authAtiva(5L));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.rotacionar(AUTH_ID, requestValido(3L), IDEMPOTENCY_KEY, "req-1"));
            assertEquals("AUTHORIZATION_CHANGED", ex.getErrorCode());
            assertEquals(409, ex.getHttpStatus());
            verify(authMapper, never()).rotacionar(anyLong(), any(), any(), any(), any());
            verify(transactionManager).rollback(any());
        }

        /**
         * Corrida real (achado do GPT): duas chamadas com o MESMO authId e a MESMA Idempotency-Key
         * passam pela checagem inicial (fora da transação) vendo a chave como "não encontrada",
         * porque nenhuma das duas commitou ainda. A primeira obtém o FOR UPDATE, rotaciona e
         * commita. A segunda só prossegue depois de esperar o lock — neste ponto, sem o recheck
         * pós-lock, ela veria expectedVersion=3 divergindo da versao já avançada para 4 e
         * devolveria AUTHORIZATION_CHANGED, mesmo tendo usado a MESMA chave da operação que acabou
         * de suceder. Este teste simula exatamente esse timing: a chave é "não encontrada" na
         * checagem inicial, mas "encontrada" (pertencente ao MESMO authId/evento) no recheck feito
         * DEPOIS do FOR UPDATE — o resultado deve ser replay (200), nunca AUTHORIZATION_CHANGED.
         */
        @Test
        void corridaMesmoAuthIdMesmaChave_recheckAposLockRetornaReplaySemAuthorizationChanged() {
            OmsFiscalAuthorizationAudit vencedora = new OmsFiscalAuthorizationAudit();
            vencedora.setAuthId(AUTH_ID);
            vencedora.setEvento("ROTACAO");
            vencedora.setJtiNovo("jti-da-primeira-chamada");
            vencedora.setEmitidoEmNovo(LocalDateTime.now());
            vencedora.setTokenExpiraEmNovo(LocalDateTime.now().plusYears(1));
            vencedora.setVersaoNova(4L);

            // auth já reflete o resultado da PRIMEIRA chamada (versao 3->4, jti trocado) no
            // momento em que a SEGUNDA chamada finalmente obtém o lock.
            OmsFiscalAuthorization authJaRotacionado = authAtiva(4L);
            authJaRotacionado.setJti("jti-da-primeira-chamada");

            when(auditMapper.buscarPorIdempotencyKey(IDEMPOTENCY_KEY))
                    .thenReturn(null)       // checagem inicial (antes da transação): ainda não existe
                    .thenReturn(vencedora); // recheck DEPOIS do FOR UPDATE: já existe (a 1ª commitou)
            when(authMapper.buscarPorIdParaAtualizar(AUTH_ID)).thenReturn(authJaRotacionado);
            when(jwtUtil.generateOmsToken(anyString(), anyLong(), eq("jti-da-primeira-chamada"), any(), any()))
                    .thenReturn("jwt-da-primeira-chamada");

            // expectedVersion=3 é o valor que o cliente tinha ANTES da primeira chamada suceder —
            // seria rejeitado como AUTHORIZATION_CHANGED se o recheck não existisse.
            OmsAuthorizationAdminResponse resp = service.rotacionar(AUTH_ID, requestValido(3L), IDEMPOTENCY_KEY, "req-2");

            assertEquals("ROTACIONADA", resp.getStatus());
            assertEquals("jwt-da-primeira-chamada", resp.getToken());
            assertEquals(4L, resp.getVersao());
            verify(authMapper, never()).rotacionar(anyLong(), any(), any(), any(), any());
            verify(auditMapper, never()).inserir(any());
        }

        @Test
        void validadeInsuficiente_lancaCertificateValidityInsufficient() {
            OmsFiscalAuthorization auth = authAtiva(3L);
            auth.setTokenExpiraEm(LocalDateTime.now().plusMinutes(5)); // menor que o piso de 1h
            when(auditMapper.buscarPorIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(null);
            when(authMapper.buscarPorIdParaAtualizar(AUTH_ID)).thenReturn(auth);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.rotacionar(AUTH_ID, requestValido(3L), IDEMPOTENCY_KEY, "req-1"));
            assertEquals("CERTIFICATE_VALIDITY_INSUFFICIENT", ex.getErrorCode());
            assertEquals(422, ex.getHttpStatus());
        }

        @Test
        void idempotencyKeyDeOutraAutorizacao_lancaIdempotencyKeyConflict_nuncaVazaToken() {
            OmsFiscalAuthorizationAudit deOutraAuth = new OmsFiscalAuthorizationAudit();
            deOutraAuth.setAuthId(OUTRO_AUTH_ID);
            deOutraAuth.setEvento("ROTACAO");
            deOutraAuth.setJtiNovo("jti-da-outra-autorizacao");
            when(auditMapper.buscarPorIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(deOutraAuth);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.rotacionar(AUTH_ID, requestValido(1L), IDEMPOTENCY_KEY, "req-1"));
            assertEquals("IDEMPOTENCY_KEY_CONFLICT", ex.getErrorCode());
            assertEquals(409, ex.getHttpStatus());
            verifyNoInteractions(jwtUtil); // nunca chega a gerar/regenerar nenhum token
            verifyNoInteractions(authMapper);
        }

        @Test
        void rotacaoComSucesso_geraNovoJtiEIncrementaVersao() {
            OmsFiscalAuthorization auth = authAtiva(3L);
            OmsFiscalAuthorization authApos = authAtiva(4L);
            authApos.setJti("jti-novo-qualquer");

            when(auditMapper.buscarPorIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(null);
            when(authMapper.buscarPorIdParaAtualizar(AUTH_ID)).thenReturn(auth);
            when(authMapper.rotacionar(eq(AUTH_ID), any(), any(), any(), eq(3L))).thenReturn(1);
            when(authMapper.buscarPorId(AUTH_ID)).thenReturn(authApos);
            when(jwtUtil.generateOmsToken(anyString(), anyLong(), anyString(), any(), any()))
                    .thenReturn("jwt-regenerado");

            OmsAuthorizationAdminResponse resp = service.rotacionar(AUTH_ID, requestValido(3L), IDEMPOTENCY_KEY, "req-1");

            assertEquals("ROTACIONADA", resp.getStatus());
            assertEquals("jwt-regenerado", resp.getToken());
            assertEquals(4L, resp.getVersao());
            verify(auditMapper).inserir(argThat(a ->
                    "ROTACAO".equals(a.getEvento())
                            && a.getAuthId().equals(AUTH_ID)
                            && a.getJtiNovo() != null
                            && a.getJtiAnterior().equals("jti-atual")
                            && a.getVersaoAnterior().equals(3L)
                            && a.getVersaoNova().equals(4L)
                            && a.getExecutadoPorUsuarioId().equals(99L)));
            verify(transactionManager).commit(any());
        }

        @Test
        void replayComEstadoAindaVigente_regeneraTokenSemNovaEscrita() {
            OmsFiscalAuthorizationAudit auditoria = new OmsFiscalAuthorizationAudit();
            auditoria.setAuthId(AUTH_ID);
            auditoria.setEvento("ROTACAO");
            auditoria.setJtiNovo("jti-ja-emitido");
            auditoria.setEmitidoEmNovo(LocalDateTime.now().minusMinutes(5));
            auditoria.setTokenExpiraEmNovo(LocalDateTime.now().plusYears(1));
            auditoria.setVersaoNova(4L);

            OmsFiscalAuthorization authAtual = authAtiva(4L);
            authAtual.setJti("jti-ja-emitido");

            when(auditMapper.buscarPorIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(auditoria);
            when(authMapper.buscarPorId(AUTH_ID)).thenReturn(authAtual);
            when(jwtUtil.generateOmsToken(anyString(), anyLong(), eq("jti-ja-emitido"), any(), any()))
                    .thenReturn("jwt-replay");

            OmsAuthorizationAdminResponse resp = service.rotacionar(AUTH_ID, requestValido(999L), IDEMPOTENCY_KEY, "req-2");

            assertEquals("ROTACIONADA", resp.getStatus());
            assertEquals("jwt-replay", resp.getToken());
            verify(authMapper, never()).buscarPorIdParaAtualizar(anyLong());
            verify(authMapper, never()).rotacionar(anyLong(), any(), any(), any(), any());
            verify(auditMapper, never()).inserir(any());
            verifyNoInteractions(transactionManager);
        }

        @Test
        void replaySuperadoPorOperacaoPosterior_lancaRotationResultSuperseded() {
            OmsFiscalAuthorizationAudit auditoria = new OmsFiscalAuthorizationAudit();
            auditoria.setAuthId(AUTH_ID);
            auditoria.setEvento("ROTACAO");
            auditoria.setJtiNovo("jti-antigo-superado");
            auditoria.setEmitidoEmNovo(LocalDateTime.now().minusMinutes(10));
            auditoria.setTokenExpiraEmNovo(LocalDateTime.now().plusYears(1));
            auditoria.setVersaoNova(4L);

            // Autorização já foi rotacionada de novo depois (jti/versao não batem mais)
            OmsFiscalAuthorization authAtual = authAtiva(5L);
            authAtual.setJti("jti-mais-recente");

            when(auditMapper.buscarPorIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(auditoria);
            when(authMapper.buscarPorId(AUTH_ID)).thenReturn(authAtual);

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> service.rotacionar(AUTH_ID, requestValido(999L), IDEMPOTENCY_KEY, "req-2"));
            assertEquals("ROTATION_RESULT_SUPERSEDED", ex.getErrorCode());
            assertEquals(409, ex.getHttpStatus());
        }

        @Test
        void reativacaoDeAutorizacaoRevogada_comVersaoPosRevogacao_sucesso() {
            OmsFiscalAuthorization authRevogada = authAtiva(4L);
            authRevogada.setRevogadoEm(LocalDateTime.now().minusMinutes(30));

            OmsFiscalAuthorization authReativada = authAtiva(5L);
            authReativada.setJti("jti-reativado");

            when(auditMapper.buscarPorIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(null);
            when(authMapper.buscarPorIdParaAtualizar(AUTH_ID)).thenReturn(authRevogada);
            when(authMapper.rotacionar(eq(AUTH_ID), any(), any(), any(), eq(4L))).thenReturn(1);
            when(authMapper.buscarPorId(AUTH_ID)).thenReturn(authReativada);
            when(jwtUtil.generateOmsToken(anyString(), anyLong(), anyString(), any(), any()))
                    .thenReturn("jwt-reativado");

            // expectedVersion=4 é a versao JÁ PÓS-REVOGAÇÃO (revogar incrementou 3->4)
            OmsAuthorizationAdminResponse resp = service.rotacionar(AUTH_ID, requestValido(4L), IDEMPOTENCY_KEY, "req-3");

            assertEquals("ROTACIONADA", resp.getStatus());
            assertEquals("jwt-reativado", resp.getToken());
            assertEquals(5L, resp.getVersao());
        }

        /**
         * Caminho de FALLBACK (não o caminho primário coberto por
         * corridaMesmoAuthIdMesmaChave_recheckAposLockRetornaReplaySemAuthorizationChanged acima):
         * mesmo se o recheck pós-lock, por alguma janela de tempo, ainda não enxergar a auditoria
         * concorrente (2º valor mockado também null), o próprio INSERT com a MESMA Idempotency-Key
         * colide (UNIQUE) e lança DuplicateKeyException — o TransactionTemplate reverte TUDO desta
         * tentativa (rollback do UPDATE de jti/versao incluso) antes de propagar, e só então,
         * já fora da transação revertida, a auditoria vencedora é reconsultada (3º valor mockado).
         */
        @Test
        void corridaDeIdempotencyKey_recheckNaoPegouMasInsertColideEDuplicateKeyRecupera() {
            OmsFiscalAuthorization auth = authAtiva(3L);
            when(authMapper.buscarPorIdParaAtualizar(AUTH_ID)).thenReturn(auth);
            when(authMapper.rotacionar(eq(AUTH_ID), any(), any(), any(), eq(3L))).thenReturn(1);
            doThrow(new DuplicateKeyException("idempotency_key duplicada")).when(auditMapper).inserir(any());

            OmsFiscalAuthorizationAudit vencedora = new OmsFiscalAuthorizationAudit();
            vencedora.setAuthId(AUTH_ID);
            vencedora.setEvento("ROTACAO");
            vencedora.setJtiNovo("jti-da-concorrente-vencedora");
            vencedora.setEmitidoEmNovo(LocalDateTime.now());
            vencedora.setTokenExpiraEmNovo(LocalDateTime.now().plusYears(1));
            vencedora.setVersaoNova(4L);

            OmsFiscalAuthorization authApos = authAtiva(4L);
            authApos.setJti("jti-da-concorrente-vencedora");

            when(auditMapper.buscarPorIdempotencyKey(IDEMPOTENCY_KEY))
                    .thenReturn(null)        // 1) checagem inicial (antes da transação): ainda não existe
                    .thenReturn(null)        // 2) recheck pós-lock: a concorrente ainda não comitou aqui
                    .thenReturn(vencedora);  // 3) reconsulta após DuplicateKeyException: já comitou
            when(authMapper.buscarPorId(AUTH_ID)).thenReturn(authApos);
            when(jwtUtil.generateOmsToken(anyString(), anyLong(), eq("jti-da-concorrente-vencedora"), any(), any()))
                    .thenReturn("jwt-da-concorrente");

            OmsAuthorizationAdminResponse resp = service.rotacionar(AUTH_ID, requestValido(3L), IDEMPOTENCY_KEY, "req-1");

            assertEquals("ROTACIONADA", resp.getStatus());
            assertEquals("jwt-da-concorrente", resp.getToken());
            verify(transactionManager).rollback(any());
            verify(transactionManager, never()).commit(any());
        }
    }
}
