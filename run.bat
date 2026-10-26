@echo off
setlocal
chcp 65001 >nul
cd /d "%~dp0"
java -cp out fatfs.Main
if errorlevel 1 (
  echo 程序启动或运行失败，请检查上方错误信息。
  pause
  exit /b 1
)
exit /b 0
