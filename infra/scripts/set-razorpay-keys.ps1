# Puts the Razorpay test keys you downloaded from the dashboard into the git-ignored env files, without printing them.
#
#   pwsh infra/scripts/set-razorpay-keys.ps1                      # uses the newest rzp-key*.csv in Downloads
#   pwsh infra/scripts/set-razorpay-keys.ps1 -File C:\path\x.csv  # or a file you name
#
# Writes RAZORPAY_KEY_ID, RAZORPAY_KEY_SECRET and RAZORPAY_WEBHOOK_SECRET (a random string made here: type the same one into the webhook's
# "Secret" box in the Razorpay dashboard, or leave the webhook out on a laptop) to infra/docker/.env and backend/.env.local. Existing
# RAZORPAY_* lines are replaced. Both files are git-ignored; nothing is committed.
param([string]$File = '')

$repo = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
if (-not $File) {
    $found = Get-ChildItem "$env:USERPROFILE\Downloads" -Filter 'rzp-key*.csv' -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $found) { Write-Error 'No rzp-key*.csv in Downloads. Pass -File <path>.'; exit 1 }
    $File = $found.FullName
}

$rows = @(Import-Csv $File)
if ($rows.Count -lt 1) { Write-Error "$File has no rows."; exit 1 }
$row = $rows[0]
$names = $row.PSObject.Properties.Name
$idName = $names | Where-Object { $_ -match 'id' } | Select-Object -First 1
$secretName = $names | Where-Object { $_ -match 'secret' } | Select-Object -First 1
if (-not $idName -or -not $secretName) { Write-Error 'Could not find the key id and secret columns in the file.'; exit 1 }
$keyId = ([string]$row.$idName).Trim()
$keySecret = ([string]$row.$secretName).Trim()
if ($keyId -notlike 'rzp_test_*') { Write-Error 'The key id is not a TEST key (rzp_test_...). Nothing written.'; exit 1 }
if (-not $keySecret) { Write-Error 'The secret column is empty. Nothing written.'; exit 1 }

$bytes = New-Object byte[] 24
[System.Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($bytes)
$webhookSecret = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', 'A').Replace('/', 'B')

foreach ($target in @("$repo\infra\docker\.env", "$repo\backend\.env.local")) {
    $lines = @()
    if (Test-Path $target) { $lines = @(Get-Content $target | Where-Object { $_ -notmatch '^\s*RAZORPAY_(KEY_ID|KEY_SECRET|WEBHOOK_SECRET)=' }) }
    $lines += "RAZORPAY_KEY_ID=$keyId"
    $lines += "RAZORPAY_KEY_SECRET=$keySecret"
    $lines += "RAZORPAY_WEBHOOK_SECRET=$webhookSecret"
    Set-Content -Path $target -Value $lines -Encoding utf8
    Write-Host "Updated $target"
}
Write-Host "Done. Key id $($keyId.Substring(0, 13))... saved. The secrets were not printed."
