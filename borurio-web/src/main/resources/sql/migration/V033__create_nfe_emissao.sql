-- V033: Ciclo operacional do nNF (Gate 1 da maquina de estados fiscal de numeracao).
-- Tabela nova, independente de nfe_documento (documento fiscal consolidado, usado por DANFE e
-- /situacao, que permanece intocada). nfe_emissao guarda o estado ATUAL do ciclo de cada numero
-- fiscal: reservado, transmitido, resolvido (autorizado/denegado/aguardando correcao/pendente de
-- confirmacao). Uma linha por (cnpj_emitente, modelo, serie, numero_nfe), atualizada in-place a
-- cada nova tentativa do mesmo pedido — nao e log de tentativas (isso continua em nfe_log).

CREATE TABLE nfe_emissao (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    pedido_id        BIGINT       NOT NULL COMMENT 'FK logica para pedido.id',
    empresa_id       BIGINT       NOT NULL COMMENT 'FK logica para empresa.id (empresa ancora do pedido)',
    cnpj_emitente    VARCHAR(14)  NOT NULL COMMENT 'CNPJ do emitente (apenas digitos)',
    modelo           VARCHAR(2)   NOT NULL DEFAULT '55' COMMENT 'modelo do documento fiscal, fixo 55 (NF-e) por enquanto',
    serie            VARCHAR(3)   NOT NULL,
    numero_nfe       INT          NOT NULL COMMENT 'nNF fiscal — nao confundir com pedido.numero (codigo interno do pedido)',
    chave_nfe        VARCHAR(44)  NULL COMMENT 'so preenchida apos montagem do XML, antes da chamada SEFAZ',
    estado           VARCHAR(30)  NOT NULL COMMENT 'RESERVADO, TRANSMITIDO, AUTORIZADO, AGUARDANDO_CORRECAO, DENEGADO, PENDENTE_CONFIRMACAO',
    cstat            INT          NULL,
    xmotivo          VARCHAR(255) NULL,
    nprot            VARCHAR(20)  NULL,
    request_id       VARCHAR(64)  NULL,
    tentativas       INT          NOT NULL DEFAULT 1,
    transmitido_em   DATETIME     NULL,
    resolvido_em     DATETIME     NULL COMMENT 'preenchido apenas quando o estado se torna terminal (AUTORIZADO/DENEGADO)',
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_nfe_emissao_pedido (pedido_id),
    UNIQUE KEY uk_nfe_emissao_numero (cnpj_emitente, modelo, serie, numero_nfe),
    UNIQUE KEY uk_nfe_emissao_chave (chave_nfe)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Ciclo operacional do nNF — fonte de verdade de reserva/gate, distinta do documento fiscal consolidado (nfe_documento)';
