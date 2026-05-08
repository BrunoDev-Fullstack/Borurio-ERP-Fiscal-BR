-- =============================================================================
-- V013__alter_produto_add_fiscal_fields.sql
-- Adiciona campos tributários mínimos ao produto para emissão automática de NF-e
-- -----------------------------------------------------------------------------
-- origem: código de origem da mercadoria (0=Nacional, 1–8=Importada — AT 1/2013)
-- csosn:  código de situação da operação no Simples Nacional (CRT=1)
--         Valores compatíveis com ICMSSN102 no leiaute NF-e 4.00:
--           102, 103, 300, 400 (não tributada), 101, 201, 202, 203, 500, 900
-- estoque: quantidade em estoque (para baixa futura após autorização)
-- =============================================================================

ALTER TABLE produto
    ADD COLUMN origem  TINYINT    NOT NULL DEFAULT 0     COMMENT '0=Nacional / 1–8=Importada (AT 1/2013)',
    ADD COLUMN csosn   VARCHAR(3) NOT NULL DEFAULT '400' COMMENT 'CSOSN Simples Nacional (102,103,300,400,500,900…)',
    ADD COLUMN estoque DECIMAL(15,4)        DEFAULT 0    COMMENT 'Quantidade em estoque';
