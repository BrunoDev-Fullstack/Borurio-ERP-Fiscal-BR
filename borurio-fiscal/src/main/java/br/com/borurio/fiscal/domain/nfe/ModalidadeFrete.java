package br.com.borurio.fiscal.domain.nfe;

/**
 * Modalidade do frete (grupo {@code transp}/{@code modFrete}) conforme o leiaute NF-e 4.00:
 * 0-Contratação do Frete por conta do Remetente (CIF); 1-Contratação do Frete por conta do
 * Destinatário (FOB); 2-Contratação do Frete por conta de Terceiros;
 * 3-Transporte Próprio por conta do Remetente; 4-Transporte Próprio por conta do Destinatário;
 * 9-Sem Ocorrência de Transporte.
 */
public enum ModalidadeFrete {

    CONTA_REMETENTE("0"),
    CONTA_DESTINATARIO("1"),
    CONTA_TERCEIROS("2"),
    PROPRIO_REMETENTE("3"),
    PROPRIO_DESTINATARIO("4"),
    SEM_OCORRENCIA_TRANSPORTE("9");

    private final String codigo;

    ModalidadeFrete(String codigo) {
        this.codigo = codigo;
    }

    public String getCodigo() {
        return codigo;
    }
}
