package br.com.borurio.fiscal.mapper;

import br.com.borurio.fiscal.entity.Ncm;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface NcmMapper {

    @Select("SELECT id, codigo, descricao, ativo FROM ncm WHERE ativo = TRUE ORDER BY codigo")
    List<Ncm> listarNcmAtivos();

    @Select("SELECT id, codigo, descricao, ativo FROM ncm WHERE codigo = #{codigo}")
    Ncm buscarPorCodigo(String codigo);

    @Insert("""
        INSERT INTO ncm (codigo, descricao)
        VALUES (#{codigo}, #{descricao})
        ON DUPLICATE KEY UPDATE descricao = VALUES(descricao)
    """)
    void upsertNcm(Ncm ncm);
}
