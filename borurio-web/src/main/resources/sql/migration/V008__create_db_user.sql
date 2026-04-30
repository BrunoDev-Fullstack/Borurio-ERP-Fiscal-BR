-- Tabela de usuários do sistema ERP.
-- Usada por DbUserMapper e DbUserService para autenticação e autorização.

CREATE TABLE IF NOT EXISTS db_user (
    id               BIGINT       AUTO_INCREMENT PRIMARY KEY,
    nome             VARCHAR(100) NOT NULL,
    email            VARCHAR(100) NOT NULL,
    senha            VARCHAR(255) NOT NULL,
    ativo            TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '1 = ativo, 0 = inativo',
    data_criacao     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_atualizacao DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_db_user_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
