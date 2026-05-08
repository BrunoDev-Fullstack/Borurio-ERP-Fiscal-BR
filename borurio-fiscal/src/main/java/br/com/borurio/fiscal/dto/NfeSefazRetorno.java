package br.com.borurio.fiscal.dto;

/**
 * Resultado da análise do retorno SOAP da SEFAZ após transmissão de NF-e.
 *
 * Distingue dois níveis de resposta:
 *   cStatLote  → resposta do serviço de recepção de lote (104=processado, 225=rejeitado no lote)
 *   cStat      → resposta por NF-e individual, dentro de protNFe/infProt
 *
 * A NF-e só é considerada autorizada quando cStat == 100.
 */
public class NfeSefazRetorno {

    private String chaveNfe;
    private int cStatLote;
    private String xMotivoLote;
    private int cStat;
    private String xMotivo;
    private String nProt;
    private String dhRecbto;
    private String verAplic;

    public NfeSefazRetorno() {}

    public boolean isAutorizada() {
        return cStat == 100;
    }

    public String getChaveNfe() { return chaveNfe; }
    public void setChaveNfe(String chaveNfe) { this.chaveNfe = chaveNfe; }

    public int getCStatLote() { return cStatLote; }
    public void setCStatLote(int cStatLote) { this.cStatLote = cStatLote; }

    public String getXMotivoLote() { return xMotivoLote; }
    public void setXMotivoLote(String xMotivoLote) { this.xMotivoLote = xMotivoLote; }

    public int getCStat() { return cStat; }
    public void setCStat(int cStat) { this.cStat = cStat; }

    public String getXMotivo() { return xMotivo; }
    public void setXMotivo(String xMotivo) { this.xMotivo = xMotivo; }

    public String getNProt() { return nProt; }
    public void setNProt(String nProt) { this.nProt = nProt; }

    public String getDhRecbto() { return dhRecbto; }
    public void setDhRecbto(String dhRecbto) { this.dhRecbto = dhRecbto; }

    public String getVerAplic() { return verAplic; }
    public void setVerAplic(String verAplic) { this.verAplic = verAplic; }

    @Override
    public String toString() {
        return "NfeSefazRetorno{cStatLote=" + cStatLote +
               ", cStat=" + cStat +
               ", xMotivo='" + xMotivo + '\'' +
               ", nProt='" + nProt + '\'' +
               ", autorizada=" + isAutorizada() + '}';
    }
}
