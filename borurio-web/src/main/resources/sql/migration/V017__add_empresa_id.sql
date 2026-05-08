ALTER TABLE db_user
    ADD COLUMN empresa_id BIGINT NULL,
    ADD CONSTRAINT fk_dbuser_empresa FOREIGN KEY (empresa_id) REFERENCES empresa(id);

ALTER TABLE produto
    ADD COLUMN empresa_id BIGINT NULL,
    ADD CONSTRAINT fk_produto_empresa FOREIGN KEY (empresa_id) REFERENCES empresa(id);

ALTER TABLE pedido
    ADD COLUMN empresa_id BIGINT NULL,
    ADD CONSTRAINT fk_pedido_empresa FOREIGN KEY (empresa_id) REFERENCES empresa(id);
