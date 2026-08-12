package br.com.borurio.fiscal.dto;

/**
 * Resultado estruturado da Consulta Situacao NF-e (consSitNFe / retConsSitNFe) — Gate 3
 * (reconciliacao, 10-08-2026). Deliberadamente separado de {@link NfeSefazRetorno}: o retorno de
 * consulta tem vocabulario de cStat proprio (ex.: 217="nao consta na base", 635="mesma serie/
 * numero ja transmitidos, aguardando processamento"), que nao existe no retorno de emissao, e
 * nao deve ser confundido com ele so por reaproveitar o mesmo parser.
 *
 * Uma resposta SEFAZ com cStat conhecido (mesmo que seja 217) NUNCA e falha de transporte —
 * chegou, foi entendida, so nao significa autorizacao. Falha de transporte (timeout/conexao) e
 * representada por excecao antes de qualquer instancia deste DTO existir; falha de parse (SOAP
 * malformado/inesperado) e representada por {@link #falhaParse} nesta mesma instancia, nunca por
 * excecao — mesmo padrao defensivo ja usado em NfeSefazRetornoParser.
 */
public class NfeConsultaSituacaoRetorno {

    private int cStat;
    private String xMotivo;
    private String chNFe;
    private String nProt;
    private String dhRecbto;
    private String digVal;
    private boolean protNFePresente;
    private boolean falhaParse;
    private String detalheFalhaParse;

    // procEventoNFe -- so preenchido quando parse(xml, tpEventoAlvo, nSeqEventoAlvo) e chamado
    // com um alvo (reconciliacao de evento, ex. cancelamento). Nunca preenchido pelo parse(xml)
    // de 1 argumento usado pela reconciliacao de emissao (Gate 3) -- aditivo, sem efeito nela.
    private boolean eventoEncontrado;
    private Integer cStatEvento;
    private String xMotivoEvento;
    private String nProtEvento;
    private String dhRegEvento;

    public static NfeConsultaSituacaoRetorno falhaParse(String detalhe) {
        NfeConsultaSituacaoRetorno r = new NfeConsultaSituacaoRetorno();
        r.falhaParse = true;
        r.detalheFalhaParse = detalhe;
        return r;
    }

    public int getCStat() { return cStat; }
    public void setCStat(int cStat) { this.cStat = cStat; }

    public String getXMotivo() { return xMotivo; }
    public void setXMotivo(String xMotivo) { this.xMotivo = xMotivo; }

    public String getChNFe() { return chNFe; }
    public void setChNFe(String chNFe) { this.chNFe = chNFe; }

    public String getNProt() { return nProt; }
    public void setNProt(String nProt) { this.nProt = nProt; }

    public String getDhRecbto() { return dhRecbto; }
    public void setDhRecbto(String dhRecbto) { this.dhRecbto = dhRecbto; }

    public String getDigVal() { return digVal; }
    public void setDigVal(String digVal) { this.digVal = digVal; }

    public boolean isProtNFePresente() { return protNFePresente; }
    public void setProtNFePresente(boolean protNFePresente) { this.protNFePresente = protNFePresente; }

    public boolean isFalhaParse() { return falhaParse; }

    public String getDetalheFalhaParse() { return detalheFalhaParse; }

    /** Atalho para o caso mais comum de reconciliacao: 100/150 com protocolo presente. */
    public boolean isAutorizadaComProtocolo() {
        return !falhaParse && protNFePresente && (cStat == 100 || cStat == 150)
                && nProt != null && !nProt.isBlank();
    }

    public boolean isEventoEncontrado() { return eventoEncontrado; }
    public void setEventoEncontrado(boolean eventoEncontrado) { this.eventoEncontrado = eventoEncontrado; }

    public Integer getCStatEvento() { return cStatEvento; }
    public void setCStatEvento(Integer cStatEvento) { this.cStatEvento = cStatEvento; }

    public String getXMotivoEvento() { return xMotivoEvento; }
    public void setXMotivoEvento(String xMotivoEvento) { this.xMotivoEvento = xMotivoEvento; }

    public String getNProtEvento() { return nProtEvento; }
    public void setNProtEvento(String nProtEvento) { this.nProtEvento = nProtEvento; }

    public String getDhRegEvento() { return dhRegEvento; }
    public void setDhRegEvento(String dhRegEvento) { this.dhRegEvento = dhRegEvento; }

    /** cStat=101 (cancelada) sem o procEventoNFe detalhado correspondente -- ver NfeEventoService: pode
     *  projetar CANCELADO, mas nunca inventa nProtEvento; a resolucao registra a origem como a propria
     *  Consulta Situacao, nao um protocolo de evento que a SEFAZ nao devolveu em detalhe. */
    public boolean isCanceladaSemEventoDetalhado() {
        return !falhaParse && protNFePresente && cStat == 101 && !eventoEncontrado;
    }

    @Override
    public String toString() {
        return "NfeConsultaSituacaoRetorno{cStat=" + cStat + ", xMotivo='" + xMotivo + '\''
                + ", chNFe='" + chNFe + '\'' + ", nProt='" + nProt + '\''
                + ", protNFePresente=" + protNFePresente + ", falhaParse=" + falhaParse + '}';
    }
}
