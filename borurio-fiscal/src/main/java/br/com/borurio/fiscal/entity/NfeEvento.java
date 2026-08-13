package br.com.borurio.fiscal.entity;

import java.time.LocalDateTime;

/**
 * Ciclo de um evento fiscal pos-autorizacao (cancelamento 110111; reaproveitavel para CC-e
 * 110110 no futuro). Distinta de NfeEmissao (ciclo do nNF, Gate 1/3) e de NfeDocumento (snapshot
 * da autorizacao original, nunca sobrescrito por um evento posterior) -- a evidencia de um
 * cancelamento vive aqui, nao em nfe_documento.cStat.
 */
public class NfeEvento {

    /** Nomes de estado usados em nfe_evento.estado. */
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

    public static final class TiposEvento {
        public static final String CANCELAMENTO = "110111";
        public static final String CCE = "110110";

        private TiposEvento() {}
    }

    public static final class OrigensResolucao {
        public static final String EVENTO_DIRETO = "EVENTO_DIRETO";
        public static final String CONSULTA_SITUACAO = "CONSULTA_SITUACAO";

        private OrigensResolucao() {}
    }

    private Long id;
    private Long pedidoId;
    private Long emissaoId;
    private Long empresaId;
    private String cnpjEmitente;
    private String chaveNfe;
    private String tipoEvento;
    private int nSeqEvento;
    private String idEvento;
    private String estado;
    private Integer cstat;
    private String xmotivo;
    private String nprot;
    private boolean foraDoPrazo;
    private String justificativa;
    private String conteudoEvento;
    private String dhEvento;
    private String payloadHash;
    private String resolucaoOrigem;
    private LocalDateTime transmitidoEm;
    private LocalDateTime resolvidoEm;
    private LocalDateTime ultimaConsultaEm;
    private int tentativasConsulta;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getPedidoId() { return pedidoId; }
    public void setPedidoId(Long pedidoId) { this.pedidoId = pedidoId; }

    public Long getEmissaoId() { return emissaoId; }
    public void setEmissaoId(Long emissaoId) { this.emissaoId = emissaoId; }

    public Long getEmpresaId() { return empresaId; }
    public void setEmpresaId(Long empresaId) { this.empresaId = empresaId; }

    public String getCnpjEmitente() { return cnpjEmitente; }
    public void setCnpjEmitente(String cnpjEmitente) { this.cnpjEmitente = cnpjEmitente; }

    public String getChaveNfe() { return chaveNfe; }
    public void setChaveNfe(String chaveNfe) { this.chaveNfe = chaveNfe; }

    public String getTipoEvento() { return tipoEvento; }
    public void setTipoEvento(String tipoEvento) { this.tipoEvento = tipoEvento; }

    public int getNSeqEvento() { return nSeqEvento; }
    public void setNSeqEvento(int nSeqEvento) { this.nSeqEvento = nSeqEvento; }

    public String getIdEvento() { return idEvento; }
    public void setIdEvento(String idEvento) { this.idEvento = idEvento; }

    public String getEstado() { return estado; }
    public void setEstado(String estado) { this.estado = estado; }

    public Integer getCstat() { return cstat; }
    public void setCstat(Integer cstat) { this.cstat = cstat; }

    public String getXmotivo() { return xmotivo; }
    public void setXmotivo(String xmotivo) { this.xmotivo = xmotivo; }

    public String getNprot() { return nprot; }
    public void setNprot(String nprot) { this.nprot = nprot; }

    public boolean isForaDoPrazo() { return foraDoPrazo; }
    public void setForaDoPrazo(boolean foraDoPrazo) { this.foraDoPrazo = foraDoPrazo; }

    public String getJustificativa() { return justificativa; }
    public void setJustificativa(String justificativa) { this.justificativa = justificativa; }

    public String getConteudoEvento() { return conteudoEvento; }
    public void setConteudoEvento(String conteudoEvento) { this.conteudoEvento = conteudoEvento; }

    public String getDhEvento() { return dhEvento; }
    public void setDhEvento(String dhEvento) { this.dhEvento = dhEvento; }

    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }

    public String getResolucaoOrigem() { return resolucaoOrigem; }
    public void setResolucaoOrigem(String resolucaoOrigem) { this.resolucaoOrigem = resolucaoOrigem; }

    public LocalDateTime getTransmitidoEm() { return transmitidoEm; }
    public void setTransmitidoEm(LocalDateTime transmitidoEm) { this.transmitidoEm = transmitidoEm; }

    public LocalDateTime getResolvidoEm() { return resolvidoEm; }
    public void setResolvidoEm(LocalDateTime resolvidoEm) { this.resolvidoEm = resolvidoEm; }

    public LocalDateTime getUltimaConsultaEm() { return ultimaConsultaEm; }
    public void setUltimaConsultaEm(LocalDateTime ultimaConsultaEm) { this.ultimaConsultaEm = ultimaConsultaEm; }

    public int getTentativasConsulta() { return tentativasConsulta; }
    public void setTentativasConsulta(int tentativasConsulta) { this.tentativasConsulta = tentativasConsulta; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
