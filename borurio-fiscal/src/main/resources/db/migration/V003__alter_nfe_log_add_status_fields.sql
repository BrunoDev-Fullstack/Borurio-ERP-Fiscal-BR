-- =====================================================================
-- Versão: V003__alter_nfe_log_add_status_fields.sql
-- Módulo: Borurio Fiscal (NF-e / SEFAZ-SP / Auditoria)
-- Finalidade: Adicionar colunas ausentes na tabela nfe_log (status, xmls, cnpj)
-- Banco de Dados: MySQL 8.4
-- Padrão: DevSecOps / Observabilidade / Idempotência
-- Data: 2025-10-15
-- Autor: Bruno Ribeiro — Desenvolvedor Java / DevSecOps
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. Adiciona coluna STATUS (controle de processamento)
-- ---------------------------------------------------------------------
ALTER TABLE `nfe_log`
    ADD COLUMN `status` VARCHAR(20) NULL
    COMMENT 'Status do evento (SUCESSO, ERRO, PROCESSANDO)'
    AFTER `descricao`;

-- ---------------------------------------------------------------------
-- 2. Adiciona campos XML para auditoria completa
-- ---------------------------------------------------------------------
ALTER TABLE `nfe_log`
    ADD COLUMN `xml_envio` LONGTEXT NULL
    COMMENT 'XML enviado à SEFAZ (enviNFe)'
    AFTER `status`;

ALTER TABLE `nfe_log`
    ADD COLUMN `xml_retorno` LONGTEXT NULL
    COMMENT 'XML de resposta SEFAZ (retEnviNFe)'
    AFTER `xml_envio`;

-- ---------------------------------------------------------------------
-- 3. Adiciona campo de rastreamento do CNPJ emitente
-- ---------------------------------------------------------------------
ALTER TABLE `nfe_log`
    ADD COLUMN `cnpj_emitente` VARCHAR(20) NULL
    COMMENT 'CNPJ do emitente responsável'
    AFTER `xml_retorno`;

-- ---------------------------------------------------------------------
-- 4. Índices complementares (melhor desempenho em consultas fiscais)
-- ---------------------------------------------------------------------
-- Os índices podem já existir; o comando falhará apenas uma vez caso duplicado.
ALTER TABLE `nfe_log`
    ADD INDEX `idx_nfe_log_status` (`status`);

ALTER TABLE `nfe_log`
    ADD INDEX `idx_nfe_log_cnpj_emitente` (`cnpj_emitente`);

-- ---------------------------------------------------------------------
-- 5. Atualiza registros existentes para consistência inicial
-- ---------------------------------------------------------------------
UPDATE `nfe_log`
SET `status` = CASE
                   WHEN `tipo_evento` = 'Autorização' THEN 'AUTORIZADA'
                   WHEN `tipo_evento` = 'Cancelamento' THEN 'CANCELADA'
                   WHEN `tipo_evento` = 'Carta de Correção' THEN 'CORRIGIDA'
                   ELSE 'PROCESSANDO'
    END
WHERE `status` IS NULL;

-- =====================================================================
-- Fim da migração V003__alter_nfe_log_add_status_fields.sql
-- =====================================================================
