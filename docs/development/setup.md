# 개발 환경 재현

## 확인한 기준 (2026-11-01)

| 항목 | 값 / 근거 |
| --- | --- |
| Android Studio | 로컬 AI-262.9437.185.2621.16467767 (2026.2.1 계열); IDE 정확한 최소 버전은 TBD |
| Gradle / AGP | Wrapper 9.6.0 / catalog 9.4.1, 변경하지 않음 |
| 실행 JDK | gradle-daemon-jvm.properties가 Java 25 요구; 로컬 Studio JBR 25.0.3 설치 |
| Java 컴파일 타깃 | app/build.gradle.kts의 11; 실행 JDK와 다름 |
| Kotlin | AGP 내장 지원 사용. 별도 플러그인 없음. Wrapper 출력 Kotlin 2.3.21은 Gradle 내부 버전이며 앱 컴파일러 버전으로 인용하지 않음; 실제 앱 컴파일러 정확한 버전 TBD |
| Android SDK | compile/target 37, min 28. 로컬 platforms;android-37.0 / build-tools;36.0.0 설치 |
| Pleos SDK | 신규 앱에는 없음. PoC Vehicle 2.3.2를 선택 이전할 후보 |
| Node / npm | 신규 앱 미사용. 로컬 24.15.0 / 12.2.0; PoC Node >=24.15.0 및 package-lock 존재 |
| Emulator | 신규 앱 실행 미검증. PoC 문서의 Pleos_Connect_v2.1.0/API 34는 참고 기준, 신규 앱 호환 TBD |

## Clone 후 Android 검사

```powershell
git clone https://github.com/SubHye/Pleos_PA.git
cd Pleos_PA
git switch -c feat/<Linear-ID>-<short-description>
.\gradlew.bat --version
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Android Studio에서 저장소 루트를 연다. SDK Manager에서 API 37 플랫폼과 Build Tools 36.0.0, Platform Tools를 준비한다. JDK 25를 설치하고 Studio의 Gradle JDK에 지정한다. Wrapper daemon 기준도 25인지 확인한다.
SDK 경로는 로컬 `local.properties`의 `sdk.dir=C:/Users/<name>/AppData/Local/Android/Sdk` 또는 `ANDROID_HOME`으로 설정한다. 개인 절대 경로는 커밋하지 않는다.
Unix에서는 `bash ./gradlew`를 사용한다. 프로젝트 Wrapper만 쓰고 전역 Gradle로 대체하지 않는다.

현재 앱은 Activity가 없어 실행 화면이 없다. APK 생성 위치는 `app/build/outputs/apk/debug/app-debug.apk`. 빈 앱 빌드 확인에 에뮬레이터나 차량 접근은 필요하지 않다.

## MCP와 ADB (이전 후에만 적용)

현재 이 저장소에서 npm/ADB 연결을 실행할 대상은 없다. 이전 PR에서 `mcp/package.json`, `package-lock.json`, `.node-version`과 계약을 함께 확정한다.

```text
cd mcp
npm ci
npm test
npm start
```

PoC는 `npm start`로 stdio 서버를 실행하며 별도 .env를 읽는다는 근거가 없다. 신규 앱의 환경 변수/인증 설정은 TBD다. 임의 토큰을 만들거나 사용자 전역 MCP 설정을 변경하지 않는다.
이전 후 검증용 에뮬레이터를 명시적으로 선택하고 아래 PoC 방향을 재확인한다:

```text
adb devices -l
adb -s <검증용-serial> forward tcp:18765 tcp:18765
adb -s <검증용-serial> forward --list
adb -s <검증용-serial> forward --remove tcp:18765
```

앞의 PC 포트에서 Android 포트로 전달한다. 실제 차량을 임의 선택하지 않는다. Pleos Virtual Console 연결·SDK Service 버전·APK·클라이언트 설정을 검증 기록에 남긴다. 웹/모바일 AI가 localhost stdio에 직접 연결된다고 가정하지 않는다.

## 로컬 비밀과 Agent

local.properties, .env*, 서명키는 기존 gitignore 대상이다. 필요 시 값 없는 예제 설정만 별도 PR에 추가한다. 실토큰·개인정보·서명키를 APK나 로그에 넣지 않는다.

Codex 공통 지침은 루트 AGENTS.md. Claude 호환 파일은 `@AGENTS.md` 한 줄만 유지한다. [공식 Claude 문서](https://code.claude.com/docs/en/memory#import-additional-files)의 import 문법을 확인했다. 신규 버전의 AGENTS 직접 지원 여부와 무관하게 지침을 중복 작성하지 않는다.
