package br.com.borurio.app.entity;

/**
 * Domínio de motivos para revogação/rotação administrativa de autorização OMS.
 * OUTRO exige motivoDetalhe preenchido (validado em serviço, não em banco —
 * mesma convenção de nfe_sequencia_auditoria.origem: sem CHECK de domínio em SQL).
 */
public enum MotivoAdminOms {
    SUSPEITA_VAZAMENTO,
    SOLICITACAO_CLIENTE,
    ROTINA_SEGURANCA,
    ENCERRAMENTO_INTEGRACAO,
    OUTRO
}
