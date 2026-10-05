[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$destination = Join-Path $PSScriptRoot '.env'
if (Test-Path -LiteralPath $destination) {
    $existing = [System.IO.File]::ReadAllText($destination)
    if ($existing -notmatch '(?m)^CORE_STREAMING_SERVICE_TOKEN=[^\r\n]+\r?$') {
        $bytes = New-Object byte[] 32
        $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
        try { $random.GetBytes($bytes) } finally { $random.Dispose() }
        $secret = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
        if ($existing -match '(?m)^CORE_STREAMING_SERVICE_TOKEN=(?=\r?$)') {
            $existing = [regex]::Replace($existing, '(?m)^CORE_STREAMING_SERVICE_TOKEN=(?=\r?$)', ('CORE_STREAMING_SERVICE_TOKEN=' + $secret))
        } else { $existing = $existing.TrimEnd() + "`nCORE_STREAMING_SERVICE_TOKEN=" + $secret + "`n" }
        [System.IO.File]::WriteAllText($destination, $existing, [System.Text.UTF8Encoding]::new($false))
        Write-Host 'Agregado secreto privado Core; se conservan todos los valores existentes.'
    } else { Write-Host 'Se conserva infra/local/.env existente; no se regeneran secretos.' }
    return
}

function New-LocalSecret {
    $bytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$template = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot '.env.example'))
foreach ($name in @('CORE_DB_PASSWORD', 'CORE_RATE_LIMIT_HMAC_SECRET', 'CORE_STREAMING_SERVICE_TOKEN')) {
    $pattern = '(?m)^' + [regex]::Escape($name) + '=\r?$'
    if (-not [regex]::IsMatch($template, $pattern)) {
        throw "La plantilla no contiene la variable vacía requerida: $name"
    }
    $template = [regex]::Replace($template, $pattern, ($name + '=' + (New-LocalSecret)))
}
# CreateNew refuses to overwrite a file created concurrently.
$stream = [System.IO.File]::Open($destination, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write)
try {
    $bytes = [System.Text.UTF8Encoding]::new($false).GetBytes($template)
    $stream.Write($bytes, 0, $bytes.Length)
} finally { $stream.Dispose() }
Write-Host 'Creado infra/local/.env con tres secretos locales independientes. Valores no mostrados.'
