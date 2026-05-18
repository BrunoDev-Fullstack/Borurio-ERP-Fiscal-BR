ALTER TABLE produto
    ADD COLUMN estoque_reservado DECIMAL(13,4) NOT NULL DEFAULT 0
        COMMENT 'Quantidade reservada para pedidos em transmissão (status AGUARDANDO)';
