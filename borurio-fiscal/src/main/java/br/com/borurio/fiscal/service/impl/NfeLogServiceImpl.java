package br.com.borurio.fiscal.service.impl;

import br.com.borurio.fiscal.entity.NfeLog;
import br.com.borurio.fiscal.mapper.NfeLogMapper;
import br.com.borurio.fiscal.service.NfeLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Implementação do serviço responsável pela auditoria de eventos fiscais da NF-e.
 *
 * Responsável por registrar e consultar eventos fiscais processados
 * pela aplicação (autorização, rejeição, cancelamento, etc.).
 *
 * Padrões aplicados:
 * - @Service: classe gerenciada pelo Spring.
 * - @Transactional: controle de transações para operações de escrita.
 * - @RequiredArgsConstructor: injeção automática via construtor (final fields).
 *
 * Conformidade DevSecOps:
 * - Nenhum dado sensível persistido (apenas metadados fiscais).
 * - Auditoria imutável e rastreável.
 * - Operações seguras e idempotentes.
 */
@Service
@RequiredArgsConstructor
public class NfeLogServiceImpl implements NfeLogService {

    private final NfeLogMapper nfeLogMapper;

    /**
     * Insere um novo registro de log no banco de dados.
     * Se a data do evento não for informada, o horário atual será definido automaticamente.
     *
     * @param log objeto contendo os dados do evento fiscal.
     */
    @Override
    @Transactional
    public void salvar(NfeLog log) {
        if (log.getDataEvento() == null) {
            log.setDataEvento(LocalDateTime.now());
        }
        nfeLogMapper.insertLog(log);
    }

    /**
     * Registra um evento fiscal diretamente, criando o objeto de log
     * e persistindo-o na base de auditoria.
     *
     * Utilizado em fluxos automáticos do serviço fiscal (autorização NF-e,
     * rejeições SEFAZ, cancelamentos, etc.).
     *
     * @param chaveNfe   chave da NF-e (44 dígitos).
     * @param tipoEvento tipo do evento (AUTORIZADA, REJEITADA, CANCELADA, etc.).
     * @param descricao  descrição detalhada do evento.
     * @param usuario    responsável pelo evento.
     */
    @Override
    @Transactional
    public void registrarEvento(String chaveNfe, String tipoEvento, String descricao, String usuario) {
        NfeLog log = NfeLog.builder()
                .chaveNfe(chaveNfe)
                .tipoEvento(tipoEvento)
                .descricao(descricao)
                .dataEvento(LocalDateTime.now())
                .usuario(usuario)
                .build();
        nfeLogMapper.insertLog(log);
    }

    /**
     * Retorna a lista completa de registros de auditoria de NF-e.
     *
     * @return lista completa de logs fiscais.
     */
    @Override
    public List<NfeLog> listarTodos() {
        return nfeLogMapper.findAll();
    }

    /**
     * Busca todos os registros vinculados a uma chave de NF-e específica.
     *
     * @param chaveNfe chave de acesso (44 dígitos) da NF-e.
     * @return lista de eventos associados à chave informada.
     */
    @Override
    public List<NfeLog> buscarPorChave(String chaveNfe) {
        return nfeLogMapper.findByChave(chaveNfe);
    }
}
