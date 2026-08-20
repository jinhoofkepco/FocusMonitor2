# FocusMonitor2 Telegram student app

선생님용 Android 앱과 Nearby 연결을 제거하고, 학생 앱이 텔레그램 봇으로 사진·상태를 보내며 명령을 받는 개인용 Android 프로토타입입니다. 선생님은 텔레그램만 사용합니다.

## 동작

- 학생 화면이 보이는 상태에서 카메라·마이크·알림 권한을 한 번 승인하면 `camera|microphone|dataSync` foreground service가 시작됩니다. 이후 화면을 끄거나 다른 앱을 열어도 세션·촬영·음성 명령·업로드 큐가 서비스에서 계속 동작합니다.
- 공부 시작/일시정지/재개/종료는 텔레그램의 `/start`, `/pause`, `/resume`, `/stop`으로만 제어합니다. `/status`는 현재 상태를 보냅니다.
- 기본 후면 카메라 원본을 10초마다 한 장만 촬영합니다. 긴 변 2000px, JPEG q90으로 디스크에 보관하고 날짜가 바뀌면 전날 원본과 책 사진을 모두 지웁니다. 기존 1x→상세 줌 왕복 촬영은 사용하지 않습니다.
- 6장을 모아 3×2 몽타주를 만들고 1분마다 `sendPhoto`로 전송합니다. 각 400px 셀에는 `HH:mm:ss`가 굽혀지고, 캡션의 `#N`은 세션 안에서 계속 증가합니다.
- `/b 14:03`, `/b 14:03:20`, `/b 14:03-14:05`, `/b -5`로 원본의 책 영역을 요청합니다. 상세 사진은 최대 8장이고, 텔레그램 재압축을 피하도록 오직 `sendDocument`를 사용합니다.
- `/index`는 현재 세션 몽타주 번호와 시각 범위를 한 메시지로 보냅니다. 세션 종료 요약은 전송 후 고정됩니다.
- 자리 이탈·복귀·책 움직임 없음·움직임 재개·`풀었어`·학생 음성 인식 메시지는 몽타주를 기다리지 않고 즉시 텍스트 큐에 들어갑니다.
- 일반 텔레그램 텍스트는 학생폰에서 확인음 뒤 한국어 TTS로 읽습니다. `/`로 시작하는 알 수 없는 입력에는 명령 도움말을 보냅니다.
- `/menu`를 보내면 시작·일시정지·계속·다음 단계·종료·처음부터·상태·최근 사진·초점·카메라 진단·시간 설정 버튼이 열립니다. `/` 입력 시 텔레그램 명령 추천 목록도 표시됩니다.
- `/area`는 한 장의 전체 사진을 기준으로 A–J, 1–10의 10×10 격자를 표시합니다. `/area D2 H5`처럼 왼쪽 위 칸과 오른쪽 아래 칸을 보내면 같은 사진 위에 실제 저장 범위를 빨간 테두리로 표시하고, `확정`을 누른 뒤부터 그 좌표를 사용합니다. 새 격자를 열면 이전 확인 버튼은 거부하므로 서로 다른 사진의 좌표가 섞이지 않습니다.
- 책 상세사진은 기본 180° 회전합니다. `/rotate` 버튼 메뉴 또는 `/rotate 0|90|180|270`으로 바꾸면 이후 숫자 버튼과 `/b` 상세사진에 즉시 적용됩니다. 전체 썸네일과 영역 설정 격자는 회전하지 않습니다.
- `/camera`는 S23 카메라 진단 메뉴입니다. `/camera info`는 CameraX가 공개한 논리·물리 렌즈 ID, 센서 크기·초점거리로 계산한 화각 배율, 최대 JPEG 크기를 보내고, `/camera test`는 기본 후면 A와 약 3× 물리 렌즈 B를 순서대로 촬영해 재압축 없는 문서 원본으로 보냅니다. B는 후보 렌즈의 최대 JPEG 크기를 우선 요청하고 Camera2 출력에도 물리 ID를 직접 지정하며, CaptureResult·EXIF 초점거리로 실제 렌즈 전환을 검증합니다. 비교 뒤에는 기본 후면 카메라와 움직임 분석 복구를 시도하고, 실패하면 2초 간격으로 다시 연결합니다.
- 학생 확인음은 알림 볼륨이 아닌 미디어 볼륨을 사용합니다. TTS는 설치된 한국어 로컬 음성 중 품질 우선으로 선택하고 미디어 출력·속도 0.88·기본 피치로 읽습니다.

## 텔레그램 설정 — 학생폰만으로 가능

1. 텔레그램 `@BotFather`에서 개인 봇을 만들고 토큰을 복사합니다.
2. 선생님 계정에서 새 봇을 열어 `/연결` 또는 `/connect`를 보냅니다.
3. 학생 앱 첫 화면에 BotFather 토큰을 붙여넣고 `내 텔레그램 채팅 찾기`를 누릅니다.
4. 앱이 표시한 텔레그램 이름·username·chat ID를 확인하고 `연결`을 누릅니다.
5. 텔레그램에서 `학생폰 연결 완료` 메시지를 확인합니다. 이후 `/start`로 공부를 시작합니다.

학생폰에는 텔레그램 앱을 설치할 필요가 없습니다. 학생 앱이 Bot API에 직접 접속하며 텔레그램 앱은 선생님 폰에만 있으면 됩니다.

토큰은 Android Keystore에서 생성한 AES-GCM 키로 암호화되어 학생폰에만 저장됩니다. 설정 화면은 `/연결`을 보낸 개인 채팅 후보를 보여주기만 하며 명령을 실행하지 않습니다. 사용자가 이름과 ID를 승인한 뒤부터 해당 `chat_id` 화이트리스트가 적용됩니다. 학생 화면의 `텔레그램 연결 초기화`는 암호화 토큰과 기존 봇의 미전송 큐를 함께 삭제합니다.

개발용으로는 커밋되지 않는 루트 `local.properties`에 미리 넣어 빌드할 수도 있습니다.

```properties
sdk.dir=/absolute/path/to/Android/sdk
TELEGRAM_BOT_TOKEN=replace_with_real_token
TELEGRAM_CHAT_ID=123456789
```

형식은 `local.properties.example`에도 있습니다. 런타임에 저장된 설정이 BuildConfig보다 우선합니다. 둘 다 비어 있으면 APK는 권한이나 촬영 서비스를 시작하지 않고 텔레그램 연결 화면만 표시합니다.

`chat_id`가 다른 update는 명령 파싱 전에 버립니다. 앱은 시작할 때 기존 webhook을 해제한 뒤 `getUpdates(timeout=50)` 롱폴링을 하나만 실행하고, 각 명령 결과가 디스크 큐에 기록된 뒤에만 offset을 원자적으로 저장합니다.

> `local.properties`로 미리 넣은 봇 토큰은 BuildConfig를 통해 APK 안에 들어가므로 공개 APK에 사용하지 마세요. GitHub 배포 APK는 토큰 없이 만들고 학생폰 최초 실행 설정을 사용합니다. chat ID 화이트리스트는 다른 채팅의 명령 실행을 막지만 유출된 토큰 자체를 보호하지는 않습니다.

## 모듈 경계

| 모듈 | 입력 | 출력/불변조건 |
|---|---|---|
| `telegram-report` | 1x JPEG, 시각, 상태 변화, Telegram update | `sendPhoto` 몽타주와 `sendDocument` 상세 경로를 함수·큐 타입으로 분리; JSONL durable queue; 성공 ACK; 지수 backoff; 원자적 update offset |
| `app-student` | 권한 승인, Telegram 명령, 카메라/음성 | foreground service가 세션·기본 단일 촬영·물리 렌즈 A/B 진단·판정·봇을 소유; Activity는 최신 전체 사진과 책 영역 편집만 담당 |
| `core-domain` | monotonic session command | Android/네트워크와 무관한 `SessionSnapshot` |
| `activity-detection` | 1fps 화면 변화량 | 이탈/복귀와 책 움직임 상태 변화; 기존 cooldown 유지 |
| `voice-command` | 연속 한국어 음성 인식 | `풀었어/벌써` 문제 완료, `아빠` 다음 발화 메시지 |
| `camera-capture`, `voice-message` | 기존 실기기 튜닝 코드 | 소스 변경 없이 보존; 새 Telegram 세션 촬영 경로에는 포함하지 않음 |

삭제된 모듈은 `app-teacher`, `transport-nearby`, `transport-api`, `core-protocol`, `core-sync`입니다.

## 디스크와 메모리

- `files/telegram-report/upload-queue.jsonl`: PUT/RETRY/ACK append journal. append마다 fsync하고 주기적으로 atomic compaction합니다.
- `files/telegram-report/update-offset.txt`: 명령 처리 성공 뒤 atomic replace합니다.
- `files/telegram-report/originals`: 오늘 촬영한 전체·책 원본. 날짜가 바뀐 뒤 앱 시작·세션 시작·첫 촬영 시 전날 파일을 모두 삭제합니다.
- 몽타주는 캔버스 한 장만 유지합니다. 셀마다 power-of-two sample decode → draw → 즉시 recycle합니다.
- 미전송 몽타주·상세 파일은 새 세션 정리에서도 보호하며, 성공 응답을 받은 뒤에만 삭제합니다.

## 빌드와 확인

필요 환경은 JDK 17, Android SDK 35, build-tools 35.0.1입니다.

```bash
./gradlew --no-daemon \
  :activity-detection:test \
  :core-domain:test \
  :telegram-report:testDebugUnitTest \
  :app-student:testDebugUnitTest \
  :voice-command:testDebugUnitTest \
  :camera-capture:testDebugUnitTest \
  :voice-message:testDebugUnitTest \
  :app-voice-lab:testDebugUnitTest \
  :app-student:assembleDebug \
  :app-student:lintDebug \
  :telegram-report:lintDebug
```

Android 기기나 emulator가 연결돼 있으면 Keystore 암호화 저장·복호화·초기화 시험도 실행합니다.

```bash
./gradlew --no-daemon \
  :telegram-report:connectedDebugAndroidTest \
  :app-student:connectedDebugAndroidTest
```

학생 APK는 `app-student/build/outputs/apk/debug/app-student-debug.apk`입니다. 선생님 APK는 더 이상 만들지 않습니다.

## 선생님 시간 원격조종

- `/settings` — 저장된 명상·공부·휴식·시작 대기시간 확인
- `/set 0 40 15` — 명상 0분, 공부 40분, 휴식 15분으로 저장. 진행 중이면 현재 단계의 이미 지난 시간은 유지하면서 즉시 반영
- `/set countdown 0` — `/start`의 시작 대기시간을 0초로 설정(즉시 시작). 허용 범위 0~60초
- `/start` — 저장된 설정으로 첫 단계부터 새 세션 시작
- `/restart` — 진행 중 회차를 종료하고 저장된 설정으로 첫 단계부터 다시 시작
- `/pause`, `/resume`, `/stop` — 일시정지·재개·종료
- `/time 25` 또는 `/time 25:30` — 현재 단계의 남은 시간을 즉시 25분 또는 25분 30초로 변경
- `/phase meditation`, `/phase study`, `/phase break` — 해당 단계의 전체 시간으로 즉시 이동. `명상`, `공부`, `휴식`도 사용 가능
- `/phase study 10` — 공부 단계로 이동하면서 남은 시간을 10분으로 지정
- `/next` — 다음 단계로 즉시 이동. 휴식에서 실행하면 세션 완료
- `/status` — 학생폰의 현재 단계·남은 시간·완료 문제 수 확인
- `/focus` — 유지 중인 책 영역 초점을 해제하고 다음 촬영 전에 다시 맞춤

모든 시간 명령은 Telegram `chat_id` 화이트리스트를 통과한 선생님 채팅에서만 실행됩니다. 설정은 학생폰에 저장되어 서비스나 휴대폰이 재시작돼도 유지되며, 현재 회차 변경도 다음 회차를 기다리지 않고 즉시 적용됩니다.

새 몽타주 아래에는 촬영 순서대로 `1 2 3 4 5 6` 버튼이 붙습니다. 버튼을 누르면 해당 칸의 촬영 시각에 저장한 고해상도 책 영역을 `sendDocument`로 전송합니다. 이미 전송된 과거 몽타주에는 버튼이 소급해서 생기지 않으므로 `/b HH:MM:SS`를 사용합니다.

평상시 카메라는 기본 후면 JPEG를 한 번만 촬영합니다. 몽타주용 전체 화면은 긴 변 2,000px로 별도 축소하고, 책 영역은 원본 JPEG에서 먼저 잘라 긴 변 최대 4,000px/JPEG 95로 저장합니다. 최초 촬영 전에 책 영역 중앙 AF를 최대 두 번 시도하고 성공한 초점은 자동취소 없이 유지합니다. 책 영역 변경·카메라 재연결·`/focus` 명령 때만 다시 맞춥니다.

책 영역을 바꾼 뒤 변경 전에 찍은 사진을 다시 요청하면 좌표 정확성을 우선해 2,000px 전체 보관본에서 새 영역을 다시 자릅니다. 따라서 과거 사진은 새 영역으로 정확히 나오지만, 변경 뒤 촬영되는 4,000px급 책 캐시보다 해상도가 낮을 수 있습니다.

`/camera test`는 진단용이며 평상시 촬영 렌즈를 영구 변경하지 않습니다. CameraX 물리 렌즈 특성의 `초점거리 ÷ 센서 너비`를 가장 큰 센서(기본 렌즈 기준)와 비교해 화각 배율을 계산하고, 2.4–3.8× 범위에서 3×에 가장 가까운 후보만 B로 선택합니다. B 캡션의 `activePhysical`, `physicalResults`, `resultFocal`, `exifFocal` 중 물리 ID 또는 기대 초점거리가 확인되어야만 `물리 약 3× 확인됨`으로 표시합니다. 확인할 수 없으면 사진은 참고용으로 보내되 성공으로 취급하지 않습니다. 후보가 없거나 바인드·촬영에 실패하면 오류를 텔레그램으로 알리고 기본 카메라를 다시 연결합니다. A/B 문서의 화각과 확인 메타데이터를 비교하고, S23에서 렌즈를 손가락으로 번갈아 가리는 시험까지 통과한 뒤 다음 버전에서 상시 3× 상세 촬영 여부를 정합니다.

## 실기기 완료 시험

아래 항목은 토큰·chat ID가 들어간 APK와 실제 S23에서 확인해야 합니다.

1. 40분 동안 몽타주 40건과 연속된 `#N` 확인.
2. 기내모드 3분 뒤 복구하고 밀린 JSONL 큐 전부 수신 확인.
3. 네 가지 `/b` 요청이 30초 안에 도착하고 상세 파일이 Telegram 문서로 표시되는지 확인.
4. 미전송 상태에서 프로세스를 강제 종료한 뒤 앱을 다시 열어 큐와 몽타주 번호가 이어지는지 확인.
5. 40분 동안 `adb logcat -s RemoteStudyService`를 저장하고 `camera_stall` 0회 및 `thermal_status` 추이를 확인.
6. `/camera info` 뒤 `/camera test`를 한 번 실행해 A/B 문서가 모두 오고, 3× 렌즈를 가렸을 때 B만 어두워지며 이후 10초 촬영이 다시 이어지는지 확인.

현재 자동 검증은 Telegram parser/JSONL 재시작·ACK/backoff 단위 테스트, 학생 APK assemble, 학생/Telegram 모듈 lint 오류 0까지 포함합니다. 실제 Bot API 왕복과 40분 발열·카메라 스톨은 비밀값과 실기기가 없으면 자동 검증할 수 없습니다.

## 이전 선생 앱과 달라진 점

- 일반 텔레그램 텍스트는 학생폰에서 짧은 확인음 뒤 한국어 TTS로 즉시 읽습니다.
- `풀었어/벌써`와 `아빠` 다음 발화는 텔레그램 텍스트 메시지로 즉시 전송됩니다.
- 대화 기록과 새 메시지 알림은 별도 선생 앱 대신 텔레그램 채팅과 텔레그램 알림이 담당합니다.
- 현재 Telegram 버전은 학생의 실제 녹음 음성파일 전송과 선생님의 Telegram 음성메시지 자동재생을 지원하지 않습니다.
- 시간은 기본 명상 5분·공부 40분·휴식 15분이며 텔레그램에서 원격 설정·현재 남은 시간 변경·단계 이동이 가능합니다. 촬영 간격은 10초로 고정되어 있습니다.
- 책 영역은 학생 화면뿐 아니라 Telegram `/area`의 10×10 격자와 빨간 선택 테두리로도 설정할 수 있습니다.
