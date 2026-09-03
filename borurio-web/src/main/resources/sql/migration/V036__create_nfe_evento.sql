-- V036: Ciclo de eventos fiscais pos-autorizacao (cancelamento 110111, reaproveitavel para
-- CC-e 110110 e outros eventos futuros). Distinta de nfe_emissao (ciclo do nNF, Gate 1/3) e de
-- nfe_documento (snapshot da autorizacao original, nunca sobrescrito por um evento posterior).
-- Mesma identidade que a propria SEFAZ usa para detectar duplicidade de evento (cStat=573):
-- chave_nfe + tipo_evento + n_seq_evento, unica GLOBALMENTE (nao por empresa) -- dois tenants
-- nunca podem representar o mesmo evento fiscal da SEFAZ.

CREATE TABLE nfe_evento (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    pedido_id            BIGINT       NOT NULL COMMENT 'FK logica para pedido.id',
    emissao_id           BIGINT       NULL COMMENT 'FK logica para nfe_emissao.id; NULL para pedidos legados sem ciclo Gate 1',
    empresa_id           BIGINT       NOT NULL COMMENT 'FK logica para empresa.id -- isolamento/indexacao, NUNCA parte da unicidade fiscal',
    cnpj_emitente        VARCHAR(14)  NOT NULL,
    chave_nfe            VARCHAR(44)  NOT NULL,
    tipo_evento          VARCHAR(6)   NOT NULL COMMENT '110111=cancelamento; reservado para 110110=CC-e no futuro',
    n_seq_evento         INT          NOT NULL DEFAULT 1,
    id_evento            VARCHAR(54)  NOT NULL COMMENT 'ID<tpEvento><chNFe><nSeqEvento> montado, auditoria/correlacao',
    estado               VARCHAR(30)  NOT NULL COMMENT 'PREPARADO, TRANSMITIDO, PENDENTE_CONFIRMACAO, REGISTRADO, REJEITADO',
    cstat                INT          NULL COMMENT 'cStat do infEvento (nunca o cStat de lote/128)',
    xmotivo              VARCHAR(255) NULL,
    nprot                VARCHAR(20)  NULL COMMENT 'protocolo do EVENTO -- distinto do nProt de autorizacao em nfe_emissao/nfe_documento',
    fora_do_prazo        TINYINT(1)   NOT NULL DEFAULT 0 COMMENT 'true quando cstat=155 (cancelamento homologado fora do prazo normal)',
    justificativa        VARCHAR(255) NULL COMMENT 'xJust enviada (cancelamento); NULL para tipos de evento sem justificativa',
    dh_evento            VARCHAR(30)  NULL COMMENT 'dhEvento exato enviado no XML assinado -- preserva a identidade temporal do evento em caso de retransmissao apos incerteza',
    payload_hash         VARCHAR(64)  NULL COMMENT 'SHA-256 do XML assinado enviado -- confirma que uma reconciliacao nao reconstruiu um evento semanticamente diferente do original',
    resolucao_origem     VARCHAR(30)  NULL COMMENT 'EVENTO_DIRETO (resposta do proprio envio) ou CONSULTA_SITUACAO (reconciliacao) -- nunca inventa nProt quando a origem foi consulta sem procEventoNFe detalhado',
    transmitido_em       DATETIME     NULL,
    resolvido_em         DATETIME     NULL,
    ultima_consulta_em   DATETIME     NULL COMMENT 'reconciliacao -- mesmo padrao de backoff de nfe_emissao (Gate 3)',
    tentativas_consulta  INT          NOT NULL DEFAULT 0,
    created_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_nfe_evento_pedido (pedido_id),
    KEY idx_nfe_evento_empresa (empresa_id),
    UNIQUE KEY uk_nfe_evento_identidade (chave_nfe, tipo_evento, n_seq_evento)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
  COMMENT='Ciclo de eventos fiscais pos-autorizacao (cancelamento, CC-e futura) -- evidencia propria, nunca sobrescreve nfe_documento/nfe_emissao de autorizacao';
