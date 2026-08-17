-- V038: Persistencia do ciclo de substituicao SVC (Fase 1 -- so persistencia/ciclo, sem
-- transporte/XML real, ver Checkpoint_Interno_Semanal_2026-08-14.md secao 3). Uma NORMAL
-- transmitida sem retorno pode ser substituida por uma nova emissao em contingencia (SVC-AN/
-- SVC-RS): a NORMAL nunca e apagada nem reescrita por esta substituicao -- emissao_origem_id na
-- linha filha e a prova duravel de que ela foi substituida, sobrevivendo inclusive depois que o
-- ciclo ativo (a propria filha) tambem terminar e o gate da serie (nfe_sequencia.emissao_ativa_id)
-- voltar a NULL.

ALTER TABLE nfe_emissao
    ADD COLUMN tp_emis VARCHAR(1) NOT NULL DEFAULT '1'
        COMMENT 'campo fiscal tpEmis da NF-e -- tabela MOC: 1=Normal, 6=SVC-AN, 7=SVC-RS (EPEC=4 fora do escopo desta fase)'
        AFTER modelo,
    ADD COLUMN autorizador_destino VARCHAR(10) NOT NULL DEFAULT 'NORMAL'
        COMMENT 'autoridade que detem o ciclo agora -- NORMAL/SVC_AN/SVC_RS, sempre derivado de tp_emis, nunca setado independente; distinto da rota por UF da Fase 0 (SefazRotaResolver), que resolve o ENDPOINT dentro do modo NORMAL'
        AFTER tp_emis,
    ADD COLUMN emissao_origem_id BIGINT NULL
        COMMENT 'FK logica para nfe_emissao.id da NORMAL substituida (Caminho B) -- prova duravel de substituicao, nunca aponta pra si mesma nem muda depois de gravada'
        AFTER autorizador_destino,
    ADD COLUMN dh_cont VARCHAR(30) NULL
        COMMENT 'dhCont da NF-e (XSD TDateTimeUTC, mesmo padrao ja usado por dh_reg_evento_resultado em V037) -- string ISO-8601 com offset, formatada uma unica vez no momento real da abertura de contingencia e persistida literalmente; offset nunca reconstruido por config global (motor e multi-UF desde a Fase 0)'
        AFTER emissao_origem_id,
    ADD COLUMN x_just_contingencia VARCHAR(256) NULL
        COMMENT 'xJust da NF-e -- justificativa da entrada em contingencia, 15-256 chars apos trim (mesma regra minima ja usada em cancelamento)'
        AFTER dh_cont,
    ADD CONSTRAINT uk_nfe_emissao_origem UNIQUE (emissao_origem_id);
