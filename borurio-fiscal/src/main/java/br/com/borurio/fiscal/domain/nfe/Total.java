package br.com.borurio.fiscal.domain.nfe;

public class Total {

    // Soma dos produtos
    private String vProd;

    // Valor total da NF-e
    private String vNF;

    // getters/setters

    public String getVProd() {
        return vProd;
    }

    public void setVProd(String vProd) {
        this.vProd = vProd;
    }

    public String getVNF() {
        return vNF;
    }

    public void setVNF(String vNF) {
        this.vNF = vNF;
    }
}