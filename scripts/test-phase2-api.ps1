# 2차 개발 API 자동 점검 (DB · 회원가입/로그인 · 관심 종목)
# 사용법: 백엔드(8085)를 켠 뒤 PowerShell 에서
#   powershell -ExecutionPolicy Bypass -File scripts\test-phase2-api.ps1
# 실행할 때마다 t2a·t2b 로 시작하는 테스트 계정 2개가 새로 만들어진다 (정리 SQL은 docs/2차개발_구현정리.md 참고)

param([string]$BaseUrl = 'http://localhost:8085')

$ErrorActionPreference = 'Stop'
$script:pass = 0
$script:fail = 0

# 요청을 보내고 { Status, Json, Raw } 를 돌려준다. 4xx·5xx 도 예외 없이 값으로 받는다
function Call($method, $path, $body = $null, $session = $null, $headers = @{}) {
  # 값을 바꾸는 요청에는 CSRF 토큰이 필요하다 (세션이 없으면 임시 세션으로 받는다)
  if ($method -in @('POST', 'DELETE')) {
    if (-not $session) { $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession }
    $csrf = Invoke-WebRequest -Uri "$BaseUrl/api/auth/csrf" -WebSession $session -UseBasicParsing
    $token = ([Text.Encoding]::UTF8.GetString($csrf.RawContentStream.ToArray()) | ConvertFrom-Json).token
    $headers = @{} + $headers
    $headers['X-CSRF-TOKEN'] = $token
  }
  $params = @{ Uri = "$BaseUrl$path"; Method = $method; UseBasicParsing = $true; Headers = $headers }
  if ($session) { $params.WebSession = $session }
  if ($null -ne $body) {
    $json = if ($body -is [string]) { $body } else { $body | ConvertTo-Json -Compress }
    $params.Body = [Text.Encoding]::UTF8.GetBytes($json)   # 한글이 깨지지 않도록 UTF-8 바이트로 보낸다
    $params.ContentType = 'application/json; charset=utf-8'
  }
  try {
    $res = Invoke-WebRequest @params
    $status = [int]$res.StatusCode
    $raw = [Text.Encoding]::UTF8.GetString($res.RawContentStream.ToArray())
    $resHeaders = $res.Headers
  } catch [System.Net.WebException] {
    $r = $_.Exception.Response
    if (-not $r) { throw "서버에 연결할 수 없습니다: $BaseUrl (백엔드가 켜져 있는지 확인)" }
    $status = [int]$r.StatusCode
    $reader = New-Object IO.StreamReader($r.GetResponseStream(), [Text.Encoding]::UTF8)
    $raw = $reader.ReadToEnd()
    $resHeaders = $r.Headers
  }
  $parsed = $null
  if ($raw) { try { $parsed = $raw | ConvertFrom-Json } catch {} }
  return [pscustomobject]@{ Status = $status; Json = $parsed; Raw = $raw; Headers = $resHeaders }
}

function Check($name, [bool]$ok, $detail = '') {
  if ($ok) { $script:pass++; Write-Host "  [PASS] $name" -ForegroundColor Green }
  else { $script:fail++; Write-Host "  [FAIL] $name  $detail" -ForegroundColor Red }
}

function NewSession { New-Object Microsoft.PowerShell.Commands.WebRequestSession }
function Symbols($res) { @($res.Json.list | ForEach-Object { $_.symbol }) -join ',' }

$suffix = Get-Random -Minimum 10000 -Maximum 99999
$idA = "t2a$suffix"
$idB = "t2b$suffix"
$pw = 'password123'

Write-Host "`n=== 1. DB · 기존 1차 API ==="
$r = Call GET '/api/stocks?query='
Check '종목 목록 조회 (company 테이블)' ($r.Status -eq 200 -and (Symbols $r) -match '005930') "status=$($r.Status) $($r.Raw)"
$r = Call GET '/api/stocks?query=%EC%82%BC%EC%84%B1'   # "삼성"
Check '종목명 검색 "삼성" → 삼성전자' ($r.Status -eq 200 -and (Symbols $r) -eq '005930') $r.Raw
$r = Call GET '/api/stocks/999999'
Check '없는 종목 404' ($r.Status -eq 404) "status=$($r.Status)"
$r = Call GET '/api/market/status'
Check '시세 수신 상태 조회' ($r.Status -eq 200) "status=$($r.Status)"

Write-Host "`n=== 2. 회원가입 ==="
$sA = NewSession
$r = Call POST '/api/auth/signup' @{ loginId = $idA; password = $pw; nickname = '테스트A' } $sA
Check "가입 성공 201 ($idA)" ($r.Status -eq 201 -and $r.Json.user.loginId -eq $idA) "status=$($r.Status) $($r.Raw)"
Check '한글 닉네임 저장' ($r.Json.user.nickname -eq '테스트A') $r.Raw
Check '응답에 비밀번호(해시) 없음' ($r.Raw -notmatch 'password') $r.Raw
$r = Call GET '/api/auth/me' $null $sA
Check '가입만 하면 로그인 상태가 아님 401' ($r.Status -eq 401) "status=$($r.Status)"
$r = Call POST '/api/auth/login' @{ loginId = $idA; password = $pw } $sA
Check '가입한 계정으로 로그인 200' ($r.Status -eq 200) "status=$($r.Status)"
$r = Call POST '/api/auth/signup' @{ loginId = $idA; password = $pw; nickname = '중복' }
Check '같은 아이디 재가입 409' ($r.Status -eq 409) "status=$($r.Status) $($r.Raw)"
$r = Call POST '/api/auth/signup' @{ loginId = 'AB'; password = $pw; nickname = '형식' }
Check '아이디 형식 오류 400' ($r.Status -eq 400) "status=$($r.Status)"
$r = Call POST '/api/auth/signup' @{ loginId = "x$suffix"; password = '1234'; nickname = '형식' }
Check '비밀번호 8자 미만 400' ($r.Status -eq 400) "status=$($r.Status)"
$r = Call POST '/api/auth/signup' @{ loginId = "y$suffix"; password = $pw; nickname = 'a' }
Check '닉네임 2자 미만 400' ($r.Status -eq 400) "status=$($r.Status)"
$r = Call POST '/api/auth/signup' '{"loginId":'
Check '잘못된 JSON 400' ($r.Status -eq 400) "status=$($r.Status)"

Write-Host "`n=== 3. 로그인 · 로그아웃 ==="
$r = Call POST '/api/auth/logout' $null $sA
Check '로그아웃 204' ($r.Status -eq 204) "status=$($r.Status)"
$r = Call GET '/api/auth/me' $null $sA
Check '로그아웃 후 me 401' ($r.Status -eq 401) "status=$($r.Status)"
$r = Call POST '/api/auth/login' @{ loginId = $idA; password = 'wrongpass' }
$wrongPwMsg = $r.Json.error
Check '틀린 비밀번호 401' ($r.Status -eq 401) "status=$($r.Status)"
$r = Call POST '/api/auth/login' @{ loginId = "none$suffix"; password = $pw }
Check '없는 아이디 401 + 같은 문구 (어느 쪽이 틀렸는지 숨김)' ($r.Status -eq 401 -and $r.Json.error -eq $wrongPwMsg) $r.Raw
$sA = NewSession
$r = Call POST '/api/auth/login' @{ loginId = $idA; password = $pw } $sA
Check '로그인 200' ($r.Status -eq 200 -and $r.Json.user.loginId -eq $idA) "status=$($r.Status)"
$r = Call GET '/api/auth/me' $null $sA
Check '로그인 후 me 200' ($r.Status -eq 200) "status=$($r.Status)"

Write-Host "`n=== 4. 관심 종목 (로그인 필요) ==="
$r = Call GET '/api/watchlist'
Check '로그인 없이 조회 401' ($r.Status -eq 401) "status=$($r.Status)"
$r = Call POST '/api/watchlist' @{ symbol = '005930' }
Check '로그인 없이 추가 401' ($r.Status -eq 401) "status=$($r.Status)"
$r = Call DELETE '/api/watchlist/005930'
Check '로그인 없이 삭제 401' ($r.Status -eq 401) "status=$($r.Status)"
$r = Call GET '/api/watchlist' $null $sA
Check '새 계정은 빈 목록' ($r.Status -eq 200 -and @($r.Json.list).Count -eq 0) $r.Raw
$r = Call POST '/api/watchlist' @{ symbol = '005930' } $sA
Check '005930 추가' ($r.Status -eq 200 -and (Symbols $r) -match '005930') $r.Raw
$r = Call POST '/api/watchlist' @{ symbol = '000660' } $sA
Check '000660 추가 → 2개' ($r.Status -eq 200 -and @($r.Json.list).Count -eq 2) $r.Raw
$r = Call POST '/api/watchlist' @{ symbol = '005930' } $sA
Check '같은 종목 다시 추가해도 2개 (중복 없음)' ($r.Status -eq 200 -and @($r.Json.list).Count -eq 2) $r.Raw
$r = Call POST '/api/watchlist' @{ symbol = '123456' } $sA
Check '지원하지 않는 종목 404' ($r.Status -eq 404) "status=$($r.Status)"
$r = Call POST '/api/watchlist' @{ symbol = 'abc' } $sA
Check '종목코드 형식 오류 400' ($r.Status -eq 400) "status=$($r.Status)"
$r = Call DELETE '/api/watchlist/abc' $null $sA
Check '삭제 종목코드 형식 오류 400' ($r.Status -eq 400) "status=$($r.Status)"

Write-Host "`n=== 5. 계정별 분리 ==="
$sB = NewSession
$null = Call POST '/api/auth/signup' @{ loginId = $idB; password = $pw; nickname = '테스트B' } $sB
$null = Call POST '/api/auth/login' @{ loginId = $idB; password = $pw } $sB
$r = Call GET '/api/watchlist' $null $sB
Check 'B 계정에는 A 관심 종목이 안 보임' ($r.Status -eq 200 -and @($r.Json.list).Count -eq 0) $r.Raw
$null = Call DELETE '/api/watchlist/005930' $null $sB
$r = Call GET '/api/watchlist' $null $sA
Check 'B가 삭제를 시도해도 A 목록 그대로' (@($r.Json.list).Count -eq 2) $r.Raw
$null = Call POST '/api/watchlist' @{ symbol = '000660' } $sB
$r = Call GET '/api/watchlist' $null $sA
Check 'B가 추가해도 A 목록 그대로' (@($r.Json.list).Count -eq 2) $r.Raw

Write-Host "`n=== 6. 재로그인 후 유지 · 삭제 ==="
$null = Call POST '/api/auth/logout' $null $sA
$sA = NewSession
$null = Call POST '/api/auth/login' @{ loginId = $idA; password = $pw } $sA
$r = Call GET '/api/watchlist' $null $sA
Check '로그아웃 → 재로그인 후 2개 유지' (@($r.Json.list).Count -eq 2) $r.Raw
$r = Call DELETE '/api/watchlist/005930' $null $sA
Check '005930 삭제 → 000660만 남음' ($r.Status -eq 200 -and (Symbols $r) -eq '000660') $r.Raw
$r = Call DELETE '/api/watchlist/005930' $null $sA
Check '이미 지운 종목 다시 삭제해도 오류 없음' ($r.Status -eq 200) "status=$($r.Status)"

Write-Host "`n=== 7. CORS (프론트 주소만 쿠키 포함 허용) ==="
$pre = @{ 'Origin' = 'http://localhost:8086'; 'Access-Control-Request-Method' = 'POST'; 'Access-Control-Request-Headers' = 'content-type' }
$r = Call OPTIONS '/api/auth/login' $null $null $pre
Check '8086 사전 요청 허용 + credentials' ($r.Status -eq 200 -and $r.Headers['Access-Control-Allow-Credentials'] -eq 'true') "status=$($r.Status)"
$pre.Origin = 'http://evil.example.com'
$r = Call OPTIONS '/api/auth/login' $null $null $pre
Check '다른 주소 사전 요청 거부 403' ($r.Status -eq 403) "status=$($r.Status)"

Write-Host ''
$color = if ($script:fail -eq 0) { 'Green' } else { 'Red' }
Write-Host "결과: 통과 $($script:pass) / 실패 $($script:fail)   (테스트 계정: $idA, $idB)" -ForegroundColor $color
if ($script:fail -gt 0) { exit 1 }
