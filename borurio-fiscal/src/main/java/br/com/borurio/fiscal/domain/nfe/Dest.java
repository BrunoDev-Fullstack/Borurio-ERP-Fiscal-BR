package br.com.borurio.fiscal.domain.nfe;

public class Dest {

    // CPF ou CNPJ (apenas números)
    private String cpfCnpj;

    // Nome do destinatário
    private String xNome;

    // Indicador IE do destinatário
    // 1 = Contribuinte ICMS
    // 2 = Isento
    // 9 = Não contribuinte
    private String indIEDest;

    // Inscrição estadual (opcional dependendo do caso)
    private String ie;

    // =========================
    // GETTERS / SETTERS
    // =========================

    public String getCpfCnpj() {
        return cpfCnpj;
    }

    public void setCpfCnpj(String cpfCnpj) {
        this.cpfCnpj = cpfCnpj;
    }

    public String getXNome() {
        return xNome;
    }

    public void setXNome(String xNome) {
        this.xNome = xNome;
    }

    public String getIndIEDest() {
        return indIEDest;
    }

    public void setIndIEDest(String indIEDest) {
        this.indIEDest = indIEDest;
    }

    public String getIe() {
        return ie;
    }

    public void setIe(String ie) {
        this.ie = ie;
    }
}