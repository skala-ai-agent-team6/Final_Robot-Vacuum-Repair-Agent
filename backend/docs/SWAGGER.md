# Swagger UI API 문서

애플리케이션 기동 후 아래 주소에서 모든 업무·내부 컨트롤러 API를 확인할 수 있다.

- UI: http://localhost:8080/swagger-ui.html (실제 화면 /swagger-ui/index.html)
- OpenAPI JSON: http://localhost:8080/v3/api-docs

포트를 변경했다면 같은 포트로 접속한다. context path를 사용하는 경우 그 경로 아래에서 연다. 문서와 UI 파일은 인증 없이 읽을 수 있고, 업무 API의 로그인·센터별 권한·CSRF 및 내부 API Bearer 인증은 그대로 적용된다. UI는 같은 서버의 실제 API를 실행하므로 예시 ID를 실제 데이터로 바꿔 호출한다.

## 실행

Java 21과 기존 DB/Kafka 환경을 사용한다. backend에서 기존 환경변수를 로드하고 `./gradlew bootRun --args='--spring.profiles.active=dev'`를 실행한다. dev 프로필은 로컬 HTTP 쿠키 설정을 사용한다. 배포 환경에서는 기존 프로필과 HTTPS 설정을 사용한다. 별도 문서 서버나 CDN 연결은 필요하지 않다. 최초 Gradle 빌드는 Maven Central에서 Swagger UI WebJar를 다운로드한다.

## UI 인증과 호출 순서

1. 인증 그룹의 POST /api/auth/login을 펼쳐 Try it out을 누르고 실제 username/password를 입력한다. UI가 로그인 전 CSRF 토큰을 조회한다.
2. 로그인에 성공하면 브라우저 세션 쿠키로 업무 API를 호출한다. 쿠키 인증은 Authorize에 임의 값을 넣지 않는다. 쓰기 요청은 UI가 매번 /api/auth/csrf에서 최신 토큰을 조회하므로 X-CSRF-TOKEN 입력은 비워도 된다. 직접 HTTP 호출할 때는 CSRF 헤더가 필요하다.
3. 내부 서비스 그룹은 Authorize → ServiceToken에 실제 내부 토큰을 입력한다. Bearer 접두어는 UI가 붙인다. 토큰은 로컬 저장소에 영구 보관하지 않는다.
4. multipart 접수의 data는 JSON, files는 JPEG/PNG·기존 지원 파일이다. targets는 파일 순서와 같은 부위 목록이다. 추가 업로드는 선택적으로 requestId/target을 받는다.
5. 사진 품질 활성화 시 접수 저장만으로 진단이 시작되지 않는다. 품질 조회 → WARNING 확인 → attachmentIds를 명시한 진단 요청 → 추가 사진 시 동일 작업 재개 순서다. 자세한 예시는 PHOTO_QUALITY.md를 참고한다.
6. SSE는 장시간 연결이며 일반 Swagger 응답 미리보기보다 EventSource 또는 curl -N으로 확인한다. Last-Event-ID 재연결 헤더를 문서화했다.

## 문서 관리

기존 응답이 Map 기반이고 bigint ID를 문자열로 변환하므로, 검토된 `docs/openapi.json` 계약을 그대로 제공한다. Swagger UI WebJar 5.32.1만 추가했으며 별도의 런타임 스키마 추론 라이브러리는 추가하지 않았다. JSON은 Gradle processResources가 JAR의 api-docs/openapi.json으로 복사한다.

API 변경 시 `scripts/generate_openapi.py`를 수정하고 backend에서 다음을 실행한다.

```bash
python3 scripts/generate_openapi.py
./gradlew test --tests '*BackendApplicationTests' bootJar
node scripts/test_swagger_ui.cjs
```

컨트롤러의 별칭과 발주 action 경로를 포함해 문서의 경로·HTTP 메서드가 일치하는지 테스트한다. 추가한 API를 생성기에 등록하지 않으면 테스트가 실패한다. 문서 제공 컨트롤러(/swagger-ui.html, /swagger-ui, /v3/api-docs)는 UI 인프라이므로 업무 API 목록에 포함하지 않는다. 로그인·로그아웃은 Spring Security 필터 API지만 함께 문서화했다. 예시는 호출 형식을 설명하며 실제 데이터 존재나 업무 상태를 보장하지 않는다.

## 실제 검증

2026-10-23, Java 21, PostgreSQL 17·Kafka 4.1.1 Testcontainers. BackendApplicationTests **23개 통과, 실패·오류·생략 0**. bootJar 성공(21초), JAR 내부 UI·OpenAPI 리소스 포함 확인. MockMvc에서 인증 없는 UI/JSON/CSS/JS 조회, 패키징된 문서와 원본 일치, 모든 업무 경로 일치, 업무·내부 API 인증 유지, 로그인 CSRF 강제를 검증했다.

Node 테스트는 context path, 매 쓰기 요청 최신 CSRF 교체, 조회·내부·다른 origin에 토큰을 주입하지 않는 동작을 검증한다. 브라우저 시각적 렌더링과 전체 실서버 API 시연은 수행하지 않았다. DB 마이그레이션 변경은 없다. 앞선 사진 품질 작업 파일은 그대로 보존했다.

참고: [Swagger UI 공식 설정](https://swagger.io/docs/open-source-tools/swagger-ui/usage/configuration/), [사용한 WebJar](https://central.sonatype.com/artifact/org.webjars/swagger-ui/5.32.1).

## API 목록 (54개)

| 분류 | 메서드 | 경로 |
|---|---|---|
| 인증 | GET | `/api/auth/csrf` |
| 인증 | POST | `/api/auth/login` |
| 인증 | GET | `/api/auth/me` |
| 인증 | POST | `/api/auth/logout` |
| 제품·부품 | GET | `/api/products` |
| 접수·진단 | POST | `/api/repairs` |
| 접수·진단 | GET | `/api/repairs` |
| 접수·진단 | GET | `/api/repairs/{id}` |
| 접수·진단 | POST | `/api/repairs/{id}/diagnosis` |
| 접수·진단 | GET | `/api/repairs/{id}/diagnosis` |
| 접수·진단 | GET | `/api/repairs/{id}/diagnosis/tools` |
| 접수·진단 | POST | `/api/repairs/{id}/attachments` |
| 접수·진단 | GET | `/api/attachments/{id}` |
| 접수·진단 | POST | `/api/repairs/{id}/diagnosis/resume` |
| 접수·진단 | GET | `/api/repairs/{id}/events` |
| 제품·부품 | GET | `/api/parts` |
| 제품·부품 | GET | `/api/parts/{id}/inventory` |
| 제품·부품 | GET | `/api/parts/{id}/factories` |
| 발주·조달 | GET | `/api/repairs/{id}/procurement` |
| 발주·조달 | POST | `/api/repairs/{id}/procurement/retry` |
| 발주·조달 | POST | `/api/repairs/{id}/orders` |
| 발주·조달 | GET | `/api/orders/{id}` |
| 발주·조달 | POST | `/api/orders/{id}/approve` |
| 발주·조달 | POST | `/api/orders/{id}/reject` |
| 발주·조달 | POST | `/api/orders/{id}/cancel` |
| 발주·조달 | POST | `/api/orders/{id}/ship` |
| 발주·조달 | POST | `/api/orders/{id}/receive` |
| 수리 리포트 | GET | `/api/repairs/{id}/report` |
| 수리 리포트 | POST | `/api/repairs/{id}/report` |
| 수리 리포트 | PUT | `/api/repairs/{id}/report/draft` |
| 수리 리포트 | GET | `/api/reports/{id}` |
| 수리 리포트 | GET | `/api/reports/{id}/history` |
| 수리 리포트 | POST | `/api/reports/{id}/confirm` |
| 수리 리포트 | GET | `/api/reports/{id}/comments` |
| 수리 리포트 | POST | `/api/reports/{id}/comments` |
| 통계 | GET | `/api/dashboard` |
| 내부 서비스 | GET | `/internal/tasks/{id}/input` |
| 내부 서비스 | GET | `/internal/attachments/{id}` |
| 내부 서비스 | GET | `/internal/products` |
| 내부 서비스 | GET | `/internal/parts` |
| 내부 서비스 | GET | `/internal/parts/{id}/inventory` |
| 내부 서비스 | GET | `/internal/parts/{id}/factories` |
| 내부 서비스 | GET | `/internal/confirmed-cases` |
| 접수·진단 | POST | `/api/repairs/{id}/diagnoses` |
| 접수·진단 | GET | `/api/tasks/{id}` |
| 접수·진단 | POST | `/api/photo-requests/{id}/submit` |
| 통계 | GET | `/api/dashboard/faults` |
| 내부 서비스 | GET | `/internal/fault-cases` |
| 내부 서비스 | GET | `/internal/parts/{id}/availability` |
| 사진 품질 | GET | `/api/attachments/{id}/quality` |
| 사진 품질 | POST | `/api/attachments/{id}/quality/acknowledgment` |
| 사진 품질 | POST | `/api/attachments/{id}/quality/retry` |
| 사진 품질 | PUT | `/api/attachments/{id}/selection` |
| 접수·진단 | POST | `/api/repairs/{id}/approval` |
