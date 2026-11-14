@echo off
rem Stand-in for mvnw.cmd: the same process chain as the real wrapper (cmd.exe -> java.exe).
"%STAND_JAVA%" -cp "%STAND_CP%" Stand %*
exit /b %ERRORLEVEL%
