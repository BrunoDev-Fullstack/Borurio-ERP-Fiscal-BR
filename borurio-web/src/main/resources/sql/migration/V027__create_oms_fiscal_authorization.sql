-- =============================================================================
-- V027__create_oms_fiscal_authorization.sql
-- Autorização fiscal OMS — integração sem conta de usuário
-- -----------------------------------------------------------------------------
-- Contexto:
--   Um sistema OMS externo emite NF-e em nome das empresas emitentes cadastradas
--   no Borurio sem precisar de conta de usuário. O fluxo é:
--
--   1. ADMIN cadastra empresa via POST /api/app/empresas (pré-requisito).
--   2. ADMIN registra o integrador em oms_integrator e emite uma API Key em oms_api_key.
--   3. OMS autentica com X-Api-Key antes de enviar qualquer certificado.
--   4. OMS envia CNPJ + certificado A1 + senha para POST /api/integration/fiscal-authorizations.
--   5. Borurio valida, armazena o certificado criptografado e retorna token técnico JWT.
--   6. OMS usa o token nos pedidos e emissões — sem reenviar o certificado.
--   7. Reautorização (novo cert ou renovação de token) atualiza o registro existente.
--
-- Quatro tabelas e suas responsabilidades:
--   oms_integrator           → identidade estável do sistema integrador;
--                              não muda com rotação de credencial.
--   oms_api_key              → credencial técnica do integrador;
--                              pode ser rotacionada sem afetar autorizações ativas.
--   oms_fiscal_authorization → autorização fiscal por empresa emitente;
--                              contém o token JWT (jti) e controle de revogação.
--   oms_company_certificate  → certificado A1 criptografado;
--                              separado da autorização para suportar histórico
--                              e múltiplos certificados sem trocar o token.
--
-- Decisões de design relevantes:
--   (a) oms_fiscal_authorization usa integrator_id, não api_key_id, para que
--       a rotação de API Key não crie nova autorização para a mesma empresa.
--   (b) empresa_id não está duplicado em oms_company_certificate; a empresa
--       é obtida via auth_id para garantir consistência relacional.
--   (c) A unicidade de certificado ativo é garantida por coluna gerada STORED
--       com UNIQUE KEY — único mecanismo relacional disponível no MySQL 8
--       equivalente a um partial unique index.
-- =============================================================================


-- =============================================================================
-- Tabela 1: oms_integrator
-- Identidade estável do sistema integrador OMS.
-- Não armazena credencial — veja oms_api_key.
-- =============================================================================

CREATE TABLE oms_integrator (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    codigo        VARCHAR(50)  NOT NULL  COMMENT 'Código único estável do integrador (ex: OMS-EXT-001)',
    nome          VARCHAR(100) NOT NULL  COMMENT 'Nome descritivo do sistema integrador',
    ativo         TINYINT(1)   NOT NULL DEFAULT 1,
    criado_em     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    desativado_em DATETIME     NULL      COMMENT 'Preenchido quando a parceria de integração é encerrada',

    PRIMARY KEY (id),
    UNIQUE KEY uq_oms_integrator_codigo (codigo),
    INDEX      idx_oms_integrator_ativo (ativo)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Identidade estável dos sistemas integradores OMS — independente de rotação de credencial';


-- =============================================================================
-- Tabela 2: oms_api_key
-- Credencial técnica do integrador para autenticação no endpoint de autorização.
-- Armazena somente o hash SHA-256 — nunca o valor original da chave.
-- A rotação de chave (nova linha) não afeta autorizações fiscais existentes.
-- =============================================================================

CREATE TABLE oms_api_key (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    integrator_id  BIGINT       NOT NULL,
    descricao      VARCHAR(100) NULL      COMMENT 'Rótulo da chave para identificação sem expor o segredo (ex: Chave-Prod-2026)',
    chave_hash     VARCHAR(64)  NOT NULL  COMMENT 'SHA-256 hex da API Key — nunca armazenar o valor em texto claro',
    ativo          TINYINT(1)   NOT NULL DEFAULT 1,
    criado_em      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expira_em      DATETIME     NULL      COMMENT 'Expiração programada; NULL indica sem data de expiração definida',
    revogado_em    DATETIME     NULL      COMMENT 'Preenchido quando a chave é revogada administrativamente',

    PRIMARY KEY (id),
    UNIQUE KEY uq_oms_api_key_hash        (chave_hash),
    INDEX      idx_oms_api_key_integrator (integrator_id),
    INDEX      idx_oms_api_key_ativo      (ativo),

    CONSTRAINT fk_oms_api_key_integrator FOREIGN KEY (integrator_id) REFERENCES oms_integrator(id)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Credenciais técnicas dos integradores OMS — rotação de chave não afeta autorizações fiscais ativas';


-- =============================================================================
-- Tabela 3: oms_fiscal_authorization
-- Uma autorização por (empresa_id, integrator_id, codigo_oms).
-- Representa o token técnico JWT emitido ao integrador para agir em nome da empresa.
-- O jti é substituído a cada reautorização — tokens anteriores ficam inválidos.
-- Não armazena dados do certificado — veja oms_company_certificate.
-- =============================================================================

CREATE TABLE oms_fiscal_authorization (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    empresa_id       BIGINT       NOT NULL,
    integrator_id    BIGINT       NOT NULL  COMMENT 'Identidade estável do integrador — não muda com rotação de API Key',
    codigo_oms       VARCHAR(100) NOT NULL  COMMENT 'Identificador da empresa emitente no sistema OMS externo',
    jti              VARCHAR(36)  NOT NULL  COMMENT 'JWT ID (UUID) — base da revogação; substituído a cada reautorização',
    token_expira_em  DATETIME     NOT NULL  COMMENT 'Validade do token JWT = not_after do certificado ativo no momento da emissão',
    emitido_em       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    atualizado_em    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    revogado_em      DATETIME     NULL      COMMENT 'Preenchido = token revogado; recusado imediatamente na próxima requisição',
    motivo_revogacao VARCHAR(255) NULL      COMMENT 'Motivo da revogação administrativa para auditoria',

    PRIMARY KEY (id),
    UNIQUE KEY uq_oms_auth_slot       (empresa_id, integrator_id, codigo_oms),
    UNIQUE KEY uq_oms_auth_jti        (jti),
    INDEX      idx_oms_auth_empresa    (empresa_id),
    INDEX      idx_oms_auth_integrator (integrator_id),

    CONSTRAINT fk_oms_auth_empresa    FOREIGN KEY (empresa_id)    REFERENCES empresa(id),
    CONSTRAINT fk_oms_auth_integrator FOREIGN KEY (integrator_id) REFERENCES oms_integrator(id)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Autorização fiscal OMS por empresa emitente — token técnico JWT para emissão de NF-e';


-- =============================================================================
-- Tabela 4: oms_company_certificate
-- Certificado A1 da empresa emitente no contexto OMS.
--
-- Separado de oms_fiscal_authorization para:
--   - isolar dados sensíveis criptografados (PFX + senha);
--   - preservar histórico de substituição de certificado;
--   - evitar FK circular entre autorização e certificado.
--
-- Regra de negócio: toda substituição de certificado gera um novo JTI na
-- tabela oms_fiscal_authorization e invalida o token anterior. O OMS recebe
-- um novo token a cada reautorização.
--
-- Empresa obtida por join via auth_id — empresa_id não é duplicado aqui.
--
-- Unicidade do certificado ativo:
--   auth_id_ativo_unico é uma coluna STORED gerada como:
--     - auth_id  quando ativo = 1 (valor único por UNIQUE KEY)
--     - NULL     quando ativo = 0 (NULLs não violam UNIQUE no MySQL)
--   Isso garante no nível do banco que apenas um certificado esteja ativo
--   por autorização, sem depender de lógica de aplicação para a restrição.
--
-- Criptografia:
--   cert_pfx_enc   → bytes PKCS12 criptografados com AES-256-GCM
--   cert_senha_enc → senha do PKCS12 criptografada com AES-256-GCM
--   key_version    → versão da chave AES usada; suporte a rotação de chave futura
--
-- Fingerprint:
--   thumbprint → SHA-256 hex do X.509 (não SHA-1)
-- =============================================================================

CREATE TABLE oms_company_certificate (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    auth_id             BIGINT       NOT NULL  COMMENT 'Autorização fiscal a que este certificado pertence',
    thumbprint          VARCHAR(64)  NOT NULL  COMMENT 'SHA-256 hex do certificado X.509 — identifica o cert sem expor dados sensíveis',
    cert_pfx_enc        MEDIUMBLOB   NOT NULL  COMMENT 'Bytes do PKCS12 criptografados AES-256-GCM — nunca em texto claro',
    cert_senha_enc      VARCHAR(500) NOT NULL  COMMENT 'Senha do PKCS12 criptografada AES-256-GCM',
    key_version         VARCHAR(10)  NOT NULL DEFAULT 'v1'
                                               COMMENT 'Versão da chave AES usada na criptografia — permite rotação sem reescrita imediata',
    not_before          DATETIME     NOT NULL  COMMENT 'Início da validade do certificado X.509',
    not_after           DATETIME     NOT NULL  COMMENT 'Fim da validade do certificado X.509',
    ativo               TINYINT(1)   NOT NULL DEFAULT 1
                                               COMMENT '1 = em uso para emissão; 0 = substituído ou inativado',
    auth_id_ativo_unico BIGINT       GENERATED ALWAYS AS (IF(ativo = 1, auth_id, NULL)) STORED
                                               COMMENT 'Coluna gerada: auth_id quando ativo=1, NULL quando ativo=0 — viabiliza UNIQUE de um cert ativo por autorização',
    cadastrado_em       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    substituido_em      DATETIME     NULL      COMMENT 'Preenchido quando este certificado foi substituído por outro na mesma autorização',

    PRIMARY KEY (id),
    UNIQUE KEY uq_oms_cert_thumbprint_auth   (auth_id, thumbprint),
    UNIQUE KEY uq_oms_cert_um_ativo_por_auth (auth_id_ativo_unico),
    INDEX      idx_oms_cert_auth             (auth_id),
    INDEX      idx_oms_cert_not_after        (not_after),

    CONSTRAINT fk_oms_cert_auth FOREIGN KEY (auth_id) REFERENCES oms_fiscal_authorization(id)

) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Certificados A1 das empresas emitentes — separados da autorização para histórico e suporte a múltiplos certs';
