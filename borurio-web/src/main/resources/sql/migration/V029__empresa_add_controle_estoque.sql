ALTER TABLE empresa
    ADD COLUMN controle_estoque_ativo TINYINT(1) NOT NULL DEFAULT 1
        COMMENT 'Se 0, /emitir não valida, reserva, baixa nem estorna estoque para esta empresa';
