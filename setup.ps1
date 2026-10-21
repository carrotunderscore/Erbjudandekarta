$ErrorActionPreference = "Stop"

$appDir = $PSScriptRoot
$apiDir = Join-Path $appDir "fyndkartan"
$archivePath = Join-Path $appDir "fyndkartan-source.zip"

if (-not (Test-Path -LiteralPath $apiDir)) {
    Write-Host "Hämtar Fyndkartan från GitHub..."
    try {
        Invoke-WebRequest -Uri "https://github.com/Armandur/fyndkartan/archive/refs/heads/main.zip" -OutFile $archivePath
    } catch {
        throw "Kunde inte hämta Fyndkartan automatiskt. Ladda ned ZIP-filen från https://github.com/Armandur/fyndkartan/archive/refs/heads/main.zip, packa upp den bredvid appen som mappen 'fyndkartan' och kör setup.ps1 igen."
    }
    Expand-Archive -LiteralPath $archivePath -DestinationPath $appDir
    Move-Item -LiteralPath (Join-Path $appDir "fyndkartan-main") -Destination $apiDir
    Remove-Item -LiteralPath $archivePath
}

$pythonCommand = Get-Command python -ErrorAction SilentlyContinue
if (-not $pythonCommand) { $pythonCommand = Get-Command py -ErrorAction SilentlyContinue }
if (-not $pythonCommand) { throw "Python saknas. Installera Python 3.11 eller senare och kör setup.ps1 igen." }

$apiUvicorn = Join-Path $apiDir ".venv\Scripts\uvicorn.exe"
if (-not (Test-Path -LiteralPath $apiUvicorn)) {
    $uvCommand = Get-Command uv -ErrorAction SilentlyContinue
    if (-not $uvCommand) {
        Write-Host "Installerar uv i ditt Python-användarkonto..."
        & $pythonCommand.Source -m pip install --user uv
        if ($LASTEXITCODE -ne 0) { throw "Kunde inte installera uv med pip." }
        $userScripts = & $pythonCommand.Source -c "import sysconfig; print(sysconfig.get_path('scripts', scheme='nt_user'))"
        $uvPath = Join-Path $userScripts "uv.exe"
        if (-not (Test-Path -LiteralPath $uvPath)) { throw "uv installerades inte på den förväntade sökvägen: $uvPath" }
    } else {
        $uvPath = $uvCommand.Source
    }

    Write-Host "Installerar Fyndkartans paket..."
    & $uvPath sync --project $apiDir
    if ($LASTEXITCODE -ne 0) { throw "uv sync misslyckades. Kontrollera nätverksanslutningen och försök igen." }
}

Write-Host "Installerar webbappens paket..."
$webVenv = Join-Path $appDir ".venv"
if (-not (Test-Path -LiteralPath (Join-Path $webVenv "Scripts\python.exe"))) {
    & $pythonCommand.Source -m venv $webVenv
    if ($LASTEXITCODE -ne 0) { throw "Kunde inte skapa webbappens virtuella Python-miljö." }
}
& (Join-Path $webVenv "Scripts\python.exe") -m pip install -r (Join-Path $appDir "requirements.txt")
if ($LASTEXITCODE -ne 0) { throw "Kunde inte installera webbappens Python-paket." }

Write-Host "Klart. Starta båda tjänsterna med start.ps1."
