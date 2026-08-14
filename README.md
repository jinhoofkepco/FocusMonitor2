# Remote Study Android debug prototype

학생폰 카메라로 책과 자리 판정 구역을 보여 주고, 선생님폰과 1:1로 근거리 소통하는 **비배포 Android debug 프로토타입**입니다. 스토어 배포·운영용 release 앱이 아니며, 1차 범위는 두 Android 기기의 근거리 연결입니다.

## 현재 실행되는 세로 단면

1. 학생 앱에서 카메라 미리보기 위의 `책 영역`과 `자리 판정 영역`을 맞춥니다.
2. 학생폰은 선생님폰을 발견하고 양쪽에 동일한 Nearby 인증 숫자를 표시합니다. 양쪽에서 승인해야 연결됩니다.
3. 학생이 직접 시작하거나 선생님이 시작을 요청합니다. 학생 시작은 즉시, 선생님 요청은 학생폰의 5초 카운트다운 뒤 시작합니다.
4. 학생폰의 monotonic clock으로 `명상 5분 → 공부 40분 → 휴식 15분`을 진행하고 상태를 선생님폰에 동기화합니다.
5. 공부 단계에서 카메라 분석 결과를 `activity-detection`에 넣어 자리 차이 10초와 책 움직임 없음 30초를 판정합니다. 알 수 없는 프레임은 `UNKNOWN`으로 두며, 연결 장애를 자리 비움으로 해석하지 않습니다.
6. 공부 중 10초마다 학생폰에서 전체 원본 JPEG를 임시 생성한 뒤 `주변을 픽셀화한 썸네일`과 `고화질 책 ROI`로 분리하고 원본을 삭제합니다. 썸네일만 자동 전송하며, 고화질 책 ROI는 선생님이 해당 썸네일을 눌렀을 때 요청·전송합니다.
   파일은 Nearby의 실제 전송 완료 뒤에만 전송됨으로 표시합니다. 실패하면 연결 중 2초 간격으로 최초 시도 뒤 최대 3회 재시도하고, 재연결하면 남은 ROI·음성 및 최신 썸네일을 다시 보냅니다.
7. 선생님 화면은 가장 최근 사진과 최근 썸네일 최대 12개를 표시합니다. 각 썸네일을 누르면 그 촬영 시점의 책 ROI를 요청합니다.
8. 학생이 `문제 풀었어` 버튼 또는 음성 명령을 사용하면 선생님폰에 알림이 옵니다. 5초 안에는 취소할 수 있습니다.
9. 학생은 최대 60초 음성 메시지를 보내고 선생님은 이를 재생할 수 있습니다. 선생님은 텍스트 또는 최대 60초 음성으로 답하며, 학생폰은 텍스트 답변을 한국어 TTS로 읽고 음성 답변을 재생합니다. 실시간 통화는 아닙니다.

## 모듈과 I/O

| 모듈 | 입력 | 출력 | 핵심 불변조건 |
|---|---|---|---|
| `core-domain` | session command, monotonic elapsed time | immutable `SessionSnapshot` | 네트워크·Android·wall clock과 무관, command/event ID 중복 무시 |
| `core-protocol` | typed `StudyMessage` | 32 KiB 이하 versioned bytes | 크기·enum·trailing bytes 검증 |
| `core-sync` | `StudyMessage`, 연결 상태, elapsed time, inbound bytes | encoded 전송, 한 번만 노출되는 inbound message, pending 목록 | ACK 전 재시도, inbound 중복 제거, snapshot coalesce; outbox와 수신 ID는 메모리에만 존재 |
| `activity-detection` | `setActive`, `FrameEvidence(presenceDifference, bookMovement)` | `AWAY`, `NO_BOOK_MOVEMENT` 및 두 복구 이벤트 | inactive 무누적, null은 `UNKNOWN`, stale time 무시, 한 상태당 경고·복구 1회 |
| `transport-api` | bytes/file, approve/reject | connection/message/file event | 인증 숫자 승인 전에는 연결 완료가 아님 |
| `transport-nearby` | transport port 호출 | Nearby P2P point-to-point, payload별 file success/failure | UI와 Google Play services 타입 격리, 파일 완료 이벤트 exactly-once 방출 |
| `camera-capture` | lifecycle, capture 요청, baseline arm | `FrameObservation`, thumbnail file, book ROI file | Preview/ImageCapture/ImageAnalysis 동시 사용, 분석 1fps 이하, 임시 원본과 종료 중 자산 cleanup |
| `voice-command` | start/stop, SpeechRecognizer result | 제한된 한국어 command | 음성 원본 저장·전송 없음, debounce와 touch 대체 입력 |
| `voice-message` | main-thread record/stop/cancel/play, output file | AAC `.m4a` `RecordedVoiceMessage`, playback callback | 최대 60초, `.part` 성공 후 commit, 취소·실패 파일 cleanup, recorder·player 각각 활성 작업 1개 |
| `app-student` | touch/voice/teacher request, camera observation, teacher reply | authoritative session state, thumbnail/ROI, alert, student voice message, TTS/playback | 타이머·촬영·판정 기준은 학생폰 |
| `app-teacher` | pairing approval, start/media request, text/voice reply | concise state, 최근 썸네일 12개, ROI dialog, notifications | 썸네일 자동 수신과 ROI 요청 전송을 분리 |

상세 계약은 [`docs/remote-study-mvp-system-design-v0.1.md`](docs/remote-study-mvp-system-design-v0.1.md), 장기 wire schema는 [`docs/remote-study-protocol-v1.proto`](docs/remote-study-protocol-v1.proto)에 있습니다. 현재 빌드는 코드 생성 도구를 강제하지 않도록 같은 개념의 수동 검증 codec을 사용합니다.

## 빌드와 테스트

필요 환경은 JDK 17, Android SDK 35, build-tools 35.0.1입니다.

다음 명령은 현재 포함된 모든 모듈의 JVM/Android unit-test task를 실행하고 두 debug APK를 조립합니다. 테스트 소스가 없는 모듈도 compile/test task로 통합 여부를 확인합니다.

```bash
./gradlew --no-daemon \
  :activity-detection:test \
  :core-domain:test \
  :core-protocol:test \
  :core-sync:test \
  :transport-api:test \
  :camera-capture:testDebugUnitTest \
  :transport-nearby:testDebugUnitTest \
  :voice-command:testDebugUnitTest \
  :voice-message:testDebugUnitTest \
  :app-student:testDebugUnitTest \
  :app-teacher:testDebugUnitTest \
  :app-student:assembleDebug \
  :app-teacher:assembleDebug
```

Android 기기 또는 emulator가 연결된 경우 Camera asset processor의 instrumented test도 실행합니다. 이 테스트는 자산 분리와 원본 삭제를 검증하지만 실제 카메라 하드웨어 촬영 시험을 대신하지는 않습니다.

```bash
./gradlew --no-daemon :camera-capture:connectedDebugAndroidTest
```

### 현재 검증 결과 (2026-08-14)

- JVM/Android unit test 62개: 실패 0, 오류 0, skip 0
- API 35 emulator camera instrumented test 1개: 통과
- 학생/선생님/Nearby/camera/두 음성 모듈 Android lint: 오류 0
- API 35 emulator에 두 debug APK 설치 및 cold launch 성공; 학생 배치 완료 → 명상 단계 전환 확인
- 실제 두 Android 기기 사이의 Nearby 승인·재연결·사진/음성 왕복과 거리·발열 시험은 아직 실행하지 않음

APK:

- `app-student/build/outputs/apk/debug/app-student-debug.apk`
- `app-teacher/build/outputs/apk/debug/app-teacher-debug.apk`

두 실제 Android 기기에 각각 설치한 뒤 근거리 기기, Bluetooth, Wi-Fi, 위치(구형 Android), 카메라, 마이크, 알림 권한을 허용합니다. 같은 Wi-Fi에서 시험하되 Nearby Connections가 Bluetooth/BLE/Wi-Fi를 조합하므로 두 기기의 Bluetooth도 켭니다.

## 현재 한계와 기기 검증 항목

- 설계 사용 거리는 100m 이내지만 **Nearby 연결이 100m를 보장하지 않습니다**. 벽, 전파 간섭, 기기 안테나, 절전 정책에 따라 훨씬 짧아질 수 있으므로 실제 학생폰·선생님폰 두 대로 연결·재연결·파일 전송을 거리별 시험해야 합니다.
- `core-sync`의 reliable outbox와 inbound dedup 기록은 in-memory입니다. 연결이 잠시 끊긴 동안에는 재시도하지만 앱 프로세스가 종료되면 pending message와 dedup 기록이 사라집니다. Room 같은 영속 outbox는 아직 없습니다.
- 두 실제 기기에서 70분 이상 세션, 10초 캡처, 화면 켜짐/꺼짐, background 전환을 함께 시험해야 합니다.
- 기준 단말뿐 아니라 실제 사용할 제조사·기종별로 CameraX use-case 동시 bind, 해상도, EXIF 회전, 초점, JPEG 처리 시간과 메모리를 확인해야 합니다.
- 40분 이상 촬영에서 배터리 감소, 발열, thermal throttling, camera stall을 측정하지 않았습니다. 목표치를 확정하려면 실기기 soak test가 필요합니다.
- 자리 차이 `0.18`, 책 움직임 `0.012`, 10초/30초 값은 초기값입니다. 실제 책상 구도, 조명 변화, 그림자, 손 위치를 포함한 영상으로 오탐·미탐을 측정하고 기기/환경별 임계값을 보정해야 합니다.
- 외부 3G/5G relay, 프로세스 종료 복구, 장기 보관, 다중 학생은 구현 범위 밖입니다.

## 구현 기준

- Gradle 8.11.1 / Android Gradle Plugin 8.6.1 / Kotlin 2.1.21
- compileSdk/targetSdk 35, minSdk 26, Java/Kotlin 17
- CameraX 1.5.3
- Google Play services Nearby 19.3.0
- framework Views 기반의 가벼운 UI

FocusMonitor 소스는 라이선스가 없어 복사하지 않았습니다. 비대 Activity, 인증 없는 평문 HTTP, polling, 전체 프레임 축소 저장 같은 실패 원인만 반면교사로 삼았습니다.
