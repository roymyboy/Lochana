@echo off
echo Quick Build and Install
echo.

echo Building project...
call .\gradlew.bat build
if %errorlevel% neq 0 (
    echo Build failed!
    pause
    exit /b 1
)

echo Build successful!

echo Installing APK...
"C:\Users\utsav\AppData\Local\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
if %errorlevel% neq 0 (
    echo Installation failed!
    pause
    exit /b 1
)

echo APK installed successfully!

echo Launching app...
"C:\Users\utsav\AppData\Local\Android\Sdk\platform-tools\adb.exe" shell am start -n com.lochana.app/.MainActivity

echo Done! App is running on emulator.
