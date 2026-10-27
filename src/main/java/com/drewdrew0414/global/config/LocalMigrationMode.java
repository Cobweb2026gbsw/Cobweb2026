package com.drewdrew0414.global.config;

import java.util.HashMap;
import java.util.Map;
import org.springframework.core.env.Environment;

/** run.sh의 명시적인 로컬 단발 마이그레이션만 허용합니다. 환경변수만으로 활성화하지 않습니다. */
public final class LocalMigrationMode {
    private static final Map<String, String> REQUIRED = Map.of(
        "local.migrate-only", "true",
        "spring.profiles.active", "local",
        "spring.main.web-application-type", "none",
        "spring.flyway.enabled", "true",
        "judge.enabled", "false",
        "demo.seed", "false",
        "curriculum.level0.enabled", "false");
    private static final String LOCAL_DATABASE = "jdbc:mysql://127.0.0.1:3307/cobweb2026?serverTimezone=UTC";

    private LocalMigrationMode() {}

    public static boolean requested(String[] args) {
        for (String arg : args) {
            if (arg.equals("--local.migrate-only") || arg.startsWith("--local.migrate-only=")) return true;
        }
        return false;
    }

    public static void validateArguments(String[] args) {
        var actual = new HashMap<String, String>();
        for (String arg : args) {
            int separator = arg.indexOf('=');
            if (!arg.startsWith("--") || separator < 3) throw invalid();
            String key = arg.substring(2, separator);
            // 별칭·추가 프로필·중복/충돌 옵션과 CLI 비밀번호를 허용하지 않습니다.
            if (!REQUIRED.containsKey(key) || actual.putIfAbsent(key, arg.substring(separator + 1)) != null)
                throw invalid();
        }
        if (!REQUIRED.equals(actual)) throw invalid();
    }

    public static void validateEnvironment(Environment environment) {
        // 설정 파일의 profiles.include/group 등으로 prod가 섞이는 경우도 bean 생성 전에 거절합니다.
        String[] profiles = environment.getActiveProfiles();
        if (profiles.length != 1 || !profiles[0].equals("local")) throw invalid();
        for (var entry : REQUIRED.entrySet()) {
            if (!entry.getValue().equals(environment.getProperty(entry.getKey()))) throw invalid();
        }
        if (!LOCAL_DATABASE.equals(environment.getProperty("spring.datasource.url"))
                || !LOCAL_DATABASE.equals(environment.getProperty("spring.flyway.url"))
                || !"cobweb".equals(environment.getProperty("spring.datasource.username"))
                || !"root".equals(environment.getProperty("spring.flyway.user"))) throw invalid();
    }

    private static IllegalArgumentException invalid() {
        // 사용자 옵션이나 자격 증명을 예외 메시지에 복사하지 않습니다.
        return new IllegalArgumentException("로컬 단발 마이그레이션 옵션/연결이 올바르지 않습니다.");
    }
}
