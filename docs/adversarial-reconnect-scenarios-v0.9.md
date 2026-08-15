# v0.9 적대적 재연결·제어 시나리오

이번 범위는 Nearby 근거리 연결, 학생 가로 화면, 선생 주도 타이머, 선생 앱의 다른 앱 전환을 다룬다. 3G·Telegram·Firebase는 포함하지 않는다.

| 상황 | 기대 결과 | 방어 장치 | 검증 |
|---|---|---|---|
| 선생 앱이 실행 중 다른 앱으로 이동 | 연결·사진·메시지 수신 지속 | connected-device foreground service, Activity의 transport를 onStop에서 중단하지 않음 | API 35에서 HOME 후 service/프로세스 생존 확인 |
| 선생 앱으로 복귀 | 떠나기 전 타이머·최신 사진 UI 유지 | 동일 Activity 인스턴스와 수신 callback 유지 | API 35에서 재개 확인 |
| 학생 또는 선생이 전송 중 연결 해제 | 이전 payload ID 재사용 금지, 논리 파일 재전송 | disconnect 때 fileSent 포함 모든 inflight payload 제거, pending 논리 항목 유지 | 코드 경로·통합 compile 확인 |
| 학생의 `재연결` 연속 누름 | stale callback이 새 연결을 오염시키지 않음 | Nearby generation 증가, channel reset, 600ms 뒤 단일 start guard | generation 구현 및 unit reset test |
| 선생의 `처음부터·재연결`을 연결 중 실행 | 이전 사진이 새 세션 화면에 나타나지 않음 | 새 READY snapshot 전 media 무시, 사진 UI·correlation map 제거 | protocol roundtrip 및 상태 분기 확인 |
| 선생의 초기화를 연결 끊긴 상태에서 실행 | 다음 연결 직후 RESET 적용 | `resetSessionOnNextConnect` 보존 | 연결 handler 분기 확인 |
| 재연결 직후 옛 파일과 RESET이 경합 | 새 READY 전 옛 파일 폐기 | `awaitingResetReady` gate | 상태 분기 확인 |
| 시작 버튼 빠른 연속 입력 | 중복 시작·pause/resume 방지 | 요청 즉시 버튼 비활성화, reliable message ID 중복 제거, domain 상태 검증 | protocol/core-domain tests |
| 학생이 `시작/멈춰`라고 발화 | 학생이 타이머를 바꾸지 않음 | 학생 voice command는 안내 문구만 표시 | command 분기 확인 |
| 학생이 세로로 들거나 180도 돌림 | 가로 또는 역가로 유지, 카메라 target rotation 갱신 | `sensorLandscape` + configChanges | API 35 requested orientation 확인 |
| 선생 앱을 최근 앱에서 완전히 쓸어 종료 | 연결 종료, 지속 알림 제거 | service `stopWithTask`, Activity 종료 시 transport stop | manifest/service 설정 확인 |
| 운영체제가 프로세스를 강제 종료 | 연결은 종료되고 앱 재실행 필요 | 이번 범위에서 디스크 세션 복원은 하지 않음 | 명시된 한계 |

Foreground service는 일반적인 앱 전환 중 프로세스 우선순위를 높이는 장치다. 사용자가 강제 종료하거나 운영체제가 프로세스 전체를 종료한 뒤 자동 복원하는 영구 서비스·세션 체크포인트는 이번 범위에 포함하지 않는다.
