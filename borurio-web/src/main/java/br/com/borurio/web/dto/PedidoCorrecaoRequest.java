package br.com.borurio.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * Payload de correção controlada de pedido (03-09-2026, V1 — escopo acordado com a OMS).
 * Todos os campos são opcionais: só os enviados (não nulos) são alterados, os demais mantêm
 * o valor atual do pedido. Escopo V1: descrição do item, endereço e demais textos fiscais do
 * cabeçalho. Quantidade, preço, NCM, CFOP, CSOSN e unidade ficam FORA desta versão — de propósito,
 * o DTO nem tem esses campos.
 */
@Data
public class PedidoCorrecaoRequest {

    private String naturezaOperacao;
    private String observacao;
    private String destRazaoSocial;
    private String destUf;
    private String destLogradouro;
    private String destNumero;
    private String destBairro;
    private String destCodigoMunicipio;
    private String destMunicipio;
    private String destCep;

    @Valid
    private List<ItemCorrecaoRequest> itens;

    @Data
    public static class ItemCorrecaoRequest {

        @NotNull(message = "id do item é obrigatório")
        private Long id;

        private String descricao;
    }
}
