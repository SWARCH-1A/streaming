[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'prepare-streaming-consumer.ps1')
$repo = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '../..')).Path
$scratch = Join-Path $repo ('services/core/target/runner-helpers-' + [guid]::NewGuid().ToString('N'))
$fixture = Join-Path $scratch 'repo'
New-Item -ItemType Directory -Path (Join-Path $fixture 'services/streaming/src/bin') -Force | Out-Null
function Invoke-FixtureGit {
    & git -C $fixture @args
    if ($LASTEXITCODE -ne 0) { throw 'Fixture Git command failed.' }
}
function Assert-True([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message } }
Invoke-FixtureGit init -q
[IO.File]::WriteAllText((Join-Path $fixture 'services/streaming/src/bin/obsolete.rs'), 'fn main() {}')
[IO.File]::WriteAllText((Join-Path $fixture 'services/streaming/old-name.txt'), 'retained contents')
Invoke-FixtureGit add services/streaming
Invoke-FixtureGit -c user.name=Contract -c user.email=contract@example.test commit -qm first
$first = Invoke-FixtureGit rev-parse HEAD
$source = Initialize-StreamingConsumer $fixture $first
$work = Split-Path $source -Parent
[IO.File]::WriteAllText((Join-Path $work '.env'), 'fixture secret must survive')
New-Item -ItemType Directory -Path (Join-Path $work 'cache') | Out-Null
[IO.File]::WriteAllText((Join-Path $work 'cache/marker'), 'preserved cache')
Invoke-FixtureGit rm -q services/streaming/src/bin/obsolete.rs
Invoke-FixtureGit mv services/streaming/old-name.txt services/streaming/new-name.txt
Invoke-FixtureGit -c user.name=Contract -c user.email=contract@example.test commit -qm second
$second = Invoke-FixtureGit rev-parse HEAD
$source = Initialize-StreamingConsumer $fixture $second
$actual = @(Get-ChildItem -LiteralPath $source -Recurse -File | ForEach-Object { [IO.Path]::GetRelativePath($source,$_.FullName).Replace('\','/') })
$expected = @(Invoke-FixtureGit ls-tree -r --name-only $second -- services/streaming)
Assert-True ($null -eq (Compare-Object $actual $expected)) 'Extracted files do not match the selected commit.'
Assert-True ([IO.File]::ReadAllText((Join-Path $source 'services/streaming/new-name.txt')) -ceq 'retained contents') 'Extracted contents changed.'
Assert-True ([IO.File]::ReadAllText((Join-Path $work '.env')) -ceq 'fixture secret must survive') 'Fixture configuration changed.'
Assert-True ([IO.File]::ReadAllText((Join-Path $work 'cache/marker')) -ceq 'preserved cache') 'Sibling cache changed.'
$rejected = $false
try { $null = Initialize-StreamingConsumer $fixture 'missing-ref-for-test' } catch { $rejected = $true }
Assert-True $rejected 'Missing ref was accepted.'
Assert-True (Test-Path -LiteralPath (Join-Path $source 'services/streaming/new-name.txt')) 'Invalid ref destroyed the previous source.'
Write-Host 'PASS archive replacement, renamed/deleted files, invalid ref and preserved siblings.'

[IO.File]::WriteAllText((Join-Path $fixture 'services/streaming/new-name.txt'), 'uncommitted change')
[IO.File]::WriteAllText((Join-Path $fixture 'services/streaming/added.txt'), 'untracked source')
[IO.File]::WriteAllText((Join-Path $fixture '.gitignore'), "ignored.txt`n")
[IO.File]::WriteAllText((Join-Path $fixture 'services/streaming/ignored.txt'), 'ignored secret')
$source = Initialize-StreamingConsumer -RepoPath $fixture
Assert-True ([IO.File]::ReadAllText((Join-Path $source 'services/streaming/new-name.txt')) -ceq 'uncommitted change') 'Current checkout modification was lost.'
Assert-True (Test-Path -LiteralPath (Join-Path $source 'services/streaming/added.txt')) 'New consumer source was lost.'
Assert-True (-not (Test-Path -LiteralPath (Join-Path $source 'services/streaming/ignored.txt'))) 'Ignored file was copied.'
Remove-Item -LiteralPath (Join-Path $fixture 'services/streaming/new-name.txt')
$source = Initialize-StreamingConsumer -RepoPath $fixture
Assert-True (-not (Test-Path -LiteralPath (Join-Path $source 'services/streaming/new-name.txt'))) 'Locally deleted file was copied.'
Write-Host 'PASS current checkout modifications, new/deleted files and ignored-file exclusion.'

$outside = Join-Path $scratch 'protected'
New-Item -ItemType Directory -Path $outside | Out-Null
[IO.File]::WriteAllText((Join-Path $outside 'marker'), 'must survive')
$linkType = if ([OperatingSystem]::IsWindows()) { 'Junction' } else { 'SymbolicLink' }
$null = New-Item -ItemType $linkType -Path (Join-Path $source 'escape') -Target $outside
$rejected = $false
try { $null = Initialize-StreamingConsumer $fixture $second } catch { $rejected = $_.Exception.Message -match 'links|reparse' }
Assert-True $rejected 'Source containing a link was not rejected.'
Assert-True ([IO.File]::ReadAllText((Join-Path $outside 'marker')) -ceq 'must survive') 'Linked data was changed.'
$rejected = $false
try { Assert-ContractPath $fixture $outside } catch { $rejected = $true }
Assert-True $rejected 'Path outside the fixture repository was accepted.'
Write-Host 'PASS link/reparse and outside-path protection.'

$fullSecret = 'existing_full_service_token_0123456789'
$catalogSecret = 'existing_catalog_service_token_0123456789'
$cases = @(
    @{name='empty-LF'; text="CORE_DB_PASSWORD=keep_db`nCORE_RATE_LIMIT_HMAC_SECRET=keep_hmac`nCORE_STREAMING_SERVICE_TOKEN=`nCORE_STREAMING_CATALOG_SERVICE_TOKEN=`n"},
    @{name='empty-CRLF'; text="CORE_DB_PASSWORD=keep_db`r`nCORE_RATE_LIMIT_HMAC_SECRET=keep_hmac`r`nCORE_STREAMING_SERVICE_TOKEN=`r`nCORE_STREAMING_CATALOG_SERVICE_TOKEN=`r`n"},
    @{name='missing-catalog-key'; text="CORE_DB_PASSWORD=keep_db`r`nCORE_RATE_LIMIT_HMAC_SECRET=keep_hmac`r`nCORE_STREAMING_SERVICE_TOKEN=$fullSecret`r`n"},
    @{name='existing-secrets'; text="CORE_DB_PASSWORD=keep_db`r`nCORE_RATE_LIMIT_HMAC_SECRET=keep_hmac`r`nCORE_STREAMING_SERVICE_TOKEN=$fullSecret`r`nCORE_STREAMING_CATALOG_SERVICE_TOKEN=$catalogSecret`r`n"},
    @{name='new-LF'; text=$null; newline="`n"},
    @{name='new-CRLF'; text=$null; newline="`r`n"}
)
foreach ($case in $cases) {
    $caseDir = Join-Path $scratch $case.name
    New-Item -ItemType Directory -Path $caseDir | Out-Null
    Copy-Item -LiteralPath (Join-Path $repo 'infra/local/init-env.ps1') -Destination $caseDir
    $template = [IO.File]::ReadAllText((Join-Path $repo 'infra/local/.env.example'))
    if ($case.newline) { $template = $template.Replace("`r`n","`n").Replace("`n",$case.newline) }
    [IO.File]::WriteAllText((Join-Path $caseDir '.env.example'),$template)
    $envPath = Join-Path $caseDir '.env'
    if ($null -ne $case.text) { [IO.File]::WriteAllText($envPath,$case.text) }
    & (Join-Path $caseDir 'init-env.ps1') *> $null
    $firstRun = [IO.File]::ReadAllText($envPath)
    $values = ConvertFrom-StringData $firstRun
    foreach ($name in @('CORE_STREAMING_SERVICE_TOKEN','CORE_STREAMING_CATALOG_SERVICE_TOKEN','CHAT_CORE_SERVICE_TOKEN','CHAT_SESSION_EVENTS_TOKEN')) {
        Assert-True ($values[$name] -match '^[A-Za-z0-9_-]{32,256}$') "Invalid generated credential: $($case.name)"
    }
    Assert-True ($values.CORE_STREAMING_SERVICE_TOKEN -cne $values.CORE_STREAMING_CATALOG_SERVICE_TOKEN) 'Service scopes share a secret.'
    if ($null -ne $case.text) {
        Assert-True ($values.CORE_DB_PASSWORD -ceq 'keep_db' -and $values.CORE_RATE_LIMIT_HMAC_SECRET -ceq 'keep_hmac') 'Existing database/HMAC secrets changed.'
    } else {
        Assert-True ((@($values.CORE_DB_PASSWORD,$values.CORE_RATE_LIMIT_HMAC_SECRET,$values.CORE_STREAMING_SERVICE_TOKEN,$values.CORE_STREAMING_CATALOG_SERVICE_TOKEN,$values.CHAT_CORE_SERVICE_TOKEN,$values.CHAT_SESSION_EVENTS_TOKEN) | Select-Object -Unique).Count -eq 6) 'New secrets are not independent.'
    }
    if ($case.name -in @('missing-catalog-key','existing-secrets')) {
        Assert-True ($values.CORE_STREAMING_SERVICE_TOKEN -ceq $fullSecret) 'Existing full-access secret changed.'
    }
    if ($case.name -eq 'existing-secrets') { Assert-True ($values.CORE_STREAMING_CATALOG_SERVICE_TOKEN -ceq $catalogSecret) 'Existing catalog secret changed.' }
    & (Join-Path $caseDir 'init-env.ps1') *> $null
    Assert-True ([IO.File]::ReadAllText($envPath) -ceq $firstRun) 'Initialization is not idempotent.'
    Write-Host "PASS env $($case.name)"
}
