-- FK constraints que deveriam ter sido incluídas em V019 (nfe_log) e V020 (cliente).
-- Seguro aplicar: colunas já existem; todos os valores são NULL ou apontam para empresa_id=1 que existe.

ALTER TABLE cliente
    ADD CONSTRAINT fk_cliente_empresa
        FOREIGN KEY (empresa_id) REFERENCES empresa(id);

ALTER TABLE nfe_log
    ADD CONSTRAINT fk_nfelog_empresa
        FOREIGN KEY (empresa_id) REFERENCES empresa(id);
