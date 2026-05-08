package br.com.borurio.web.controller.app;

import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.service.EmpresaService;
import br.com.borurio.core.mvc.api.Result;
import br.com.borurio.core.mvc.api.ResultUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/app/empresas")
@Tag(name = "Empresas", description = "Gestão de empresas emitentes (multi-tenancy)")
public class EmpresaController {

    private final EmpresaService empresaService;

    public EmpresaController(EmpresaService empresaService) {
        this.empresaService = empresaService;
    }

    @GetMapping
    @Operation(summary = "Lista todas as empresas cadastradas")
    public Result<List<Empresa>> listar() {
        return ResultUtil.success(empresaService.listarTodas());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Busca empresa por ID")
    public Result<Empresa> buscarPorId(@PathVariable Long id) {
        try {
            return ResultUtil.success(empresaService.buscarPorId(id));
        } catch (IllegalArgumentException e) {
            return ResultUtil.error(e.getMessage());
        }
    }

    @GetMapping("/cnpj/{cnpj}")
    @Operation(summary = "Busca empresa por CNPJ")
    public Result<Empresa> buscarPorCnpj(@PathVariable String cnpj) {
        try {
            return ResultUtil.success(empresaService.buscarPorCnpj(cnpj));
        } catch (IllegalArgumentException e) {
            return ResultUtil.error(e.getMessage());
        }
    }

    @PostMapping
    @Operation(summary = "Cadastra nova empresa emitente")
    public Result<?> salvar(@RequestBody Empresa empresa) {
        try {
            return ResultUtil.success(empresaService.salvar(empresa));
        } catch (IllegalArgumentException e) {
            return ResultUtil.error(e.getMessage());
        }
    }

    @PutMapping("/{id}")
    @Operation(summary = "Atualiza dados da empresa")
    public Result<?> atualizar(@PathVariable Long id, @RequestBody Empresa empresa) {
        try {
            return ResultUtil.success(empresaService.atualizar(id, empresa));
        } catch (IllegalArgumentException e) {
            return ResultUtil.error(e.getMessage());
        }
    }
}
