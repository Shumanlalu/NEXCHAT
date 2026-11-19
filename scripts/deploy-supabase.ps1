param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z0-9]{20}$')]
    [string]$ProjectRef
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot

function Find-SupabaseCli {
    $command = Get-Command supabase -ErrorAction SilentlyContinue
    if ($command -and $command.Source) {
        return $command.Source
    }

    # The npm wrapper can fail with `spawn UNKNOWN` on some Windows/Node
    # combinations. The native binary inside the same package works directly.
    $candidates = Get-ChildItem `
        -Path (Join-Path $env:LOCALAPPDATA 'npm-cache\_npx') `
        -Filter 'supabase.exe' `
        -Recurse `
        -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match 'cli-windows-x64[\\/]bin[\\/]supabase\.exe$' } |
        Sort-Object LastWriteTime -Descending

    if ($candidates.Count -gt 0) {
        return $candidates[0].FullName
    }

    throw 'Supabase CLI not found. Install it from https://supabase.com/docs/guides/cli, then rerun this script.'
}

$supabase = Find-SupabaseCli
Write-Host "Using Supabase CLI: $supabase"

if (-not $env:SUPABASE_ACCESS_TOKEN) {
    Write-Host 'No SUPABASE_ACCESS_TOKEN set. Opening the browser login flow...'
    & $supabase login
    if ($LASTEXITCODE -ne 0) {
        throw 'Supabase login failed or was cancelled.'
    }
}

Push-Location $repoRoot
try {
    Write-Host "Linking project $ProjectRef..."
    & $supabase link --project-ref $ProjectRef
    if ($LASTEXITCODE -ne 0) {
        throw 'supabase link failed. Confirm the project reference and that your account owns the project.'
    }

    Write-Host 'Pushing migrations...'
    & $supabase db push
    if ($LASTEXITCODE -ne 0) {
        throw 'supabase db push failed. No production traffic was changed.'
    }

    Write-Host 'Remote migrations:'
    & $supabase migration list
}
finally {
    Pop-Location
}

Write-Host ''
Write-Host 'Database deployment finished. Next: set DATABASE_URL in the relay host to the Supabase pooler URL and run the backend smoke tests before switching production traffic.'
