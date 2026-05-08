package br.com.borurio.app.mapper;

import br.com.borurio.app.entity.Pedido;
import org.apache.ibatis.annotations.*;

import java.math.BigDecimal;
import java.util.List;

public interface PedidoMapper {

    String SELECT_COLUMNS = """
            SELECT id,
                   empresa_id              AS empresaId,
                   numero,
                   cnpj_emitente           AS cnpjEmitente,
                   dest_cnpj_cpf           AS destCnpjCpf,
                   dest_razao_social       AS destRazaoSocial,
                   dest_uf                 AS destUf,
                   dest_logradouro         AS destLogradouro,
                   dest_numero             AS destNumero,
                   dest_bairro             AS destBairro,
                   dest_codigo_municipio   AS destCodigoMunicipio,
                   dest_municipio          AS destMunicipio,
                   dest_cep                AS destCep,
                   natureza_operacao       AS naturezaOperacao,
                   serie_nfe               AS serieNfe,
                   status,
                   chave_nfe               AS chaveNfe,
                   valor_total             AS valorTotal,
                   observacao,
                   data_pedido             AS dataPedido,
                   data_atualizacao        AS dataAtualizacao
            FROM pedido
            """;

    @Select(SELECT_COLUMNS + "WHERE id = #{id}")
    Pedido buscarPorId(Long id);

    @Select(SELECT_COLUMNS + "WHERE id = #{id} AND empresa_id = #{empresaId}")
    Pedido buscarPorIdEEmpresa(@Param("id") Long id, @Param("empresaId") Long empresaId);

    @Select(SELECT_COLUMNS + "WHERE empresa_id = #{empresaId} ORDER BY data_pedido DESC")
    List<Pedido> listarPorEmpresa(@Param("empresaId") Long empresaId);

    @Select(SELECT_COLUMNS + "WHERE cnpj_emitente = #{cnpjEmitente} ORDER BY data_pedido DESC")
    List<Pedido> listarPorEmitente(@Param("cnpjEmitente") String cnpjEmitente);

    @Select(SELECT_COLUMNS + "ORDER BY data_pedido DESC")
    List<Pedido> listarTodos();

    @Insert("""
            INSERT INTO pedido (
                empresa_id,
                numero, cnpj_emitente,
                dest_cnpj_cpf, dest_razao_social,
                dest_uf, dest_logradouro, dest_numero, dest_bairro,
                dest_codigo_municipio, dest_municipio, dest_cep,
                natureza_operacao, serie_nfe, status,
                chave_nfe, valor_total, observacao,
                data_pedido, data_atualizacao
            ) VALUES (
                #{empresaId, jdbcType=BIGINT},
                #{numero}, #{cnpjEmitente},
                #{destCnpjCpf}, #{destRazaoSocial},
                #{destUf, jdbcType=VARCHAR}, #{destLogradouro, jdbcType=VARCHAR},
                #{destNumero, jdbcType=VARCHAR}, #{destBairro, jdbcType=VARCHAR},
                #{destCodigoMunicipio, jdbcType=VARCHAR}, #{destMunicipio, jdbcType=VARCHAR},
                #{destCep, jdbcType=VARCHAR},
                #{naturezaOperacao}, #{serieNfe}, #{status},
                #{chaveNfe, jdbcType=VARCHAR}, #{valorTotal, jdbcType=DECIMAL},
                #{observacao, jdbcType=VARCHAR},
                NOW(), NOW()
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserir(Pedido pedido);

    @Update("""
            UPDATE pedido SET
                numero                = #{numero},
                dest_cnpj_cpf         = #{destCnpjCpf},
                dest_razao_social     = #{destRazaoSocial},
                dest_uf               = #{destUf, jdbcType=VARCHAR},
                dest_logradouro       = #{destLogradouro, jdbcType=VARCHAR},
                dest_numero           = #{destNumero, jdbcType=VARCHAR},
                dest_bairro           = #{destBairro, jdbcType=VARCHAR},
                dest_codigo_municipio = #{destCodigoMunicipio, jdbcType=VARCHAR},
                dest_municipio        = #{destMunicipio, jdbcType=VARCHAR},
                dest_cep              = #{destCep, jdbcType=VARCHAR},
                natureza_operacao     = #{naturezaOperacao},
                serie_nfe             = #{serieNfe},
                observacao            = #{observacao, jdbcType=VARCHAR},
                data_atualizacao      = NOW()
            WHERE id = #{id}
            """)
    int atualizar(Pedido pedido);

    @Update("""
            UPDATE pedido SET
                status           = #{status},
                chave_nfe        = #{chaveNfe, jdbcType=VARCHAR},
                data_atualizacao = NOW()
            WHERE id = #{id}
            """)
    int atualizarStatus(@Param("id") Long id,
                        @Param("status") String status,
                        @Param("chaveNfe") String chaveNfe);

    @Update("""
            UPDATE pedido SET
                numero           = #{numero},
                valor_total      = #{valorTotal},
                data_atualizacao = NOW()
            WHERE id = #{id}
            """)
    int atualizarPosInsercao(@Param("id") Long id,
                              @Param("numero") String numero,
                              @Param("valorTotal") BigDecimal valorTotal);
}
