# 사진 품질 검사 v1

Java 21의 ImageIO/BufferedImage만 사용한다. OpenCV·Python·외부 AI를 추가하지 않는다. 현재는 모든 촬영 목적에 같은 GENERAL 정책을 적용한다. 부품 종류·촬영 거리·반사광에 의한 결함 가림을 판별하지 않는다. TOO_BRIGHT는 과노출 가능성에 대한 경고일 뿐이다.

## 활성화와 기존 흐름

기본 `PHOTO_QUALITY_ENABLED=true`. 새 접수와 파일은 먼저 저장하고 사진별 PENDING 결과를 같은 저장 transaction에 만든다. 접수 HTTP 202는 유지하지만, **활성화 시 taskId=null/status=RECEIVED**이며 자동 진단하지 않는다. UI는 품질 결과를 조회하고 사용할 사진을 선택한 후 기존 진단 API에 attachmentIds를 보내야 한다. 업로드만으로 task/outbox를 생성하지 않는다.

기존 파일 adapter의 로컬 UUID object key·SHA-256·10MiB 제한·인증된 다운로드를 유지한다. 원본은 수정하지 않는다. 초기 접수 후 진단 전에는 requestId 없이 기존 첨부 업로드 API로 재촬영 파일을 추가할 수 있다. Agent 요청에 대한 추가 사진은 기존 requestId를 사용한다. 재촬영 횟수는 Agent photo_request_count에 영향을 주지 않는다.

`PHOTO_QUALITY_ENABLED=false`는 기존 접수 자동 진단과 본문 없는 진단 요청을 유지한다. 검사 worker는 일시 중지하고 기존 PENDING/ERROR를 PASS로 바꾸지 않는다. 진단 입력에는 DISABLED 우회를 기록한다. 디코딩·파일 크기/유형 안전성 검증은 비활성화 상태에서도 적용한다. 이전에 허용됐던 깨진 PNG/JPEG는 이제 업로드 오류다. 재활성화하면 대기 검사를 재개하고 신규 입력 품질 gate를 다시 적용한다.

V6 적용 전 첨부와 비활성화 중 업로드는 quality_required=false다. 결과가 없으면 **LEGACY_UNCHECKED**로 표시하고 명시적 사진 선택 시 이전 자료로 허용한다. 신규 활성화 업로드의 required=true/PENDING과 구분한다. 기존 자료도 retry API에 resultId=null을 보내 검사 대상으로 전환할 수 있으며, 전환 이후에는 완료·경고 확인을 요구한다. 과거 실행에 이미 포함된 입력은 변경하지 않는다.

## 계산과 입력 검증

파일당 10MiB, 요청 전체 50MiB 제한을 유지한다. 파일명 확장자에 의존하지 않고 실제 바이트 정책과 ImageIO reader를 사용한다. 실제 형식과 클라이언트 MIME이 다르면 기존 정책대로 거절한다. JPEG/PNG를 지원하며 PDF·UTF-8 로그는 기존 업로드 정책대로 보관하되 품질 검사는 NOT_APPLICABLE이다. 지원하지 않는 형식·reader 없음·깨진/잘린 파일·reader 경고·디코딩 실패는 업로드 400이다. 내부 검증 실행 오류/timeout은 503, 실행 풀 포화는 429이며 품질 WARNING이나 PASS로 바꾸지 않는다.

ImageReader.getWidth/getHeight로 디코딩 전에 가로·세로·전체 픽셀 수를 확인한다. 허용치를 넘으면 픽셀 배열을 읽지 않는다. 입력 스트림·MemoryCacheImageInputStream·reader·Graphics2D를 정리하고 이미지 버퍼를 flush한다. ImageIO 전역 disk cache 설정은 변경하지 않는다. 클라이언트가 보낸 점수/PASS는 사용하지 않는다.

검사기 버전: `java-imageio-luma-laplace-v1`.

1. 해상도 판정은 **원본 크기**를 사용한다. 가로/세로 방향에 무관하게 짧은 변≥min(minWidth,minHeight), 긴 변≥max(minWidth,minHeight)인지 확인한다.
2. 원본 긴 변이 inspectionMaxSize를 넘을 때만 동일 비율로 축소하고 가장 가까운 정수 크기로 반올림한다. 작은 이미지는 확대하지 않는다.
3. 사본은 TYPE_INT_RGB의 흰색(#FFFFFF) 배경에 원본을 Graphics2D bilinear interpolation으로 그려 알파를 합성한다. 완전히 투명한 사진은 흰색으로 평가되므로 밝음/흐림 경고가 나올 수 있다.
4. ImageIO가 기본 제공하는 색 변환 후 RGB에 `Y=0.299R+0.587G+0.114B`를 적용한다(0..255 encoded RGB, gamma 선형화 없음). 이는 조도의 물리 측정이 아니다.
5. 평균 밝기와 `Y<=darkPixelThreshold`, `Y>=brightPixelThreshold` 픽셀 비율을 계산한다. **개별 픽셀 밝기 임계값과 전체 사진 비율 임계값은 별도**다.
6. 원본 해상도를 기록하고 축소된 흑백 사본에서 `L=위+아래+왼쪽+오른쪽-4×중앙`을 계산한다. 이웃이 부족한 바깥 1픽셀 테두리는 제외한다. Welford double 누적으로 모집단 분산 M2/N을 구한다. 유효 내부 픽셀이 없는 극소 이미지는 0, 음수 반올림 오차는 0으로 보정하고 비정상 수치는 ERROR 처리한다.
7. 선명도 점수는 흐림의 간접 지표이며 노이즈·질감·축소 크기의 영향을 받는다. 높은 점수만으로 결함 관찰 가능성을 보장하지 않는다.

**EXIF 방향 회전은 적용하지 않는다.** JPEG metadata를 무시하고 디코딩된 raster 크기를 사용한다. 실제 프런트 표시와 raster 방향이 다를 수 있다. 해상도 최소값은 방향에 무관하게 비교하지만 다른 도구와의 좌표/점수 일치를 주장하지 않는다.

## 임시 정책 설정

application.properties의 `app.photo-quality.*`를 Spring 환경변수/설정으로 덮어쓸 수 있다.

| 설정 | 초기값 | 의미 |
|---|---:|---|
| enabled / PHOTO_QUALITY_ENABLED | true | gate/worker 활성화 |
| policy-version | pilot-v1 | 사람이 관리하는 정책 이름 |
| min-width / min-height | 640 / 480 | 원본 긴 변·짧은 변 기준 |
| max-width / max-height | 10000 / 10000 | 디코딩 전 각 축 상한 |
| max-decode-pixels | 25000000 | 전체 픽셀 상한 |
| inspection-max-size | 1024 | 검사 사본 긴 변 상한 |
| dark-pixel-threshold / bright-pixel-threshold | 40 / 245 | 개별 픽셀 임계값, 양끝 포함 |
| dark-ratio-warning / bright-ratio-warning | 0.60 / 0.40 | 전체 비율이 이상이면 경고 |
| sharpness-warning | 100 | 분산이 미만이면 BLURRY |
| workers / queue-capacity | 2 / 8 | 계산/검증 공유 pool 및 메모리 대기열 상한 |
| max-attempts | 3 | 같은 첨부·검사기·정책의 최초 포함 수동 시도 상한 |
| lease-seconds | 120 | worker claim 복구 시간 |

기준값은 **부품 데이터로 보정하지 않은 임시값**이다. 실제 PCB·모터 사진의 촬영 조건·해상도·기기별 샘플을 모으고, 사람이 판독 가능 여부를 라벨링한 뒤 점수/비율 분포와 오경고를 검토한다. 밝기·선명도 임계값은 한 번에 한 요소씩 바꾸고 같은 전처리 크기로 비교한다. 검증용 사진을 별도로 분리해 평가하고 합의한 운영 허용치를 반영한다. 현재는 검증하지 않은 부품별 기준을 제공하지 않는다. 향후 attachment.target/정책 이름으로 촬영 목적별 정책을 선택하도록 확장할 수 있으며 계산 인터페이스 PhotoQualityChecker를 다른 구현으로 교체할 수 있다.

checker_version과 policy_version을 각각 저장한다. policy_version에는 전처리·한계·임계 설정의 SHA-256 일부를 자동 덧붙여 설정을 바꾸고 이름을 그대로 둬도 다른 정책으로 인식한다. resize/밝기/라플라시안/알파/EXIF 동작 변경 시 검사기 버전도 바꿔야 한다. 정책 변경 시 모든 Spring 인스턴스의 설정을 통일한다. 이전 정책 PENDING 작업은 POLICY_CHANGED/ERROR로 마무리하고 새 정책 재검사를 요청한다. 새 진단 입력에 이전 정책 완료 결과를 사용하면 PHOTO_POLICY_CHANGED로 거절한다.

## 상태·DB·동시성

처리 상태 PENDING/RUNNING/COMPLETED/ERROR와 판정 PASS/WARNING을 구분한다. COMPLETED에만 판정이 있다. LOW_RESOLUTION/TOO_DARK/TOO_BRIGHT/BLURRY는 여러 개가 동시에 발생할 수 있고 모두 WARNING이다. ERROR는 품질 불량이 아니라 계산/파일 접근 등의 실패다.

V6에서 추가한 테이블:

- photo_quality_result: 첨부별 attempt 이력·현재 상태·검사기/정책 버전·원본/검사 크기·밝기/비율/분산·문제·처리 시각/시간·실패 코드·실행 token/lease.
- photo_quality_confirmation: 해당 결과 ID에 대한 확인 사용자·시각. 같은 결과/사용자 UNIQUE로 중복 확인을 방지한다.
- diagnosis_input_attachment: job/input_revision마다 **실제로 선택된 ID**, 당시 검사 결과·경고 확인·품질 gate(CHECKED/LEGACY/DISABLED/NOT_APPLICABLE)·이번 추가 여부를 보존한다.

attachment에는 quality_required/latest_quality_result_id/use_status/replaced_by_id를 추가했다. 기존 결과와 확인은 삭제하지 않는다. latest FK는 같은 첨부 결과만 연결한다. 새 검사 결과를 만들 때만 attachment의 최신 포인터를 바꾸고, worker는 이 포인터를 변경하지 않는다.

재검사는 ERROR 또는 정책/검사기 버전 변경 때만 허용한다. 같은 정책의 완료 결과를 임의 반복 실행하지 않는다. 이전 결과 ID를 지정하고 첨부 행 잠금·previous_result_id UNIQUE로 동시/중복 retry를 한 번으로 만든다. resultId가 달라졌으면 409, 이미 같은 이전 결과에서 만든 작업이 있으면 그 requestedResultId를 멱등 반환한다. 이 경우 최신 상태의 기준은 항상 GET quality다. legacy 첫 검사는 resultId=null이다.

DB PENDING이 durable queue다. 기존 scheduler가 500ms마다 bounded executor의 빈 슬롯 범위에서만 SKIP LOCKED로 claim한다. 새로운 Kafka 토픽이나 큐 서비스를 추가하지 않는다. 이미지 검증/디코딩/픽셀 계산은 DB transaction 밖에서 수행하며 상태 claim·저장·SSE 알림만 짧게 transaction 처리한다. 저장 커밋이 끝나야 worker가 사진을 볼 수 있다. 업로드 검증은 같은 제한된 pool에서 기다리고 30초 timeout, 포화 429로 응답한다.

서버 종료 시 미처리 PENDING은 재기동 후 계속 처리한다. RUNNING lease 만료는 run_token을 폐기하고 최대 2번 회수 후 3번째 만료 시 ERROR(WORKER_LEASE_EXHAUSTED)로 둔다. 늦게 끝난 이전 token은 결과를 저장할 수 없다. 프로세스 크래시의 처리 시간은 알 수 없어 null일 수 있다. lease는 대기열과 최대 이미지 처리 시간보다 충분히 길게 설정한다. 계산 worker 수와 최대 픽셀/byte 크기를 함께 고려해 JVM 메모리를 정하고 실제 부하로 점검해야 한다.

## 프런트 API 순서

모든 쓰기는 기사 권한·센터·CSRF를 검사한다. 본사는 quality 조회만 가능하다. 기존 접수 202 예:

```json
{"repairId":"13","taskId":null,"status":"RECEIVED","requiresDiagnosisRequest":true,"attachments":[{"attachmentId":"21","mimeType":"image/png","size":12345,"sha256":"...","quality":{"attachmentId":"21","enabled":true,"required":true,"status":"PENDING","latest":{"id":"7","status":"PENDING","verdict":null}}}]}
```

1. `GET /api/attachments/{id}/quality`: 최신 결과와 이력. 아래는 주요 필드 발췌이며 전체 응답은 OpenAPI에 있다.

```json
{"attachmentId":"21","enabled":true,"required":true,"useStatus":"AVAILABLE","replacedByAttachmentId":null,"status":"COMPLETED","latest":{"id":"7","status":"COMPLETED","verdict":"WARNING","checker_version":"java-imageio-luma-laplace-v1","policy_version":"pilot-v1-...","original_width":320,"original_height":240,"inspection_width":320,"inspection_height":240,"mean_brightness":28.1,"dark_ratio":0.8,"bright_ratio":0.01,"sharpness":42.3,"issues":["LOW_RESOLUTION","TOO_DARK","BLURRY"],"confirmedByCurrentUser":false,"guidance":[{"code":"LOW_RESOLUTION","message":"사진 해상도가 낮습니다. 원본 사진으로 다시 등록해 주세요."},{"code":"TOO_DARK","message":"사진이 어둡습니다. 조명을 보완해 촬영해 주세요."},{"code":"BLURRY","message":"사진이 흐릴 수 있습니다. 부품에 초점을 맞춰 다시 촬영해 주세요."}]}}
```

TOO_BRIGHT 안내: “사진 일부가 너무 밝습니다. 조명이나 촬영 각도를 조정해 주세요.”

2. 재촬영하면 기존 업로드 API에 새 파일을 저장한다. 최초 진단 전 requestId 생략, Agent 대기 중에는 기존 requestId 필수.
3. `PUT /api/attachments/{oldId}/selection` `{ "excluded":true,"replacedByAttachmentId":"22" }`. 교체 대상은 같은 접수·같은 추가 요청의 AVAILABLE 사진이어야 한다. 제외만 하려면 replacement를 생략한다. 다시 쓰려면 excluded=false. 과거에 보낸 input revision은 바꾸지 않고 다음 revision에만 반영한다.
4. WARNING을 그대로 쓸 경우 `POST /api/attachments/{id}/quality/acknowledgment` `{ "resultId":"7" }`. **현재 사용자와 현재 최신 결과에만** 확인을 저장한다. 다른 사진·재검사 결과로 확인을 이전하지 않는다.
5. `POST /api/repairs/{repairId}/diagnoses`(기존 /diagnosis도 지원) `{ "attachmentIds":["21","22"] }`. 적어도 이미지 1장이 필요하다. 선택 사진 모두 완료 PASS 또는 현재 사용자가 확인한 WARNING이어야 한다. PENDING/RUNNING/ERROR, 제외·교체된 사진, 이전 정책이면 409. 로그/문서는 함께 포함할 수 있다. 첫 선택은 input revision 1에 고정되며 같은 선택 재전송은 기존 task를 반환하고 다른 선택은 DIAGNOSIS_SELECTION_CHANGED다.
6. 추가 사진은 기존 resume API/submit alias를 사용한다. attachmentIds는 이번 추가 사진, 선택적 excludedAttachmentIds는 이전 revision에서 제외할 ID다. 누적 입력은 이전 선택 중 AVAILABLE/미제외 파일+이번 선택이다. 재촬영했지만 선택하지 않은 파일은 LangGraph로 보내지 않는다.
7. ERROR이면 `POST /api/attachments/{id}/quality/retry` `{ "resultId":"7" }`. 최대 시도 초과는 QUALITY_RETRY_LIMIT/409. 새 결과가 생겼다면 이전 WARNING 확인은 재사용하지 못한다.

Kafka는 기존 v2 토픽과 outbox를 사용한다. attachmentIds/attachments는 **선택된 누적 입력만** 포함하고 addedAttachmentIds, selectedAttachmentIds, excludedAttachmentIds를 구분한다. 내부 input 조회도 현재 revision의 선택 snapshot을 기준으로 반환한다. excludedAttachmentIds는 이 revision에서 보내지 않은 전체 첨부, explicitlyExcludedAttachmentIds는 resume 요청 본문으로 명시적으로 제외한 이전 선택을 뜻한다.

SSE는 기존 접수별 스트림에 PHOTO_QUALITY_COMPLETED/PHOTO_QUALITY_ERROR를 추가한다. `{attachmentId,resultId,status}` 알림 후 GET quality로 최신 내용을 조회한다. 완료·실패·업무 상태 알림은 DB 커밋 뒤 전달한다.

## 검증과 한계

[PHOTO_QUALITY_VERIFICATION.md](PHOTO_QUALITY_VERIFICATION.md)에 실제 테스트·측정 결과를 기록한다. 합성 JPEG/PNG는 계산·흐름 검증용이며 실제 PCB·모터 사진에서 판독 정확도나 임계값의 유효성을 검증한 것이 아니다. 얕은 심도·무질감 면·노이즈가 많은 사진·반사광·피사체 크기는 단순 분산/밝기 검사만으로 판단할 수 없다. 경고는 사용자가 확인하고 계속 진행할 수 있다.
