package br.com.borurio.fiscal.dto;

/**
 * Resultado estruturado do retorno SOAP de um evento (retEnvEvento/retEvento/infEvento) --
 * cancelamento 110111 hoje, reaproveitavel para outros tipos de evento no futuro.
 *
 * Distingue TRES niveis, nunca confundidos entre si (revisao tecnica de 12-08-2026):
 *   cStatLote     -> resultado do LOTE de evento (retEnvEvento/cStat, ex. 128="lote processado").
 *                    128 prova apenas que a SEFAZ processou o lote -- NAO prova nada sobre o
 *                    resultado do evento individual.
 *   infEvento     -> resultado do EVENTO individual (retEvento/infEvento/cStat) -- so existe
 *                    quando a SEFAZ avaliou o evento especifico; pode estar ausente mesmo com
 *                    cStatLote=128 (resposta incompleta/truncada).
 *   falhaParse    -> resposta SOAP recebida mas ilegivel (nunca convertida em rejeicao).
 *
 * Este parser NUNCA decide se o resultado e REGISTRADO/PENDENTE_CONFIRMACAO/REJEITADO -- essa
 * decisao pertence ao orquestrador (NfeEventoService), que tem o contexto completo (lote +
 * evento + falha de parse) para nunca inventar uma rejeicao fiscal que a SEFAZ nao emitiu.
 */
public class NfeEventoRetorno {

    private boolean falhaParse;
    private String detalheFalhaParse;

    private Integer cStatLote;
    private String xMotivoLote;

    private boolean infEventoPresente;
    private Integer cStatEvento;
    private String xMotivoEvento;
    private String nProtEvento;
    private String chNFeEvento;
    private String tpEventoRetornado;
    private String nSeqEventoRetornado;
    private String dhRegEvento;

    public static NfeEventoRetorno falhaParse(String detalhe) {
        NfeEventoRetorno r = new NfeEventoRetorno();
        r.falhaParse = true;
        r.detalheFalhaParse = detalhe;
        return r;
    }

    /** true somente quando o lote foi processado (128) e o infEvento individual esta presente e legivel. */
    public boolean isResultadoIndividualDisponivel() {
        return !falhaParse && cStatLote != null && cStatLote == 128 && infEventoPresente && cStatEvento != null;
    }

    /** true quando a SEFAZ recusou o LOTE inteiro de forma explicita (cStat != 128) -- nunca ambiguo. */
    public boolean isLoteRejeitado() {
        return !falhaParse && cStatLote != null && cStatLote != 128;
    }

    public boolean isFalhaParse() { return falhaParse; }
    public String getDetalheFalhaParse() { return detalheFalhaParse; }

    public Integer getCStatLote() { return cStatLote; }
    public void setCStatLote(Integer cStatLote) { this.cStatLote = cStatLote; }

    public String getXMotivoLote() { return xMotivoLote; }
    public void setXMotivoLote(String xMotivoLote) { this.xMotivoLote = xMotivoLote; }

    public boolean isInfEventoPresente() { return infEventoPresente; }
    public void setInfEventoPresente(boolean infEventoPresente) { this.infEventoPresente = infEventoPresente; }

    public Integer getCStatEvento() { return cStatEvento; }
    public void setCStatEvento(Integer cStatEvento) { this.cStatEvento = cStatEvento; }

    public String getXMotivoEvento() { return xMotivoEvento; }
    public void setXMotivoEvento(String xMotivoEvento) { this.xMotivoEvento = xMotivoEvento; }

    public String getNProtEvento() { return nProtEvento; }
    public void setNProtEvento(String nProtEvento) { this.nProtEvento = nProtEvento; }

    public String getChNFeEvento() { return chNFeEvento; }
    public void setChNFeEvento(String chNFeEvento) { this.chNFeEvento = chNFeEvento; }

    public String getTpEventoRetornado() { return tpEventoRetornado; }
    public void setTpEventoRetornado(String tpEventoRetornado) { this.tpEventoRetornado = tpEventoRetornado; }

    public String getNSeqEventoRetornado() { return nSeqEventoRetornado; }
    public void setNSeqEventoRetornado(String nSeqEventoRetornado) { this.nSeqEventoRetornado = nSeqEventoRetornado; }

    public String getDhRegEvento() { return dhRegEvento; }
    public void setDhRegEvento(String dhRegEvento) { this.dhRegEvento = dhRegEvento; }

    @Override
    public String toString() {
        return "NfeEventoRetorno{falhaParse=" + falhaParse + ", cStatLote=" + cStatLote
                + ", infEventoPresente=" + infEventoPresente + ", cStatEvento=" + cStatEvento
                + ", xMotivoEvento='" + xMotivoEvento + '\'' + ", nProtEvento='" + nProtEvento + '\'' + '}';
    }
}
