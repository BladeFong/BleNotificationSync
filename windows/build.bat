@echo off
REM Build script for LibTomCrypt on Windows
REM Requires CMake and Visual Studio Build Tools

setlocal

set PROJECT_ROOT=%~dp0..
set LIBTOMCRYPT_DIR=%PROJECT_ROOT%\third_party\libtomcrypt
set LIBTOMMATH_DIR=%PROJECT_ROOT%\third_party\libtommath
set BUILD_DIR=%~dp0build\libtomcrypt
set INSTALL_DIR=%~dp0install

echo Building LibTomCrypt...

REM Create build directory
if not exist "%BUILD_DIR%" mkdir "%BUILD_DIR%"

REM Configure
echo Configuring...
cmake -S "%LIBTOMCRYPT_DIR%" -B "%BUILD_DIR%" ^
    -DCMAKE_BUILD_TYPE=Release ^
    -DBUILD_SHARED_LIBS=ON ^
    -DWITH_LTM=ON ^
    -DLIBTOMMATH_SOURCE_DIR=%LIBTOMMATH_DIR% ^
    -DCMAKE_INSTALL_PREFIX=%INSTALL_DIR%

if %ERRORLEVEL% neq 0 (
    echo CMake configure failed!
    exit /b 1
)

REM Build
echo Building...
cmake --build "%BUILD_DIR%" --config Release --parallel

if %ERRORLEVEL% neq 0 (
    echo Build failed!
    exit /b 1
)

REM Install
echo Installing...
cmake --install "%BUILD_DIR%" --config Release

if %ERRORLEVEL% neq 0 (
    echo Install failed!
    exit /b 1
)

echo LibTomCrypt built successfully!
echo Library installed to: %INSTALL_DIR%

endlocal
