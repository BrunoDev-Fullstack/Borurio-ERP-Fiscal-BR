-- =============================================================================
-- V015__pedido_item_fiscal_snapshot.sql
-- Adiciona snapshot fiscal imutável ao pedido_item.
-- Os dados fiscais são copiados do produto no momento da criação do pedido
-- e ficam congelados — alterações posteriores no produto não afetam o pedido.
-- =============================================================================

ALTER TABLE pedido_item
    ADD COLUMN codigo_produto VARCHAR(60)   NOT NULL DEFAULT '' COMMENT 'cProd snapshot',
    ADD COLUMN descricao      VARCHAR(120)  NOT NULL DEFAULT '' COMMENT 'xProd snapshot',
    ADD COLUMN ncm            VARCHAR(8)    NOT NULL DEFAULT '' COMMENT 'NCM snapshot (8 dígitos)',
    ADD COLUMN cfop           VARCHAR(4)    NOT NULL DEFAULT '' COMMENT 'CFOP snapshot',
    ADD COLUMN unidade        VARCHAR(6)    NOT NULL DEFAULT '' COMMENT 'unidade comercial snapshot',
    ADD COLUMN origem         TINYINT       NOT NULL DEFAULT 0  COMMENT 'origem mercadoria snapshot (0-8)',
    ADD COLUMN csosn          VARCHAR(3)    NOT NULL DEFAULT '400' COMMENT 'CSOSN Simples Nacional snapshot';
