# 통합 저장소 사진 품질 검사 이식

2026-10-22. 대상: `Final_Robot-Vacuum-Repair-Agent/backend`, 브랜치 `backend/feat/image-precheck`.

원본 A의 HEAD `63e0f96` 이후 미커밋 사진 품질 검사 변경을 기준으로 이식했다. A는 수정하지 않았다. backend 외 팀 코드, 프런트엔드, Agent, 승인 처리, 사진별 target(PCB/MECHANICAL→MOTOR/SW) 매핑을 보존했다. 기존 파일은 3-way 비교 후 충돌 부분만 통합했다. 커밋·push·전체 시연은 수행하지 않았다.

## DB

기존 V1~V5와 번호 충돌 없이 `V6__photo_quality_and_diagnosis_selection.sql`을 추가했다. 기존 마이그레이션은 변경하지 않았다.

- 신규: photo_quality_result, photo_quality_confirmation, diagnosis_input_attachment.
- attachment 추가: quality_required, latest_quality_result_id, use_status, replaced_by_id.
- docs/db/schema.sql에 같은 확장을 반영했다.
- 이번 실행에서 마이그레이션 적용은 독립 Testcontainers DB에서만 검증했다. 개발·운영 DB에는 앱을 기동하거나 마이그레이션하지 않았다.

## 실행·연동

Java 21, 기존 Gradle 의존성을 사용한다. 새 외부 이미지 라이브러리나 서비스는 없다.

`PHOTO_QUALITY_ENABLED=true`가 기본값이다. 업로드만으로 진단을 시작하지 않는다. 접수 응답의 attachmentId로 품질 조회 → WARNING 확인 → 선택 attachmentIds를 진단 요청 본문에 제출한다. 추가 사진 제출에도 같은 검증을 적용한다. 알고리즘·임시 기준·요청/응답 예시는 [PHOTO_QUALITY.md](PHOTO_QUALITY.md), 전체 API는 [openapi.json](openapi.json)에 있다. B의 승인 API와 사진 부위 입력도 OpenAPI에 유지했다.

프런트엔드는 이번 이식 범위에 포함하지 않았다. 기존 자동 진단 UI를 그대로 사용할 때는 `PHOTO_QUALITY_ENABLED=false`로 실행한다. 디코딩 안전성 검증은 비활성화 상태에서도 적용한다. 기존 첨부는 LEGACY_UNCHECKED 전환 정책으로 구분한다.

원본의 scripts/demo.py 및 전체 시연 변경은 이식하지 않았다. PHOTO_QUALITY_VERIFICATION.md의 43개 테스트·시연·성능 수치는 A에서 실행한 기록이며 B에서 재현했다는 뜻이 아니다. 실제 부품 사진의 흐림 판단 정확도는 검증하지 않았다.

## 이번 이식 검증

backend에서 실행:

```bash
DOCKER_HOST=unix://$HOME/.docker/run/docker.sock ./gradlew test \
  --tests '*PhotoQuality*' \
  --tests '*JavaPhotoQualityCheckerTests' \
  --tests '*SchemaMigrationTests' \
  --tests '*BackendApplicationTests.disablingQuality*' \
  --tests '*BackendApplicationTests.openApiListsEveryApplicationRoute' bootJar
```

BUILD SUCCESSFUL (최종 16초). 총 24개, 실패 0·오류 0·생략 0.

- 계산 8, 품질 통합 11, 실행 풀 상한 1, 계산 benchmark 1.
- V6/schema.sql 일치 및 기존 자료 이관 1.
- 비활성화 기존 흐름 1, 통합 승인 API를 포함한 API 경로/OpenAPI 일치 1.
- 통합 테스트에 사진 부위 매핑과 진단 승인 전이 보존 검증을 추가했다.

결과: build/reports/tests/test/index.html, build/test-results/test. JAR: build/libs/backend-0.0.1-SNAPSHOT.jar. git diff --check 통과. 전체 테스트·전체 시연·실서버 부하 측정은 요청 범위에 따라 실행하지 않았다.
