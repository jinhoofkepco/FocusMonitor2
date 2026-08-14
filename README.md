# Remote Study Android debug prototype

학생폰 카메라로 책과 자리 판정 구역을 보여 주고, 선생님폰과 1:1로 근거리 소통하는 **비배포 Android debug 프로토타입**입니다. 스토어 배포·운영용 release 앱이 아니며, 1차 범위는 두 Android 기기의 근거리 연결입니다.

## 현재 실행 화면

1. 학생 앱은 별도 배치 조작 없이 카메라 준비 즉시 시작할 수 있습니다. 선생님 앱에서 최신 사진 위의 노란 `책 영역`을 이동하거나 네 모서리를 끌어 크기를 정하면 학생 카메라에 적용됩니다.
2. 학생폰과 선생님폰이 서로 발견되면 이 개인용 프로토타입은 Nearby 연결 요청을 양쪽에서 자동 승인합니다.
3. 양쪽 앱의 조작 메뉴는 화면 맨 아래 한 줄이며, 접으면 오른쪽 아래 `⋮` 버튼 하나만 남습니다. 선생님은 설정에서 시간·촬영 간격·판정 감도·알림 사용/무음·재알림 제한을 변경할 수 있습니다. 알림·촬영 설정은 진행 중에도 즉시 적용되고 일정 시간은 다음 시작부터 적용됩니다.
4. 학생이 직접 시작하거나 선생님이 시작을 요청합니다. 선생님 시작은 저장된 설정을 학생폰에 먼저 전달하고 항상 첫 명상 사이클부터 시작합니다.
5. 학생폰의 monotonic clock으로 기본 `명상 5분 → 공부 40분 → 휴식 15분`을 진행하고 상태를 선생님폰에 동기화합니다.
6. 공부 단계에서 기준 화면 차이와 자리 구역/책 영역의 프레임 간 미세 움직임을 함께 사용합니다. 복귀는 3개 연속 프레임으로 확인하고 동일 경고는 기본 5분 동안 재발송하지 않습니다. 연결 장애는 자리 비움으로 해석하지 않습니다.
7. 명상·공부 중 10초마다 전체 화면 `1440px/JPEG 82` 썸네일과 책 상세 ROI를 분리합니다. 책 영역 설정 시 `12MP 일반` 또는 `50MP 초고화질`을 선택하며, 학생폰이 실제 적용한 해상도를 선생님폰에 회신합니다. 50MP 출력이 CameraX에 공개되지 않거나 동시 use-case bind가 실패하면 12MP로 복구하고 대체 적용 사실을 표시합니다. 썸네일만 자동 전송하며 상세 ROI는 눌렀을 때 전송합니다.
   파일은 Nearby의 실제 전송 완료 뒤에만 전송됨으로 표시합니다. 실패하면 연결 중 2초 간격으로 최초 시도 뒤 최대 3회 재시도하고, 재연결하면 남은 ROI·음성 및 최신 썸네일을 다시 보냅니다.
8. 선생님 사진은 디스플레이 픽셀을 180도 변환해 보여 주므로 확대 상태의 드래그 방향이 손가락과 일치합니다. 최근 썸네일은 최대 12개이며, 상세 ROI는 전체화면에서 최대 5배 확대·이동할 수 있습니다.
9. 두 앱 모두 세로·가로 화면을 지원하며 회전 중 학생 세션과 책 영역을 유지합니다.
10. 학생이 `풀었어`라고 말하면 확인음을 먼저 내고 선생님폰에 문제 완료를 보냅니다. `아빠`라고 말하면 확인음 뒤 다음 발화를 인식 텍스트로 보내고 완료음을 냅니다. `아빠 녹음`이라고 말하면 이어지는 12초 음성을 녹음해 자동 전송합니다.
11. 학생은 최대 60초 음성 메시지를 보내고 선생님은 이를 재생할 수 있습니다. 선생님은 텍스트 또는 최대 60초 음성으로 답하며, 학생폰은 수신 즉시 확인음을 내고 텍스트 답변을 한국어 TTS로 읽거나 음성 답변을 자동 재생합니다. 실시간 통화는 아닙니다.

## 모듈과 I/O

| 모듈 | 입력 | 출력 | 핵심 불변조건 |
|---|---|---|---|
| `core-domain` | session command, monotonic elapsed time | immutable `SessionSnapshot` | 네트워크·Android·wall clock과 무관, command/event ID 중복 무시 |
| `core-protocol` | typed `StudyMessage` | 32 KiB 이하 versioned bytes | 크기·enum·trailing bytes 검증 |
| `core-sync` | `StudyMessage`, 연결 상태, elapsed time, inbound bytes | encoded 전송, 한 번만 노출되는 inbound message, pending 목록 | ACK 전 재시도, inbound 중복 제거, snapshot coalesce; outbox와 수신 ID는 메모리에만 존재 |
| `activity-detection` | `setActive`, 기준 차이·자리 미세 움직임·책 움직임 | `AWAY`, `NO_BOOK_MOVEMENT` 및 두 복구 이벤트 | 3프레임 복귀 확인, hysteresis, 재알림 cooldown, stale time 무시 |
| `transport-api` | bytes/file, approve/reject | connection/message/file event | 개인용 앱에서 발견된 상대 요청 자동 승인 |
| `transport-nearby` | transport port 호출 | Nearby P2P point-to-point, payload별 file success/failure | UI와 Google Play services 타입 격리, 파일 완료 이벤트 exactly-once 방출 |
| `camera-capture` | lifecycle, capture 요청, baseline arm | `FrameObservation`, thumbnail file, book ROI file | Preview/ImageCapture/ImageAnalysis 동시 사용, 분석 1fps 이하, 임시 원본과 종료 중 자산 cleanup |
| `voice-command` | start/stop, SpeechRecognizer result | 제한된 한국어 command | 음성 원본 저장·전송 없음, debounce와 touch 대체 입력 |
| `voice-message` | main-thread record/stop/cancel/play, output file | AAC `.m4a` `RecordedVoiceMessage`, playback callback | 최대 60초, `.part` 성공 후 commit, 취소·실패 파일 cleanup, recorder·player 각각 활성 작업 1개 |
| `app-student` | touch/voice/teacher request, camera observation, teacher reply | authoritative session state, thumbnail/ROI, alert, student voice message, TTS/playback | 타이머·촬영·판정 기준은 학생폰 |
| `app-teacher` | pairing approval, settings/start/media request, text/voice reply | concise state, 최근 썸네일 12개, 전체화면 ROI, notifications | 설정 후 첫 사이클 시작, 썸네일 자동 수신과 ROI 요청 전송 분리 |

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

### 현재 검증 결과 (2026-08-15)

- JVM/Android unit test 67개: 실패 0, 오류 0, skip 0
- API 35 emulator camera instrumented test 1개: 통과
- 학생/선생님/Nearby/camera/두 음성 모듈 Android lint: 오류 0
- API 35 emulator에 두 debug APK 설치 및 cold launch 성공; 접이식 메뉴·설정 화면·세로/가로 회전·학생 카메라 화면 확인
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
