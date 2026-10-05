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
    param([Parameter(Mandatory)][string]$RepoPath, [Parameter(Mandatory)][string]$StreamingRef)
    $repo = (Resolve-Path -LiteralPath $RepoPath).Path
    $work = Join-Path $repo 'services/core/target/streaming-contract'
    $source = Join-Path $work 'source'
    $archive = Join-Path $work 'streaming.tar'
    Assert-ContractPath $repo $archive
    Assert-ContractTree $repo $source
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
    return $source
}
