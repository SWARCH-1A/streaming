[CmdletBinding()]
param(
    [ValidateSet('Verificar', 'Calentar', 'Medir')]
    [string]$Modo = 'Verificar',

    [string]$BaseUrl,

    [ValidateRange(1, 2000)]
    [int]$Usuarios = 1
)

$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $false

if ($Modo -eq 'Medir' -and -not $PSBoundParameters.ContainsKey('Usuarios')) {
    throw 'Indica -Usuarios entre 1 y 2000 para Medir.'
}
if ($Modo -ne 'Medir' -and $Usuarios -ne 1) {
    throw 'Verificar y Calentar usan un solo usuario virtual. Elige Medir para cambiar el número.'
}

if ([string]::IsNullOrWhiteSpace($BaseUrl)) {
    $BaseUrl = Read-Host 'Origen HTTPS que entregó el Grupo 1'
}
$parsedUri = $null
if (
    [string]::IsNullOrWhiteSpace($BaseUrl) -or
    -not [Uri]::TryCreate($BaseUrl.Trim(), [UriKind]::Absolute, [ref]$parsedUri) -or
    $parsedUri.Scheme -ne 'https' -or
    $parsedUri.AbsolutePath -ne '/' -or
    $parsedUri.Query -or $parsedUri.Fragment -or $parsedUri.UserInfo
) {
    throw 'Usa el origen HTTPS del Grupo 1, por ejemplo https://192.168.1.50:8443, sin ruta ni credenciales.'
}
$origin = $parsedUri.GetLeftPart([UriPartial]::Authority)

$k6Path = Join-Path $PSScriptRoot 'programas\k6.exe'
if (-not (Test-Path -LiteralPath $k6Path -PathType Leaf)) {
    $k6Path = Join-Path $env:ProgramFiles 'k6\k6.exe'
}
if (-not (Test-Path -LiteralPath $k6Path -PathType Leaf)) {
    $command = Get-Command 'k6.exe' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $command) {
        throw 'No se encontró k6.exe. Instala k6 y abre PowerShell de nuevo, o colócalo en programas\k6.exe.'
    }
    $k6Path = $command.Source
}

$scriptPath = Join-Path $PSScriptRoot 'performance_test.js'
if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) {
    throw 'Falta performance_test.js junto a este iniciador.'
}
$resultsPath = Join-Path $PSScriptRoot 'resultados'
if (-not (Test-Path -LiteralPath $resultsPath -PathType Container)) {
    $null = New-Item -ItemType Directory -Path $resultsPath
}

$modeName = $Modo.ToLowerInvariant()
$timestamp = [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssfffZ', [Globalization.CultureInfo]::InvariantCulture)
$suffix = [Guid]::NewGuid().ToString('N').Substring(0, 8)
$runId = '{0}_{1}_{2:D4}vu_{3}' -f $timestamp, $modeName, $Usuarios, $suffix
$arguments = @(
    'run'
    '--no-usage-report'
    '--insecure-skip-tls-verify=false'
    '--no-thresholds=false'
    '--new-machine-readable-summary=false'
    '--summary-mode=compact'
    '--summary-trend-stats=avg,p(95)'
    '-e', "BASE_URL=$origin"
    '-e', "MODO=$modeName"
    '-e', "VUS=$Usuarios"
    '-e', "RUN_ID=$runId"
    $scriptPath
)

Write-Host "Destino: $origin/api/screens/channels/lab6_canal"
Write-Host "Corrida: $runId"
Push-Location -LiteralPath $PSScriptRoot
try {
    & $k6Path @arguments
    $exitCode = $LASTEXITCODE
} finally {
    Pop-Location
}
if ($exitCode -ne 0) {
    Write-Warning "k6 terminó con código $exitCode. Revisa el resumen y los mensajes de la consola."
}

$missing = @()
foreach ($extension in 'csv', 'json') {
    $report = Join-Path $resultsPath "$runId.$extension"
    if (Test-Path -LiteralPath $report -PathType Leaf) {
        Write-Host "Resultado: $report"
    } else {
        $missing += "$runId.$extension"
    }
}
if ($missing.Count -gt 0) {
    Write-Warning ('No se generaron estos resultados: ' + ($missing -join ', '))
    if ($exitCode -eq 0) {
        exit 1
    }
}
exit $exitCode
