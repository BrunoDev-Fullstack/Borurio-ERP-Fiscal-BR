-- =============================================================================
-- V026__produto_unique_key_empresa_codigo.sql
-- Corrige chave única de produto: global por codigo → composta por empresa_id + codigo.
-- Pré-requisito para o endpoint POST /api/app/produtos/batch (upsert multitenancy).
-- Verificado em HOM antes da execução: 4 produtos, todos empresa_id=1, sem duplicatas.
-- =============================================================================

ALTER TABLE produto DROP INDEX uq_produto_codigo;

ALTER TABLE produto
    ADD UNIQUE KEY uq_produto_codigo_empresa (empresa_id, codigo);
