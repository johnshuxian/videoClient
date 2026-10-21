@echo off
chcp 65001 >nul
pushd "%~dp0"
where pwsh.exe >nul 2>&1
if errorlevel 1 (
  echo PowerShell 7 is required. Install it or run the script with pwsh.exe.
  set "result=1"
  goto done
)
pwsh.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-install-android.ps1" %*
set "result=%errorlevel%"
:done
echo.
if "%result%"=="0" (echo Build and install completed.) else (echo Build or install failed. See the message above.)
if not defined ANDROID_NO_PAUSE pause
popd
exit /b %result%
