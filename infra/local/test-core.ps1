[CmdletBinding()]
param(
    [ValidateSet('Unit', 'Integration')]
    [string]$Mode = 'Integration'
)

$ErrorActionPreference = 'Stop'
$repoPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$wrapperPath = Join-Path $repoPath 'services/core/mvnw'

if (-not (Test-Path -LiteralPath $wrapperPath -PathType Leaf)) {
    throw "No se encontro el Maven Wrapper de Core: $wrapperPath"
}
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw 'Docker no esta disponible. Inicia Docker Desktop con contenedores Linux.'
}

$dockerArguments = @(
    'run', '--rm',
    '--mount', "type=bind,source=$repoPath,target=/workspace",
    '--mount', 'type=volume,source=streaming-core-maven-cache,target=/root/.m2',
    '--workdir', '/workspace/services/core'
)

if ($Mode -eq 'Integration') {
    $dockerArguments += @(
        '--mount', 'type=bind,source=/var/run/docker.sock,target=/var/run/docker.sock',
        '--env', 'DOCKER_HOST=unix:///var/run/docker.sock',
        '--env', 'TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal'
    )
}

$dockerArguments += @('eclipse-temurin:25-jdk', 'sh', './mvnw', '-B')
if ($Mode -eq 'Integration') {
    $dockerArguments += @('verify', '-P', 'integration')
} else {
    $dockerArguments += 'test'
}

Write-Host "Core: pruebas $Mode con JDK 25 en Docker."
& docker @dockerArguments
$testExitCode = $LASTEXITCODE
if ($testExitCode -ne 0) {
    Write-Error "Las pruebas Core fallaron (Docker/Maven: $testExitCode)." -ErrorAction Continue
    exit $testExitCode
}

Write-Host 'Las pruebas Core finalizaron correctamente.'
exit 0
