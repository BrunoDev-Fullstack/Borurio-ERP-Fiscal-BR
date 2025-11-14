package br.com.borurio.app.mapper;

import br.com.borurio.app.entity.Cliente;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ClienteMapper {

    @Select("SELECT id, nome, email, telefone, estado FROM cliente")
    List<Cliente> listarTodos();

    @Select("SELECT id, nome, email, telefone, estado FROM cliente WHERE id = #{id}")
    Cliente buscarPorId(Long id);

    @Insert("INSERT INTO cliente (nome, email, telefone, estado) VALUES (#{nome}, #{email}, #{telefone}, #{estado})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int inserir(Cliente cliente);

    @Update("UPDATE cliente SET nome=#{nome}, email=#{email}, telefone=#{telefone}, estado=#{estado} WHERE id=#{id}")
    int atualizar(Cliente cliente);

    @Update("UPDATE cliente SET estado = 0 WHERE id=#{id}")
    int desativar(Long id);
}
