package br.com.borurio.fiscal.entity;

import java.time.LocalDateTime;

/**
 * Idempotência de OPERAÇÃO (identidade da intenção do OMS) -- distinta da identidade fiscal em
 * nfe_evento. Uma mesma identidade fiscal (chave+tipo+nSeq) pode ser legitimamente reutilizada ao
 * longo do tempo por operações DIFERENTES (tentativa rejeitada -> nova tentativa corrigida com
 * nova Idempotency-Key) -- por isso este registro guarda um SNAPSHOT do desfecho da própria
 * operação, nunca dereferencia o estado atual de nfe_evento (achado de banca, 12-08-2026: sem
 * isso, o replay de uma chave antiga devolveria o resultado de uma operação alheia que reabriu a
 * mesma linha depois).
 */
public class NfeEventoIdempotencia {

    public static final class Estados {
        public static final String PREPARADO = "PREPARADO";
        public static final String TRANSMITIDO = "TRANSMITIDO";
        public static final String PENDENTE_CONFIRMACAO = "PENDENTE_CONFIRMACAO";
        public static final String REGISTRADO = "REGISTRADO";
        public static final String REJEITADO = "REJEITADO";

        public static boolean isTerminal(String estado) {
            return REGISTRADO.equals(estado) || REJEITADO.equals(estado);
        }

        private Estados() {}
    }

    private Long id;
    private String idempotencyKey;
    private Long empresaId;
    private Long pedidoId;
    private String tipoEvento;
    private String conteudoHash;
    private Long nfeEventoId;
    private String estadoResultado;
    private Integer cstatResultado;
    private String xmotivoResultado;
    private String nprotResultado;
    private String dhRegEventoResultado;
    private LocalDateTime resolvidoEm;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Long getEmpresaId() { return empresaId; }
    public void setEmpresaId(Long empresaId) { this.empresaId = empresaId; }

    public Long getPedidoId() { return pedidoId; }
    public void setPedidoId(Long pedidoId) { this.pedidoId = pedidoId; }

    public String getTipoEvento() { return tipoEvento; }
    public void setTipoEvento(String tipoEvento) { this.tipoEvento = tipoEvento; }

    public String getConteudoHash() { return conteudoHash; }
    public void setConteudoHash(String conteudoHash) { this.conteudoHash = conteudoHash; }

    public Long getNfeEventoId() { return nfeEventoId; }
    public void setNfeEventoId(Long nfeEventoId) { this.nfeEventoId = nfeEventoId; }

    public String getEstadoResultado() { return estadoResultado; }
    public void setEstadoResultado(String estadoResultado) { this.estadoResultado = estadoResultado; }

    public Integer getCstatResultado() { return cstatResultado; }
    public void setCstatResultado(Integer cstatResultado) { this.cstatResultado = cstatResultado; }

    public String getXmotivoResultado() { return xmotivoResultado; }
    public void setXmotivoResultado(String xmotivoResultado) { this.xmotivoResultado = xmotivoResultado; }

    public String getNprotResultado() { return nprotResultado; }
    public void setNprotResultado(String nprotResultado) { this.nprotResultado = nprotResultado; }

    public String getDhRegEventoResultado() { return dhRegEventoResultado; }
    public void setDhRegEventoResultado(String dhRegEventoResultado) { this.dhRegEventoResultado = dhRegEventoResultado; }

    public LocalDateTime getResolvidoEm() { return resolvidoEm; }
    public void setResolvidoEm(LocalDateTime resolvidoEm) { this.resolvidoEm = resolvidoEm; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
