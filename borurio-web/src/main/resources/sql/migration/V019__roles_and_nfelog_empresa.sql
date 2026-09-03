-- Fase 9 — Segurança: role por usuário + empresa_id no log fiscal

ALTER TABLE db_user
    ADD COLUMN role VARCHAR(20) NOT NULL DEFAULT 'OPERADOR';

UPDATE db_user SET role = 'ADMIN' WHERE email = 'admin';

ALTER TABLE nfe_log
    ADD COLUMN empresa_id BIGINT NULL,
    ADD INDEX idx_nfe_log_empresa (empresa_id);
