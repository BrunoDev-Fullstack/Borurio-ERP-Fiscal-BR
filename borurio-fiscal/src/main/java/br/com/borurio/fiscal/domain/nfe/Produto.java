package br.com.borurio.fiscal.domain.nfe;

public class Produto {

    // Código interno do produto
    private String cProd;

    // Descrição do produto
    private String xProd;

    // Código NCM (8 dígitos)
    private String NCM;

    // Código CFOP (4 dígitos)
    private String CFOP;

    // Unidade comercial (ex: UN, KG)
    private String uCom;

    // Quantidade comercial
    private String qCom;

    // Valor unitário
    private String vUnCom;

    // Valor total do item
    private String vProd;

    // getters/setters

    public String getCProd() {
        return cProd;
    }

    public void setCProd(String cProd) {
        this.cProd = cProd;
    }

    public String getXProd() {
        return xProd;
    }

    public void setXProd(String xProd) {
        this.xProd = xProd;
    }

    public String getNCM() {
        return NCM;
    }

    public void setNCM(String NCM) {
        this.NCM = NCM;
    }

    public String getCFOP() {
        return CFOP;
    }

    public void setCFOP(String CFOP) {
        this.CFOP = CFOP;
    }

    public String getUCom() {
        return uCom;
    }

    public void setUCom(String uCom) {
        this.uCom = uCom;
    }

    public String getQCom() {
        return qCom;
    }

    public void setQCom(String qCom) {
        this.qCom = qCom;
    }

    public String getVUnCom() {
        return vUnCom;
    }

    public void setVUnCom(String vUnCom) {
        this.vUnCom = vUnCom;
    }

    public String getVProd() {
        return vProd;
    }

    public void setVProd(String vProd) {
        this.vProd = vProd;
    }
}