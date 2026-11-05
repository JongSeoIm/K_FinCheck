param([int]$Port = 8080)

# backend 폴더에 두고 실행합니다: .\start-realtime.ps1
# 개발용 내장 Kafka(Docker 없음)와 함께 백엔드를 실행합니다. 종료는 Ctrl+C 입니다.
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath $PSScriptRoot

# 1) 컴파일하고 테스트 포함 라이브러리 경로를 파일로 저장합니다. (실제 KIS 테스트는 실행하지 않습니다.)
& .\mvnw.cmd -B -q test-compile dependency:build-classpath "-Dmdep.includeScope=test" "-Dmdep.outputFile=target/realtime-classpath.txt"
if ($LASTEXITCODE -ne 0) { throw '실행 준비에 실패했습니다. 위의 오류를 확인하세요.' }

# 2) 저장한 경로로 java 를 직접 실행합니다.
$dependencies = [IO.File]::ReadAllText((Join-Path $PSScriptRoot 'target/realtime-classpath.txt')).Trim()
$java = 'java'
if ($env:JAVA_HOME -and (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    $java = Join-Path $env:JAVA_HOME 'bin/java.exe'
}
& $java -cp "target/classes;target/test-classes;$dependencies" com.example.stock_api.realtime.LocalRealtimeApplication "--server.port=$Port"
