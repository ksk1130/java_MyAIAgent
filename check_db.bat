@echo off
setlocal

cd /d "%~dp0"

echo Derby DB の内容を確認します...
echo.

java -cp "app\build\install\app\lib\*" org.apache.derby.tools.ij << EOF
connect 'jdbc:derby:cobol_dependencies';
SELECT 'variable_definitions テーブル:' AS info FROM SYSIBM.SYSDUMMY1;
SELECT * FROM variable_definitions;
SELECT 'variable_assignments テーブル:' AS info FROM SYSIBM.SYSDUMMY1;
SELECT * FROM variable_assignments;
disconnect;
exit;
EOF

endlocal
