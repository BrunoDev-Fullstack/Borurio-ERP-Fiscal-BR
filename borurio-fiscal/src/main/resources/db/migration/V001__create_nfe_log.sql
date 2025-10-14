-- =====================================================================
-- Versão: V001__create_nfe_log.sql
-- Módulo: Borurio Fiscal (NF-e / SEFAZ / Auditoria)
-- Finalidade: Criação da tabela de auditoria de eventos da NF-e
-- Banco de Dados: MySQL 8.4
-- Data de criação: 2025-10-10
-- Autor: Bruno Ribeiro (Desenvolvedor Java / DevSecOps)
-- =====================================================================

-- ---------------------------------------------------------------------
-- Criação da tabela nfe_log
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `nfe_log` (
                                         `id` BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Identificador único do log',
                                         `chave_nfe` VARCHAR(44) NOT NULL COMMENT 'Chave da NF-e (44 dígitos)',
    `tipo_evento` VARCHAR(100) NOT NULL COMMENT 'Tipo de evento (Autorização, Cancelamento, Carta de Correção, etc.)',
    `descricao` TEXT NULL COMMENT 'Descrição detalhada do evento fiscal',
    `usuario` VARCHAR(100) NULL COMMENT 'Usuário ou sistema responsável pelo evento',
    `data_evento` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Data e hora do evento registrado',
    INDEX `idx_chave_nfe` (`chave_nfe`)
    )
    ENGINE = InnoDB
    DEFAULT CHARSET = utf8mb4
    COLLATE = utf8mb4_general_ci
    COMMENT = 'Tabela de auditoria dos eventos da NF-e (NF-e Log)';

-- =====================================================================
-- Fim da migração V001__create_nfe_log.sql
-- =====================================================================
