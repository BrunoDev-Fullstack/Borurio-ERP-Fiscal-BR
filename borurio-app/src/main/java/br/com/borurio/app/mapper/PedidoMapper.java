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
                   external_order_id       AS externalOrderId,
                   observacao,
                   data_pedido             AS dataPedido,
                   data_atualizacao        AS dataAtualizacao
            FROM pedido
            """;

    @Select(SELECT_COLUMNS + "WHERE id = #{id}")
    Pedido buscarPorId(Long id);

    @Select(SELECT_COLUMNS + "WHERE id = #{id} AND empresa_id = #{empresaId}")
    Pedido buscarPorIdEEmpresa(@Param("id") Long id, @Param("empresaId") Long empresaId);

    @Select(SELECT_COLUMNS + "WHERE empresa_id = #{empresaId} AND external_order_id = #{externalOrderId}")
    Pedido buscarPorExternalOrderIdEEmpresa(@Param("externalOrderId") String externalOrderId,
                                            @Param("empresaId") Long empresaId);

    @Select(SELECT_COLUMNS + "WHERE empresa_id = #{empresaId} ORDER BY data_pedido DESC")
    List<Pedido> listarPorEmpresa(@Param("empresaId") Long empresaId);

    @Select(SELECT_COLUMNS + "WHERE cnpj_emitente = #{cnpjEmitente} ORDER BY data_pedido DESC")
    List<Pedido> listarPorEmitente(@Param("cnpjEmitente") String cnpjEmitente);

    @Select(SELECT_COLUMNS + "ORDER BY data_pedido DESC")
    List<Pedido> listarTodos();

    @Select(SELECT_COLUMNS + "ORDER BY data_pedido DESC LIMIT #{limit} OFFSET #{offset}")
    List<Pedido> listarTodosPaginado(@Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM pedido")
    long countTodos();

    @Select(SELECT_COLUMNS + "WHERE empresa_id = #{empresaId} ORDER BY data_pedido DESC LIMIT #{limit} OFFSET #{offset}")
    List<Pedido> listarPorEmpresaPaginado(@Param("empresaId") Long empresaId, @Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM pedido WHERE empresa_id = #{empresaId}")
    long countPorEmpresa(@Param("empresaId") Long empresaId);

    @Insert("""
            INSERT INTO pedido (
                empresa_id,
                numero, cnpj_emitente,
                dest_cnpj_cpf, dest_razao_social,
                dest_uf, dest_logradouro, dest_numero, dest_bairro,
                dest_codigo_municipio, dest_municipio, dest_cep,
                natureza_operacao, serie_nfe, status,
                chave_nfe, valor_total, external_order_id, observacao,
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
                #{externalOrderId, jdbcType=VARCHAR}, #{observacao, jdbcType=VARCHAR},
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

    /**
     * Claim atômico de emissão (P0.1) — só transiciona pra EMITINDO se o pedido ainda
     * estiver num status emissível no momento exato do UPDATE. rowsAffected=1 significa
     * que esta chamada venceu a corrida; rowsAffected=0 significa que outra requisição já
     * reivindicou a emissão (ou o status mudou entre a leitura em PedidoEmissaoService e
     * esta tentativa). Os três valores do IN precisam continuar sincronizados com
     * PedidoEmissaoService.STATUS_EMISSIVEIS.
     */
    @Update("""
            UPDATE pedido SET
                status           = 'EMITINDO',
                data_atualizacao = NOW()
            WHERE id = #{id}
            AND status IN ('RASCUNHO', 'REJEITADO', 'ERRO')
            """)
    int reivindicarParaEmissao(@Param("id") Long id);

    /**
     * Persiste a série efetivamente reservada por ReservaFiscalService para esta tentativa de
     * emissão — snapshot pós-reserva (20-07-2026), não mais resolvida/congelada na criação do
     * pedido. Chamado sempre entre o claim de emissão (reivindicarParaEmissao) e a montagem do
     * XML, nunca antes.
     */
    @Update("""
            UPDATE pedido SET
                serie_nfe        = #{serie},
                data_atualizacao = NOW()
            WHERE id = #{id}
            """)
    int atualizarSerieReservada(@Param("id") Long id, @Param("serie") String serie);

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
