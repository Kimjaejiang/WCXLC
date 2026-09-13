@echo off
rem =====================================================================
rem Hot-update plugin publisher (manual build chain, no Gradle)
rem
rem Why not Gradle: the plugin project intentionally stays out of the main
rem repo settings.gradle.kts and has no gradlew of its own. Pulling in a
rem full Gradle setup just to produce a few hundred KB of dex is not worth
rem it. This uses JDK javac + SDK d8/aapt2/zipalign/apksigner directly.
rem
rem Steps:
rem   1) javac the plugin Java sources (SPI comes from the shell, not bundled)
rem   2) d8 -> dex, aapt2 -> base.apk
rem   3) zipalign then sign with the SAME key as the shell
rem   4) compute SHA-256 and write hot-update.json
rem
rem Usage:
rem   publish-plugin.bat 1.0.1 changelog-text
rem
rem Signing key resolution (first hit wins):
rem   1) HOTPLUGIN_KEYSTORE env var (full path to the .jks)
rem   2) ../../../_keystore/wcx-release.jks  (legacy layout)
rem The keystore is intentionally NOT committed: the plugin must be signed
rem with the SAME cert as the shell or HotUpdateManager rejects it at
rem download time. Override credentials with HOTPLUGIN_KS_PASS /
rem HOTPLUGIN_KEY_PASS when using your own key.
rem
rem Output (in tools\hotplugin\release\):
rem   plugin-<version>.apk    upload to GitHub Release
rem   hot-update.json         upload to GitHub Release
rem =====================================================================
setlocal enabledelayedexpansion

set "VERSION=%~1"
set "CHANGELOG=%~2"
if "%VERSION%"=="" (
  echo [ERROR] version required. Example: publish-plugin.bat 1.0.1 changelog-text
  exit /b 1
)
if "%CHANGELOG%"=="" set "CHANGELOG=-"

set ROOT=%~dp0
set BT=%LOCALAPPDATA%\Android\Sdk\build-tools\36.0.0
set PLATFORM=%LOCALAPPDATA%\Android\Sdk\platforms\android-37.0\android.jar
set JAR=%JAVA_HOME%\bin\jar.exe
if defined HOTPLUGIN_KEYSTORE (set "KS=%HOTPLUGIN_KEYSTORE%") else (set "KS=%ROOT%..\..\..\_keystore\wcx-release.jks")
if defined HOTPLUGIN_KS_PASS (set "KS_PASS=%HOTPLUGIN_KS_PASS%") else (set "KS_PASS=102001")
if defined HOTPLUGIN_KEY_ALIAS (set "KS_ALIAS=%HOTPLUGIN_KEY_ALIAS%") else (set "KS_ALIAS=a")
if defined HOTPLUGIN_KEY_PASS (set "KEY_PASS=%HOTPLUGIN_KEY_PASS%") else (set "KEY_PASS=102001")

set WORK=%ROOT%build-publish
set OUT=%ROOT%release
set APK_NAME=plugin-%VERSION%.apk

if not exist "%PLATFORM%" (
  echo [ERROR] android.jar not found: %PLATFORM%
  echo         install SDK Platform 37 first
  exit /b 1
)

echo.
echo === [1/5] compile java ===
rem The java/ tree holds two groups:
rem   java/com/Johnny/wcx/hot/*.java       SPI stubs, COMPILE-TIME ONLY
rem   java/com/Johnny/wcx/hotplugin/*.java the actual plugin
rem SPI stubs must never reach the dex: the shell already provides those
rem types, and shipping a second copy would make the plugin-side HotPlugin
rem a DIFFERENT class from the shell's, so "as HotPlugin" would throw
rem ClassCastException at load time. They are compiled to their own output
rem dir purely so javac can resolve the plugin's imports.
if exist "%WORK%" rmdir /s /q "%WORK%"
mkdir "%WORK%\spi" 2>nul
mkdir "%WORK%\classes" 2>nul

> "%WORK%\spi-src.txt" (
  for /r "%ROOT%java\com\Johnny\wcx\hot" %%F in (*.java) do echo %%F
)
javac -encoding UTF-8 -nowarn ^
  -classpath "%PLATFORM%" ^
  -d "%WORK%\spi" "@%WORK%\spi-src.txt"
if errorlevel 1 (
  echo [ERROR] javac failed for SPI stubs
  exit /b 1
)

> "%WORK%\plugin-src.txt" (
  for /r "%ROOT%java\com\Johnny\wcx\hotplugin" %%F in (*.java) do echo %%F
)
javac -encoding UTF-8 -nowarn ^
  -classpath "%PLATFORM%;%WORK%\spi" ^
  -d "%WORK%\classes" "@%WORK%\plugin-src.txt"
if errorlevel 1 (
  echo [ERROR] javac failed for plugin sources
  exit /b 1
)
echo.
echo === [2/5] d8 to dex ===
if not exist "%WORK%\dex" mkdir "%WORK%\dex"
pushd "%WORK%\classes"
> "%WORK%\classes.txt" (
  for /r . %%F in (*.class) do echo %%F
)
popd
call "%BT%\d8.bat" --release --min-api 28 --lib "%PLATFORM%" ^
  --output "%WORK%\dex" "@%WORK%\classes.txt"
if errorlevel 1 (
  echo [ERROR] d8 failed
  exit /b 1
)

echo.
echo === [3/5] package apk ===
call "%BT%\aapt2.exe" link ^
  -I "%PLATFORM%" ^
  --manifest "%ROOT%apk\AndroidManifest.xml" ^
  --min-sdk-version 28 --target-sdk-version 37 ^
  -o "%WORK%\base.apk"
if errorlevel 1 (
  echo [ERROR] aapt2 link failed
  exit /b 1
)

pushd "%WORK%\dex"
"%JAR%" uf "%WORK%\base.apk" classes.dex
popd
if errorlevel 1 (
  echo [ERROR] failed to inject classes.dex
  exit /b 1
)

echo.
echo === [4/5] align then sign ===
rem ORDER MATTERS: zipalign must run BEFORE apksigner.
rem Aligning after signing rewrites the zip central directory and strips the
rem v2/v3 signature blocks, producing an APK that fails verification.
if not exist "%OUT%" mkdir "%OUT%"
call "%BT%\zipalign.exe" -f 4 "%WORK%\base.apk" "%WORK%\aligned.apk"
if errorlevel 1 (
  echo [ERROR] zipalign failed
  exit /b 1
)
call "%BT%\apksigner.bat" sign ^
  --ks "%KS%" --ks-pass pass:%KS_PASS% --ks-key-alias %KS_ALIAS% --key-pass pass:%KEY_PASS% ^
  --out "%OUT%\%APK_NAME%" "%WORK%\aligned.apk"
if errorlevel 1 (
  echo [ERROR] signing failed
  exit /b 1
)

rem Fail loudly rather than shipping an unusable artifact.
call "%BT%\apksigner.bat" verify "%OUT%\%APK_NAME%" >nul 2>&1
if errorlevel 1 (
  echo [ERROR] apksigner verify FAILED - artifact is not usable
  exit /b 1
)
echo   signature: OK
call "%BT%\apksigner.bat" verify --print-certs "%OUT%\%APK_NAME%" 2>&1 | findstr /i "Signer #1 certificate DN"

echo.
echo === [5/5] sha256 and manifest ===
set SHA256=
for /f "skip=1 tokens=* delims=" %%H in ('certutil -hashfile "%OUT%\%APK_NAME%" SHA256') do (
  if not defined SHA256 set SHA256=%%H
)
set SHA256=%SHA256: =%
echo   sha256 = %SHA256%

rem The manifest is published under the FIXED tag "hot-latest" (the shell
rem hardcodes that URL). The APK keeps a versioned tag so old builds stay
rem downloadable: if every release reused "hot-latest", each one would
rem overwrite the previous binary and leave no way to roll back.
set TAG=v%VERSION%
set URL=https://github.com/Kimjaejiang/WCXLC/releases/download/%TAG%/%APK_NAME%

> "%OUT%\hot-update.json" (
  echo {
  echo   "hotApi": 1,
  echo   "version": "%VERSION%",
  echo   "minShellVersionCode": 3180,
  echo   "minShellVersionName": "8.0.77",
  echo   "fileName": "%APK_NAME%",
  echo   "url": "%URL%",
  echo   "sha256": "%SHA256%",
  echo   "entryClass": "com.Johnny.wcx.hotplugin.ProbePlugin",
  echo   "changelog": "%CHANGELOG%"
  echo }
)

echo.
echo ============ DONE ============
echo output dir: %OUT%
type "%OUT%\hot-update.json"
echo.
echo next - publish in TWO places:
echo.
echo   1^) new Release, tag = %TAG%, upload:
echo        %APK_NAME%
echo      ^(versioned tag so this build stays downloadable^)
echo.
echo   2^) Release tagged hot-latest, REPLACE its asset:
echo        hot-update.json
echo      ^(the shell hardcodes this tag; if it does not exist yet,
echo       create it. Reusing one tag keeps the URL fixed, and keeps
echo       it immune to module-wide releases stealing releases/latest.^)
echo.
echo WARNING - UNCHECK "Set as the latest release" when publishing.
echo   GitHub checks it by default. If a plugin release becomes latest, the
echo   module updater (api.github.com/.../releases/latest) reads a plugin
echo   tag, fails to parse a 12-digit version code, and then believes the
echo   app is up to date forever - module-wide updates stop arriving.
echo.
endlocal
