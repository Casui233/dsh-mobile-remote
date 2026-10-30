# Run as Administrator.
# Allow inbound TCP 8765 for the DSH mobile bridge on the Private profile
# (the Tailscale adapter is Private), optionally re-enable Windows Firewall.
#
#   powershell -ExecutionPolicy Bypass -File .\allow-firewall.ps1
#   powershell -ExecutionPolicy Bypass -File .\allow-firewall.ps1 -EnableFirewall
[CmdletBinding()]
param(
  [switch]$EnableFirewall
)

$ErrorActionPreference = 'Stop'
$ruleName = 'DSH Mobile Bridge 8765'
$log = Join-Path $env:TEMP 'dsh-firewall-apply.log'

try {
  $principal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
  $isAdmin = $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
  Add-Content -LiteralPath $log -Value ("start {0} admin={1}" -f (Get-Date -Format o), $isAdmin) -ErrorAction SilentlyContinue
} catch { }

try { Start-Transcript -Path $log -Append -Force | Out-Null } catch { }

$existing = Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue
if ($existing) {
  Write-Host "Rule already exists: $ruleName"
} else {
  New-NetFirewallRule `
    -DisplayName $ruleName `
    -Description 'DeepSeek Harness mobile bridge (Tailscale, inbound TCP 8765)' `
    -Direction Inbound `
    -Action Allow `
    -Protocol TCP `
    -LocalPort 8765 `
    -Profile Private | Out-Null
  Write-Host "Added inbound rule: $ruleName (Private / TCP 8765)"
}

if ($EnableFirewall) {
  Set-NetFirewallProfile -Profile Domain,Private,Public -Enabled True
  Write-Host 'Windows Firewall enabled for Domain / Private / Public.'
} else {
  Write-Host 'Firewall profiles left unchanged. Re-run with -EnableFirewall to turn them on.'
}

Write-Host '--- current state ---'
Get-NetFirewallProfile | Select-Object Name, Enabled | Format-Table -AutoSize | Out-String | Write-Host
Get-NetFirewallRule -DisplayName $ruleName -ErrorAction SilentlyContinue |
  Select-Object DisplayName, Enabled, Direction, Action, Profile | Format-Table -AutoSize | Out-String | Write-Host

try { Stop-Transcript | Out-Null } catch { }
