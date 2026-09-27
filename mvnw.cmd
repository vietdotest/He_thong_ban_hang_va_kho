@echo off
setlocal
set "PROJECT_DIR=%~dp0"
"C:\Windows\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%PROJECT_DIR%.mvn\wrapper\maven-wrapper.ps1" %*
exit /b %ERRORLEVEL%
