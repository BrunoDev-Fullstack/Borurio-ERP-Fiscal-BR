-- ============================================================
-- Script de inicialização do banco de dados - Borurio ERP Fiscal BR
-- Ambiente: Docker (vboot-mysql)
-- Banco: borurio_dev / borurio_prd
-- Autor: Equipe Técnica Borurio Brasil
-- Data: 10/10/2025
-- ============================================================

-- ------------------------------------------------------------
-- 1. Criação dos bancos de dados
-- ------------------------------------------------------------
CREATE DATABASE IF NOT EXISTS borurio_dev
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

CREATE DATABASE IF NOT EXISTS borurio_prd
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

-- ------------------------------------------------------------
-- 2. Criação do usuário e permissões
-- ------------------------------------------------------------
CREATE USER IF NOT EXISTS 'borurio'@'%' IDENTIFIED BY 'borurio123';

GRANT ALL PRIVILEGES ON borurio_dev.* TO 'borurio'@'%';
GRANT ALL PRIVILEGES ON borurio_prd.* TO 'borurio'@'%';
FLUSH PRIVILEGES;

-- ------------------------------------------------------------
-- 3. Seleciona o banco de desenvolvimento por padrão
-- ------------------------------------------------------------
USE borurio_dev;

-- ------------------------------------------------------------
-- 4. Estrutura da tabela de auditoria fiscal (NF-e)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS nfe_log (
                                       id BIGINT AUTO_INCREMENT PRIMARY KEY COMMENT 'Identificador único do log fiscal',
                                       chave_nfe VARCHAR(44) NOT NULL COMMENT 'Chave de acesso da NF-e',
    tipo_evento VARCHAR(50) NOT NULL COMMENT 'Tipo do evento (AUTORIZADA, REJEITADA, CANCELADA, DUPLICADA)',
    descricao TEXT COMMENT 'Descrição detalhada do evento retornado pela SEFAZ',
    data_evento DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT 'Data e hora do evento fiscal',
    usuario VARCHAR(100) COMMENT 'Usuário responsável pela operação',
    INDEX idx_chave_nfe (chave_nfe)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Tabela de logs fiscais NF-e (auditoria SEFAZ)';

-- ------------------------------------------------------------
-- 5. Logs de execução
-- ------------------------------------------------------------
-- Verificação da criação:
-- SHOW DATABASES;
-- USE borurio_dev;
-- SHOW TABLES;
-- DESCRIBE nfe_log;

-- ------------------------------------------------------------
-- 6. Inserção de registro inicial opcional (teste)
-- ------------------------------------------------------------
INSERT INTO nfe_log (chave_nfe, tipo_evento, descricao, usuario)
VALUES ('99999999999999999999999999999999999999999999', 'AUTORIZADA', 'Evento de teste inicial', 'sistema');
