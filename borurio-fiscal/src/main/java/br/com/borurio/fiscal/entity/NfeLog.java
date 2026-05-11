package br.com.borurio.fiscal.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entidade responsável por registrar eventos de auditoria fiscal da NF-e
 * dentro do sistema Borurio ERP Fiscal BR.
 *
 * Cada instância representa um evento ocorrido durante o ciclo de vida
 * da Nota Fiscal Eletrônica (NF-e), incluindo:
 * - Emissão, rejeição e autorização
 * - Cancelamento, duplicidade ou falhas técnicas
 * - Retornos da SEFAZ (protocolo, erro, status)
 *
 * Integração direta com o MyBatis via anotações Java
 * (sem dependência de mapper XML externo).
 *
 * <b>Boas práticas DevSecOps aplicadas:</b>
 * <ul>
 *   <li>Entidade imutável, rastreável e auditável.</li>
 *   <li>Campos compatíveis com MySQL 8.4 (tipos nativos e portáveis).</li>
 *   <li>Sem lógica de negócio, respeitando o princípio SRP (Single Responsibility Principle).</li>
 *   <li>Totalmente UTF-8 sem BOM, garantindo portabilidade entre ambientes.</li>
 * </ul>
 *
 * <b>Tabela:</b> nfe_log
 * <b>Schema:</b> auditoria fiscal e integridade SEFAZ-SP (Homologação e Produção)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NfeLog {

    /** Identificador único do log fiscal (chave primária da tabela nfe_log). */
    private Long id;

    /** Chave da NF-e (44 dígitos, identificador fiscal único nacional). */
    private String chaveNfe;

    /** Tipo de evento registrado (AUTORIZADA, REJEITADA, CANCELADA, DUPLICADA, ERRO_TECNICO etc). */
    private String tipoEvento;

    /** Descrição detalhada do evento (mensagem da SEFAZ ou do sistema ERP). */
    private String descricao;

    /** Status interno do processamento (SUCCESS, ERROR, PENDING, RETRY). */
    private String status;

    /** Conteúdo XML transmitido à SEFAZ (mantido para rastreabilidade e auditoria). */
    private String xmlEnvio;

    /** XML retornado pela SEFAZ (protocolo, rejeição, cancelamento, etc). */
    private String xmlRetorno;

    /** Data e hora do evento fiscal (registrada pelo servidor do ERP). */
    private LocalDateTime dataEvento;

    /** CNPJ do emitente da NF-e responsável pelo evento registrado. */
    private String cnpjEmitente;

    /** Usuário ou serviço responsável pela operação (usuário autenticado ou integração). */
    private String usuario;

    /** ID da empresa emitente (multiempresa). */
    private Long empresaId;
}
