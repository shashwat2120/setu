# Creates the metrics scrape token once and puts the same value where both sides read it:
#   infra/docker/observability/scrape-token.local   -> mounted into Prometheus (git-ignored)
#   backend/.env.local  METRICS_SCRAPE_TOKEN=...    -> read by the backend  (git-ignored)
# Safe to run again: an existing token is kept. Run it from anywhere:  pwsh infra/docker/observability/init-scrape-token.ps1
$ErrorActionPreference = 'Stop'
$root = Resolve-Path (Join-Path $PSScriptRoot '..\..\..')
$tokenFile = Join-Path $PSScriptRoot 'scrape-token.local'
$envFile = Join-Path $root 'backend\.env.local'

if (Test-Path $tokenFile -PathType Leaf) {
    $token = (Get-Content $tokenFile -Raw).Trim()
    Write-Host 'Keeping the existing scrape token.'
} else {
    $bytes = New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $token = ([System.BitConverter]::ToString($bytes) -replace '-', '').ToLower()
    [System.IO.File]::WriteAllText($tokenFile, $token)   # no trailing newline
    Write-Host "Wrote $tokenFile"
}

$line = "METRICS_SCRAPE_TOKEN=$token"
if (-not (Test-Path $envFile)) { New-Item -ItemType File -Path $envFile | Out-Null }
$existing = Get-Content $envFile -ErrorAction SilentlyContinue
if ($existing -match '^METRICS_SCRAPE_TOKEN=') {
    $updated = $existing | ForEach-Object { if ($_ -match '^METRICS_SCRAPE_TOKEN=') { $line } else { $_ } }
    [System.IO.File]::WriteAllText($envFile, (($updated -join "`n") + "`n"))
    Write-Host 'Updated METRICS_SCRAPE_TOKEN in backend/.env.local'
} else {
    Add-Content -Path $envFile -Value $line
    Write-Host 'Added METRICS_SCRAPE_TOKEN to backend/.env.local'
}
Write-Host 'Done. Restart the backend so it picks the token up, then: docker compose up -d'
