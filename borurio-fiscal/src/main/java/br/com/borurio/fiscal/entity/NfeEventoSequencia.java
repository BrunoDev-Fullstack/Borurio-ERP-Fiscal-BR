package br.com.borurio.fiscal.entity;

import java.time.LocalDateTime;

/**
 * Gate de reserva atômica da próxima sequência de um tipo de evento (CC-e 110110 hoje) para uma
 * chave de NF-e -- mesmo padrão de NfeSequencia (Gate 1), generalizado para eventos com múltiplas
 * ocorrências fiscais legítimas. Distinto de nfe_evento: esta linha só sabe "qual é a próxima
 * sequência livre e quem está com o gate ocupado agora", nunca o conteúdo/resultado do evento.
 */
public class NfeEventoSequencia {

    private Long id;
    private String chaveNfe;
    private String tipoEvento;
    private int ultimoNSeqRegistrado;
    private Long eventoAtivoId;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getChaveNfe() { return chaveNfe; }
    public void setChaveNfe(String chaveNfe) { this.chaveNfe = chaveNfe; }

    public String getTipoEvento() { return tipoEvento; }
    public void setTipoEvento(String tipoEvento) { this.tipoEvento = tipoEvento; }

    public int getUltimoNSeqRegistrado() { return ultimoNSeqRegistrado; }
    public void setUltimoNSeqRegistrado(int ultimoNSeqRegistrado) { this.ultimoNSeqRegistrado = ultimoNSeqRegistrado; }

    public Long getEventoAtivoId() { return eventoAtivoId; }
    public void setEventoAtivoId(Long eventoAtivoId) { this.eventoAtivoId = eventoAtivoId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
