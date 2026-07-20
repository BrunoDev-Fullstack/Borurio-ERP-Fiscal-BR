-- =============================================================================
-- V028__oms_multiempresa.sql
-- Motor fiscal OMS — modelo multiempresa
-- Token por cliente OMS; certificado por (auth_id + CNPJ)
-- =============================================================================
--
-- CONTEXTO
--   V027 modelou o slot de autorização como (empresa_id, integrator_id, codigo_oms):
--   um auth por empresa/CNPJ por cliente OMS.
--   CC confirmou o modelo definitivo:
--     - token pertence ao cliente OMS (codigo_oms), não ao CNPJ;
--     - o mesmo cliente OMS pode autorizar múltiplos CNPJs/certificados;
--     - adicionar ou renovar um CNPJ mantém o token existente;
--     - pedido usa cnpj_emitente para selecionar o certificado correto na emissão.
--
-- TABELAS ALTERADAS
--   1. oms_fiscal_authorization  — slot vira (integrator_id, codigo_oms)
--   2. oms_company_certificate   — adiciona cnpj + empresa_id; UNIQUE por (auth_id, cnpj)
--
-- TABELA NÃO ALTERADA
--   pedido — cnpj_emitente já existe desde V014 (NOT NULL) e não precisa de nova coluna.
--
-- =============================================================================
-- PRÉ-CONDIÇÃO — executar ANTES de rodar a migration em HOM/PROD
-- Detecta (integrator_id, codigo_oms) duplicados que impediriam a nova UNIQUE KEY.
-- Resultado esperado: 0 linhas. Se retornar linhas, ver seção RISCO-01 abaixo.
--
--   SELECT integrator_id, codigo_oms, COUNT(*) AS n
--   FROM   oms_fiscal_authorization
--   GROUP  BY integrator_id, codigo_oms
--   HAVING n > 1;
-- =============================================================================


-- =============================================================================
-- BLOCO 1 — oms_fiscal_authorization
-- Objetivo: slot por cliente OMS, independente de CNPJ.
--
-- Antes: UNIQUE KEY uq_oms_auth_slot (empresa_id, integrator_id, codigo_oms)
--        → 1 auth por empresa por cliente OMS
-- Depois: UNIQUE KEY uq_oms_auth_slot (integrator_id, codigo_oms)
--         → 1 auth por cliente OMS (todos os CNPJs compartilham o mesmo auth/token)
--
-- Coluna empresa_id permanece NOT NULL — é a "empresa âncora" criada automaticamente
-- na primeira autorização e usada como empresa_id para produtos criados via token OMS.
-- =============================================================================

-- 1a. Remover constraint antiga
ALTER TABLE oms_fiscal_authorization
    DROP INDEX uq_oms_auth_slot;

-- 1b. Criar nova constraint sem empresa_id
ALTER TABLE oms_fiscal_authorization
    ADD UNIQUE KEY uq_oms_auth_slot (integrator_id, codigo_oms);


-- =============================================================================
-- BLOCO 2 — oms_company_certificate
-- Objetivo: permitir múltiplos certificados ativos por auth_id, um por CNPJ.
--
-- V027 garantia "1 cert ativo por autorização" via coluna gerada auth_id_ativo_unico.
-- V028 expande para "1 cert ativo por (autorização, CNPJ)" via coluna gerada cnpj_ativo_unico.
--
-- Ordem obrigatória:
--   2a. Dropar UNIQUE KEY que usa a coluna gerada (MySQL exige isso antes de dropar a coluna)
--   2b. Dropar a coluna gerada auth_id_ativo_unico (agora sem referência)
--   2c. Adicionar cnpj e empresa_id como NULL (backfill a seguir)
--   2d. Backfill: popular cnpj e empresa_id via JOIN auth → empresa
--   2e. Tornar NOT NULL (aborta se alguma linha ficou NULL — proteção contra dado inconsistente)
--   2f. Adicionar nova coluna gerada cnpj_ativo_unico
--   2g. Adicionar constraints, FK e índice
-- =============================================================================

-- 2a. Remover UNIQUE KEY obsoleta
--     Precisa ser o primeiro passo — MySQL não permite dropar coluna gerada
--     enquanto ela é referenciada por um index.
ALTER TABLE oms_company_certificate
    DROP INDEX uq_oms_cert_um_ativo_por_auth;

-- 2b. Remover coluna gerada STORED — seguro após remoção do index em 2a
ALTER TABLE oms_company_certificate
    DROP COLUMN auth_id_ativo_unico;

-- 2c. Adicionar cnpj e empresa_id como NULL (backfill vem a seguir)
ALTER TABLE oms_company_certificate
    ADD COLUMN cnpj       VARCHAR(14) NULL
        COMMENT 'CNPJ da empresa emitente deste certificado (somente dígitos)' AFTER auth_id,
    ADD COLUMN empresa_id BIGINT      NULL
        COMMENT 'FK para empresa — criada automaticamente na primeira autorização do CNPJ' AFTER cnpj;

-- 2d. Backfill — popular cnpj e empresa_id a partir do join
--     Lógica: certificado → autorização → empresa (empresa âncora do cliente OMS)
--     Todos os registros existentes têm auth_id válido com empresa_id preenchido.
UPDATE oms_company_certificate c
    JOIN oms_fiscal_authorization a ON a.id = c.auth_id
    JOIN empresa                  e ON e.id = a.empresa_id
SET c.cnpj       = e.cnpj,
    c.empresa_id = e.id;

-- 2e. Tornar NOT NULL — se alguma linha ficar NULL aqui, a migration aborta.
--     Isso indica dado inconsistente no banco (auth sem empresa_id válido).
--     Não contornar — investigar antes de prosseguir.
ALTER TABLE oms_company_certificate
    MODIFY COLUMN cnpj       VARCHAR(14) NOT NULL
        COMMENT 'CNPJ da empresa emitente deste certificado (somente dígitos)',
    MODIFY COLUMN empresa_id BIGINT      NOT NULL
        COMMENT 'FK para empresa — criada automaticamente na primeira autorização do CNPJ';

-- 2f. Adicionar nova coluna gerada STORED para unicidade de cert ativo por (auth, CNPJ)
--     Lógica da gerada:
--       ativo = 1 → valor = cnpj   → UNIQUE KEY garante exatamente 1 cert ativo por (auth_id, cnpj)
--       ativo = 0 → valor = NULL   → MySQL UNIQUE permite múltiplos NULLs (histórico irrestrito)
--     MySQL computa o valor para todas as linhas existentes durante o ALTER.
ALTER TABLE oms_company_certificate
    ADD COLUMN cnpj_ativo_unico VARCHAR(14)
        GENERATED ALWAYS AS (IF(ativo = 1, cnpj, NULL)) STORED
        COMMENT 'Gerada STORED: NULL quando ativo=0, cnpj quando ativo=1 — viabiliza UNIQUE de 1 cert ativo por (auth_id, cnpj)';

-- 2g. Constraints, FK e índice finais
ALTER TABLE oms_company_certificate
    ADD CONSTRAINT fk_oms_cert_empresa
        FOREIGN KEY (empresa_id) REFERENCES empresa(id),
    ADD UNIQUE KEY uq_oms_cert_ativo_por_auth_cnpj (auth_id, cnpj_ativo_unico),
    ADD INDEX      idx_oms_cert_cnpj               (cnpj);


-- =============================================================================
-- VERIFICAÇÃO PÓS-EXECUÇÃO
-- Executar após a migration para confirmar o estado esperado.
--
-- 1. Nova UNIQUE KEY do slot (sem empresa_id):
--    SHOW INDEX FROM oms_fiscal_authorization WHERE Key_name = 'uq_oms_auth_slot';
--    → Esperado: Seq_in_index 1 = integrator_id, Seq_in_index 2 = codigo_oms
--
-- 2. Coluna gerada removida, novas colunas presentes:
--    SHOW COLUMNS FROM oms_company_certificate LIKE '%cnpj%';
--    → Esperado: cnpj VARCHAR(14) NOT NULL + cnpj_ativo_unico VARCHAR(14) GENERATED
--    SHOW COLUMNS FROM oms_company_certificate LIKE 'auth_id_ativo_unico';
--    → Esperado: 0 linhas (coluna removida)
--
-- 3. Indexes corretos:
--    SHOW INDEX FROM oms_company_certificate;
--    → Esperado: uq_oms_cert_ativo_por_auth_cnpj presente
--    → Esperado: uq_oms_cert_um_ativo_por_auth AUSENTE
--    → Esperado: idx_oms_cert_cnpj presente
--
-- 4. Dados backfillados:
--    SELECT c.id, c.auth_id, c.cnpj, c.empresa_id, c.ativo, c.cnpj_ativo_unico
--    FROM oms_company_certificate c;
--    → Esperado: cnpj e empresa_id NOT NULL em todas as linhas
-- =============================================================================


-- =============================================================================
-- RISCOS
-- =============================================================================
--
-- RISCO-01 (CRITICO): Duplicatas em (integrator_id, codigo_oms)
--   Sintoma: "Duplicate entry" ao criar uq_oms_auth_slot em 1b.
--   Causa:   Dois registros em oms_fiscal_authorization com mesmo integrator_id +
--            codigo_oms mas empresa_id diferente (modelo anterior com 2 CNPJs
--            cadastrados separadamente para o mesmo cliente OMS).
--   Reparo:  Identificar as linhas duplicadas:
--              SELECT integrator_id, codigo_oms, GROUP_CONCAT(id) AS ids
--              FROM oms_fiscal_authorization
--              GROUP BY integrator_id, codigo_oms HAVING COUNT(*) > 1;
--            Manter a linha mais antiga (menor id) e atualizar os certificados
--            do(s) registro(s) mais novo(s) para apontar para o id mantido.
--            Deletar as linhas excedentes antes de executar o passo 1b.
--
-- RISCO-02 (BAIXO): Backfill com resultado NULL (passo 2e aborta)
--   Causa:   Algum cert em oms_company_certificate com auth_id sem empresa_id
--            preenchido em oms_fiscal_authorization, ou empresa_id aponta para
--            empresa inexistente.
--   Reparo:  Identificar:
--              SELECT c.id, c.auth_id, c.cnpj, c.empresa_id
--              FROM oms_company_certificate c
--              WHERE c.cnpj IS NULL OR c.empresa_id IS NULL;
--            Corrigir os dados antes de prosseguir.
--
-- RISCO-03 (OPERACIONAL): Flyway não auto-reverte DDL
--   Se a migration falhar após 2a (DROP INDEX) mas antes de 2g, o banco fica em
--   estado parcial. Usar o ROLLBACK MANUAL abaixo para restaurar o estado original
--   e depois excluir o registro em flyway_schema_history antes de re-executar.
-- =============================================================================


-- =============================================================================
-- ROLLBACK MANUAL
-- Executar na ordem indicada se precisar reverter esta migration.
-- Após executar, deletar o registro da migration:
--   DELETE FROM flyway_schema_history WHERE version = '028';
-- =============================================================================
--
-- Passo R1 — Reverter oms_company_certificate (ordem inversa de 2a..2g):
--
--   ALTER TABLE oms_company_certificate
--       DROP INDEX uq_oms_cert_ativo_por_auth_cnpj,
--       DROP INDEX idx_oms_cert_cnpj,
--       DROP FOREIGN KEY fk_oms_cert_empresa,
--       DROP COLUMN cnpj_ativo_unico;
--
--   ALTER TABLE oms_company_certificate
--       MODIFY COLUMN cnpj       VARCHAR(14) NULL,
--       MODIFY COLUMN empresa_id BIGINT      NULL;
--
--   ALTER TABLE oms_company_certificate
--       DROP COLUMN cnpj,
--       DROP COLUMN empresa_id;
--
--   ALTER TABLE oms_company_certificate
--       ADD COLUMN auth_id_ativo_unico BIGINT
--           GENERATED ALWAYS AS (IF(ativo = 1, auth_id, NULL)) STORED
--           COMMENT 'Coluna gerada: auth_id quando ativo=1, NULL quando ativo=0';
--
--   ALTER TABLE oms_company_certificate
--       ADD UNIQUE KEY uq_oms_cert_um_ativo_por_auth (auth_id_ativo_unico);
--
-- Passo R2 — Reverter oms_fiscal_authorization:
--
--   ALTER TABLE oms_fiscal_authorization DROP INDEX uq_oms_auth_slot;
--   ALTER TABLE oms_fiscal_authorization
--       ADD UNIQUE KEY uq_oms_auth_slot (empresa_id, integrator_id, codigo_oms);
-- =============================================================================
