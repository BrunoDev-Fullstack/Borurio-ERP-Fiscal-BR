CREATE TABLE estoque_movimento (
    id              BIGINT          NOT NULL AUTO_INCREMENT,
    produto_id      BIGINT          NOT NULL,
    empresa_id      BIGINT          NOT NULL,
    tipo            VARCHAR(30)     NOT NULL COMMENT 'RESERVA | DESFAZER_RESERVA | BAIXA | ESTORNO | ENTRADA',
    quantidade      DECIMAL(13,4)   NOT NULL,
    referencia_tipo VARCHAR(30)     NULL     COMMENT 'PEDIDO | MANUAL',
    referencia_id   BIGINT          NULL,
    observacao      VARCHAR(255)    NULL,
    criado_por      VARCHAR(120)    NULL,
    criado_em       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,

    PRIMARY KEY (id),
    INDEX idx_estoque_mov_produto  (produto_id),
    INDEX idx_estoque_mov_empresa  (empresa_id),
    INDEX idx_estoque_mov_ref      (referencia_tipo, referencia_id),

    CONSTRAINT fk_estoque_mov_produto FOREIGN KEY (produto_id) REFERENCES produto(id),
    CONSTRAINT fk_estoque_mov_empresa FOREIGN KEY (empresa_id) REFERENCES empresa(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
