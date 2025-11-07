package br.com.borurio.fiscal.service;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.fiscal.entity.Ncm;
import java.util.List;

/**
 * ________________________________________________________________________________
 * Serviço responsável por operações de leitura e sincronização da Tabela NCM.
 * ________________________________________________________________________________
 * Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
 * Data: 07/11/2025
 * ________________________________________________________________________________
 */
public interface NcmService {

    /**
     * Retorna todos os registros ativos da tabela NCM.
     */
    List<Ncm> listarTodos();

    /**
     * Busca um registro específico pelo código NCM.
     */
    Ncm buscarPorCodigo(String codigo);

    /**
     * Sincroniza a tabela NCM a partir do arquivo CSV oficial.
     */
    Result sincronizarTabela();
}
