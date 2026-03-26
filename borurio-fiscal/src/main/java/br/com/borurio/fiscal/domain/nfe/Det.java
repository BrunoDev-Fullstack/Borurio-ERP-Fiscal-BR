package br.com.borurio.fiscal.domain.nfe;

public class Det {

    // Número do item (1, 2, 3...)
    private int nItem;

    // Produto do item
    private Produto prod;

    // getters/setters

    public int getNItem() {
        return nItem;
    }

    public void setNItem(int nItem) {
        this.nItem = nItem;
    }

    public Produto getProd() {
        return prod;
    }

    public void setProd(Produto prod) {
        this.prod = prod;
    }
}