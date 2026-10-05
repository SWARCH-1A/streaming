[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
function New-LocalSecret {
    $bytes = New-Object byte[] 32
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}

$destination = Join-Path $PSScriptRoot '.env'
$serviceSecrets = @('CORE_STREAMING_SERVICE_TOKEN', 'CORE_STREAMING_CATALOG_SERVICE_TOKEN')
if (Test-Path -LiteralPath $destination) {
    $existing = [System.IO.File]::ReadAllText($destination)
    $updated = $existing
    foreach ($name in $serviceSecrets) {
        $line = '(?m)^' + [regex]::Escape($name) + '='
        if ($updated -match ($line + '[^\r\n]+\r?$')) { continue }
        $entry = $name + '=' + (New-LocalSecret)
        if ($updated -match ($line + '(?=\r?$)')) {
            $updated = [regex]::Replace($updated, ($line + '(?=\r?$)'), $entry)
        } else { $updated = $updated.TrimEnd() + "`n" + $entry + "`n" }
    }
    if ($updated -cne $existing) {
        [System.IO.File]::WriteAllText($destination, $updated, [System.Text.UTF8Encoding]::new($false))
        Write-Host 'Agregados secretos privados faltantes; se conservan todos los valores existentes.'
    } else { Write-Host 'Se conserva infra/local/.env existente; no se regeneran secretos.' }
    return
}

$template = [System.IO.File]::ReadAllText((Join-Path $PSScriptRoot '.env.example'))
foreach ($name in (@('CORE_DB_PASSWORD', 'CORE_RATE_LIMIT_HMAC_SECRET') + $serviceSecrets)) {
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
Write-Host 'Creado infra/local/.env con cuatro secretos locales independientes. Valores no mostrados.'
