-- Adiciona campos fiscais e de endereço à tabela cliente.
-- Todos os campos são nullable para preservar registros legados existentes.
-- A validação de obrigatoriedade é responsabilidade da camada de serviço (ClienteServiceImpl).

ALTER TABLE cliente
    ADD COLUMN tipo_pessoa        VARCHAR(2)   NULL COMMENT '"PJ" ou "PF"' AFTER id,
    ADD COLUMN cnpj               VARCHAR(14)  NULL COMMENT '14 dígitos sem pontuação',
    ADD COLUMN cpf                VARCHAR(11)  NULL COMMENT '11 dígitos sem pontuação',
    ADD COLUMN razao_social       VARCHAR(150) NULL COMMENT 'Razão social (PJ) ou nome completo (PF). Mapeado para <xNome> na NF-e',
    ADD COLUMN nome_fantasia      VARCHAR(60)  NULL COMMENT 'Nome fantasia (PJ). Mapeado para <xFant> na NF-e',
    ADD COLUMN inscricao_estadual VARCHAR(14)  NULL COMMENT 'IE do contribuinte ICMS',
    ADD COLUMN logradouro         VARCHAR(60)  NULL,
    ADD COLUMN numero             VARCHAR(60)  NULL,
    ADD COLUMN complemento        VARCHAR(60)  NULL,
    ADD COLUMN bairro             VARCHAR(60)  NULL,
    ADD COLUMN codigo_municipio   VARCHAR(7)   NULL COMMENT 'Código IBGE do município (7 dígitos). Mapeado para <cMun> na NF-e',
    ADD COLUMN municipio          VARCHAR(60)  NULL,
    ADD COLUMN uf                 VARCHAR(2)   NULL COMMENT 'Sigla do estado (ex: SP)',
    ADD COLUMN cep                VARCHAR(8)   NULL COMMENT '8 dígitos sem hífen',
    ADD COLUMN atualizado_em      DATETIME     NULL;
