package br.com.borurio.web.config;

import br.com.borurio.app.entity.DbUser;
import br.com.borurio.app.entity.Empresa;
import br.com.borurio.app.mapper.DbUserMapper;
import br.com.borurio.app.mapper.EmpresaMapper;
import br.com.borurio.fiscal.config.EmitenteProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;

@Slf4j
@Configuration
public class StartupListener {

    @Value("${spring.profiles.active:default}")
    private String activeProfile;

    @Value("${spring.application.name:borurio-web}")
    private String appName;

    @Value("${server.port:8080}")
    private String serverPort;

    private final EmitenteProperties emitente;
    private final EmpresaMapper empresaMapper;
    private final DbUserMapper dbUserMapper;
    private final PasswordEncoder passwordEncoder;

    public StartupListener(EmitenteProperties emitente,
                           EmpresaMapper empresaMapper,
                           DbUserMapper dbUserMapper,
                           PasswordEncoder passwordEncoder) {
        this.emitente        = emitente;
        this.empresaMapper   = empresaMapper;
        this.dbUserMapper    = dbUserMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @PostConstruct
    public void onStartup() {
        seedEmpresaDefault();
        logBanner();
    }

    // -------------------------------------------------------------------------
    // Seed + backfill — garante que empresa padrão existe e dados legados têm FK
    // -------------------------------------------------------------------------

    private void seedEmpresaDefault() {
        try {
            String cnpj = emitente.getCnpj() != null
                    ? emitente.getCnpj().replaceAll("\\D", "")
                    : null;

            if (cnpj == null || cnpj.isBlank()) {
                log.warn("[Startup] fiscal.emitente.cnpj não configurado — seed de empresa ignorado.");
                return;
            }

            Empresa existente = empresaMapper.buscarPorCnpj(cnpj);
            if (existente != null) {
                log.info("[Startup] Empresa default já existe | id={} | cnpj={}", existente.getId(), cnpj);
                backfill(existente.getId());
                seedAdminUser(existente.getId());
                return;
            }

            Empresa nova = new Empresa();
            nova.setCnpj(cnpj);
            nova.setRazaoSocial(emitente.getRazaoSocial() != null ? emitente.getRazaoSocial() : "EMPRESA PADRAO");
            nova.setNomeFantasia(emitente.getNomeFantasia());
            nova.setIe(emitente.getIe());
            nova.setCrt(emitente.getCrt() != null ? emitente.getCrt() : "1");
            nova.setUf(emitente.getUf() != null ? emitente.getUf() : "SP");
            nova.setLogradouro(emitente.getLogradouro());
            nova.setNumero(emitente.getNumero());
            nova.setBairro(emitente.getBairro());
            nova.setMunicipio(emitente.getMunicipio());
            nova.setCodigoMunicipio(emitente.getCodigoMunicipio());
            nova.setCep(emitente.getCep() != null ? emitente.getCep().replaceAll("\\D", "") : null);
            nova.setSerieNfePadrao("1");
            nova.setAtivo(true);
            nova.setControleEstoqueAtivo(true);

            empresaMapper.inserir(nova);
            log.info("[Startup] Empresa default criada | id={} | cnpj={}", nova.getId(), cnpj);

            backfill(nova.getId());
            seedAdminUser(nova.getId());

        } catch (Exception e) {
            log.error("[Startup] Falha ao seed empresa default | erro={}", e.getMessage(), e);
        }
    }

    private void seedAdminUser(Long empresaId) {
        try {
            if ("prd".equalsIgnoreCase(activeProfile)) {
                log.info("[Startup] Seed de admin desabilitado em perfil prd.");
                return;
            }
            if (dbUserMapper.count() > 0) return;
            DbUser admin = new DbUser();
            admin.setEmpresaId(empresaId);
            admin.setNome("Administrador");
            admin.setEmail("admin");
            admin.setSenha(passwordEncoder.encode("admin123"));
            admin.setRole("ADMIN");
            admin.setAtivo(true);
            dbUserMapper.insert(admin);
            log.info("[Startup] Usuário admin criado | id={} | empresaId={}", admin.getId(), empresaId);
        } catch (Exception e) {
            log.warn("[Startup] Falha ao criar admin user | erro={}", e.getMessage());
        }
    }

    private void backfill(Long empresaId) {
        try {
            int users    = empresaMapper.backfillDbUser(empresaId);
            int produtos = empresaMapper.backfillProduto(empresaId);
            int pedidos  = empresaMapper.backfillPedido(empresaId);
            if (users + produtos + pedidos > 0) {
                log.info("[Startup] Backfill empresa_id={} | users={} | produtos={} | pedidos={}",
                        empresaId, users, produtos, pedidos);
            }
        } catch (Exception e) {
            log.warn("[Startup] Falha no backfill de empresa_id | erro={}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------

    private void logBanner() {
        String externalPort = System.getenv("SERVER_PORT_EXTERNAL");
        String effectivePort = (externalPort != null && !externalPort.isBlank())
                ? externalPort
                : serverPort;

        log.info("""
                =====================================================================
                SISTEMA ERP FISCAL BORURIO BRASIL INICIADO
                ---------------------------------------------------------------------
                ENDPOINTS DISPONÍVEIS:
                    - Swagger UI:      http://localhost:{}/swagger-ui/index.html
                    - OpenAPI JSON:    http://localhost:{}/v3/api-docs
                    - Actuator Health: http://localhost:{}/actuator/health
                ---------------------------------------------------------------------
                PERFIL ATIVO: {} | DATA: {}
                MÓDULOS: core | app | fiscal | web | multiempresa
                =====================================================================
                """,
                effectivePort, effectivePort, effectivePort,
                activeProfile, LocalDateTime.now()
        );
    }
}
