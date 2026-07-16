ALTER TABLE empresa
    ADD COLUMN ind_final_padrao CHAR(1) NOT NULL DEFAULT '1'
        COMMENT 'Indicador padrão de consumidor final da empresa emitente: 0=normal, 1=consumidor final';
