@echo off
REM ============================================================================
REM  TailorAgent - Windows native packaging script (jpackage)
REM ----------------------------------------------------------------------------
REM  Prerequisites:
REM    1. JDK 21 (bundles jpackage). JAVA_HOME must point to it.
REM    2. To build .msi/.exe you need WiX Toolset v3 (https://wixtoolset.org/)
REM       on PATH. The "app-image" type needs no WiX.
REM
REM  Usage:
REM    package.bat            build a .msi installer (needs WiX)
REM    package.bat app-image  build a portable app folder only (no WiX needed)
REM    package.bat cds-train  run a CDS training run (validates startup + builds
REM                           a .jsa archive on THIS machine; no install package)
REM
REM  CDS (Class Data Sharing):
REM    The installer passes -XX:+AutoCreateSharedArchive with
REM    -XX:SharedArchiveFile=$APPDIR\app\tailoragent.jsa, so the FIRST run after
REM    installation creates the archive and later runs load it for faster startup.
REM    A pre-built archive is NOT bundled because CDS archives embed the machine
REM    absolute classpath (JDK-8279366) and would be rejected on other machines.
REM ============================================================================

setlocal
set APP_NAME=TailorAgent
set APP_VERSION=0.1.6
set MAIN_JAR=TailorAgent-0.0.1-SNAPSHOT.jar
REM Launcher class of a Spring Boot 3.2+ executable fat jar
set MAIN_CLASS=org.springframework.boot.loader.launch.JarLauncher

set PKG_TYPE=msi
if not "%~1"=="" set PKG_TYPE=%~1

set PROJECT_DIR=%~dp0
set FAT_JAR=%PROJECT_DIR%target\%MAIN_JAR%
set APP_ICON=%PROJECT_DIR%src\main\resources\icons\app-icon.ico
set STAGE_DIR=%PROJECT_DIR%target\jpackage-input
set DEST_DIR=%PROJECT_DIR%dist

REM Always use the jpackage from JDK 21 via JAVA_HOME. PATH may have another
REM JDK (e.g. 17) whose runtime is too old to run Java 21 classes.
if "%JAVA_HOME%"=="" (
  echo [ERROR] JAVA_HOME is not set; cannot locate the JDK 21 jpackage.
  exit /b 1
)
set JPACKAGE="%JAVA_HOME%\bin\jpackage.exe"
set JAVA_EXE="%JAVA_HOME%\bin\java.exe"
if not exist %JPACKAGE% (
  echo [ERROR] %JPACKAGE% not found. Make sure JAVA_HOME points to JDK 21+.
  exit /b 1
)

if not exist "%FAT_JAR%" (
  echo [ERROR] %FAT_JAR% not found. Run "mvnw.cmd clean package" first.
  exit /b 1
)
if not exist "%APP_ICON%" (
  echo [ERROR] Application icon not found: %APP_ICON%
  exit /b 1
)

REM WiX Toolset v3 (candle.exe / light.exe) location. jpackage locates WiX
REM via PATH; prepend it here so there is no need to edit the system PATH.
REM Change WIX_DIR if you extracted WiX somewhere else.
set WIX_DIR=C:\wix314-binaries
if exist "%WIX_DIR%\candle.exe" set PATH=%WIX_DIR%;%PATH%

REM CDS training run: no install package, just validate the startup path and
REM create a .jsa archive on this machine.
if /i "%PKG_TYPE%"=="cds-train" goto :cds-train

REM jpackage copies the whole --input dir, so stage only the fat jar.
if exist "%STAGE_DIR%" rmdir /s /q "%STAGE_DIR%"
mkdir "%STAGE_DIR%"
copy /y "%FAT_JAR%" "%STAGE_DIR%\" >nul

if exist "%DEST_DIR%" rmdir /s /q "%DEST_DIR%"
mkdir "%DEST_DIR%"

REM The --win-* options are only valid for installer types (msi/exe),
REM not for app-image (a portable folder).
REM --win-upgrade-uuid is FIXED so the upgrade chain stays stable and the
REM ProductCode is predictable (without it every build gets a random UUID,
REM leaving old versions installed side-by-side and uninstalls fighting).
REM This GUID must NEVER change once published.
set WIN_OPTS=
if /i not "%PKG_TYPE%"=="app-image" set WIN_OPTS=--win-menu --win-shortcut --win-dir-chooser --win-upgrade-uuid b39f2ed9-646e-4a8f-9f64-eefa740809fb

echo [INFO] Running jpackage, type=%PKG_TYPE% ...

REM CDS: auto-create the archive on the first run in the install dir and load
REM it from the second run on. $APPDIR is expanded by the jpackage launcher.
%JPACKAGE% ^
  --type %PKG_TYPE% ^
  --name %APP_NAME% ^
  --app-version %APP_VERSION% ^
  --input "%STAGE_DIR%" ^
  --main-jar %MAIN_JAR% ^
  --main-class %MAIN_CLASS% ^
  --dest "%DEST_DIR%" ^
  --icon "%APP_ICON%" ^
  --vendor "Changy" ^
  --java-options "-XX:+AutoCreateSharedArchive -XX:SharedArchiveFile=$APPDIR\app\tailoragent.jsa" ^
  %WIN_OPTS%

if errorlevel 1 (
  echo [ERROR] jpackage failed. For msi/exe make sure WiX Toolset v3 is on PATH.
  exit /b 1
)

echo [OK] Output written to: %DEST_DIR%
endlocal
exit /b 0

:cds-train
REM ----------------------------------------------------------------------------
REM CDS training run: start the app with the same -XX options the installer
REM uses, but exit right after the Spring context has refreshed
REM (-Dspring.context.exit=onRefresh). JCEF is skipped so no Chromium process
REM starts on the build machine. The generated .jsa validates that the startup
REM path and the JVM options work; the installed app auto-creates and loads
REM its own archive (classpath differs per machine, so nothing is bundled).
REM ----------------------------------------------------------------------------
set TRAIN_DIR=%PROJECT_DIR%target\cds-train
if exist "%TRAIN_DIR%" rmdir /s /q "%TRAIN_DIR%"
mkdir "%TRAIN_DIR%"

echo [INFO] CDS training run: context starts, then exits on refresh ...
%JAVA_EXE% ^
  -XX:+AutoCreateSharedArchive ^
  -XX:SharedArchiveFile="%TRAIN_DIR%\tailoragent.jsa" ^
  -Dspring.context.exit=onRefresh ^
  -Dtailoragent.skip-jcef=true ^
  -jar "%FAT_JAR%" ^
  --spring.main.banner-mode=off

if errorlevel 1 (
  echo [ERROR] CDS training run failed.
  exit /b 1
)
if not exist "%TRAIN_DIR%\tailoragent.jsa" (
  echo [ERROR] CDS archive was not created: %TRAIN_DIR%\tailoragent.jsa
  exit /b 1
)
REM AutoCreate marks the archive read-only; clear the attribute so that
REM `mvn clean` can later delete the training output (a read-only file
REM blocks maven-clean-plugin on Windows).
attrib -R "%TRAIN_DIR%\tailoragent.jsa"
echo [OK] CDS archive created: %TRAIN_DIR%\tailoragent.jsa
echo [INFO] The installer does not bundle this archive; the installed app
echo        creates it on first run and loads it from the second run on.
endlocal
exit /b 0
