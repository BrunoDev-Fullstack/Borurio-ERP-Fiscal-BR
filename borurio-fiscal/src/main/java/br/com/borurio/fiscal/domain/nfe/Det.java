package br.com.borurio.fiscal.domain.nfe;

public class Det {

    private int nItem;
    private Produto prod;

    /** Origem da mercadoria: 0=Nacional, 1–8=Importada (AT 1/2013). */
    private String orig = "0";

    /** CSOSN Simples Nacional (CRT=1). Determina o grupo ICMSSN no XML. */
    private String csosn = "400";

    public int getNItem() { return nItem; }
    public void setNItem(int nItem) { this.nItem = nItem; }

    public Produto getProd() { return prod; }
    public void setProd(Produto prod) { this.prod = prod; }

    public String getOrig() { return orig; }
    public void setOrig(String orig) { this.orig = orig != null ? orig : "0"; }

    public String getCsosn() { return csosn; }
    public void setCsosn(String csosn) { this.csosn = csosn != null ? csosn : "400"; }
}