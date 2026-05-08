-- =============================================================================
-- V014__create_pedido.sql
-- Pedido de venda — base para conversão automática em NF-e
-- =============================================================================

CREATE TABLE IF NOT EXISTS pedido (
    id                    BIGINT        AUTO_INCREMENT PRIMARY KEY,
    numero                VARCHAR(20)   NOT NULL                           COMMENT 'Número interno do pedido',
    cnpj_emitente         VARCHAR(14)   NOT NULL                           COMMENT 'CNPJ emitente (somente dígitos)',
    dest_cnpj_cpf         VARCHAR(14)   NOT NULL                           COMMENT 'CNPJ/CPF destinatário',
    dest_razao_social     VARCHAR(60)   NOT NULL                           COMMENT 'Razão social destinatário',
    dest_uf               VARCHAR(2)                                       COMMENT 'UF destinatário',
    dest_logradouro       VARCHAR(60)                                      COMMENT 'Logradouro destinatário',
    dest_numero           VARCHAR(10)                                      COMMENT 'Número endereço destinatário',
    dest_bairro           VARCHAR(60)                                      COMMENT 'Bairro destinatário',
    dest_codigo_municipio VARCHAR(7)                                       COMMENT 'Código IBGE município destinatário',
    dest_municipio        VARCHAR(60)                                      COMMENT 'Nome do município destinatário',
    dest_cep              VARCHAR(8)                                       COMMENT 'CEP destinatário (somente dígitos)',
    natureza_operacao     VARCHAR(60)   NOT NULL DEFAULT 'VENDA DE MERCADORIA',
    serie_nfe             VARCHAR(3)    NOT NULL DEFAULT '1'               COMMENT 'Série da NF-e a emitir',
    status                VARCHAR(20)   NOT NULL DEFAULT 'RASCUNHO'        COMMENT 'RASCUNHO | EMITIDO | CANCELADO',
    chave_nfe             VARCHAR(44)                                      COMMENT 'Preenchido após emissão autorizada',
    valor_total           DECIMAL(15,2)                                    COMMENT 'Soma dos itens',
    observacao            TEXT                                             COMMENT 'Informações adicionais para a NF-e',
    data_pedido           DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    data_atualizacao      DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uq_pedido_numero        (cnpj_emitente, numero),
    INDEX      idx_pedido_status       (status),
    INDEX      idx_pedido_dest         (dest_cnpj_cpf),
    INDEX      idx_pedido_emitente     (cnpj_emitente)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Pedido de venda — converte em NF-e via POST /api/app/pedidos/{id}/emitir';


CREATE TABLE IF NOT EXISTS pedido_item (
    id             BIGINT        AUTO_INCREMENT PRIMARY KEY,
    pedido_id      BIGINT        NOT NULL,
    produto_id     BIGINT        NOT NULL,
    quantidade     DECIMAL(15,4) NOT NULL,
    valor_unitario DECIMAL(15,2) NOT NULL,
    valor_total    DECIMAL(15,2) NOT NULL,

    CONSTRAINT fk_pedido_item_pedido  FOREIGN KEY (pedido_id)  REFERENCES pedido(id)  ON DELETE CASCADE,
    CONSTRAINT fk_pedido_item_produto FOREIGN KEY (produto_id) REFERENCES produto(id),
    INDEX idx_pedido_item_pedido (pedido_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Itens do pedido de venda, com referência ao produto cadastrado';
