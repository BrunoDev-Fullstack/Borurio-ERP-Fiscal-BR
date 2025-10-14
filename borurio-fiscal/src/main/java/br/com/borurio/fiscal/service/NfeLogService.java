package br.com.borurio.fiscal.service;

import br.com.borurio.fiscal.entity.NfeLog;
import java.util.List;

/**
 * Serviço responsável pelas operações de auditoria fiscal de NF-e.
 * Define os contratos para gravação e consulta de eventos fiscais
 * processados pelo módulo SEFAZ (mock ou produção).
 *
 * Boas práticas DevSecOps aplicadas:
 * - Segregação de responsabilidades (Controller → Service → Mapper)
 * - Nenhum dado sensível é persistido (apenas metadados fiscais)
 * - Log de auditoria imutável e rastreável
 */
public interface NfeLogService {

    /**
     * Insere um novo registro de auditoria fiscal no banco de dados.
     *
     * @param log objeto NfeLog contendo as informações do evento.
     */
    void salvar(NfeLog log);

    /**
     * Registra um evento fiscal diretamente (atalho utilizado por serviços
     * como NfeServiceImpl durante a autorização ou rejeição de NF-e).
     *
     * @param chaveNfe   chave de acesso da NF-e (44 dígitos)
     * @param tipoEvento tipo do evento (AUTORIZADA, REJEITADA, CANCELADA etc.)
     * @param descricao  descrição detalhada do evento
     * @param usuario    responsável que originou o evento
     */
    void registrarEvento(String chaveNfe, String tipoEvento, String descricao, String usuario);

    /**
     * Retorna a lista completa de logs fiscais registrados.
     *
     * @return lista de NfeLog.
     */
    List<NfeLog> listarTodos();

    /**
     * Busca os logs vinculados a uma chave específica de NF-e.
     *
     * @param chaveNfe chave de acesso da NF-e (44 dígitos).
     * @return lista de registros associados à chave.
     */
    List<NfeLog> buscarPorChave(String chaveNfe);
}
