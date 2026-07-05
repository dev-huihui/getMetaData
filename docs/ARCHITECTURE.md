# 아키텍처 해설 (Architecture)

getMetaData 는 **메일 헤더 / URL 에서 메타데이터를 추출**하는 REST API 입니다.
요청 수신·검증·응답은 **Spring Boot(Java)** 가 담당하고, 실제 파싱은 **Python 표준 라이브러리 스크립트**가 수행합니다.
Java 와 Python 은 `ProcessBuilder` 기반 프로세스 실행 + `stdin/stdout(JSON)` 파이프로 통신합니다.

> **이 문서는 "왜 이렇게 만들었는가"를 설명합니다.** 설치·실행·API 사용법은 [README.md](../README.md) 를 참고하세요.

**읽는 순서 가이드**

| 알고 싶은 것 | 볼 곳 |
| --- | --- |
| 전체 그림과 각 클래스의 역할 | 1부 — 시스템 구조 (§1~§3) |
| Java↔Python 이 어떻게/왜 이렇게 통신하는가 | 2부 — 핵심 설계 (§4~§5) |
| 이 설계의 장단점과, 다르게 만들었다면 어땠을까 | 3부 — 설계 평가 (§6~§7) |
| 오류 처리·실행 환경·앞으로의 확장 | 4부 — 운영과 확장 (§8~§10) |

**목차**

- **1부. 시스템 구조**
  - [1. 전체 구조](#1-전체-구조)
  - [2. 계층별 책임](#2-계층별-책임)
  - [3. 요청 처리 흐름](#3-요청-처리-흐름)
- **2부. 핵심 설계**
  - [4. Java ↔ Python 통신 설계](#4-java--python-통신-설계)
  - [5. 설계상 중요한 결정 두 가지](#5-설계상-중요한-결정-두-가지)
- **3부. 설계 평가**
  - [6. 설계 장단점 (Trade-offs)](#6-설계-장단점-trade-offs)
  - [7. 대안 아키텍처 비교 (현재 버전 vs 다른 방향)](#7-대안-아키텍처-비교-현재-버전-vs-다른-방향)
- **4부. 운영과 확장**
  - [8. 오류 처리](#8-오류-처리)
  - [9. 실행 요구사항 (환경)](#9-실행-요구사항-환경)
  - [10. 확장 포인트](#10-확장-포인트)

---

# 1부. 시스템 구조

## 1. 전체 구조

```
┌─────────────────────────┐
│  브라우저 웹 UI          │  static/index.html (루트 /)
│  (URL / 메일 헤더 입력)  │  fetch(POST) 로 API 호출
└───────────┬─────────────┘
            │            HTTP (JSON / text)
            ▼───────────────────────────────▶ ┌──────────────────────────────┐
                     (또는 curl/Postman)       │        Spring Boot           │
                    ◀───────────────────────  │                              │
                                                │  MetadataController  (REST)  │
                                                │          │                   │
                                                │          ▼                   │
                                                │  MetadataService  (검증/조립) │
                                                │          │                   │
                                                │          ▼                   │
                                                │  PythonScriptRunner (IPC)    │
                                                └──────────┼───────────────────┘
                                                           │ stdin  {"type","value"}
                                                           ▼
                                                ┌──────────────────────────────┐
                                                │   python/extractor.py         │
                                                │   (표준 라이브러리만 사용)      │
                                                └──────────┼───────────────────┘
                                                           │ stdout {"success",...}
                                                           ▼
                                                (다시 Service → Controller → 응답)
```

한 문장으로: **웹 UI/외부 클라이언트 → Spring Boot(수문장) → Python 스크립트(추출 엔진) → 원본 JSON 그대로 응답.**

## 2. 계층별 책임

| 계층 | 클래스 / 파일 | 책임 |
| --- | --- | --- |
| **웹 UI** | `resources/static/index.html` | 브라우저 입력 화면. `fetch` 로 API 호출 후 결과 JSON 표시 |
| **표현(Web)** | `controller/MetadataController` | REST 엔드포인트 정의, 요청 본문 수신 |
| | `controller/GlobalExceptionHandler` | 예외 → 일관된 JSON 오류 응답 변환 |
| **서비스** | `service/MetadataService` | 입력 검증, Python 요청 JSON 조립, 응답 성공 여부 검증 |
| **연동(IPC)** | `python/PythonScriptRunner` | Python 프로세스 실행, stdin/stdout 통신, 타임아웃/에러 처리 |
| | `python/PythonExecutionException` | Python 실행/추출 실패를 나타내는 예외 |
| **추출 엔진** | `resources/python/extractor.py` | 메일 헤더·URL 실제 파싱 |
| **설정/DTO** | `config/PythonProperties` | `app.python.*` 설정 바인딩 |
| | `config/JacksonConfig` | Jackson 2 용 `ObjectMapper` 빈 제공 |
| | `dto/ExtractRequest` | URL 요청 본문(`{"value": "..."}`) |

## 3. 요청 처리 흐름

### 3-1. 메일 헤더 추출 `POST /api/metadata/mail-header`

1. 클라이언트가 메일 헤더 원문을 `text/plain` 본문으로 전송
2. `MetadataController.fromMailHeader()` 가 문자열 그대로 수신
3. `MetadataService.extract("mail_header", raw)` :
   - 빈 값 검증 → 비어 있으면 `IllegalArgumentException`(→ 400)
   - `{"type":"mail_header","value":"..."}` 요청 JSON 조립
4. `PythonScriptRunner.run()` 이 Python 프로세스 실행 후 요청 JSON 을 stdin 으로 전달
5. `extractor.py` 가 `email` 표준 모듈로 헤더 파싱 → 결과 JSON 을 stdout 으로 출력
6. Service 가 응답의 `success` 여부만 검증하고 **원본 JSON 문자열을 그대로 반환** (→ §5-1)
7. Controller 가 `application/json` 으로 응답

### 3-2. URL 추출 `POST /api/metadata/url`

- 본문은 JSON `{"value":"https://..."}` (`ExtractRequest` DTO 로 매핑)
- 이후 흐름은 위와 동일하며, `type` 이 `"url"` 로 전달됨
- `extractor.py` 가 URL 을 직접 요청해 `<title>`, `description`, Open Graph, Twitter Card, canonical 등을 수집

### 3-3. 웹 UI (`static/index.html`)

- Spring Boot 는 `src/main/resources/static/` 의 파일을 루트(`/`)로 제공하므로,
  `http://localhost:8080` 접속 시 `index.html` 이 열린다.
- 별도 프레임워크 없이 순수 HTML/CSS/JS 로 작성된 단일 페이지이며, 위 두 API 를 `fetch(POST)` 로 호출한다.
  - **🌐 URL 탭** → `POST /api/metadata/url`
  - **✉️ 메일 헤더 탭** → `POST /api/metadata/mail-header`
- 응답 JSON 을 구문 강조해 표시하고, 성공/실패 배지와 HTTP 상태를 함께 보여준다.
- 즉 이 UI 는 API 의 또 다른 **클라이언트**일 뿐, 서버 로직에는 관여하지 않는다
  (curl·Postman 으로 호출하는 것과 동일).

> 참고: 루트 외 경로를 GET 으로 접근하면 매핑이 없어 Spring 기본 안내(Whitelabel Error / 405)가 뜬다.
> 이는 정상이며 오류가 아니다 — API 는 POST 로만 호출한다.

---

# 2부. 핵심 설계

## 4. Java ↔ Python 통신 설계

### 왜 프로세스 실행 방식인가
- 파싱 로직(메일/HTML)을 Python 표준 라이브러리로 간결하게 구현하기 위함
- 별도 서버·네트워크 포트 없이 **한 방향 요청/응답**만 필요하므로 stdin/stdout 파이프가 가장 단순
- (이 선택의 장단점과 대안은 §6-1, §7-1 에서 평가)

### 통신 규약
```
요청(stdin) : {"type": "mail_header" | "url", "value": "<원본 문자열>"}
응답(stdout): 성공 {"success": true,  "type": "...", "metadata": {...}}
             실패 {"success": false, "type": "...", "error": "메시지"}
```

### `PythonScriptRunner` 의 안전장치
- **스크립트 임시 파일화**: classpath 의 `extractor.py` 를 기동 시 임시 파일로 복사(`@PostConstruct`).
  WAR/JAR 로 패키징되면 스크립트가 아카이브 내부에 있어 직접 실행할 수 없기 때문.
- **stderr 별도 스레드 소비**: 파이프 버퍼가 가득 차 프로세스가 멈추는(deadlock) 상황 방지.
- **타임아웃**: `app.python.timeout-seconds`(기본 30초) 초과 시 `destroyForcibly()`.
- **비정상 종료 처리**: exit code ≠ 0 이고 stdout 이 비면 `PythonExecutionException`.
- **I/O 인코딩 고정**: `PYTHONIOENCODING=utf-8`, `PYTHONUTF8=1` 환경변수 설정 (→ §5-2).

## 5. 설계상 중요한 결정 두 가지

이 프로젝트를 Spring Boot 4 + Windows 환경에서 실행하며 드러난, 반드시 알아야 할 두 지점입니다.
(각각의 장단점 평가는 §6-2, §6-3 참고)

### 5-1. 응답을 "파싱된 트리"가 아닌 "원본 JSON 문자열"로 반환

- **문제**: Spring Boot 4 부터 기본 JSON 라이브러리가 **Jackson 3(`tools.jackson`)** 으로 변경됨.
  서비스 계층은 Jackson 2(`com.fasterxml.jackson`) API 로 작성되어 있어,
  컨트롤러가 Jackson 2 의 `JsonNode` 를 반환하면 Jackson 3 HTTP 컨버터가 이를 **트리로 인식하지 못하고
  게터 기반 POJO 로 직렬화**(`{"array":false,"object":true,...}`)하는 문제가 발생.
- **해결**: 서비스가 Python 응답의 `success` 만 검증한 뒤 **원본 JSON 문자열을 그대로 반환**하고,
  컨트롤러는 `String`(`produces=application/json`)으로 응답. 직렬화 라이브러리에 무관하게 정확한 JSON 전달.
- Jackson 2 `ObjectMapper` 는 내부 검증/요청 조립용으로만 사용하며 `JacksonConfig` 에서 빈으로 등록.

### 5-2. Python 프로세스 I/O 를 UTF-8 로 강제

- **문제**: Windows 의 Python 은 stdin/stdout 기본 인코딩이 콘솔 코드페이지(예: cp949).
  Java 는 UTF-8 로 읽고 쓰므로, 그대로 두면 한글 등 멀티바이트 문자가 깨짐(U+FFFD 대체문자).
- **해결**: `PythonScriptRunner` 가 프로세스 환경변수에 `PYTHONIOENCODING=utf-8`, `PYTHONUTF8=1` 설정.
  `pom.xml` 에도 소스/빌드 인코딩을 UTF-8 로 명시.

---

# 3부. 설계 평가

## 6. 설계 장단점 (Trade-offs)

각 핵심 설계 결정의 장점과 단점, 그리고 대안입니다.
(대안들의 정량적 비교는 §7 의 비교표 참고)

### 6-1. Java ↔ Python 을 "프로세스 실행 + stdin/stdout" 으로 연동

**장점**
- 파싱 로직을 Python 표준 라이브러리(`email`, `html.parser`, `urllib`)로 간결하게 구현 — Java 로 재구현할 필요 없음.
- 별도 서버·포트·직렬화 프로토콜 없이 JSON 파이프 한 줄로 통신. 의존성이 거의 없음(`pip install` 불필요).
- Java 와 Python 관심사가 분리돼 스크립트를 독립적으로 수정/테스트 가능.

**단점**
- 요청마다 프로세스를 새로 띄워 **기동 비용(수십 ms~)** 이 든다 → 고빈도 트래픽에 불리.
- 런타임에 **Python 설치·PATH 의존**. 환경에 따라 `command` 설정 필요, 배포 환경 제약.
- 프로세스 경계(인코딩, 타임아웃, 좀비 프로세스, stderr 버퍼)를 직접 관리해야 함.

**대안** — 순수 Java 라이브러리(Jsoup, Jakarta Mail)로 재구현 / 상주 Python 워커(소켓·gRPC) / 별도 마이크로서비스. (→ §7-1)

### 6-2. 응답을 "파싱된 트리" 대신 "원본 JSON 문자열" 로 반환

**장점**
- 직렬화 라이브러리(Jackson 2/3)에 무관하게 Python 이 만든 JSON 을 **정확히 그대로** 전달.
- 트리를 다시 직렬화하지 않으므로 **불필요한 재직렬화 비용이 없음**.
- Spring Boot 4 의 Jackson 3 전환 이슈(§5-1)를 근본적으로 회피.

**단점**
- 컨트롤러 반환 타입이 `String` 이라 **타입 안정성이 약함**(스키마가 코드에 드러나지 않음).
- Java 계층에서 응답 내용을 가공/필터링하기 어렵다(그대로 통과시키는 구조).
- Python 이 잘못된 JSON 을 내보내면 `success` 검증은 통과해도 클라이언트로 그대로 나갈 여지가 있음
  (현재는 `readTree` 파싱으로 최소 검증).

**대안** — DTO 로 매핑해 반환(스키마 명시·가공 용이하나 매핑 비용/유지보수 증가) / Jackson 3 로 전면 이관. (→ §7-2)

### 6-3. Jackson 2 를 유지 (Jackson 3 로 이관하지 않음)

**장점**
- 기존 서비스 코드를 거의 손대지 않아 **변경 범위·리스크 최소화**.
- `ObjectMapper` 빈만 추가하면 되어 도입이 단순.

**단점**
- Spring Boot 4 기본(Jackson 3)과 **다른 스택을 병행**해 인지 부하가 있음.
- 프레임워크의 자동 구성·기본 동작과 어긋날 수 있어 장기적으로는 관성 부채.

**대안** — 서비스 계층을 Jackson 3(`tools.jackson`)로 이관해 프레임워크 기본에 정렬.

### 6-4. 스크립트를 기동 시 임시 파일로 복사 (`@PostConstruct`)

**장점**
- WAR/JAR 아카이브 내부의 스크립트도 실행 가능(아카이브 안 파일은 직접 실행 불가).
- 스크립트를 classpath 리소스로 함께 배포 — 별도 배치 불필요.

**단점**
- 임시 파일 생성 권한/디스크가 필요하고, 정리는 `deleteOnExit` 에 의존.
- 스크립트가 파일시스템에 노출되는 순간이 생김(민감 로직이면 고려 필요).

**대안** — 외부 경로의 스크립트를 직접 지정 / 스크립트를 별도 패키지로 배포.

### 6-5. 정적 HTML 단일 페이지 UI

**장점**
- 빌드 도구·프런트 프레임워크 없이 파일 하나로 즉시 사용 가능, 의존성 0.
- API 테스트/시연에 충분하고 유지보수가 쉬움.

**단점**
- 상태 관리·컴포넌트화·라우팅 등 **복잡한 UI 로 확장하기엔 부적합**.
- 입력 검증·i18n·접근성 등은 직접 구현해야 함.

**대안** — SPA(React/Vue 등) 분리 / 서버사이드 템플릿(Thymeleaf).

## 7. 대안 아키텍처 비교 (현재 버전 vs 다른 방향)

현재 버전은 **"Java 오케스트레이터 + 요청마다 Python 서브프로세스"** 구조입니다.
같은 요구사항을 다른 방향으로 구현했다면 어떻게 달라지는지 비교합니다.

### 7-1. 메타데이터 추출 방식

| 항목 | **현재: Java + Python 서브프로세스** | A. 순수 Java 라이브러리<br/>(Jsoup + Jakarta Mail) | B. 상주 Python 워커<br/>(소켓/HTTP 상시 실행) | C. 별도 Python 마이크로서비스<br/>(FastAPI 등) |
| --- | --- | --- | --- | --- |
| 요청당 지연 | 프로세스 기동 비용 존재(수십 ms~) | **가장 빠름**(in-process) | 빠름(프로세스 재사용) | 네트워크 홉 추가 |
| 처리량/확장 | 프로세스 수에 제약 | **높음** | 높음 | 서비스 수평 확장 용이 |
| 개발 난이도 | 낮음(스크립트 + 얇은 래퍼) | 중(라이브러리 학습·매핑) | 중상(워커·프로토콜·생명주기) | 상(별도 서비스·배포 파이프라인) |
| 런타임 의존성 | **Python 설치 필요** | **JVM 만** | Python 상주 프로세스 | 별도 서비스 인프라 |
| 배포 복잡도 | 낮음(스크립트 동봉) | **가장 낮음**(단일 아티팩트) | 중(워커 함께 관리) | 상(2개 서비스 운영) |
| 장애 격리 | 프로세스 단위 격리 | 격리 없음(같은 JVM) | 워커 크래시 시 영향 | **서비스 단위 격리** |
| 언어 강점 활용 | Python 파싱 생태계 | Java 생태계 | Python | Python |
| 적합 상황 | **PoC·중저빈도·빠른 구현** | 고성능·단일 배포 선호 | 고빈도 + Python 유지 | 대규모·독립 확장 필요 |

> 요약: 현재 방식은 **"구현 속도와 단순함"** 을 얻는 대신 **"요청당 프로세스 비용과 Python 의존성"** 을 지불한다.
> 트래픽이 커지면 B(상주 워커) 또는 C(마이크로서비스)로, 배포 단순화·고성능이 우선이면 A(순수 Java)로 이동하는 것이 자연스러운 진화 경로다.

### 7-2. 응답 반환 방식

| 항목 | **현재: 원본 JSON 문자열 반환** | A. DTO 로 매핑해 반환 | B. Jackson 3 트리로 반환 |
| --- | --- | --- | --- |
| 직렬화 안정성 | 라이브러리 무관, 정확히 통과 | 프레임워크 기본과 정렬 | 프레임워크 기본과 정렬 |
| 타입 안정성/스키마 | 약함(`String`) | **강함(명시적 DTO)** | 중(트리) |
| 재직렬화 비용 | **없음**(그대로 전달) | 있음(매핑+직렬화) | 있음(직렬화) |
| 응답 가공 용이성 | 낮음(통과 구조) | **높음** | 중 |
| 구현/유지보수 | 가장 단순 | 필드 늘면 매핑 부담 | Jackson 3 이관 필요 |
| 적합 상황 | **패스스루·빠른 구현** | 계약이 중요한 공개 API | SB4 기본에 정렬하고 싶을 때 |

> 요약: 현재 방식은 **"통과(pass-through)의 단순함·정확성"** 을 취하고 **"타입 안정성·서버측 가공력"** 을 양보한다.
> 응답 스키마를 계약으로 고정하거나 서버에서 가공이 필요해지면 DTO 매핑(A)이 유리하다.

---

# 4부. 운영과 확장

## 8. 오류 처리

`GlobalExceptionHandler` 가 예외를 일관된 JSON 으로 변환합니다.

| 예외 | HTTP 상태 | 의미 |
| --- | --- | --- |
| `IllegalArgumentException` | `400 Bad Request` | 잘못된 입력(빈 값 등) |
| `PythonExecutionException` | `502 Bad Gateway` | Python 실행 실패, 타임아웃, 추출 실패 |

응답 형식:
```json
{ "success": false, "status": 400, "error": "입력 값(value)이 비어 있습니다." }
```

## 9. 실행 요구사항 (환경)

- **JDK 21** — 코드가 `record` 등 Java 16+ 문법 사용.
  터미널에서 Maven 실행 시 `JAVA_HOME` 이 JDK 21 을 가리켜야 함
  (JDK 8 이면 `record` 컴파일 실패: *"class, interface, or enum expected"*).
- **Python 3** — PATH 에 등록되어 있어야 함(`app.python.command` 로 명령어 변경 가능).
- 실행: `mvnw.cmd spring-boot:run` (Windows) / `./mvnw spring-boot:run` (macOS·Linux), 기본 포트 `8080`.
- 실행/트러블슈팅 상세는 [README.md](../README.md) 참고.

## 10. 확장 포인트

- **새 추출 타입 추가**: `extractor.py` 에 `type` 분기 추가 → Service 에 진입 메서드 → Controller 에 엔드포인트.
- **Python 실행 환경 변경**: `application.properties` 의 `app.python.*`(명령어/타임아웃/스크립트 경로).
- **성능**: 요청마다 Python 프로세스를 새로 띄우므로, 고빈도 트래픽에서는
  프로세스 풀 또는 장기 실행 워커(예: 상주 프로세스 + 요청 스트리밍) 도입을 고려.
  (진화 경로는 §7-1 비교표 참고)
