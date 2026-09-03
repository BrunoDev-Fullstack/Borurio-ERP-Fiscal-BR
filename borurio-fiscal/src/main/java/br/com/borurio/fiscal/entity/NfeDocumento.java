package br.com.borurio.fiscal.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Estado atual de um documento NF-e 4.00.
 *
 * Diferença em relação a NfeLog:
 *   NfeLog       → diário de eventos (N eventos por NF-e)
 *   NfeDocumento → snapshot do estado atual (1 registro por chave_nfe)
 *
 * Campos fiscais relevantes para o compliance brasileiro:
 *   nProt         → obrigatório para emissão de DANFE e cancelamento
 *   cStat=100     → única condição que representa NF-e autorizada
 *   xmlProtocolo  → nfeProc completo exigido pelo SEFAZ para arquivamento (5 anos)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NfeDocumento {

    private Long id;

    /** Chave de acesso: 44 dígitos, identificador único nacional. */
    private String chaveNfe;

    /** Número da NF-e (até 9 dígitos). */
    private String nNf;

    /** Série da NF-e (0–999). */
    private String serie;

    /** CNPJ do emitente — somente dígitos, 14 caracteres. */
    private String cnpjEmitente;

    /** CNPJ ou CPF do destinatário — somente dígitos. */
    private String destCnpjCpf;

    /** Razão social do destinatário. */
    private String destRazaoSocial;

    /** Valor total da NF-e. */
    private BigDecimal valorTotal;

    /** Ambiente: 1=Produção, 2=Homologação. */
    private Integer tpAmb;

    /** Último cStat recebido da SEFAZ. 100=Autorizada. 101=Cancelada. */
    private String cStat;

    /** Descrição do status SEFAZ correspondente ao cStat. */
    private String xMotivo;

    /** Número do protocolo de autorização ou cancelamento (15 dígitos). Null quando rejeitada. */
    private String nProt;

    /** Data/hora de recebimento pelo SEFAZ. */
    private LocalDateTime dhRecbto;

    /** XML assinado da NF-e (sem protocolo). Usado para reemissão de DANFE. */
    private String xmlNfe;

    /**
     * nfeProc: XML NF-e + protocolo embutido.
     * Formato exigido pelo SEFAZ para arquivamento fiscal (prazo mínimo 5 anos).
     * Null enquanto não autorizada.
     */
    private String xmlProtocolo;

    /** Caminho ou URL do DANFE em PDF. Null até a geração do PDF. */
    private String danfePath;

    /** Data/hora de emissão (dhEmi do XML). */
    private LocalDateTime dataEmissao;

    private LocalDateTime dataCriacao;
    private LocalDateTime dataAtualizacao;

    /** Retorna true somente quando cStat=100 (única condição de NF-e autorizada). */
    public boolean isAutorizada() {
        return "100".equals(cStat);
    }

    /** Retorna true quando cStat=101 (cancelada com protocolo). */
    public boolean isCancelada() {
        return "101".equals(cStat);
    }
}
