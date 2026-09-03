package br.com.borurio.fiscal.entity;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * Log de sincronização de tabelas NCM.
 * Armazena informações sobre importações e atualizações executadas via Flyway ou scripts.
 */
@Data
@Builder
public class NcmSyncLog {

    private Long id;
    private String arquivo;                  // Nome do arquivo processado (ex: Tabela_NCM_2025.csv)
    private Integer registrosImportados;     // Quantidade de registros importados
    private LocalDateTime dataExecucao;      // Data/hora da execução
    private String usuarioResponsavel;       // Responsável pela importação
}
