package br.com.borurio.fiscal.service.impl;

import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import br.com.borurio.fiscal.entity.Ncm;
import br.com.borurio.fiscal.mapper.NcmMapper;
import br.com.borurio.fiscal.service.NcmService;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Implementação dos serviços relacionados à Tabela NCM.
 */
@Service
public class NcmServiceImpl implements NcmService {

    private final NcmMapper ncmMapper;

    public NcmServiceImpl(NcmMapper ncmMapper) {
        this.ncmMapper = ncmMapper;
    }

    @Override
    public List<Ncm> listarTodos() {
        return ncmMapper.listarNcmAtivos();
    }

    @Override
    public Ncm buscarPorCodigo(String codigo) {
        return ncmMapper.buscarPorCodigo(codigo);
    }

    @Override
    public Result sincronizarTabela() {
        try {
            Path csvPath = Paths.get("docs/data/ncm_oficial_20251107.csv");
            if (!Files.exists(csvPath)) {
                return ResultUtil.error("Arquivo NCM oficial não encontrado: " + csvPath.toAbsolutePath());
            }

            List<String> linhas = Files.readAllLines(csvPath);
            if (linhas.size() <= 1) {
                return ResultUtil.error("Arquivo NCM vazio ou corrompido.");
            }

            int count = 0;
            for (int i = 1; i < linhas.size(); i++) {
                String[] colunas = linhas.get(i).split(";");
                if (colunas.length >= 2) {
                    String codigo = colunas[0].trim();
                    String descricao = colunas[1].trim();

                    Ncm ncm = new Ncm();
                    ncm.setCodigo(codigo);
                    ncm.setDescricao(descricao);

                    ncmMapper.upsertNcm(ncm);
                    count++;
                }
            }

            return ResultUtil.ok("Sincronização NCM concluída com sucesso. Registros processados: " + count);

        } catch (Exception e) {
            return ResultUtil.error("Erro durante sincronização da Tabela NCM: " + e.getMessage());
        }
    }
}
