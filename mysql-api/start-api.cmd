@echo off
setlocal
set "TARGET=C:\WorckCloudR2-MySQL"
set "RAW=https://raw.githubusercontent.com/ningyun00/WorckCloudR2/Reconstruction/mysql-api"
if not exist "%TARGET%" mkdir "%TARGET%"
echo Updating WorkClockR2 MySQL API files...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing '%RAW%/server.js' -OutFile '%TARGET%\server.js'; Invoke-WebRequest -UseBasicParsing '%RAW%/package.json' -OutFile '%TARGET%\package.json'"
if errorlevel 1 (
  echo Download failed. Check network access and try again.
  pause
  exit /b 1
)
if not exist "%TARGET%\.env" (
  echo Creating .env template. Fill in your MySQL password and TOKEN_SECRET before starting.
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing '%RAW%/.env.example' -OutFile '%TARGET%\.env'"
  echo.
  echo Open %TARGET%\.env and replace the two placeholder values.
  pause
  exit /b 2
)
cd /d "%TARGET%"
echo Installing/updating required Node.js packages...
call npm.cmd install
if errorlevel 1 (
  echo npm install failed.
  pause
  exit /b 1
)
echo Starting API on 127.0.0.1:3000. Keep this window open.
call npm.cmd start
pause
