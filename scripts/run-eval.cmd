@echo off
setlocal
powershell.exe -NoLogo -NoProfile -File "%~dp0run-eval.ps1" %*
exit /b %ERRORLEVEL%