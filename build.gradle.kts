plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.drewdrew0414"
version = "0.0.1-SNAPSHOT"
description = "cobweb2026"

// Spring Boot 4.1.1 BOM의 공식 버전 속성으로 관련 모듈 전체를 같은 보안 패치로 맞춥니다.
extra["tomcat.version"] = "11.0.26"
extra["jackson-bom.version"] = "3.1.7"
extra["jackson-2-bom.version"] = "2.21.7"

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
repositories { mavenCentral() }

// 실제 사용하는 MVC/JPA/JDBC/메일/보안만 선언합니다.
// Data REST의 자동 엔티티 노출, 중복 웹 스택, 미사용 인증 서버 등은 포함하지 않습니다.
dependencies {
    // JJWT는 Jackson 2, Spring MVC는 Jackson 3을 사용하므로 두 BOM을 함께 갱신합니다.
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-mail")
    implementation("org.springframework.boot:spring-boot-starter-restclient")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
    testImplementation("org.springframework.boot:spring-boot-starter-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.withType<Test> {
    useJUnitPlatform()
    // 작은 서버의 공용 풀에서도 채점 stdin/stdout/stderr가 교착되지 않는지 항상 검증합니다.
    systemProperty("java.util.concurrent.ForkJoinPool.common.parallelism", "2")
}

// ZIP 기반 React UI도 JAR의 일부입니다. run.sh/bootJar/CI가 동일한 잠금 파일로 빌드합니다.
val frontendInstall by tasks.registering(Exec::class) {
    workingDir("frontend")
    commandLine("npm", "ci", "--ignore-scripts", "--no-audit", "--no-fund")
    inputs.files("frontend/package.json", "frontend/package-lock.json")
    outputs.dir("frontend/node_modules")
}
val frontendBuild by tasks.registering(Exec::class) {
    dependsOn(frontendInstall)
    workingDir("frontend")
    commandLine("npm", "run", "build")
    inputs.dir("frontend/src")
    inputs.files("frontend/index.html", "frontend/vite.config.js", "frontend/package-lock.json")
    outputs.dir(layout.buildDirectory.dir("generated-frontend"))
}
tasks.processResources {
    dependsOn(frontendBuild)
    from(layout.buildDirectory.dir("generated-frontend"))
}
