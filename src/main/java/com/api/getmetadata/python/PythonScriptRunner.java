package com.api.getmetadata.python;

import com.api.getmetadata.config.PythonProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * classpath 의 Python 스크립트를 임시 파일로 풀어놓고,
 * ProcessBuilder 로 실행하여 stdin(JSON) -> stdout(JSON) 방식으로 통신한다.
 *
 * <p>WAR/JAR 로 패키징되면 스크립트가 아카이브 내부에 있어 직접 실행할 수 없으므로,
 * 애플리케이션 기동 시 임시 파일로 복사해 두고 그 경로로 실행한다.</p>
 */
@Component
public class PythonScriptRunner {

    private static final Logger log = LoggerFactory.getLogger(PythonScriptRunner.class);

    private final PythonProperties properties;
    private Path scriptPath;

    public PythonScriptRunner(PythonProperties properties) {
        this.properties = properties;
    }

    /** 기동 시 classpath 리소스를 임시 파일로 복사. */
    @PostConstruct
    void init() throws IOException {
        ClassPathResource resource = new ClassPathResource(properties.getScript());
        if (!resource.exists()) {
            throw new IllegalStateException("Python 스크립트를 찾을 수 없습니다: " + properties.getScript());
        }
        Path temp = Files.createTempFile("extractor-", ".py");
        try (InputStream in = resource.getInputStream()) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        }
        temp.toFile().deleteOnExit();
        this.scriptPath = temp;
        log.info("Python 추출 스크립트 준비 완료: {} (command={})", temp, properties.getCommand());
    }

    /**
     * Python 스크립트를 실행하고 stdout(JSON 문자열)을 반환한다.
     *
     * @param jsonInput 스크립트 stdin 으로 전달할 JSON 문자열
     * @return 스크립트가 stdout 으로 출력한 JSON 문자열
     */
    public String run(String jsonInput) {
        ProcessBuilder pb = new ProcessBuilder(properties.getCommand(), scriptPath.toString());
        pb.redirectErrorStream(false);
        // Windows 의 Python 은 stdin/stdout 기본 인코딩이 콘솔 코드페이지(예: cp949)라
        // 한글이 깨진다. Java 는 UTF-8 로 주고받으므로 Python I/O 도 UTF-8 로 강제한다.
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("PYTHONUTF8", "1");

        Process process = null;
        try {
            process = pb.start();

            // stderr 는 별도 스레드에서 소비 (파이프 버퍼가 차서 프로세스가 멈추는 것을 방지)
            AtomicReference<String> stderrHolder = new AtomicReference<>("");
            InputStream errStream = process.getErrorStream();
            Thread errThread = new Thread(() -> stderrHolder.set(readAll(errStream)), "python-stderr");
            errThread.setDaemon(true);
            errThread.start();

            // stdin 으로 요청 JSON 전달
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write(jsonInput.getBytes(StandardCharsets.UTF_8));
            }

            // stdout 읽기
            String stdout = readAll(process.getInputStream());

            boolean finished = process.waitFor(properties.getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new PythonExecutionException(
                        "Python 실행 타임아웃(" + properties.getTimeoutSeconds() + "초)");
            }
            errThread.join(TimeUnit.SECONDS.toMillis(2));

            String stderr = stderrHolder.get();
            if (!stderr.isBlank()) {
                log.debug("Python stderr: {}", stderr);
            }

            int exit = process.exitValue();
            if (exit != 0 && stdout.isBlank()) {
                throw new PythonExecutionException(
                        "Python 프로세스가 비정상 종료했습니다. exitCode=" + exit
                                + (stderr.isBlank() ? "" : ", stderr=" + stderr));
            }
            return stdout;
        } catch (IOException e) {
            throw new PythonExecutionException(
                    "Python 실행 실패 (command='" + properties.getCommand()
                            + "' 가 설치/PATH 등록되어 있는지 확인하세요): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PythonExecutionException("Python 실행이 중단되었습니다.", e);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private String readAll(InputStream stream) {
        try {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("스트림 읽기 실패: {}", e.getMessage());
            return "";
        }
    }
}
