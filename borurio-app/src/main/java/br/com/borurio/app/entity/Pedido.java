package br.com.borurio.app.entity;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class Pedido {

    private Long id;
    private Long empresaId;
    private String numero;
    private String cnpjEmitente;

    @NotBlank(message = "CNPJ/CPF do destinatário é obrigatório")
    private String destCnpjCpf;

    @NotBlank(message = "Razão social do destinatário é obrigatória")
    private String destRazaoSocial;

    private String destUf;
    private String destLogradouro;
    private String destNumero;
    private String destBairro;
    private String destCodigoMunicipio;
    private String destMunicipio;
    private String destCep;
    private String naturezaOperacao;
    private String serieNfe;

    /**
     * Endereço do emitente — recebido opcionalmente no payload de criação do pedido (fluxo OMS).
     * Não é persistido no pedido: usado apenas para completar o cadastro da Empresa quando incompleto.
     */
    private String emitLogradouro;
    private String emitNumero;
    private String emitBairro;
    private String emitCodigoMunicipio;
    private String emitMunicipio;
    private String emitCep;

    private String status;
    private String chaveNfe;
    private BigDecimal valorTotal;
    private String externalOrderId;
    private String observacao;
    private LocalDateTime dataPedido;
    private LocalDateTime dataAtualizacao;
    private List<PedidoItem> itens;
}
