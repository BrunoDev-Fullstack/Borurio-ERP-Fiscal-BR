CREATE TABLE IF NOT EXISTS produto (
    id            BIGINT        AUTO_INCREMENT PRIMARY KEY,
    codigo        VARCHAR(60)   NOT NULL                   COMMENT 'Código interno (cProd na NF-e)',
    descricao     VARCHAR(120)  NOT NULL                   COMMENT 'Descrição (xProd na NF-e)',
    ncm           VARCHAR(8)    NOT NULL                   COMMENT 'Código NCM 8 dígitos',
    cfop          VARCHAR(4)    NOT NULL                   COMMENT 'CFOP padrão de saída (ex: 5102)',
    unidade       VARCHAR(6)    NOT NULL DEFAULT 'UN'      COMMENT 'Unidade comercial: UN, KG, CX...',
    preco         DECIMAL(15,2) NOT NULL                   COMMENT 'Preço unitário de venda',
    estado        TINYINT(1)    NOT NULL DEFAULT 1         COMMENT '1=ativo, 0=inativo',
    criado_em     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atualizado_em DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uq_produto_codigo (codigo)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
