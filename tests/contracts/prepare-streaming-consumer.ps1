# Dot-source this helper. Only the archived source tree is replaced; caches and fixtures are siblings.
function Assert-ContractPath {
    param([string]$RepoPath, [string]$Path)
    $root = [IO.Path]::GetFullPath($RepoPath).TrimEnd([IO.Path]::DirectorySeparatorChar)
    $full = [IO.Path]::GetFullPath($Path)
    if (-not $full.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Contract workspace must stay inside the repository.'
    }
    $current = $full
    while ($true) {
        $item = Get-Item -LiteralPath $current -Force -ErrorAction SilentlyContinue
        if ($null -ne $item) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Contract workspace cannot contain links or reparse points.' }
        }
        if ($current -eq $root) { break }
        $current = [IO.Path]::GetDirectoryName($current)
    }
}

function Assert-ContractTree {
    param([string]$RepoPath, [string]$Path)
    Assert-ContractPath $RepoPath $Path
    if (-not (Test-Path -LiteralPath $Path)) { return }
    $pending = [Collections.Generic.Stack[string]]::new()
    $pending.Push($Path)
    while ($pending.Count -gt 0) {
        foreach ($item in Get-ChildItem -LiteralPath $pending.Pop() -Force) {
            if ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Contract source tree cannot contain links or reparse points.' }
            if ($item.PSIsContainer) { $pending.Push($item.FullName) }
        }
    }
}

function Initialize-StreamingConsumer {
    [CmdletBinding()]
    param([Parameter(Mandatory)][string]$RepoPath, [string]$StreamingRef = '')
    $repo = (Resolve-Path -LiteralPath $RepoPath).Path
    $work = Join-Path $repo 'tests/contracts/.cache/streaming-core'
    $source = Join-Path $work 'source'
    $archive = Join-Path $work 'streaming.tar'
    Assert-ContractPath $repo $archive
    Assert-ContractTree $repo $source
    if (-not $StreamingRef) {
        $head = & git -C $repo rev-parse --verify HEAD
        if ($LASTEXITCODE -ne 0) { throw 'Cannot identify the current checkout.' }
        $entries = @(& git -C $repo -c core.quotepath=false ls-files --cached --others --exclude-standard -- services/streaming)
        if ($LASTEXITCODE -ne 0 -or -not $entries) { throw 'Current checkout has no Streaming consumer tree.' }
        # Resolve and validate every input before touching the previous extraction. Ignored build
        # artifacts/secrets are excluded by Git; locally modified/new files are included.
        $files = @($entries | Sort-Object -Unique | Where-Object { Test-Path -LiteralPath (Join-Path $repo $_) })
        foreach ($relative in $files) { Assert-ContractPath $repo (Join-Path $repo $relative) }
        Assert-ContractTree $repo $source
        if (Test-Path -LiteralPath $source) { Remove-Item -LiteralPath $source -Recurse -Force }
        New-Item -ItemType Directory -Path $source -Force | Out-Null
        foreach ($relative in $files) {
            $destination = Join-Path $source $relative
            New-Item -ItemType Directory -Path (Split-Path $destination -Parent) -Force | Out-Null
            Copy-Item -LiteralPath (Join-Path $repo $relative) -Destination $destination
        }
        Write-Host "Contract consumer: current checkout HEAD=$head (includes nonignored working changes)."
        & git -C $repo diff --stat HEAD -- services/streaming | Out-Host
        return $source
    }
    $commit = & git -C $repo rev-parse --verify --end-of-options "$StreamingRef^{commit}" 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'Fetch the selected Streaming commit before running the contract test.' }
    $entries = & git -C $repo ls-tree -r $commit -- services/streaming
    if ($LASTEXITCODE -ne 0 -or -not $entries) { throw 'Selected commit has no Streaming consumer tree.' }
    if ($entries -match '^(120000|160000) ') { throw 'Archived Streaming consumer must not contain symbolic links or submodules.' }
    New-Item -ItemType Directory -Path $work -Force | Out-Null
    & git -C $repo archive --format=tar --output=$archive $commit services/streaming
    if ($LASTEXITCODE -ne 0) { throw 'Cannot archive the selected Streaming consumer.' }
    # Validate exact absolute target immediately before recursive deletion; never follow links.
    Assert-ContractTree $repo $source
    if (Test-Path -LiteralPath $source) { Remove-Item -LiteralPath $source -Recurse -Force }
    New-Item -ItemType Directory -Path $source | Out-Null
    & tar -xf $archive -C $source
    if ($LASTEXITCODE -ne 0) { throw 'Cannot unpack the selected Streaming consumer.' }
    Assert-ContractTree $repo $source
    Write-Host "Contract consumer: explicit historical regression commit=$commit."
    return $source
}
