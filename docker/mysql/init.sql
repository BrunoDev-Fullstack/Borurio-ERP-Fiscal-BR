-- ============================================================
-- Script de inicialização do banco de dados - Borurio ERP Fiscal BR
-- Escopo: criação de bancos e permissões de usuário apenas.
-- Tabelas são gerenciadas exclusivamente pelo Flyway.
-- ============================================================

-- ------------------------------------------------------------
-- 1. Bancos de dados
-- ------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS borurio_fiscal_dev
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS borurio_fiscal_hom
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- ------------------------------------------------------------
-- 2. Usuário e permissões
-- ------------------------------------------------------------
CREATE USER IF NOT EXISTS 'borurio'@'%' IDENTIFIED BY 'H4ck3r123';

GRANT ALL PRIVILEGES ON borurio_fiscal_dev.* TO 'borurio'@'%';
GRANT ALL PRIVILEGES ON borurio_fiscal_hom.* TO 'borurio'@'%';
FLUSH PRIVILEGES;
