# Puts your NVIDIA API key (and the model to use) into the git-ignored env files. It asks for the key WITHOUT showing what you type, and prints
# neither it nor anything derived from it beyond the first few characters of the model name.
#
#   pwsh infra/scripts/set-nvidia-key.ps1
#   pwsh infra/scripts/set-nvidia-key.ps1 -Model meta/llama-3.3-70b-instruct
#
# Writes NVIDIA_API_KEY and NVIDIA_MODEL to infra/docker/.env and backend/.env.local; existing NVIDIA_* lines are replaced. Both files are git-ignored.
param([string]$Model = '')

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$secure = Read-Host -Prompt 'NVIDIA API key (starts with nvapi-; typing is hidden)' -AsSecureString
$key = [System.Net.NetworkCredential]::new('', $secure).Password.Trim()
if (-not $key) { Write-Error 'No key entered. Nothing written.'; exit 1 }
if ($key -notlike 'nvapi-*') { Write-Error 'That does not look like an NVIDIA key (they start with nvapi-). Nothing written.'; exit 1 }
if (-not $Model) { $Model = (Read-Host -Prompt 'Model name [meta/llama-3.3-70b-instruct]').Trim() }
if (-not $Model) { $Model = 'meta/llama-3.3-70b-instruct' }

foreach ($target in @("$repo\infra\docker\.env", "$repo\backend\.env.local")) {
    $lines = @()
    if (Test-Path $target) { $lines = @(Get-Content $target | Where-Object { $_ -notmatch '^\s*NVIDIA_(API_KEY|MODEL)=' }) }
    $lines += "NVIDIA_API_KEY=$key"
    $lines += "NVIDIA_MODEL=$Model"
    Set-Content -Path $target -Value $lines -Encoding utf8
    Write-Host "Updated $target"
}
Write-Host "Done. Model $Model saved. The key was not printed."
