-- V010: Adiciona coluna ativo na tabela ncm.
-- NcmMapper.listarNcmAtivos() usa WHERE ativo = TRUE, mas V006 não criou a coluna.
-- Todos os registros existentes recebem ativo = 1 (ativos por padrão).

ALTER TABLE ncm
    ADD COLUMN ativo TINYINT(1) NOT NULL DEFAULT 1
        COMMENT 'Flag de registro ativo (1=ativo, 0=inativo)';

CREATE INDEX idx_ncm_ativo ON ncm(ativo);
