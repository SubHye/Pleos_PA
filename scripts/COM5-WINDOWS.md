# COM-22·25 Windows 검수

이 키트는 별도 패키지 `com.example.pleos_pa.com5`와 테스트 APK를 사용한다. 기존 Pleos_PA/COM-9 앱과 데이터를 삭제하거나 초기화하지 않는다. APK 두 개는 같은 빌드이며 설치 전 해시를 확인한다. 이미 같은 `.com5` 패키지가 다른 키로 설치되어 있다면 삭제하지 말고 설치 오류를 기록한다.

ZIP을 영문 경로에 풀고 그 폴더에서 PowerShell을 연다. Pleos 에뮬레이터를 실행하고 다음 명령을 한 단계씩 수행한다. 실제 기기 번호가 다르면 바꾼다. 스크립트는 지정한 에뮬레이터 외의 기기를 선택하지 않는다.

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force
.\scripts\verify-com5-local.ps1 -Stage Install -Serial emulator-5554
.\scripts\verify-com5-local.ps1 -Stage UiTest -Serial emulator-5554
.\scripts\verify-com5-local.ps1 -Stage ProcessRestart -Serial emulator-5554
.\scripts\verify-com5-local.ps1 -Stage Preview -Serial emulator-5554
```

ADB를 찾지 못하면 각 명령에 `-AdbPath 'C:\Users\user\AppData\Local\Android\Sdk\platform-tools\adb.exe'`를 추가한다. 설치는 adb push와 기기 내 60초 제한 pm install을 사용한다. 시간 초과 시 해당 ADB 클라이언트만 종료하며 기기 측 작업이 끝났다고 단정하지 않는다. 자동 재시도·앱 삭제·기기/서버 재시작을 하지 않는다.

결과는 `evidence` 아래 실행별 폴더의 `result.json`과 텍스트에 남는다. 실패는 `failure.txt`에 남는다. UiTest는 `OK (4 tests)`를 확인하며 ADB 종료 코드 0만으로 통과 처리하지 않는다.

- UI: 모의 규제 활성/정보 상실/갱신 중단 때 상세 숨김, 중지 버튼의 화면 내 표시 및 터치, 미확인 클라이언트, 되돌리기 비활성, 기록 삭제를 확인한다.
- 상한: Android에서 모의 2,000건 중 최근 100건과 문자열 상한을 확인하고 처리 시간·Java 힙 사용량을 측정한다. 이 수치는 Pleos의 최종 상한 채택 근거 전체가 아니다.
- 재시작: 공용 DriverHistory에 모의 기록을 넣고 **진단 패키지만** force-stop한다. 새 PID, 기록 0건, 중지 초기 상태를 비교한다. 기존 앱·AVD·ADB 서버는 종료하지 않는다.
- 수동 화면: `모의 기록 생성` → 에너지 기록 선택 → 모의 규제 활성/정보 상실/갱신 중단 → 상세 숨김·중지 유지 → 제한 없음 복귀를 관찰한다. 모의 화면의 연결 제어는 실제 SDK/MCP 서버를 열지 않는다.

앞의 UI/재시작 검수만으로 실제 주행 규제 전환, 운전자 표시 영역, 실제 FGS/알림 패널 중지, 내비 변경·복원 성공을 증명하지 않는다. 아래 ServiceTest는 실제 서비스/게시 알림 액션을 별도로 검사한다. 실제 앱의 상세 기록은 화면 정책 검증 전까지 차단한다. 내비 계약(COM-11) 연결 전에는 접수/완료/결과 불명과 복원 동작을 실제 호출로 검수할 수 없다. DDS 복구는 이 키트 범위 밖이다.

소스에서 다시 만들려면 저장소 루트에서 다음을 실행한다. 기존 버전·lockfile은 바꾸지 않는다.

```powershell
.\gradlew.bat -Pcom5Diagnostic=true :app:assembleDebug :app:assembleDebugAndroidTest
python scripts/package-com5-verification.py --output C:\Temp\COM5-verification-new.zip
```

패키지 도구는 커밋된 깨끗한 소스에서 진단 빌드를 다시 확인한다. 소스 커밋 식별용 Git, Python 표준 라이브러리와 Android SDK Build Tools 36.0.0의 aapt를 사용한다. 이미 만들어진 키트를 실행할 때는 Python이 필요 없다.

## COM-22 실제 경로 추가 검수

같은 키트 설치 후, 외부 AI/MCP 호출을 멈춘 상태에서 다음을 실행한다. 다른 앱이 18766 포트를 쓰고 있으면 오류로 기록하며 스크립트가 그 앱을 중지하지 않는다.

```powershell
.\scripts\verify-com5-local.ps1 -Stage PermissionTest -Serial emulator-5554
.\scripts\verify-com5-local.ps1 -Stage ServiceTest -Serial emulator-5554
.\scripts\verify-com5-local.ps1 -Stage LiveStart -Serial emulator-5554
.\scripts\verify-com5-local.ps1 -Stage LiveCapture -Serial emulator-5554
```

PermissionTest는 진단 앱에 한해서 알림 권한을 회수하고 해당 권한의 user-set/user-fixed 플래그를 해제한 뒤 실제 권한 요청 창에서 거부하고, 안내 표시와 실제 화면 중지를 검사한다(1개 테스트). 권한 회수로 진단 프로세스가 종료될 수 있다. 고유 앱 이름 `Pleos PA 검수 (.com5)`를 확인해 다른 앱의 권한 창은 누르지 않는다. 이어서 ServiceTest를 실행하면 진단 앱의 알림 권한을 부여한다. 기존 앱·차량 권한은 변경하지 않는다.

ServiceTest는 실제 MainActivity·Foreground Service·MCP 초기화/tools/list·게시된 알림 PendingIntent·18766 소켓을 사용한다. 진단 앱에 POST_NOTIFICATIONS 권한만 부여하며 Car 권한/정책은 우회하지 않는다. 차량 조회 요청은 보내지 않는다. 3개 테스트가 모두 통과해야 성공이며, 이 결과는 실제 알림 패널의 터치/주행 중 접근성을 증명하지 않는다.

LiveStart는 실제 플랫폼 관찰 화면을 열며 SDK의 UX 값이나 기어를 주입하지 않는다. LiveStart 후 최초 API 응답을 몇 초 기다리고 LiveCapture한다. 이후 화면의 `실제 UX 관찰 연결 해제`를 누르고 다시 LiveCapture한다. `화면 제한 상태 다시 확인` 후 한 번 더 LiveCapture하여 실제 초기 조회·관찰 연결 해제·재관찰을 각각 보관한다. 세션 UUID로 로그를 구분하고 캐시된 이전 값을 현재 연결의 증거로 사용하지 않는다. 콜백에 실제 전달된 제한값은 CALLBACK/ux_callback으로, 이후 현재값 재조회는 SAMPLE/callback_refresh로 구분한다. 폴링 SAMPLE/poll을 실제 제한 전환 callback으로 계산하지 않는다. elapsedMillis는 클라이언트의 수신/조회 시각이며 차량 센서의 측정 시각이 아니다.

`cleanupCallsSucceeded`는 클라이언트의 해제 API 호출이 오류 없이 반환됐다는 뜻이다. 시스템 전체 리스너 수나 실제 주행 전환 성공을 뜻하지 않는다. driverDisplayVerified/historyAllowed는 계속 false다. sourceCurrentlyLive는 최신 SAMPLE의 단말 monotonic 시각으로 판단하며, captureOnly 결과는 AT-11 통과 표시가 아니다. 일반 Android에서는 UNAVAILABLE/null을 기록하고 값을 만들어내지 않는다.

DDS unavailable이 계속되면 주행 전환은 시도 성공으로 기록하지 않는다. 실제 규제 callback이 없으면 restrictedCallbackObserved=false로 남긴다. 실제 Pleos의 정책·운전자 표시 영역·알림 패널 접근성과 허용 결과 안내 검수는 별도로 필요하다. 다른 이슈인 COM-25의 기록 상한/내비 계약을 COM-22의 추가 완료 조건으로 삼지 않는다.

## COM-25 실제 요청·메모리 기록 검수

같은 키트를 설치하고 외부 AI/MCP 호출을 멈춘 상태에서 실행한다.

```powershell
.\scripts\verify-com5-local.ps1 -Stage HistoryTest -Serial emulator-5554
```

HistoryTest는 별도 `.com5` 앱에서 실제 MCP 초기화와 tools/call을 127.0.0.1:18766/mcp의 내장 서버/게이트웨이/공용 DriverHistory로 보낸다. 시작 전에 진단 기록이 남아 있으면 실패하고 기존 기록을 지우지 않는다. 필요한 경우 진단 앱에서 직접 `메모리 기록 지우기`를 누른 뒤 다시 실행한다. 다른 앱이나 그 데이터는 변경하지 않는다.

POST_NOTIFICATIONS만 진단 앱에 부여한다. 거부할 합성 요청1개와 읽기 전용 에너지 조회(고정 계약의 연료·EV 잔량)와 온도 표시 단위 조회 각1개를 보낸다. Pleos에서는 SDK가 실제 값을 읽을 수 있으나 차량 값/기어/정책을 주입하지 않는다. 게이트웨이 requestId는 이제 서버가 호출마다 생성한다. 두 MCP 결과의 requestId를 해당 기록과 대조하고 서로 다른 시도인지 확인한다. 내부 중복 ID의 분리는 JVM 모델 테스트로 별도 검증한다. 실패한 조회를 재시도하지 않으며 두 조회는 동시 스냅샷이 아니다. 실제 SDK의 OK 값이 계약 검증을 통과하고 메모리 값과 일치한 경우에만 `sdkOkValueObserved=true`다. 일반 Android는 `APP_NOT_READY`를 기록하며 실제 SDK 성공으로 보고하지 않는다. 실제 SDK가 오류를 반환해도 그 상태를 기록하므로 테스트 성공만으로 값 조회 성공을 판단하지 않는다.

거부된 입력 미저장, 서버가 생성한 requestId별 시도2건, Activity 재생성 후 메모리·연결 상태 유지와 중지 뒤 재개 버튼 복원, 기본 제한 중 상세 숨김, 직접 UI 삭제, 중지 후 새 연결 거부를 확인한다. 이 테스트가 만든2건만 UI에서 지우며, 다른 요청이 끼면 삭제 전에 실패한다. 차량 값/요청 원문은 증거 로그에 출력하지 않는다. SDK 자체 로그의 값은 수집하지 않는다.

결과 `result.json`의 `history`에는 MCP 결과 코드(mcpCodes)·출처·불리언 검증값만 저장한다. `OK (1 test)` 및 해당 결과가 모두 있어야 성공이다. 실제 결과 상세 화면·운전자 표시 영역/주행 정책·최종 힙 상한·내비 연동은 별도 검수다.

COM-12 이후에는 Node 서버와 18765 HTTP Bridge가 없다. 이전 HTTP 방식 키트의 HistoryTest는 사용하지 않고 최신 내장 MCP 키트를 설치한다. MCP의 APP_NOT_READY는 tools/call의 구조화된 오류이며 HTTP503으로 판정하지 않는다. 클라이언트가 떠나도 Ktor가 조회 취소를 알리지 않아 조회는 데드라인까지 계속될 수 있다. 연결됨은 MCP 서버 준비 상태이며 AI의 접속/이탈을 뜻하지 않는다(ADR-0008).


## 알림 검수 범위

현재 게시 알림은 고정된 연결 상태와 중지/재개만 제공한다. 차량 조회 값·목적지·경로·연락처·메시지·SDK 오류 원문은 알림에 넣지 않는다. 오류 알림도 고정 문구로 표시하며 상세 결과 미리보기/음성 자동 안내는 제공하지 않는다. 결과 내용을 허용할 OEM 정책 근거가 없는 동안 이 범위를 유지한다.

ServiceTest는 실제 게시 알림의 제목/연결 상태 문구, 오류 상세 비노출 및 게시된 중지 액션을 검사한다. 이는 실제 D 상태에서 차량 화면으로 알림 패널을 여는 경로나 향후 결과 미리보기의 허용을 대신하지 않는다. 현재 기기에서 패널 자체의 아이콘/제스처가 확인되지 않았다면 `cmd statusbar expand-notifications`로 열었다는 사실을 증거에 구분해 남긴다.
