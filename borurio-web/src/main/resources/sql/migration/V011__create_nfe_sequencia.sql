-- V011: Controle de sequência de numeração NF-e por emitente e série.
-- Garante que cada (cnpj_emitente, serie) tenha um contador atômico e
-- evita duplicidade de nNF que causaria rejeição cStat=508 na SEFAZ.

CREATE TABLE nfe_sequencia (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    cnpj_emitente    VARCHAR(14)  NOT NULL COMMENT 'CNPJ do emitente (apenas dígitos)',
    serie            VARCHAR(3)   NOT NULL COMMENT 'Série da NF-e (ex: 1)',
    ultimo_numero    INT          NOT NULL DEFAULT 0 COMMENT 'Último nNF emitido para esta série',
    data_atualizacao DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
                                           ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_emitente_serie (cnpj_emitente, serie)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Sequenciador de numeração NF-e — controla último nNF por emitente/série';
