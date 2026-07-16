package br.com.borurio.web.dto;

import br.com.borurio.app.entity.Empresa;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EmpresaResponse {

    private Long id;
    private String cnpj;
    private String razaoSocial;
    private String nomeFantasia;
    private String ie;
    private String crt;
    private String uf;
    private String logradouro;
    private String numero;
    private String bairro;
    private String municipio;
    private String codigoMunicipio;
    private String cep;
    private String serieNfePadrao;
    private String indFinalPadrao;
    private Boolean ativo;
    private LocalDateTime criadoEm;
    private LocalDateTime atualizadoEm;
    private String certPath;
    private String certTipo;
    private Boolean controleEstoqueAtivo;

    public static EmpresaResponse from(Empresa e) {
        EmpresaResponse r = new EmpresaResponse();
        r.setId(e.getId());
        r.setCnpj(e.getCnpj());
        r.setRazaoSocial(e.getRazaoSocial());
        r.setNomeFantasia(e.getNomeFantasia());
        r.setIe(e.getIe());
        r.setCrt(e.getCrt());
        r.setUf(e.getUf());
        r.setLogradouro(e.getLogradouro());
        r.setNumero(e.getNumero());
        r.setBairro(e.getBairro());
        r.setMunicipio(e.getMunicipio());
        r.setCodigoMunicipio(e.getCodigoMunicipio());
        r.setCep(e.getCep());
        r.setSerieNfePadrao(e.getSerieNfePadrao());
        r.setIndFinalPadrao(e.getIndFinalPadrao());
        r.setAtivo(e.getAtivo());
        r.setCriadoEm(e.getCriadoEm());
        r.setAtualizadoEm(e.getAtualizadoEm());
        r.setCertPath(e.getCertPath());
        r.setCertTipo(e.getCertTipo());
        r.setControleEstoqueAtivo(e.getControleEstoqueAtivo());
        return r;
    }
}
