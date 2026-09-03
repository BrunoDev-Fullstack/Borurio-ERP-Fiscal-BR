-- V041: Correcao de semantica dos recovery administrativos ABANDONADO e TRANSPORTE_NAO_ENTREGUE
-- para o "modelo gap" (02-09-2026, pos-incidente de colisao em uk_nfe_emissao_numero).
--
-- CONTEXTO -----------------------------------------------------------------------------------------
-- As V039/V040 descreveram ABANDONADO e TRANSPORTE_NAO_ENTREGUE como "libera o gate mas NAO
-- consome numero (nfe_sequencia.ultimo_numero permanece intocado) -- o mesmo nNF volta a ser
-- alocavel". Isso e IMPOSSIVEL no modelo de dados atual: a linha de nfe_emissao do ciclo
-- encerrado nao e apagada e ocupa permanentemente o slot (cnpj_emitente, modelo, serie,
-- numero_nfe) via a UNIQUE uk_nfe_emissao_numero (V033). Ao deixar ultimo_numero atras desse
-- nNF, o proximo abrirCiclo recalcula o candidato como ultimo_numero + 1, tenta INSERT no mesmo
-- slot e falha com "Duplicate entry ... for key 'nfe_emissao.uk_nfe_emissao_numero'".
--
-- MODELO GAP (semantica correta) ----------------------------------------------------------------
-- ABANDONADO e TRANSPORTE_NAO_ENTREGUE agora:
--   - encerram o ciclo;
--   - liberam o gate da serie (nfe_sequencia.emissao_ativa_id -> NULL);
--   - AVANCAM nfe_sequencia.ultimo_numero ate o numero_nfe daquele ciclo -- nunca alem, nunca
--     regredindo (UPDATE ... SET ultimo_numero = :nNF WHERE ultimo_numero < :nNF);
--   - a chamada idempotente (estado ja encerrado) ainda repara o contador se ultimo_numero
--     ficou abaixo de numero_nfe -- nunca e no-op cego;
--   - preservam cstat/xmotivo/nprot/chave e evidencias; nao chamam a SEFAZ.
-- O nNF fica como "numero nao autorizado no historico"; a inutilizacao formal junto a SEFAZ,
-- se desejada, e passo administrativo separado (fora do escopo desta migration).
--
-- ALTERACAO DE SCHEMA: nenhuma. A coluna ja e VARCHAR(30) livre e comporta os dois estados.
-- O MODIFY abaixo apenas mantem o COMMENT do schema alinhado ao conjunto de estados vigente
-- (identico ao da V040) e da a esta correcao um ponto de migracao versionado e auditavel.

ALTER TABLE nfe_emissao
    MODIFY COLUMN estado VARCHAR(30) NOT NULL
        COMMENT 'RESERVADO, TRANSMITIDO, AUTORIZADO, AGUARDANDO_CORRECAO, DENEGADO, PENDENTE_CONFIRMACAO, NUMERO_OCUPADO, CANCELADO, ABANDONADO, TRANSPORTE_NAO_ENTREGUE';
