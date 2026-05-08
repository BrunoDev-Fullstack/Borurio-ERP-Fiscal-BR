package br.com.borurio.app.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class Pedido {

    private Long id;
    private Long empresaId;

    /** Número do pedido. Gerado automaticamente como PED-{id:08d} se não informado. */
    private String numero;

    private String cnpjEmitente;

    // =========================================================================
    // DESTINATÁRIO
    // =========================================================================

    private String destCnpjCpf;
    private String destRazaoSocial;
    private String destUf;
    private String destLogradouro;
    private String destNumero;
    private String destBairro;
    private String destCodigoMunicipio;
    private String destMunicipio;
    private String destCep;

    // =========================================================================
    // FISCAL
    // =========================================================================

    /** Natureza da operação para o campo <natOp> da NF-e. */
    private String naturezaOperacao;

    /** Série da NF-e. Default "1". */
    private String serieNfe;

    // =========================================================================
    // CONTROLE
    // =========================================================================

    /** RASCUNHO | EMITIDO | CANCELADO | ERRO */
    private String status;

    /** Chave de acesso da NF-e (44 dígitos). Preenchida após emissão autorizada. */
    private String chaveNfe;

    private BigDecimal valorTotal;
    private String observacao;

    private LocalDateTime dataPedido;
    private LocalDateTime dataAtualizacao;

    /** Itens do pedido. Não mapeado diretamente — populado por buscarComItens(). */
    private List<PedidoItem> itens;
}
