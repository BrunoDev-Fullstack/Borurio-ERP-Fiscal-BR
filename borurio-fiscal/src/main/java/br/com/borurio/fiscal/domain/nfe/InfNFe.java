package br.com.borurio.fiscal.domain.nfe;

import java.util.List;

public class InfNFe {

    // ID da NF-e (formato: NFe + chave)
    private String id;

    // Versão do layout (default 4.00)
    private String versao = "4.00";

    // Identificação
    private Ide ide;

    // Emitente
    private Emit emit;

    // Destinatário
    private Dest dest;

    // Lista de itens
    private List<Det> det;

    // Totais
    private Total total;

    // =========================
    // GETTERS / SETTERS
    // =========================

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getVersao() {
        return versao;
    }

    // ✔ necessário para compatibilidade com builder/debug
    public void setVersao(String versao) {
        if (versao != null && !versao.isBlank()) {
            this.versao = versao;
        }
    }

    public Ide getIde() {
        return ide;
    }

    public void setIde(Ide ide) {
        this.ide = ide;
    }

    public Emit getEmit() {
        return emit;
    }

    public void setEmit(Emit emit) {
        this.emit = emit;
    }

    public Dest getDest() {
        return dest;
    }

    public void setDest(Dest dest) {
        this.dest = dest;
    }

    public List<Det> getDet() {
        return det;
    }

    public void setDet(List<Det> det) {
        this.det = det;
    }

    public Total getTotal() {
        return total;
    }

    public void setTotal(Total total) {
        this.total = total;
    }
}