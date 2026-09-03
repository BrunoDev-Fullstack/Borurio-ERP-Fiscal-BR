-- V031: Trilha de auditoria da sincronização de série e numeração NF-e (OMS -> Borurio).
-- Tabela nova e independente; nao altera nfe_sequencia, empresa ou nfe_log existentes.

CREATE TABLE nfe_sequencia_auditoria (
    id                      BIGINT       NOT NULL AUTO_INCREMENT,
    cnpj_emitente           VARCHAR(14)  NOT NULL COMMENT 'CNPJ do emitente (apenas digitos)',
    serie_anterior          VARCHAR(3)   NULL COMMENT 'serie padrao da empresa antes da atualizacao',
    serie_atual             VARCHAR(3)   NOT NULL COMMENT 'serie padrao da empresa apos a atualizacao',
    proximo_numero_anterior INT          NULL COMMENT 'proximo nNF que seria alocado pela sequencia de destino antes desta atualizacao; NULL quando a sequencia nao existia ainda (nunca usa 0, que seria um nNF invalido)',
    proximo_numero_atual    INT          NOT NULL COMMENT 'proximo numero registrado apos a atualizacao',
    origem                  VARCHAR(30)  NOT NULL COMMENT 'constante interna, ex: OMS_SYNC',
    cliente_oms             VARCHAR(100) NULL COMMENT 'id interno da autorizacao OMS (oms_fiscal_authorization.id), nunca o jti completo',
    request_id              VARCHAR(36)  NULL COMMENT 'correlaciona com X-Request-Id / MDC da requisicao',
    aplicado                TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '0 quando a chamada foi idempotente (nada mudou)',
    criado_em               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_auditoria_cnpj_serie (cnpj_emitente, serie_atual)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Auditoria de sincronizacao de serie/numeracao NF-e via OMS_SYNC — nunca grava token, certificado, senha ou XML';
