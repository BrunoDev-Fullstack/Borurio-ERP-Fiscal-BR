-- V032: Revogação e rotação administrativa de autorização OMS (correção de segurança —
-- JwtFilter não verificava revogado_em; ver Gate 7H). Concorrência otimista via `versao`,
-- idempotência administrativa via `idempotency_key`, auditoria append-only sem nunca gravar
-- token/JWT.

ALTER TABLE oms_fiscal_authorization
    ADD COLUMN versao BIGINT NOT NULL DEFAULT 0
        COMMENT 'Controle de concorrência otimista — incrementado em toda rotação e na primeira revogação (revogação repetida não incrementa)';

CREATE TABLE oms_fiscal_authorization_audit (
    id                       BIGINT       NOT NULL AUTO_INCREMENT,
    auth_id                  BIGINT       NOT NULL COMMENT 'oms_fiscal_authorization.id afetado',
    evento                   VARCHAR(20)  NOT NULL COMMENT 'REVOGACAO ou ROTACAO',
    jti_anterior             VARCHAR(36)  NOT NULL COMMENT 'jti vigente antes do evento',
    jti_novo                 VARCHAR(36)  NULL COMMENT 'jti emitido pela rotação; NULL em REVOGACAO',
    emitido_em_novo          DATETIME     NULL COMMENT 'iat usado para regenerar o JWT (replay determinístico); NULL em REVOGACAO',
    token_expira_em_novo     DATETIME     NULL COMMENT 'exp usado para regenerar o JWT (replay determinístico); NULL em REVOGACAO',
    versao_anterior          BIGINT       NOT NULL,
    versao_nova              BIGINT       NOT NULL,
    motivo_codigo            VARCHAR(50)  NOT NULL COMMENT 'MotivoAdminOms — sem CHECK, mesma convenção de nfe_sequencia_auditoria.origem',
    motivo_detalhe           VARCHAR(255) NULL COMMENT 'obrigatório quando motivo_codigo = OUTRO (validado em serviço)',
    executado_por_usuario_id BIGINT       NOT NULL COMMENT 'db_user.id do ADMIN autenticado que executou a ação — nunca e-mail/nome',
    idempotency_key          VARCHAR(36)  NOT NULL COMMENT 'header Idempotency-Key (UUID) — replay administrativo, nunca X-Request-Id',
    request_id               VARCHAR(36)  NOT NULL COMMENT 'correlaciona com X-Request-Id / MDC da requisição — apenas rastreabilidade',
    criado_em                DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    UNIQUE KEY uq_oms_auth_audit_idempotency_key (idempotency_key),
    KEY idx_oms_auth_audit_auth (auth_id),
    KEY idx_oms_auth_audit_request_id (request_id),

    CONSTRAINT chk_oms_auth_audit_evento CHECK (evento IN ('REVOGACAO', 'ROTACAO')),
    CONSTRAINT chk_oms_auth_audit_jti_novo CHECK (
        (evento = 'REVOGACAO' AND jti_novo IS NULL AND emitido_em_novo IS NULL AND token_expira_em_novo IS NULL)
        OR
        (evento = 'ROTACAO' AND jti_novo IS NOT NULL AND emitido_em_novo IS NOT NULL AND token_expira_em_novo IS NOT NULL)
    ),

    CONSTRAINT fk_oms_auth_audit_auth FOREIGN KEY (auth_id)
        REFERENCES oms_fiscal_authorization (id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    CONSTRAINT fk_oms_auth_audit_usuario FOREIGN KEY (executado_por_usuario_id)
        REFERENCES db_user (id) ON UPDATE RESTRICT ON DELETE RESTRICT

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Auditoria append-only de revogação/rotação administrativa OMS. Nunca grava token/JWT — jti_novo/emitido_em_novo/token_expira_em_novo permitem regenerar deterministicamente (HS256) o mesmo JWT em replay de Idempotency-Key.';
