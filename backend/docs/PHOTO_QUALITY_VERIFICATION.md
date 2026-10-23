> 아래 기록은 원본 A에서 수행한 검증이다. 통합 저장소 이식 검증은 [PHOTO_QUALITY_PORT.md](PHOTO_QUALITY_PORT.md)를 참고한다.

# 사진 품질 검사 실제 검증 기록

일자: 2026-10-22 Asia/Seoul. Java 21.0.12.1, Spring Boot 4.1.1, macOS aarch64, JVM availableProcessors=10. 테스트 DB/Kafka는 독립 PostgreSQL 17·Kafka 4.1.1 Testcontainers다. 실제 부품 사진은 사용하지 않았다.

## 빌드·자동 테스트

```bash
DOCKER_HOST=unix://$HOME/.docker/run/docker.sock ./gradlew test bootJar
```

**BUILD SUCCESSFUL**, 총43개, 실패0·오류0·생략0. 최종 Gradle 실행 약27초. 실행 JAR 생성. 기존 기능의 기본 정책과 동일하게 비활성화한 회귀 테스트, 활성화 품질 통합 테스트를 별도 Spring context로 실행했다. SchemaMigrationTests는 V6을 포함한 Flyway DB와 docs/db/schema.sql의 컬럼·제약·인덱스 일치도 확인한다.

| 분류 | 수 | 검증 |
|---|---:|---|
| 기존 backend 회귀+비활성화 | 22 | 인증/센터/리포트/발주/Kafka/SSE, 자동 진단 유지, PENDING을 PASS로 바꾸지 않는 DISABLED gate |
| 스키마 이관/문서 일치 | 1 | 기존 자료 보존 및 V6 확장 일치 |
| Java 계산 | 8 | JPEG·PNG, 검정/흰색·복수 경고, 단계적 blur, 극소/투명/portrait, 원본 크기·축소, 손상/잘린 JPEG·미지원 형식·픽셀 상한, 정책 identity |
| 활성화 통합 | 10 | 저장 후 검사, transaction 밖 계산, 선택 입력만 전송, 미완료/미확인 WARNING 차단, 정확한 결과 확인, ERROR와 WARNING 구분, retry 상한·멱등/동시 요청, 추가 사진 재촬영/재개, 센터·역할, legacy/제외, 만료 token fencing, 위장 파일·크기 상한, 검사 중 HTTP 응답 |
| 실행 풀 상한 | 1 | worker1/queue1을 막은 상태에서 세 번째 검증을 429로 거절하고 동시 실행/대기 수 확인 |
| 계산 시간 측정 | 1 | 아래 합성 JPEG 3개 크기 |

상세 XML: build/test-results/test, HTML: build/reports/tests/test/index.html. 손상 확장자에 대해서는 확장자가 .exe인 유효 PNG도 실제 디코딩으로 허용하는 것을 확인하고, JPEG MIME으로 위장한 비이미지는 거절했다.

## 합성 이미지 처리 시간

한 JVM에서 고정 random seed의 grayscale texture를 JPEG로 인코딩하고, 크기별 2회 warm-up 후 5회 검사했다. 측정에는 ImageIO decode·resize·알파 합성·밝기·분산 계산이 포함되고, 이미지 생성/인코딩·업로드·디스크 저장·DB 시간은 제외된다. 검사 사본 긴 변1024, 일반 정책, 성능 개선 전후 비교나 정확도 검증이 아니다. 실행 시점 부하/JIT/하드웨어에 따라 달라진다.

| 원본 | JPEG bytes | 중앙값 ms | 최대 ms |
|---|---:|---:|---:|
| 640×480 | 137274 | 4.960 | 7.035 |
| 1920×1080 | 922718 | 19.800 | 21.522 |
| 4000×3000 | 5335895 | 64.602 | 67.241 |

## 검사 실행 중 API 응답 측정

worker2 중 검사1개를 latch로 정지시키고, quality GET5회와 동일 접수의 추가 업로드1회를 측정했다. **MockMvc transport**이며 실제 네트워크/브라우저 지연이 아니다. 다른 계산 worker1개가 업로드 사전 디코딩을 수행하고 결과는 PENDING으로 반환했다. 원래 검사의 latch를 풀기 전에 업로드가 완료됨을 확인했다. 포화 상태 처리량/지속 부하 성능은 측정하지 않았다.

```
PHOTO_API_BENCH transport=MockMvc workers=2 busyInspection=1 getRuns=5 getMedianMs=4.673 getMaxMs=7.436 uploadMs=28.931
```

## 실제 로컬 실행

실행 JAR를 18080 포트에서 PHOTO_QUALITY_ENABLED=true로 기동했다. 기존 Compose PostgreSQL에 V6이 성공 적용됐고 Kafka는 기존 v2 토픽을 그대로 사용했다. 실제 HTTP 시연 결과:

```text
complete: SUCCEEDED, photo rounds=0, repairId=13
procurement -> approval -> shipping -> report v1 -> HQ comment -> report v2: OK
photo: SUCCEEDED, photo rounds=1, repairId=14
manual: FAILED, photo rounds=3, repairId=15
Dashboard draft/confirmation update and SSE Last-Event-ID replay: OK
All HTTP/Kafka demonstration scenarios passed.
```

검증을 위해 새 접수3건·품질 검사7건·리포트1건·코멘트1건을 만들고 모터1개를 로컬 출고했다. 이전 파일과 접수는 보존했다. 실제 파일은 정상 PNG이며 임시 기준의 저해상도/흐림 경고를 확인한 뒤 명시적으로 선택해 진단했다. 시연 앱은 검증 후 종료한다.
 시연 스크립트는 scripts/demo.py이며 새 접수 품질 완료→WARNING 확인→명시적 진단 선택→사진 추가/같은 작업 재개→3회 후 수동 검토→재고/발주→리포트/코멘트/통계→SSE Last-Event-ID를 검사한다. 시연은 새 fixture 접수·사진·리포트 등을 생성하고 모터1개를 로컬 출고할 수 있다.

## 한계

기준값은 미보정 임시값이다. 실제 PCB·모터 사진에 대한 흐림/조명 판별 정확도, 촬영 거리·부품 식별·반사광 가림 판단은 검증하거나 제공하지 않는다. EXIF 방향 회전은 미지원이다. 여러 Spring 인스턴스의 실서버 장기부하·강제 종료 중 파일/DB 동시 장애는 미검증이며, DB lease 만료/늦은 token 거절은 자동 테스트로 검증했다. JVM 메모리·압축 이미지 상한·worker/queue/lease는 운영 데이터로 함께 조정해야 한다.
