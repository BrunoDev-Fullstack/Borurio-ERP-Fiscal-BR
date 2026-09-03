package br.com.borurio.app.mapper;

import br.com.borurio.app.entity.OmsCompanyCertificate;
import org.apache.ibatis.annotations.*;

import java.time.LocalDateTime;

public interface OmsCompanyCertificateMapper {

    String SELECT_COLUMNS = """
            SELECT id,
                   auth_id        AS authId,
                   cnpj,
                   empresa_id     AS empresaId,
                   thumbprint,
                   cert_pfx_enc   AS certPfxEnc,
                   cert_senha_enc AS certSenhaEnc,
                   key_version    AS keyVersion,
                   not_before     AS notBefore,
                   not_after      AS notAfter,
                   ativo,
                   cadastrado_em  AS cadastradoEm,
                   substituido_em AS substituidoEm
            FROM oms_company_certificate
            """;

    /**
     * Retorna o certificado ativo de uma autorização por CNPJ específico.
     * Usar este método em todos os fluxos multi-CNPJ (V028+).
     */
    @Select(SELECT_COLUMNS + "WHERE auth_id = #{authId} AND cnpj = #{cnpj} AND ativo = 1")
    OmsCompanyCertificate buscarAtivoPorAuthIdECnpj(@Param("authId") Long authId,
                                                     @Param("cnpj")   String cnpj);

    /**
     * Retorna qualquer certificado ativo de uma autorização.
     * Uso interno legado — ambíguo quando há múltiplos CNPJs ativos por auth.
     * Preferir buscarAtivoPorAuthIdECnpj para fluxos OMS multi-CNPJ.
     */
    @Select(SELECT_COLUMNS + "WHERE auth_id = #{authId} AND ativo = 1 LIMIT 1")
    OmsCompanyCertificate buscarAtivoPorAuthId(@Param("authId") Long authId);

    @Insert("""
            INSERT INTO oms_company_certificate
                (auth_id, cnpj, empresa_id, thumbprint, cert_pfx_enc, cert_senha_enc,
                 key_version, not_before, not_after, ativo)
            VALUES
                (#{authId}, #{cnpj}, #{empresaId}, #{thumbprint},
                 #{certPfxEnc, jdbcType=BLOB}, #{certSenhaEnc},
                 #{keyVersion}, #{notBefore}, #{notAfter}, #{ativo})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserir(OmsCompanyCertificate cert);

    /**
     * Inativa o certificado ativo de uma autorização para um CNPJ específico.
     * Chamado dentro da mesma transação que insere o certificado substituto.
     */
    @Update("""
            UPDATE oms_company_certificate
               SET ativo          = 0,
                   substituido_em = #{substituidoEm}
             WHERE auth_id = #{authId}
               AND cnpj    = #{cnpj}
               AND ativo   = 1
            """)
    int desativarCertsAtivos(@Param("authId")        Long authId,
                              @Param("cnpj")          String cnpj,
                              @Param("substituidoEm") LocalDateTime substituidoEm);
}
