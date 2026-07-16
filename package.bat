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
REM ============================================================================

setlocal
set APP_NAME=TailorAgent
set APP_VERSION=0.1.5
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
  %WIN_OPTS%

if errorlevel 1 (
  echo [ERROR] jpackage failed. For msi/exe make sure WiX Toolset v3 is on PATH.
  exit /b 1
)

echo [OK] Output written to: %DEST_DIR%
endlocal
