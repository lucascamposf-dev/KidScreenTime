# Gera uma versão nova do app para publicar no GitHub Releases.
#
# Uso (no PowerShell, dentro da pasta do projeto):
#   .\publicar.ps1                 -> versão automática (ex.: 0.4)
#   .\publicar.ps1 -Versao 1.0     -> escolhe o nome da versão
#
# O que faz: aumenta o VERSION_CODE em version.properties, gera o APK assinado
# e copia para a pasta dist\. Depois é só criar a release no GitHub (instruções no final).

param([string]$Versao = "")

$ErrorActionPreference = "Stop"
Set-Location $PSScriptRoot

$props = @{}
Get-Content "version.properties" | Where-Object { $_ -match "^\s*([A-Z_]+)=(.*)$" } | ForEach-Object {
    $props[$Matches[1]] = $Matches[2].Trim()
}
$code = [int]$props["VERSION_CODE"] + 1
if ($Versao -eq "") { $Versao = "0.$code" }

@"
# Versao do app. O publicar.ps1 aumenta VERSION_CODE sozinho a cada publicacao.
# O app so oferece a atualizacao se o VERSION_CODE publicado for MAIOR que o instalado.
VERSION_CODE=$code
VERSION_NAME=$Versao
"@ | Set-Content -Encoding ASCII "version.properties"

Write-Host "Gerando versao $Versao (codigo $code)..." -ForegroundColor Cyan
& .\gradlew.bat assembleRelease
if ($LASTEXITCODE -ne 0) {
    Write-Host "Falhou ao gerar o APK. Nada foi publicado." -ForegroundColor Red
    exit 1
}

New-Item -ItemType Directory -Force "dist" | Out-Null
$apk = "dist\tempo-de-tela-v$code.apk"
Copy-Item "app\build\outputs\apk\release\app-release.apk" $apk -Force

Write-Host ""
Write-Host "Pronto! APK: $((Resolve-Path $apk).Path)" -ForegroundColor Green
Write-Host ""
Write-Host "Agora publique no GitHub:" -ForegroundColor Yellow
Write-Host "  1. Abra o repositorio no GitHub > Releases > 'Draft a new release'"
Write-Host "  2. Tag (Choose a tag > criar):  v$code      <- tem que ser exatamente isso"
Write-Host "  3. Titulo (Release title):     $Versao"
Write-Host "  4. Descricao: o que mudou (aparece para os pais no app)"
Write-Host "  5. Arraste o arquivo $apk para 'Attach binaries'"
Write-Host "  6. Clique em 'Publish release'"
Write-Host ""
Write-Host "Na proxima vez que abrir a Area dos pais, o celular oferece a atualizacao."
