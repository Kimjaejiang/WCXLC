@echo off
REM -- Build and overwrite-install to the device (local fast iteration) --------
REM
REM Only does "pm install -r" (overwrite install). No uninstall, no data wipe.
REM Module preferences and WeChat data are untouched.
REM
REM ---------------------------------------------------------------------------
REM NOTE: keep this file ASCII-only.
REM   cmd.exe reads .bat files using the console code page (GBK on zh-CN
REM   Windows). UTF-8 Chinese comments get mis-decoded, and the resulting bytes
REM   can end a REM line early, so the rest of the comment is run as a command.
REM   Keep every byte ASCII, including comments.
REM ---------------------------------------------------------------------------
REM
REM [Install method: push first, then pm install]
REM   "adb install" fails intermittently on this machine, with an empty error
REM   message ("failed to install ...: "). --no-incremental helps but does not
REM   fully fix it. Switching to push + "pm install -r" made it reliable.
REM
REM [adb location]
REM   Uses "adb" from PATH by default. If adb is not on PATH, set the ADB
REM   environment variable to the full path of adb.exe.
REM
REM [NEVER add uninstall or data wipe]
REM   "adb uninstall" / "pm clear" destroy local WeChat data, and chat history
REM   is not recoverable. This script only overwrite-installs. Do not add either
REM   of those commands here under any circumstances.

setlocal

if "%ADB%"=="" set ADB=adb

REM If adb is not already usable, probe a few common install locations so the
REM script works without configuration on a typical Windows setup.
"%ADB%" version >nul 2>nul
if not errorlevel 1 goto :adb_ready
set "ADB=D:\platform-tools\adb.exe"
if exist "%ADB%" goto :adb_check
set "ADB=C:\platform-tools\adb.exe"
if exist "%ADB%" goto :adb_check
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
if exist "%ADB%" goto :adb_check
set "ADB=%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe"
if exist "%ADB%" goto :adb_check
set "ADB=adb"
goto :adb_ready

:adb_check
"%ADB%" version >nul 2>nul
if errorlevel 1 set "ADB=adb"

:adb_ready

set APK=%~dp0app\build\outputs\apk\standard\release\app-standard-release.apk

call "%~dp0fastbuild.bat" %*
if errorlevel 1 (
    echo [FAIL] build failed, skip install
    exit /b 1
)

if not exist "%APK%" (
    echo [FAIL] apk not found: %APK%
    exit /b 1
)

REM Check availability by actually running it. "where" is unreliable here:
REM it misses an adb that exists but is not on PATH, and its quoting differs
REM between cmd and the git-bash environment.
"%ADB%" version >nul 2>nul
if errorlevel 1 (
    echo [FAIL] adb not usable: "%ADB%"
    echo        Add adb to PATH, or set the ADB environment variable to the
    echo        full path of adb.exe ^(for example D:\platform-tools\adb.exe^).
    exit /b 1
)

echo.
echo === installing (push + pm install -r, no uninstall) ===
"%ADB%" push "%APK%" /data/local/tmp/wcx-install.apk
if errorlevel 1 (
    echo [FAIL] push failed
    exit /b 1
)

"%ADB%" shell "pm install -r /data/local/tmp/wcx-install.apk"
if errorlevel 1 (
    echo [FAIL] install failed
    exit /b 1
)

"%ADB%" shell "rm -f /data/local/tmp/wcx-install.apk"

echo.
echo === installed. restart WeChat to apply hooks. ===

endlocal

