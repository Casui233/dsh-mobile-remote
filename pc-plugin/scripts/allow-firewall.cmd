@echo off
setlocal
set "PS1=%~dp0allow-firewall.ps1"
powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process powershell.exe -Verb RunAs -ArgumentList @('-NoProfile','-ExecutionPolicy','Bypass','-File','%PS1%','-EnableFirewall')"
endlocal
