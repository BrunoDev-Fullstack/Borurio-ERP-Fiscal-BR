-- V039: Estado ABANDONADO no ciclo do nNF (Gate 1) -- recovery administrativo (02-09-2026).
--
-- Encerra um ciclo em AGUARDANDO_CORRECAO cujo dado de origem NAO pode mais ser corrigido pelo
-- fluxo normal (ex.: xProd rejeitado por schema num pedido sem endpoint de edicao de item).
-- Semantica: "este ciclo terminou operacionalmente, mas esta NF-e nunca existiu fiscalmente".
--
-- Diferenca em relacao aos terminais AUTORIZADO/DENEGADO/NUMERO_OCUPADO: ABANDONADO libera o gate
-- da serie (nfe_sequencia.emissao_ativa_id -> NULL) mas NAO consome numero
-- (nfe_sequencia.ultimo_numero permanece intocado) -- o mesmo nNF volta a ser alocavel pelo
-- proximo ciclo. Preserva cstat/xmotivo da rejeicao; preenche resolvido_em.
--
-- So alcancavel via POST /api/admin/nfe-emissoes/{id}/abandonar (ROLE_ADMIN); nunca por
-- resolverCiclo/reconciliacao. Guard: origem obrigatoriamente AGUARDANDO_CORRECAO e nprot NULL.
--
-- Alteracao apenas de COMMENT: a coluna ja e VARCHAR(30) livre e comporta 'ABANDONADO' (10 chars)
-- sem mudanca de tipo. MODIFY so atualiza a documentacao do schema para incluir os estados
-- adicionados desde a V033 (NUMERO_OCUPADO, CANCELADO) e o novo ABANDONADO.

ALTER TABLE nfe_emissao
    MODIFY COLUMN estado VARCHAR(30) NOT NULL
        COMMENT 'RESERVADO, TRANSMITIDO, AUTORIZADO, AGUARDANDO_CORRECAO, DENEGADO, PENDENTE_CONFIRMACAO, NUMERO_OCUPADO, CANCELADO, ABANDONADO';
