# getMetaData

메일 헤더와 URL에서 메타데이터를 추출하는 REST API + 웹 화면입니다.

Spring Boot(Java)가 요청을 받고, 실제 파싱은 **Python 표준 라이브러리 스크립트**가 담당합니다.
Java는 `ProcessBuilder`로 Python 프로세스를 실행하고 `stdin(JSON) → stdout(JSON)` 방식으로 통신합니다.

> 설계 배경·구조·장단점·대안 비교는 **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** 에 정리되어 있습니다.
> 이 문서(README)는 **설치·실행·사용법** 중심입니다.

---

## 목차

1. [주요 기능](#1-주요-기능)
2. [기술 스택](#2-기술-스택)
3. [빠른 시작](#3-빠른-시작)
4. [웹 화면 (UI)](#4-웹-화면-ui)
5. [API 레퍼런스](#5-api-레퍼런스)
6. [설정](#6-설정)
7. [프로젝트 구조](#7-프로젝트-구조)
8. [동작 방식 (요약)](#8-동작-방식-요약)
9. [트러블슈팅](#9-트러블슈팅)

---

## 1. 주요 기능

- **메일 헤더 추출**: `From`, `To`, `Subject`, `Date`, `Message-ID` 등 주요 헤더와 전달 경로(`Received`), 인증 정보(SPF/DKIM/ARC)를 파싱
- **URL 메타데이터 추출**: 페이지 `<title>`, `description`, `canonical`, Open Graph(`og:*`), Twitter Card(`twitter:*`) 태그 수집
- **웹 화면(UI)**: `http://localhost:8080` 접속만으로 브라우저에서 바로 추출 실행/확인
- Python **표준 라이브러리만 사용** — 별도 `pip install` 불필요

## 2. 기술 스택

| 구분 | 내용 |
| --- | --- |
| 언어 | Java 21, Python 3 |
| 프레임워크 | Spring Boot 4.1.0 (Spring Web MVC) |
| JSON | Jackson 2 (`jackson-databind`) — 내부 검증/요청 조립용 |
| 빌드 | Maven (`war` 패키징) |
| 연동 | `ProcessBuilder` 기반 Java ↔ Python IPC |
| 프런트 | 정적 HTML/JS 단일 페이지 (`static/index.html`) |

## 3. 빠른 시작

### 3-1. 요구 사항

| 항목 | 내용 |
| --- | --- |
| **JDK 21+** | 코드가 `record` 등 Java 16+ 문법 사용. 터미널 Maven 실행 시 `JAVA_HOME`이 JDK 21을 가리켜야 함 ([트러블슈팅 9-2](#9-2-record-컴파일-에러-class-interface-or-enum-expected)) |
| **Python 3** | PATH 등록 필요. Windows: `python` / macOS·Linux: `python3` ([설정](#6-설정)에서 명령어 변경) |

```powershell
# Windows PowerShell — 실행 전 JDK 21 지정 예시
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.2"
```

### 3-2. 실행

```bash
# Windows
mvnw.cmd spring-boot:run

# macOS / Linux
./mvnw spring-boot:run
```

### 3-3. 확인

기동 후 브라우저에서 **http://localhost:8080** 접속 → 화면에서 URL/메일 헤더를 입력해 추출합니다.
기본 포트는 `8080`입니다(`server.port`로 변경 가능).

```bash
# 터미널에서 바로 확인하고 싶다면
curl -X POST http://localhost:8080/api/metadata/url \
  -H "Content-Type: application/json" \
  -d '{"value": "https://example.com"}'
```

## 4. 웹 화면 (UI)

`http://localhost:8080` 에 접속하면 정적 페이지(`src/main/resources/static/index.html`)가 열립니다.

- **🌐 URL 탭** — URL 입력 후 `추출하기` → 제목·설명·Open Graph 등 표시
- **✉️ 메일 헤더 탭** — 헤더 원문을 붙여넣고 `추출하기` → From/To/Subject·전달 경로·인증 정보 표시
- 결과는 색상이 입혀진 JSON으로 출력되며, 성공/실패 배지와 HTTP 상태가 함께 표시됩니다.
- 두 입력 칸에는 바로 눌러볼 수 있는 예시 값이 미리 채워져 있습니다.

> UI는 아래 API의 클라이언트일 뿐이므로, curl·Postman 등으로 동일하게 호출할 수 있습니다.

## 5. API 레퍼런스

Base URL: `http://localhost:8080/api/metadata` — 두 엔드포인트 모두 **POST 전용**입니다.

### 5-1. 메일 헤더에서 추출

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
      "From": "Alice <alice@example.com>",
      "To": "bob@example.com",
      "Subject": "안녕하세요",
      "Date": "Mon, 5 Jul 2026 10:00:00 +0900"
    },
    "received": ["from mx1.example.com by mx2.example.com"],
    "hopCount": 1,
    "authentication": {}
  }
}
```

### 5-2. URL에서 추출

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
    "finalUrl": "https://example.com",
    "statusCode": 200,
    "contentType": "text/html",
    "charset": "utf-8",
    "title": "Example Domain",
    "description": null,
    "canonical": null,
    "openGraph": {},
    "twitter": {}
  }
}
```

### 5-3. 오류 응답

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
| GET으로 API 호출 | `405 Method Not Allowed` (POST 전용) |

## 6. 설정

`src/main/resources/application.properties`에서 Python 연동 동작을 변경할 수 있습니다.

```properties
# Python 실행 명령 (Windows: python / macOS,Linux: python3)
app.python.command=python
# Python 스크립트 실행 타임아웃(초)
app.python.timeout-seconds=30
# classpath 내 추출 스크립트 위치
app.python.script=python/extractor.py
```

## 7. 프로젝트 구조

```
├── docs/
│   └── ARCHITECTURE.md                  # 아키텍처 해설 문서 (설계·장단점·대안 비교)
└── src/main/
    ├── java/com/api/getmetadata/
    │   ├── GetMetaDataApplication.java   # 애플리케이션 진입점
    │   ├── ServletInitializer.java       # WAR 배포용 초기화
    │   ├── config/
    │   │   ├── PythonProperties.java      # app.python.* 설정 바인딩
    │   │   └── JacksonConfig.java         # Jackson 2 ObjectMapper 빈
    │   ├── controller/
    │   │   ├── MetadataController.java     # REST 엔드포인트
    │   │   └── GlobalExceptionHandler.java # 공통 예외 처리
    │   ├── dto/ExtractRequest.java        # URL 요청 본문 DTO
    │   ├── service/MetadataService.java   # 요청 조립 · 응답 검증
    │   └── python/
    │       ├── PythonScriptRunner.java     # Python 프로세스 실행/통신
    │       └── PythonExecutionException.java # Python 실행 예외
    └── resources/
        ├── application.properties
        ├── static/index.html             # 웹 UI 페이지 (루트 /)
        └── python/extractor.py           # 실제 메타데이터 추출 스크립트
```

## 8. 동작 방식 (요약)

```
브라우저 화면(static/index.html)
        │  fetch(POST)
        ▼
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
                                      MetadataService (성공 검증)
                                                  │  원본 JSON 문자열
클라이언트 ◀──JSON── MetadataController ◀──────────┘
```

- 기동 시 `PythonScriptRunner`가 classpath의 `extractor.py`를 임시 파일로 복사해 두므로,
  WAR/JAR로 패키징된 후에도 스크립트를 정상 실행할 수 있습니다.
- 계층별 책임, 설계 결정(왜 원본 JSON 문자열을 반환하는지, 왜 UTF-8을 강제하는지),
  장단점과 대안 아키텍처 비교는 **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** 를 참고하세요.

## 9. 트러블슈팅

실제 개발 과정에서 겪은 문제와 해결책입니다.

### 9-1. `localhost:8080` 접속 시 Whitelabel Error Page가 뜸

- **원인**: 예전 버전에는 루트(`/`)에 화면이 없어 404 안내가 표시됐습니다. 서버 오류가 아닙니다.
- **현재**: `static/index.html` 추가로 루트에서 UI가 열립니다. 그 외 매핑 없는 경로는 여전히 404,
  API를 GET으로 호출하면 405가 뜨는데 이는 **POST 전용이라는 뜻**으로 정상 동작입니다.

### 9-2. `record` 컴파일 에러 (*class, interface, or enum expected*)

- **원인**: `JAVA_HOME`이 JDK 8 등 낮은 버전을 가리키면 Java 16+ 문법인 `record`를 컴파일하지 못합니다.
- **해결**: 터미널에서 Maven 실행 전 `JAVA_HOME`을 JDK 21로 지정하세요. ([빠른 시작 3-1](#3-1-요구-사항))

### 9-3. 응답의 한글이 `?` / `�` 로 깨짐

- **원인**: Windows의 Python은 stdout 기본 인코딩이 콘솔 코드페이지(cp949)라, UTF-8로 읽는 Java와 어긋납니다.
- **해결(적용됨)**: `PythonScriptRunner`가 Python 프로세스에 `PYTHONIOENCODING=utf-8`, `PYTHONUTF8=1`
  환경변수를 설정해 항상 UTF-8로 통신합니다. `pom.xml`에도 UTF-8 소스 인코딩이 명시되어 있습니다.

### 9-4. 응답 JSON이 `{"array":false,"object":true,...}` 형태로 나옴

- **원인**: Spring Boot 4의 기본 JSON은 Jackson 3(`tools.jackson`)이라, Jackson 2의 `JsonNode`를
  컨트롤러에서 반환하면 트리가 아닌 POJO(게터 모음)로 잘못 직렬화됩니다.
- **해결(적용됨)**: 서비스가 성공 여부만 검증하고 **Python이 만든 원본 JSON 문자열을 그대로 반환**합니다.
  상세한 배경은 [ARCHITECTURE.md §5-1](docs/ARCHITECTURE.md) 참고.

### 9-5. `Python 실행 실패 (command='python' ...)` 오류

- **원인**: Python 미설치 또는 PATH 미등록.
- **해결**: Python 3 설치 후, 필요 시 `application.properties`의 `app.python.command`를
  환경에 맞는 명령(`python3`, 절대경로 등)으로 변경하세요.
