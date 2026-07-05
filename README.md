# getMetaData

메일 헤더와 URL에서 메타데이터를 추출하는 REST API입니다.
Spring Boot(Java)가 요청을 받고, 실제 파싱은 **Python 표준 라이브러리 스크립트**가 담당합니다.
Java는 `ProcessBuilder`로 Python 프로세스를 실행하고 `stdin(JSON) → stdout(JSON)` 방식으로 통신합니다.

## 주요 기능

- **메일 헤더 추출**: `From`, `To`, `Subject`, `Date`, `Message-ID` 등 주요 헤더와 전달 경로(`Received`), 인증 정보(SPF/DKIM/ARC)를 파싱
- **URL 메타데이터 추출**: 페이지 `<title>`, `description`, `canonical`, Open Graph(`og:*`), Twitter Card(`twitter:*`) 태그 수집
- Python 표준 라이브러리만 사용 — 별도 `pip install` 불필요

## 기술 스택

| 구분 | 내용 |
| --- | --- |
| 언어 | Java 21, Python 3 |
| 프레임워크 | Spring Boot 4.1.0 (Spring Web MVC) |
| 빌드 | Maven (`war` 패키징) |
| 연동 | `ProcessBuilder` 기반 Java ↔ Python IPC |

## 요구 사항

- **JDK 21** 이상
- **Python 3** 설치 및 PATH 등록
  - Windows: `python`
  - macOS / Linux: `python3` (아래 설정에서 명령어 변경)

## 실행 방법

```bash
# Windows
mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```

기본 포트는 `8080` 입니다.

## API

Base URL: `http://localhost:8080/api/metadata`

### 1. 메일 헤더에서 추출

메일 헤더 원문을 `text/plain` 본문으로 전송합니다.

```
POST /api/metadata/mail-header
Content-Type: text/plain
```

```bash
curl -X POST http://localhost:8080/api/metadata/mail-header \
  -H "Content-Type: text/plain" \
  --data-binary @mail.txt
```

**응답 예시**

```json
{
  "success": true,
  "type": "mail_header",
  "metadata": {
    "headers": {
      "From": "sender@example.com",
      "To": "receiver@example.com",
      "Subject": "안녕하세요",
      "Date": "Mon, 5 Jul 2026 10:00:00 +0900"
    },
    "received": ["from ... by ..."],
    "hopCount": 1,
    "authentication": {
      "Authentication-Results": ["spf=pass ..."]
    }
  }
}
```

### 2. URL에서 추출

URL을 JSON 본문으로 전송합니다.

```
POST /api/metadata/url
Content-Type: application/json
```

```bash
curl -X POST http://localhost:8080/api/metadata/url \
  -H "Content-Type: application/json" \
  -d '{"value": "https://example.com"}'
```

**응답 예시**

```json
{
  "success": true,
  "type": "url",
  "metadata": {
    "requestedUrl": "https://example.com",
    "finalUrl": "https://example.com/",
    "statusCode": 200,
    "contentType": "text/html; charset=UTF-8",
    "charset": "UTF-8",
    "title": "Example Domain",
    "description": null,
    "canonical": null,
    "openGraph": {},
    "twitter": {}
  }
}
```

### 오류 응답

오류는 일관된 JSON 형태로 반환됩니다.

```json
{
  "success": false,
  "status": 400,
  "error": "입력 값(value)이 비어 있습니다."
}
```

| 상황 | HTTP 상태 |
| --- | --- |
| 잘못된 입력(빈 값 등) | `400 Bad Request` |
| Python 실행/추출 실패 | `502 Bad Gateway` |

## 설정

`src/main/resources/application.properties`에서 Python 연동 동작을 변경할 수 있습니다.

```properties
# Python 실행 명령 (Windows: python / macOS,Linux: python3)
app.python.command=python
# Python 스크립트 실행 타임아웃(초)
app.python.timeout-seconds=30
# classpath 내 추출 스크립트 위치
app.python.script=python/extractor.py
```

## 프로젝트 구조

```
src/main/
├── java/com/api/getmetadata/
│   ├── GetMetaDataApplication.java      # 애플리케이션 진입점
│   ├── ServletInitializer.java          # WAR 배포용 초기화
│   ├── config/PythonProperties.java     # app.python.* 설정 바인딩
│   ├── controller/
│   │   ├── MetadataController.java       # REST 엔드포인트
│   │   └── GlobalExceptionHandler.java   # 공통 예외 처리
│   ├── dto/ExtractRequest.java          # URL 요청 본문 DTO
│   ├── service/MetadataService.java     # 요청 조립 · 응답 파싱
│   └── python/
│       ├── PythonScriptRunner.java       # Python 프로세스 실행/통신
│       └── PythonExecutionException.java  # Python 실행 예외
└── resources/
    ├── application.properties
    └── python/extractor.py              # 실제 메타데이터 추출 스크립트
```

## 동작 방식

```
클라이언트 ──HTTP──▶ MetadataController ──▶ MetadataService
                                                  │
                                    {"type","value"} (JSON)
                                                  ▼
                                        PythonScriptRunner
                                          (ProcessBuilder)
                                                  │ stdin
                                                  ▼
                                        python/extractor.py
                                                  │ stdout
                                          {"success",...} (JSON)
                                                  ▼
                                        MetadataService (파싱)
                                                  │
클라이언트 ◀──JSON── MetadataController ◀──────────┘
```

기동 시 `PythonScriptRunner`가 classpath의 `extractor.py`를 임시 파일로 복사해 두므로,
WAR/JAR로 패키징된 후에도 스크립트를 정상 실행할 수 있습니다.
