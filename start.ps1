$ErrorActionPreference = "Stop"

$appDir = $PSScriptRoot
$apiDir = Join-Path $appDir "fyndkartan"
$apiUrl = "http://127.0.0.1:8700"
$webUrl = "http://127.0.0.1:8501"
$apiProcess = $null
$webProcess = $null

function Test-Endpoint([string] $url) {
    try {
        $null = Invoke-WebRequest -Uri $url -TimeoutSec 2 -UseBasicParsing
        return $true
    } catch {
        return $false
    }
}

if (-not (Test-Path -LiteralPath (Join-Path $apiDir "pyproject.toml")) -or
    -not (Test-Path -LiteralPath (Join-Path $apiDir ".venv\Scripts\uvicorn.exe")) -or
    -not (Test-Path -LiteralPath (Join-Path $appDir ".venv\Scripts\python.exe"))) {
    & (Join-Path $appDir "setup.ps1")
    if ($LASTEXITCODE -ne 0) { throw "Förberedelserna misslyckades." }
}

$apiUvicorn = Join-Path $apiDir ".venv\Scripts\uvicorn.exe"

try {
    if (-not (Test-Endpoint "$apiUrl/openapi.json")) {
        Write-Host "Startar Fyndkartan API..."
        if (Test-Path -LiteralPath $apiUvicorn) {
            $apiArgs = "api.main:app --host 0.0.0.0 --port 8700"
            $apiProcess = Start-Process -FilePath $apiUvicorn -ArgumentList $apiArgs -WorkingDirectory $apiDir -PassThru -WindowStyle Hidden
        } else {
            $uvCommand = Get-Command uv -ErrorAction SilentlyContinue
            if (-not $uvCommand) { throw "Fyndkartan saknar beroenden. Kör setup.ps1 först." }
            $apiArgs = "run --project `"$apiDir`" uvicorn api.main:app --host 0.0.0.0 --port 8700"
            $apiProcess = Start-Process -FilePath $uvCommand.Source -ArgumentList $apiArgs -WorkingDirectory $apiDir -PassThru -WindowStyle Hidden
        }

        $ready = $false
        for ($i = 0; $i -lt 60; $i++) {
            if (Test-Endpoint "$apiUrl/openapi.json") { $ready = $true; break }
            if ($apiProcess.HasExited) { throw "Fyndkartan API avslutades vid uppstart. Kontrollera installationen och försök igen." }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) { throw "Fyndkartan API svarade inte inom 60 sekunder." }
    } else {
        Write-Host "Fyndkartan API körs redan."
    }

    if (-not (Test-Endpoint "$webUrl/_stcore/health")) {
        Write-Host "Startar Erbjudandekartan..."
        $pythonPath = Join-Path $appDir ".venv\Scripts\python.exe"
        if (-not (Test-Path -LiteralPath $pythonPath)) { throw "Webbappens Python-miljö saknas. Kör setup.ps1 först." }
        $appArgs = "-m streamlit run `"$(Join-Path $appDir 'app.py')`" --server.headless true --server.port 8501"
        $webProcess = Start-Process -FilePath $pythonPath -ArgumentList $appArgs -WorkingDirectory $appDir -PassThru -WindowStyle Hidden

        $ready = $false
        for ($i = 0; $i -lt 30; $i++) {
            if (Test-Endpoint "$webUrl/_stcore/health") { $ready = $true; break }
            if ($webProcess.HasExited) { throw "Webbappen avslutades vid uppstart. Kontrollera Python-paketen och försök igen." }
            Start-Sleep -Seconds 1
        }
        if (-not $ready) { throw "Webbappen svarade inte inom 30 sekunder." }
    } else {
        Write-Host "Webbappen körs redan."
    }

    Start-Process $webUrl
    Write-Host "Båda tjänsterna körs. Tryck Ctrl+C här när du vill stänga tjänsterna som startades av det här skriptet."
    while ($true) { Start-Sleep -Seconds 2 }
} finally {
    foreach ($process in @($webProcess, $apiProcess)) {
        if ($null -ne $process -and -not $process.HasExited) {
            & taskkill.exe /PID $process.Id /T /F | Out-Null
        }
    }
}
