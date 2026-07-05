package com.api.getmetadata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * application.properties 의 app.python.* 설정을 담는 클래스.
 */
@ConfigurationProperties(prefix = "app.python")
public class PythonProperties {

    /** Python 실행 명령 (예: python, python3, /usr/bin/python3). */
    private String command = "python";

    /** Python 스크립트 실행 타임아웃(초). */
    private int timeoutSeconds = 30;

    /** classpath 상의 추출 스크립트 경로. */
    private String script = "python/extractor.py";

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String getScript() {
        return script;
    }

    public void setScript(String script) {
        this.script = script;
    }
}
