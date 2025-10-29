###################################################################################################
# BORURIO ERP FISCAL BR – IMPORTADOR NCM OFICIAL (SISCOMEX)
# ------------------------------------------------------------------------------------------------
# Conversão e carga da Tabela NCM oficial (XLSX → CSV → MySQL)
# Autor: Bruno Ribeiro — Desenvolvedor Fullstack / DevSecOps
# Data: 24/10/2025
# Módulo: borurio-fiscal
# Banco: borurio_fiscal_dev
# ------------------------------------------------------------------------------------------------
# Descrição:
#  - Converte a planilha oficial do Portal Siscomex (Tabela NCM Vigente)
#  - Exporta CSV em UTF-8 puro (sem BOM)
#  - Copia o arquivo para o container MySQL (borurio-mysql-dev)
#  - Realiza a carga na tabela "ncm"
###################################################################################################

Write-Host "==== [BORURIO ERP FISCAL BR] INICIANDO IMPORTAÇÃO NCM ====" -ForegroundColor Cyan

# 1. Caminhos
$xlsxPath = "C:\Projetos\borurio-erp-br\docs\data\ncm.xlsx"
$csvPath  = "C:\Projetos\borurio-erp-br\docs\data\ncm.csv"

# 2. Converter XLSX → CSV (UTF-8 puro, sem depender do Excel COM)
Write-Host "[1/4] Convertendo XLSX para CSV (UTF-8)..." -ForegroundColor Yellow
$data = Import-Excel -Path $xlsxPath
$data | Export-Csv -Path $csvPath -Delimiter ';' -NoTypeInformation -Encoding UTF8
Write-Host "[OK] Arquivo CSV exportado em UTF-8 com sucesso." -ForegroundColor Green

# 3. Copiar CSV para o container MySQL
Write-Host "[2/4] Copiando CSV para container MySQL (borurio-mysql-dev)..." -ForegroundColor Yellow
docker cp $csvPath borurio-mysql-dev:/tmp/ncm.csv
Write-Host "[OK] Arquivo copiado para /tmp/ncm.csv" -ForegroundColor Green

# 4. Importar dados no MySQL
Write-Host "[3/4] Importando dados para tabela 'ncm' no banco borurio_fiscal_dev..." -ForegroundColor Yellow
docker exec -i borurio-mysql-dev mysql --local-infile=1 -u borurio -pH4ck3r123 borurio_fiscal_dev -e "
LOAD DATA LOCAL INFILE '/tmp/ncm.csv'
INTO TABLE ncm
CHARACTER SET utf8mb4
FIELDS TERMINATED BY ';'
LINES TERMINATED BY '\n'
IGNORE 1 ROWS
(codigo, descricao, unidade, @inicio, @fim)
SET vigencia_inicio = IF(@inicio <> '' AND @inicio IS NOT NULL, STR_TO_DATE(@inicio, '%d/%m/%Y'), NULL),
    vigencia_fim = IF(@fim <> '' AND @fim IS NOT NULL, STR_TO_DATE(@fim, '%d/%m/%Y'), NULL);
"
Write-Host "[OK] Dados carregados na tabela 'ncm' com sucesso." -ForegroundColor Green

# 5. Verificar total de registros
Write-Host "[4/4] Conferindo total de registros importados..." -ForegroundColor Yellow
docker exec -i borurio-mysql-dev mysql -u borurio -pH4ck3r123 borurio_fiscal_dev -e "SELECT COUNT(*) AS total_registros FROM ncm;"
Write-Host "==== IMPORTAÇÃO CONCLUÍDA COM SUCESSO ====" -ForegroundColor Cyan
