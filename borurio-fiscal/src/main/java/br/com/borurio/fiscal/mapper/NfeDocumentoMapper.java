package br.com.borurio.fiscal.mapper;

import br.com.borurio.fiscal.entity.NfeDocumento;
import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Optional;

/**
 * Mapper MyBatis para a tabela nfe_documento.
 *
 * nfe_documento armazena o estado atual de cada NF-e (1 linha por chave_nfe).
 * Difere de nfe_log que registra N eventos por NF-e.
 */
@Mapper
public interface NfeDocumentoMapper {

    @Insert("""
        INSERT INTO nfe_documento
            (chave_nfe, n_nf, serie, cnpj_emitente, dest_cnpj_cpf, dest_razao_social,
             valor_total, tp_amb, c_stat, x_motivo, n_prot, dh_recbto,
             xml_nfe, xml_protocolo, danfe_path, data_emissao)
        VALUES
            (#{chaveNfe}, #{nNf}, #{serie}, #{cnpjEmitente}, #{destCnpjCpf}, #{destRazaoSocial},
             #{valorTotal}, #{tpAmb}, #{cStat}, #{xMotivo}, #{nProt}, #{dhRecbto},
             #{xmlNfe}, #{xmlProtocolo}, #{danfePath}, #{dataEmissao})
        """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(NfeDocumento doc);

    @Update("""
        UPDATE nfe_documento
        SET c_stat           = #{cStat},
            x_motivo         = #{xMotivo},
            n_prot           = #{nProt},
            dh_recbto        = #{dhRecbto},
            xml_protocolo    = #{xmlProtocolo},
            danfe_path       = #{danfePath},
            data_atualizacao = NOW()
        WHERE chave_nfe = #{chaveNfe}
        """)
    int updateStatus(NfeDocumento doc);

    @Select("""
        SELECT id, chave_nfe, n_nf, serie, cnpj_emitente, dest_cnpj_cpf, dest_razao_social,
               valor_total, tp_amb, c_stat, x_motivo, n_prot, dh_recbto,
               xml_nfe, xml_protocolo, danfe_path, data_emissao, data_criacao, data_atualizacao
        FROM nfe_documento
        WHERE chave_nfe = #{chaveNfe}
        """)
    @Results(id = "NfeDocumentoMap", value = {
        @Result(property = "id",              column = "id"),
        @Result(property = "chaveNfe",        column = "chave_nfe"),
        @Result(property = "nNf",             column = "n_nf"),
        @Result(property = "serie",           column = "serie"),
        @Result(property = "cnpjEmitente",    column = "cnpj_emitente"),
        @Result(property = "destCnpjCpf",     column = "dest_cnpj_cpf"),
        @Result(property = "destRazaoSocial", column = "dest_razao_social"),
        @Result(property = "valorTotal",      column = "valor_total"),
        @Result(property = "tpAmb",           column = "tp_amb"),
        @Result(property = "cStat",           column = "c_stat"),
        @Result(property = "xMotivo",         column = "x_motivo"),
        @Result(property = "nProt",           column = "n_prot"),
        @Result(property = "dhRecbto",        column = "dh_recbto"),
        @Result(property = "xmlNfe",          column = "xml_nfe"),
        @Result(property = "xmlProtocolo",    column = "xml_protocolo"),
        @Result(property = "danfePath",       column = "danfe_path"),
        @Result(property = "dataEmissao",     column = "data_emissao"),
        @Result(property = "dataCriacao",     column = "data_criacao"),
        @Result(property = "dataAtualizacao", column = "data_atualizacao")
    })
    Optional<NfeDocumento> findByChave(@Param("chaveNfe") String chaveNfe);

    @Select("""
        SELECT id, chave_nfe, n_nf, serie, cnpj_emitente, dest_cnpj_cpf, dest_razao_social,
               valor_total, tp_amb, c_stat, x_motivo, n_prot, dh_recbto,
               xml_nfe, xml_protocolo, danfe_path, data_emissao, data_criacao, data_atualizacao
        FROM nfe_documento
        WHERE cnpj_emitente = #{cnpjEmitente}
        ORDER BY data_emissao DESC
        LIMIT #{limite}
        """)
    @ResultMap("NfeDocumentoMap")
    List<NfeDocumento> findByEmitente(@Param("cnpjEmitente") String cnpjEmitente,
                                      @Param("limite") int limite);

    @Select("""
        SELECT id, chave_nfe, n_nf, serie, cnpj_emitente, dest_cnpj_cpf, dest_razao_social,
               valor_total, tp_amb, c_stat, x_motivo, n_prot, dh_recbto,
               xml_nfe, xml_protocolo, danfe_path, data_emissao, data_criacao, data_atualizacao
        FROM nfe_documento
        WHERE cnpj_emitente = #{cnpjEmitente}
          AND serie         = #{serie}
          AND n_nf          = #{nNf}
        LIMIT 1
        """)
    @ResultMap("NfeDocumentoMap")
    Optional<NfeDocumento> findBySerieNumero(@Param("cnpjEmitente") String cnpjEmitente,
                                              @Param("serie") String serie,
                                              @Param("nNf") String nNf);
}
