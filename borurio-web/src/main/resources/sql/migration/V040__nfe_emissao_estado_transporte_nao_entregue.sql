-- V040: Estado TRANSPORTE_NAO_ENTREGUE no ciclo do nNF (Gate 1) -- recovery administrativo
-- (02-09-2026).
--
-- Encerra um ciclo em TRANSMITIDO/PENDENTE_CONFIRMACAO cuja tentativa de transmissao foi
-- COMPROVADAMENTE rejeitada no transporte/gateway ANTES de chegar ao autorizador da SEFAZ
-- (ex.: HTTP 403 do proxy por certificado cliente invalido, resposta HTML em vez de SOAP).
-- Semantica: "a NF-e nunca foi recebida pela SEFAZ -- nao existe fiscalmente".
--
-- Diferenca em relacao ao ABANDONADO: ABANDONADO parte de AGUARDANDO_CORRECAO (cStat 225 -- o
-- lote FOI recebido e rejeitado por schema); TRANSPORTE_NAO_ENTREGUE parte de TRANSMITIDO/
-- PENDENTE_CONFIRMACAO e exige prova de que o lote nunca chegou (sem nProt, sem tentativas_
-- consulta, sem n_prot/dh_recbto/xml_protocolo em nfe_documento).
--
-- Efeito igual: libera o gate da serie (nfe_sequencia.emissao_ativa_id -> NULL), NAO consome
-- numero (ultimo_numero intocado), preenche resolvido_em, preserva cstat/xmotivo/chave como
-- evidencia. Adicionalmente devolve o Pedido de origem a ERRO (emissivel) e limpa a chave
-- espuria; desfaz a reserva de estoque se o emit a fez.
--
-- So alcancavel via POST /api/admin/nfe-emissoes/{id}/marcar-transporte-nao-entregue (ROLE_ADMIN).
--
-- Alteracao apenas de COMMENT: a coluna ja e VARCHAR(30) livre e comporta
-- 'TRANSPORTE_NAO_ENTREGUE' (23 chars) sem mudanca de tipo.

ALTER TABLE nfe_emissao
    MODIFY COLUMN estado VARCHAR(30) NOT NULL
        COMMENT 'RESERVADO, TRANSMITIDO, AUTORIZADO, AGUARDANDO_CORRECAO, DENEGADO, PENDENTE_CONFIRMACAO, NUMERO_OCUPADO, CANCELADO, ABANDONADO, TRANSPORTE_NAO_ENTREGUE';
