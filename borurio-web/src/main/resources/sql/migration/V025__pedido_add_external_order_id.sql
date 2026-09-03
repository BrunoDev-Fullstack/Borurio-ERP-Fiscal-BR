-- =============================================================================
-- V025: externalOrderId — idempotência na criação de pedido pelo OMS
-- Se a mesma empresa reenviar POST /api/app/pedidos com o mesmo externalOrderId,
-- o motor retorna o pedido existente sem criar duplicata.
-- NULL não quebra a unicidade: múltiplos pedidos sem externalOrderId são permitidos.
-- =============================================================================

ALTER TABLE pedido
    ADD COLUMN external_order_id VARCHAR(100) NULL
        COMMENT 'ID externo OMS para idempotência — (empresa_id, external_order_id) é único';

ALTER TABLE pedido
    ADD UNIQUE KEY uq_pedido_external_order (empresa_id, external_order_id);
