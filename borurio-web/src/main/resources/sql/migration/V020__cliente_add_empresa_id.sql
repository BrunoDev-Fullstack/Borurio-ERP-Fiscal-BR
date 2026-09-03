ALTER TABLE cliente ADD COLUMN empresa_id BIGINT NULL;
CREATE INDEX idx_cliente_empresa ON cliente (empresa_id);
