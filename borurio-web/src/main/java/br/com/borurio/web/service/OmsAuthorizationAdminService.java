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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Revogação e rotação administrativa de autorização OMS (Gate 7H) — hotfix para o achado de que
 * JwtFilter nunca consultava revogado_em. Não altera a política de expiração já documentada de
 * /fiscal-authorizations (POST): tokenExpiraEm continua limitado ao not_after do certificado.
 * oms-rotation-min-validity-ms é só um piso mínimo para PERMITIR a rotação, não um novo teto.
 *
 * Fronteira transacional: cada tentativa de mutação (tentarRevogar/tentarRotacionar) roda dentro
 * de um {@link TransactionTemplate} explícito, nunca via self-invocation de método @Transactional
 * (que não passaria pelo proxy do Spring). Se a inserção da auditoria falhar por
 * DuplicateKeyException (corrida de Idempotency-Key), TODA a transação — incluindo a mutação já
 * aplicada na linha — sofre rollback automático antes da exceção propagar; só então, já fora da
 * transação revertida, o estado é reconsultado para decidir entre replay válido ou conflito.
 */
@Service
public class OmsAuthorizationAdminService {

    private static final Logger log = LoggerFactory.getLogger(OmsAuthorizationAdminService.class);

    private final OmsFiscalAuthorizationMapper authMapper;
    private final OmsFiscalAuthorizationAuditMapper auditMapper;
    private final DbUserMapper dbUserMapper;
    private final JwtUtil jwtUtil;
    private final TransactionTemplate transactionTemplate;

    @Value("${security.jwt.oms-rotation-min-validity-ms:3600000}")
    private long rotationMinValidityMs;

    public OmsAuthorizationAdminService(OmsFiscalAuthorizationMapper authMapper,
                                         OmsFiscalAuthorizationAuditMapper auditMapper,
                                         DbUserMapper dbUserMapper,
                                         JwtUtil jwtUtil,
                                         PlatformTransactionManager transactionManager) {
        this.authMapper          = authMapper;
        this.auditMapper         = auditMapper;
        this.dbUserMapper        = dbUserMapper;
        this.jwtUtil             = jwtUtil;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    // -------------------------------------------------------------------------
    // Revogação
    // -------------------------------------------------------------------------

    /**
     * Revoga imediatamente. Idempotente: revogar uma autorização já revogada retorna 200 sem
     * nova escrita nem novo incremento de versao. A revogação prevalece sobre qualquer rotação
     * concorrente — não exige expectedVersion, apenas o lock de linha (FOR UPDATE) para
     * serializar contra uma rotação simultânea.
     */
    public OmsAuthorizationAdminResponse revogar(Long authId, OmsAuthorizationRevogarRequest req,
                                                  String idempotencyKey, String requestId) {
        validarIdempotencyKey(idempotencyKey);
        validarMotivo(req.getMotivoCodigo(), req.getMotivoDetalhe());

        OmsFiscalAuthorizationAudit existente = auditMapper.buscarPorIdempotencyKey(idempotencyKey);
        if (existente != null) {
            return replayOuConflito(existente, authId, "REVOGACAO", requestId);
        }

        Long executorId = resolverExecutorId();

        try {
            return transactionTemplate.execute(status -> tentarRevogar(authId, req, idempotencyKey, requestId, executorId));
        } catch (DuplicateKeyException e) {
            // A transação inteira já sofreu rollback (inclusive a mutação de authMapper.revogar
            // desta tentativa) — recuperar aqui é seguro: não há efeito órfão para limpar.
            OmsFiscalAuthorizationAudit concorrente = auditMapper.buscarPorIdempotencyKey(idempotencyKey);
            if (concorrente == null) {
                throw BusinessException.authorizationServiceUnavailable();
            }
            return replayOuConflito(concorrente, authId, "REVOGACAO", requestId);
        }
    }

    private OmsAuthorizationAdminResponse tentarRevogar(Long authId, OmsAuthorizationRevogarRequest req,
                                                         String idempotencyKey, String requestId, Long executorId) {
        OmsFiscalAuthorization auth = authMapper.buscarPorIdParaAtualizar(authId);
        if (auth == null) {
            throw BusinessException.omsAuthorizationNotFound(authId);
        }

        if (auth.getRevogadoEm() != null) {
            log.info("[OmsAuthAdmin] Revogação idempotente (já revogada) | authId={}", authId);
            return new OmsAuthorizationAdminResponse(authId, "REVOGADA", auth.getVersao(), null, null, requestId);
        }

        Long versaoAnterior = auth.getVersao();
        LocalDateTime agora = LocalDateTime.now();
        int linhas = authMapper.revogar(authId, agora, motivoTexto(req.getMotivoCodigo(), req.getMotivoDetalhe()));
        if (linhas == 0) {
            // Sob FOR UPDATE isto não deveria ocorrer; outra transação já revogou entre o SELECT e o UPDATE.
            auth = authMapper.buscarPorId(authId);
            return new OmsAuthorizationAdminResponse(authId, "REVOGADA", auth.getVersao(), null, null, requestId);
        }

        auth = authMapper.buscarPorId(authId);

        OmsFiscalAuthorizationAudit audit = new OmsFiscalAuthorizationAudit();
        audit.setAuthId(authId);
        audit.setEvento("REVOGACAO");
        audit.setJtiAnterior(auth.getJti());
        audit.setVersaoAnterior(versaoAnterior);
        audit.setVersaoNova(auth.getVersao());
        audit.setMotivoCodigo(req.getMotivoCodigo().name());
        audit.setMotivoDetalhe(req.getMotivoDetalhe());
        audit.setExecutadoPorUsuarioId(executorId);
        audit.setIdempotencyKey(idempotencyKey);
        audit.setRequestId(requestId);
        // DuplicateKeyException propaga para fora do TransactionTemplate — nunca capturada aqui —
        // provocando rollback de TODA a transação, inclusive do authMapper.revogar acima.
        auditMapper.inserir(audit);

        log.info("[OmsAuthAdmin] Autorização revogada | authId={} | executorId={}", authId, executorId);
        return new OmsAuthorizationAdminResponse(authId, "REVOGADA", auth.getVersao(), null, null, requestId);
    }

    // -------------------------------------------------------------------------
    // Rotação
    // -------------------------------------------------------------------------

    /**
     * Rotaciona o jti (novo token) condicionado a expectedVersion. Permite reativar uma
     * autorização revogada quando expectedVersion corresponder à versao pós-revogação. Replay
     * seguro por Idempotency-Key: repetir a mesma chave regenera deterministicamente (HS256) o
     * mesmo JWT já emitido, sem nova escrita — nunca persistimos o token em si.
     */
    public OmsAuthorizationAdminResponse rotacionar(Long authId, OmsAuthorizationRotacionarRequest req,
                                                     String idempotencyKey, String requestId) {
        validarIdempotencyKey(idempotencyKey);
        validarMotivo(req.getMotivoCodigo(), req.getMotivoDetalhe());

        OmsFiscalAuthorizationAudit existente = auditMapper.buscarPorIdempotencyKey(idempotencyKey);
        if (existente != null) {
            return replayOuConflito(existente, authId, "ROTACAO", requestId);
        }

        Long executorId = resolverExecutorId();

        try {
            return transactionTemplate.execute(status -> tentarRotacionar(authId, req, idempotencyKey, requestId, executorId));
        } catch (DuplicateKeyException e) {
            // Idem: rollback total já ocorreu (inclusive o UPDATE de jti/versao desta tentativa) —
            // nada órfão fica na base. Reconsulta a auditoria vencedora fora da transação revertida.
            OmsFiscalAuthorizationAudit concorrente = auditMapper.buscarPorIdempotencyKey(idempotencyKey);
            if (concorrente == null) {
                throw BusinessException.authorizationServiceUnavailable();
            }
            return replayOuConflito(concorrente, authId, "ROTACAO", requestId);
        }
    }

    private OmsAuthorizationAdminResponse tentarRotacionar(Long authId, OmsAuthorizationRotacionarRequest req,
                                                            String idempotencyKey, String requestId, Long executorId) {
        OmsFiscalAuthorization auth = authMapper.buscarPorIdParaAtualizar(authId);
        if (auth == null) {
            throw BusinessException.omsAuthorizationNotFound(authId);
        }

        // Reconfere a Idempotency-Key JÁ COM O LOCK adquirido — fecha a corrida em que duas
        // chamadas com a MESMA chave e o MESMO authId passam pela checagem inicial (fora da
        // transação) como "não encontrada" antes de qualquer uma commitar. Sem este recheck, a
        // segunda chamada (que só prossegue depois de esperar o FOR UPDATE da primeira) veria a
        // versao já incrementada pela primeira e devolveria AUTHORIZATION_CHANGED em vez de
        // replay — quebrando a idempotência exatamente no caso mais comum (perda de resposta /
        // retry simultâneo com a mesma chave).
        OmsFiscalAuthorizationAudit existenteAposLock = auditMapper.buscarPorIdempotencyKey(idempotencyKey);
        if (existenteAposLock != null) {
            return avaliarReplayRotacao(existenteAposLock, authId, auth, requestId);
        }

        if (!auth.getVersao().equals(req.getExpectedVersion())) {
            throw BusinessException.authorizationChanged(authId);
        }

        LocalDateTime minimoAceitavel = LocalDateTime.now().plus(Duration.ofMillis(rotationMinValidityMs));
        if (auth.getTokenExpiraEm() == null || auth.getTokenExpiraEm().isBefore(minimoAceitavel)) {
            throw BusinessException.certificateValidityInsufficient(authId);
        }

        String jtiAnterior = auth.getJti();
        Long versaoAnterior = auth.getVersao();
        String jtiNovo = UUID.randomUUID().toString();
        LocalDateTime emitidoEmNovo = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime tokenExpiraEmNovo = auth.getTokenExpiraEm(); // validade do certificado não muda nesta operação

        int linhas = authMapper.rotacionar(authId, jtiNovo, emitidoEmNovo, tokenExpiraEmNovo, req.getExpectedVersion());
        if (linhas == 0) {
            throw BusinessException.authorizationChanged(authId);
        }

        auth = authMapper.buscarPorId(authId);

        OmsFiscalAuthorizationAudit audit = new OmsFiscalAuthorizationAudit();
        audit.setAuthId(authId);
        audit.setEvento("ROTACAO");
        audit.setJtiAnterior(jtiAnterior);
        audit.setJtiNovo(jtiNovo);
        audit.setEmitidoEmNovo(emitidoEmNovo);
        audit.setTokenExpiraEmNovo(tokenExpiraEmNovo);
        audit.setVersaoAnterior(versaoAnterior);
        audit.setVersaoNova(auth.getVersao());
        audit.setMotivoCodigo(req.getMotivoCodigo().name());
        audit.setMotivoDetalhe(req.getMotivoDetalhe());
        audit.setExecutadoPorUsuarioId(executorId);
        audit.setIdempotencyKey(idempotencyKey);
        audit.setRequestId(requestId);
        // DuplicateKeyException propaga para fora do TransactionTemplate — nunca capturada aqui —
        // provocando rollback de TODA a transação, inclusive do authMapper.rotacionar acima.
        auditMapper.inserir(audit);

        log.info("[OmsAuthAdmin] Autorização rotacionada | authId={} | executorId={}", authId, executorId);
        String token = jwtUtil.generateOmsToken(
                auth.getCodigoOms(), auth.getEmpresaId(), jtiNovo, tokenExpiraEmNovo, emitidoEmNovo);
        return new OmsAuthorizationAdminResponse(authId, "ROTACIONADA", auth.getVersao(), tokenExpiraEmNovo, token, requestId);
    }

    // -------------------------------------------------------------------------
    // Replay / conflito de Idempotency-Key — compartilhado por revogação e rotação
    // -------------------------------------------------------------------------

    /**
     * Nunca confia apenas na Idempotency-Key: exige authId e evento batendo com o solicitado.
     * Caso contrário, a chave pertence a uma operação DIFERENTE (outra autorização ou outro tipo
     * de evento) — devolver seus dados vazaria estado/token de uma autorização para outra.
     * Usado no caminho externo (checagem antes da transação, e após DuplicateKeyException); busca
     * o auth atual por conta própria. {@link #avaliarReplayRotacao} é a mesma lógica reutilizada
     * de DENTRO da transação de rotação, quando o auth já foi lido via FOR UPDATE.
     */
    private OmsAuthorizationAdminResponse replayOuConflito(OmsFiscalAuthorizationAudit audit, Long authId,
                                                            String eventoEsperado, String requestId) {
        // Checa authId/evento ANTES de tocar a autorização — se a chave pertence a uma operação
        // diferente, a autorização solicitada nunca é lida (nem sequer um SELECT).
        if (!audit.getAuthId().equals(authId) || !eventoEsperado.equals(audit.getEvento())) {
            throw BusinessException.idempotencyKeyConflict(authId);
        }
        OmsFiscalAuthorization auth = authMapper.buscarPorId(authId);
        if ("REVOGACAO".equals(eventoEsperado)) {
            return avaliarReplayRevogacao(audit, authId, auth, requestId);
        }
        return avaliarReplayRotacao(audit, authId, auth, requestId);
    }

    private OmsAuthorizationAdminResponse avaliarReplayRevogacao(OmsFiscalAuthorizationAudit audit, Long authId,
                                                                  OmsFiscalAuthorization auth, String requestId) {
        if (!audit.getAuthId().equals(authId) || !"REVOGACAO".equals(audit.getEvento())) {
            throw BusinessException.idempotencyKeyConflict(authId);
        }
        boolean aindaVigente = auth != null
                && auth.getRevogadoEm() != null
                && audit.getVersaoNova().equals(auth.getVersao());
        if (!aindaVigente) {
            // Foi reativada por uma rotação posterior — o resultado desta revogação foi superado.
            throw BusinessException.rotationResultSuperseded(authId);
        }
        return new OmsAuthorizationAdminResponse(authId, "REVOGADA", auth.getVersao(), null, null, requestId);
    }

    /**
     * Reutilizada tanto pelo caminho externo (replayOuConflito) quanto pelo recheck DENTRO da
     * transação de rotação (tentarRotacionar, já com o auth lido via FOR UPDATE) — por isso valida
     * authId/evento aqui também, e não só no chamador externo.
     */
    private OmsAuthorizationAdminResponse avaliarReplayRotacao(OmsFiscalAuthorizationAudit audit, Long authId,
                                                                OmsFiscalAuthorization auth, String requestId) {
        if (!audit.getAuthId().equals(authId) || !"ROTACAO".equals(audit.getEvento())) {
            throw BusinessException.idempotencyKeyConflict(authId);
        }
        boolean aindaVigente = auth != null
                && auth.getRevogadoEm() == null
                && audit.getJtiNovo().equals(auth.getJti())
                && audit.getVersaoNova().equals(auth.getVersao());

        if (!aindaVigente) {
            throw BusinessException.rotationResultSuperseded(authId);
        }

        log.info("[OmsAuthAdmin] Replay de rotação (Idempotency-Key já processada) | authId={}", authId);
        String token = jwtUtil.generateOmsToken(auth.getCodigoOms(), auth.getEmpresaId(),
                audit.getJtiNovo(), audit.getTokenExpiraEmNovo(), audit.getEmitidoEmNovo());
        return new OmsAuthorizationAdminResponse(authId, "ROTACIONADA", auth.getVersao(),
                audit.getTokenExpiraEmNovo(), token, requestId);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Grava só o id numérico do executor — nunca e-mail ou nome na auditoria. */
    private Long resolverExecutorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null) {
            throw BusinessException.adminContextInvalid();
        }

        DbUser user;
        try {
            user = dbUserMapper.findByEmail(authentication.getName());
        } catch (DataAccessException e) {
            log.error("[OmsAuthAdmin] Falha ao resolver usuário ADMIN executor", e);
            throw BusinessException.authorizationServiceUnavailable();
        }

        if (user == null) {
            throw BusinessException.adminContextInvalid();
        }
        return user.getId();
    }

    private void validarIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw BusinessException.invalidIdempotencyKey();
        }
        try {
            UUID.fromString(idempotencyKey);
        } catch (IllegalArgumentException e) {
            throw BusinessException.invalidIdempotencyKey();
        }
    }

    private void validarMotivo(MotivoAdminOms motivoCodigo, String motivoDetalhe) {
        if (motivoCodigo == MotivoAdminOms.OUTRO && (motivoDetalhe == null || motivoDetalhe.isBlank())) {
            throw BusinessException.motivoDetalheObrigatorio();
        }
    }

    private String motivoTexto(MotivoAdminOms codigo, String detalhe) {
        return (detalhe != null && !detalhe.isBlank()) ? codigo.name() + ": " + detalhe : codigo.name();
    }
}
