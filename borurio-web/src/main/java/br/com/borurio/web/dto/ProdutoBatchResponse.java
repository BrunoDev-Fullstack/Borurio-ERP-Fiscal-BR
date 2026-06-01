package br.com.borurio.web.dto;

import br.com.borurio.app.entity.ProdutoBatchItemResultado;

import java.util.ArrayList;
import java.util.List;

public class ProdutoBatchResponse {

    private final int              total;
    private final int              criados;
    private final int              atualizados;
    private final int              rejeitados;
    private final List<ItemResultado> resultados;

    private ProdutoBatchResponse(int total, int criados, int atualizados,
                                  int rejeitados, List<ItemResultado> resultados) {
        this.total       = total;
        this.criados     = criados;
        this.atualizados = atualizados;
        this.rejeitados  = rejeitados;
        this.resultados  = resultados;
    }

    public static ProdutoBatchResponse from(List<ProdutoBatchItemResultado> itens) {
        int c = 0, a = 0, r = 0;
        List<ItemResultado> lista = new ArrayList<>(itens.size());
        for (ProdutoBatchItemResultado item : itens) {
            switch (item.getStatus()) {
                case CRIADO    -> c++;
                case ATUALIZADO -> a++;
                case REJEITADO  -> r++;
            }
            lista.add(ItemResultado.from(item));
        }
        return new ProdutoBatchResponse(itens.size(), c, a, r, lista);
    }

    public int              getTotal()       { return total; }
    public int              getCriados()     { return criados; }
    public int              getAtualizados() { return atualizados; }
    public int              getRejeitados()  { return rejeitados; }
    public List<ItemResultado> getResultados() { return resultados; }

    public static class ItemResultado {

        private final String codigo;
        private final String status;
        private final Long   produtoId;
        private final String errorCode;
        private final String message;

        private ItemResultado(String codigo, String status,
                              Long produtoId, String errorCode, String message) {
            this.codigo    = codigo;
            this.status    = status;
            this.produtoId = produtoId;
            this.errorCode = errorCode;
            this.message   = message;
        }

        public static ItemResultado from(ProdutoBatchItemResultado item) {
            return new ItemResultado(
                    item.getCodigo(),
                    item.getStatus().name(),
                    item.getProdutoId(),
                    item.getErrorCode(),
                    item.getMessage()
            );
        }

        public String getCodigo()    { return codigo; }
        public String getStatus()    { return status; }
        public Long   getProdutoId() { return produtoId; }
        public String getErrorCode() { return errorCode; }
        public String getMessage()   { return message; }
    }
}
