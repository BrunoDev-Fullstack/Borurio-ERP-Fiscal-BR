CREATE TABLE IF NOT EXISTS ncm (
                                   codigo VARCHAR(10) NOT NULL PRIMARY KEY COMMENT 'Código NCM (8 dígitos)',
                                   descricao VARCHAR(255) NOT NULL COMMENT 'Descrição do item NCM',
                                   unidade VARCHAR(50) NULL COMMENT 'Unidade de medida',
                                   vigencia_inicio DATE NULL COMMENT 'Início da vigência',
                                   vigencia_fim DATE NULL COMMENT 'Fim da vigência'
) ENGINE=InnoDB
  DEFAULT CHARSET=utf8mb4
  COLLATE=utf8mb4_unicode_ci
    COMMENT='Tabela oficial de códigos NCM – Receita Federal';
