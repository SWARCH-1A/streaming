[CmdletBinding()]
param([string]$StreamingRef = 'f9dc6d164242b24bdc20e29ceefdc3978b215390')
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$work = Join-Path $repo 'services/core/target/streaming-contract'
. (Join-Path $PSScriptRoot 'prepare-streaming-consumer.ps1')
$source = Initialize-StreamingConsumer -RepoPath $repo -StreamingRef $StreamingRef
function Invoke-CheckedDocker {
    & docker @args
    if ($LASTEXITCODE -ne 0) { throw 'Docker contract verification failed; inspect its preceding diagnostics.' }
}
function New-FixtureSecret {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}
$crate = Join-Path $source 'tests/contracts/streaming-core'
New-Item -ItemType Directory -Path $crate -Force | Out-Null
Copy-Item -Path (Join-Path $PSScriptRoot 'streaming-core/*') -Destination $crate -Recurse -Force
$envFile = Join-Path $work '.env'
if (-not (Test-Path -LiteralPath $envFile)) {
    $envText = "CORE_DB_PASSWORD=$(New-FixtureSecret)`nCORE_RATE_LIMIT_HMAC_SECRET=$(New-FixtureSecret)`nCORE_STREAMING_SERVICE_TOKEN=$(New-FixtureSecret)`nCORE_PORT=18081`nCORE_DB_PORT=15440`nCORE_SECURE_COOKIE=false`nWEB_ORIGIN=http://localhost:3000`n"
    [IO.File]::WriteAllText($envFile, $envText, [Text.UTF8Encoding]::new($false))
}
$envText = [IO.File]::ReadAllText($envFile)
if ($envText -notmatch '(?m)^CORE_STREAMING_CATALOG_SERVICE_TOKEN=[^\r\n]+\r?$') {
    $entry = 'CORE_STREAMING_CATALOG_SERVICE_TOKEN=' + (New-FixtureSecret)
    if ($envText -match '(?m)^CORE_STREAMING_CATALOG_SERVICE_TOKEN=(?=\r?$)') {
        $envText = [regex]::Replace($envText, '(?m)^CORE_STREAMING_CATALOG_SERVICE_TOKEN=(?=\r?$)', $entry)
    } else { $envText = $envText.TrimEnd() + "`n" + $entry + "`n" }
    [IO.File]::WriteAllText($envFile, $envText, [Text.UTF8Encoding]::new($false))
}
$settings = ConvertFrom-StringData ([IO.File]::ReadAllText($envFile))
$compose = @('compose','--env-file',$envFile,'-p','taxonomy-contracts','-f',(Join-Path $repo 'infra/local/compose.core.yaml'),'-f',(Join-Path $repo 'infra/local/compose.core-private-dev.yaml'))
$base = 'http://127.0.0.1:18081'
function Wait-Core {
    for ($attempt=0; $attempt -lt 60; $attempt++) {
        try { if ((Invoke-RestMethod "$base/actuator/health" -TimeoutSec 5).status -eq 'UP') { return } } catch { }
        Start-Sleep -Seconds 2
    }
    throw 'Core did not become healthy within the verification deadline.'
}
function New-ContractUser {
    $web = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    $csrf = Invoke-RestMethod "$base/api/identity/csrf" -WebSession $web
    $headers = @{ 'X-XSRF-TOKEN'=$csrf.token; 'Idempotency-Key'=[guid]::NewGuid().ToString() }
    $handle = 'ct_' + [guid]::NewGuid().ToString('N').Substring(0,16)
    $password = New-FixtureSecret
    $account = Invoke-RestMethod "$base/api/identity/registrations" -Method Post -WebSession $web -Headers $headers -ContentType 'application/json' -Body (@{email="$handle@example.test";handle=$handle;password=$password} | ConvertTo-Json)
    $null = Invoke-RestMethod "$base/api/identity/sessions" -Method Post -WebSession $web -Headers $headers -ContentType 'application/json' -Body (@{login=$handle;password=$password} | ConvertTo-Json)
    @{ id=$account.userId; channel=$account.channelId; credential=$web.Cookies.GetCookies([uri]$base)['stream_session'].Value; web=$web; headers=$headers }
}
try {
    Invoke-CheckedDocker @compose up --build -d
    Wait-Core
    $owner=New-ContractUser; $stranger=New-ContractUser; $revoked=New-ContractUser
    $null = Invoke-RestMethod "$base/api/identity/sessions/current" -Method Delete -WebSession $revoked.web -Headers $revoked.headers
    "INSERT INTO taxonomy.categories(id,name,active) VALUES ('cat_contract_tombstone','Contrato conservado',false) ON CONFLICT (id) DO NOTHING;" | & docker @compose exec -T postgres psql -U core -d core -v ON_ERROR_STOP=1
    if ($LASTEXITCODE -ne 0) { throw 'Could not prepare the isolated tombstone fixture.' }
    $fixture = @{privateUrl='http://core:8082';publicUrl='http://core:8081';serviceToken=$settings.CORE_STREAMING_SERVICE_TOKEN;catalogServiceToken=$settings.CORE_STREAMING_CATALOG_SERVICE_TOKEN;userId=$owner.id;channelId=$owner.channel;credential=$owner.credential;strangerCredential=$stranger.credential;revokedCredential=$revoked.credential;categoryId='cat_00000000000000000000000000000001';tagId='tag_00000000000000000000000000000001';tombstoneId='cat_contract_tombstone'}
    $fixturePath = Join-Path $work 'fixture.json'
    [IO.File]::WriteAllText($fixturePath, ($fixture | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
    $before = Invoke-RestMethod "$base/api/taxonomy" | ConvertTo-Json -Depth 8 -Compress
    Invoke-CheckedDocker run --rm --network taxonomy-contracts_default --mount "type=bind,source=$source,target=/source" --mount "type=bind,source=$fixturePath,target=/fixture.json,readonly" --mount 'type=volume,source=streaming-contract-cargo,target=/usr/local/cargo' --mount 'type=volume,source=streaming-contract-target,target=/target' --env CARGO_TARGET_DIR=/target --env CONTRACT_FIXTURE=/fixture.json --env CARGO_BUILD_JOBS=2 --workdir /source/tests/contracts/streaming-core rust:1.98 cargo test
    Invoke-CheckedDocker @compose restart postgres
    for ($attempt=0; $attempt -lt 30; $attempt++) {
        & docker @compose exec -T postgres pg_isready -U core -d core | Out-Null
        if ($LASTEXITCODE -eq 0) { break }
        Start-Sleep -Seconds 1
    }
    Invoke-CheckedDocker @compose restart core
    Wait-Core
    $after = Invoke-RestMethod "$base/api/taxonomy" | ConvertTo-Json -Depth 8 -Compress
    if ($before -ne $after) { throw 'Catalog IDs, labels or version changed across persisted-volume restart.' }
    $channel = Invoke-RestMethod "$base/api/channels/by-owner/$($owner.id)"
    if ($channel.channel.channelId -ne $owner.channel) { throw 'Account/channel identity did not survive restart.' }
    Write-Host 'PASS: real Rust gateway, public/private isolation, owner/errors/tombstones and persisted-volume restart.'
} finally {
    & docker @compose down
    if (Test-Path -LiteralPath (Join-Path $work 'fixture.json')) { [IO.File]::WriteAllText((Join-Path $work 'fixture.json'), '{}') }
}
