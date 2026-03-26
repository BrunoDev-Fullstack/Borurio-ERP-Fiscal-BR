package br.com.borurio.fiscal.domain.nfe;

public class Dest {

    // CPF ou CNPJ (apenas números)
    private String cpfCnpj;

    // Nome do destinatário
    private String xNome;

    // getters/setters

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
}