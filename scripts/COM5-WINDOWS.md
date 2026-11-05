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
- 수동 화면: `모의 기록 생성` → 에너지 기록 선택 → 모의 규제 활성/정보 상실/갱신 중단 → 상세 숨김·중지 유지 → 제한 없음 복귀를 관찰한다. 모의 화면의 연결 제어는 실제 SDK/Bridge를 열지 않는다.

이 검수는 실제 주행 규제 전환, 운전자 표시 영역, 실제 FGS/알림 패널 중지, 내비 변경·복원 성공을 증명하지 않는다. 실제 앱의 상세 기록은 화면 정책 검증 전까지 차단한다. 내비 계약(COM-11) 연결 전에는 접수/완료/결과 불명과 복원 동작을 실제 호출로 검수할 수 없다. DDS 복구는 이 키트 범위 밖이다.

소스에서 다시 만들려면 저장소 루트에서 다음을 실행한다. 기존 버전·lockfile은 바꾸지 않는다.

```powershell
.\gradlew.bat -Pcom5Diagnostic=true :app:assembleDebug :app:assembleDebugAndroidTest
python scripts/package-com5-verification.py --output C:\Temp\COM5-verification-new.zip
```

패키지 도구는 커밋된 깨끗한 소스에서 진단 빌드를 다시 확인한다. Python 표준 라이브러리와 Android SDK Build Tools 36.0.0의 aapt를 사용한다. 이미 만들어진 키트를 실행할 때는 Python이 필요 없다.
