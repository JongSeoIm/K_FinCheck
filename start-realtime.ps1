param(
    [int]$Port = 8080,
    [string]$MavenCommand = 'mvn'
)

$ErrorActionPreference = 'Stop'
$projectDirectory = $PSScriptRoot
$backendDirectory = Join-Path $projectDirectory 'backend'

if (-not (Get-Command $MavenCommand -ErrorAction SilentlyContinue)) {
    $mavenCandidates = @(Get-ChildItem -LiteralPath (Join-Path $env:USERPROFILE '.m2/wrapper/dists') -Filter mvn.cmd -Recurse -ErrorAction SilentlyContinue |
        Where-Object FullName -Match 'apache-maven-3\.9\.' | Sort-Object FullName -Descending)
    if ($mavenCandidates.Count -eq 0) { throw 'Maven을 설치하거나 -MavenCommand로 mvn.cmd 경로를 지정하세요.' }
    $MavenCommand = $mavenCandidates[0].FullName
}

if (-not $env:JAVA_HOME -or -not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin/java.exe'))) {
    $jdkCandidates = @(Get-ChildItem -LiteralPath (Join-Path $env:ProgramFiles 'Java') -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue)
    if ($jdkCandidates.Count -eq 0) { throw 'Java 21 JDK의 JAVA_HOME을 설정하세요.' }
    $env:JAVA_HOME = $jdkCandidates[0].FullName
}

Push-Location -LiteralPath $backendDirectory
try {
    # Compile tests, but never run the existing live KIS integration tests here.
    & $MavenCommand -B -q test-compile dependency:build-classpath '-Dmdep.includeScope=test' '-Dmdep.outputFile=target/realtime-classpath.txt'
    if ($LASTEXITCODE -ne 0) { throw '실시간 개발 환경 준비에 실패했습니다.' }
    $dependencyClasspath = [IO.File]::ReadAllText((Join-Path $backendDirectory 'target/realtime-classpath.txt'))
    $applicationClasspath = "target/classes;target/test-classes;$dependencyClasspath"
    & (Join-Path $env:JAVA_HOME 'bin/java.exe') -cp $applicationClasspath com.kfincheck.realtime.LocalRealtimeApplication "--server.port=$Port"
    if ($LASTEXITCODE -ne 0) { throw '실시간 서버 실행이 종료되었습니다. 앞선 오류를 확인하세요.' }
} finally {
    Pop-Location
}
