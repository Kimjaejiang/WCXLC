@echo off
REM -- Fast iterative build (local development only) --------------------------
REM
REM Does one thing: run the task graph in parallel.
REM (org.gradle.parallel=false in gradle.properties is the conservative CI setting.)
REM
REM ---------------------------------------------------------------------------
REM NOTE: keep this file ASCII-only.
REM   cmd.exe reads .bat files using the console code page (GBK on zh-CN
REM   Windows). UTF-8 Chinese comments get mis-decoded, and the resulting bytes
REM   can end a REM line early, so the rest of the comment is run as a command.
REM   This actually happened: running the UTF-8 version produced
REM   "'ADB' is not recognized as an internal or external command".
REM   Keep every byte ASCII, including comments.
REM ---------------------------------------------------------------------------
REM
REM [Where signing credentials come from]
REM   Same as CI: read from environment variables only, never hardcoded here.
REM   Locally, put them in local-creds.env at the repo root (gitignored).
REM   Copy local-creds.env.example to local-creds.env and fill it in.
REM   If the four variables are already set (CI does this), they are not
REM   overwritten.
REM
REM [Never add -PdisableMinify]
REM   Measured: disabling R8 cuts the build from 3m37s to 50s, but the APK
REM   grows from 28MB to 90MB. Transferring that over adb fails intermittently
REM   on this machine ("failed to install", no error detail). The time saved is
REM   eaten by repeated install retries, and it stays unstable.
REM
REM Measured (minify + parallel): 3m47s -> 3m37s, APK stays 28MB.

setlocal

if exist "%~dp0local-creds.env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%~dp0local-creds.env") do (
        if "%%A"=="WEKIT_KEYSTORE_FILE" if "%WEKIT_KEYSTORE_FILE%"=="" set WEKIT_KEYSTORE_FILE=%%B
        if "%%A"=="WEKIT_KEYSTORE_PASSWORD" if "%WEKIT_KEYSTORE_PASSWORD%"=="" set WEKIT_KEYSTORE_PASSWORD=%%B
        if "%%A"=="WEKIT_KEY_ALIAS" if "%WEKIT_KEY_ALIAS%"=="" set WEKIT_KEY_ALIAS=%%B
        if "%%A"=="WEKIT_KEY_PASSWORD" if "%WEKIT_KEY_PASSWORD%"=="" set WEKIT_KEY_PASSWORD=%%B
    )
)

if "%WEKIT_KEYSTORE_FILE%"=="" (
    echo [FAIL] Signing credentials not found.
    echo        Create local-creds.env at the repo root
    echo        ^(copy local-creds.env.example and fill it in^),
    echo        or set WEKIT_KEYSTORE_FILE / WEKIT_KEYSTORE_PASSWORD /
    echo        WEKIT_KEY_ALIAS / WEKIT_KEY_PASSWORD in the environment.
    exit /b 1
)

call gradlew.bat :app:assembleStandardRelease ^
  -x lintVitalStandardRelease ^
  -x lintVitalReportStandardRelease ^
  --offline ^
  --parallel ^
  --max-workers=8 ^
  %*

endlocal

