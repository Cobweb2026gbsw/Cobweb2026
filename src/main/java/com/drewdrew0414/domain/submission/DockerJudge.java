package com.drewdrew0414.domain.submission;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;

/**
 * 컴파일도 사용자 코드 실행으로 취급하여 네트워크/권한/메모리/시간을 제한합니다.
 * 컴파일은 제출당 한 번, 실행은 케이스별 새 컨테이너입니다. 정답은 컨테이너에 전달하지 않습니다.
 * 산출물은 256MiB tmpfs 볼륨에만 보관하고 런타임에서는 읽기 전용으로 마운트합니다.
 */
@Component
public class DockerJudge {
    private final String image;
    private final Path root;
    public DockerJudge(@Value("${judge.image:cobweb-languages:local}") String image,
                       @Value("${judge.workspace:/tmp/cobweb-judge}") String root) {
        this.image=image; this.root=Path.of(root);
    }

    /** 예전 Python 호출의 호환 진입점입니다. 워커는 prepare를 한 번 호출하여 컴파일을 재사용합니다. */
    public SubmissionService.JudgeResult run(String code,String input,String expected,String mode,int millis,int memory,Long caseId) {
        return run(Language.PYTHON,code,input,expected,mode,millis,memory,caseId);
    }
    public SubmissionService.JudgeResult run(Language language,String code,String input,String expected,String mode,int millis,int memory,Long caseId) {
        try(var prepared=prepare(language,code,mode)) {
            return prepared.run(input,expected,millis,memory,caseId);
        }
    }
    public Prepared prepare(Language language,String code,String mode) {
        return preparePinned(language,code,mode,image);
    }
    /** 범용 함수의 공개 서명만 전달합니다. 정답·비공개 테스트는 준비 단계에 넘기지 않습니다. */
    public Prepared prepare(Language language,String code,String mode,String signature) {
        return preparePinned(language,code,mode,image,signature);
    }
    /** 태그가 채점 중 다른 이미지로 바뀌어도 동일한 immutable image ID만 실행합니다. */
    public Prepared preparePinned(Language language,String code,String mode,String imageId) {
        return preparePinned(language,code,mode,imageId,null);
    }
    public Prepared preparePinned(Language language,String code,String mode,String imageId,String signature) {
        language.requireMode(mode);
        if(signature!=null && (!"FUNCTION".equals(mode) || signature.getBytes(StandardCharsets.UTF_8).length>8192))
            throw new IllegalArgumentException("Invalid generic function signature");
        if(imageId==null || !(image.equals(imageId) || imageId.matches("sha256:[0-9a-f]{64}")))
            throw new IllegalArgumentException("Invalid judge image identity");
        var prepared=new Prepared(language,mode,imageId,signature!=null,code);
        try {
            Files.createDirectories(root);
            prepared.dir=Files.createTempDirectory(root,"submission-");
            Files.setPosixFilePermissions(prepared.dir,java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            prepared.source=prepared.dir.resolve(language.fileName());
            Files.writeString(prepared.source,code,StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(prepared.source,java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            if(signature!=null) {
                prepared.signature=prepared.dir.resolve("signature.json");
                Files.writeString(prepared.signature,signature,StandardCharsets.UTF_8);
                Files.setPosixFilePermissions(prepared.signature,java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            }
            if(language.compiled()) {
                prepared.volume="cobweb-artifacts-"+UUID.randomUUID();
                requireSuccess(execute(List.of("docker","volume","create","--driver","local",
                    "--opt","type=tmpfs","--opt","device=tmpfs","--opt","o=size=256m,uid=65534,gid=65534,mode=0700",
                    prepared.volume),"",10000));
                prepared.compiler="cobweb-compile-"+UUID.randomUUID();
                var command=container(prepared.compiler,1024,60,256);
                command.add("-d");
                command.addAll(prepared.mounts(false));
                // tmpfs를 컴파일 뒤에도 유지합니다. 최대 50케이스의 실행/정리 시간보다 긴
                // 30분 뒤 자동 종료하여 워커가 죽었을 때도 무기한 메모리를 점유하지 않습니다.
                // 사용자 프로그램을 PID 1로 두지 않으므로 컴파일 종료 코드와 볼륨 수명을 분리합니다.
                command.addAll(List.of("--entrypoint","/bin/sleep",prepared.imageId,"1800"));
                requireSuccess(execute(command,"",10000));
                var result=execute(List.of("docker","exec",prepared.compiler,"python3","-I","/opt/judge/runner.py",
                    "compile",language.name(),mode,"1024"),"",65000);
                prepared.diagnostic=result.error.text();
                if(result.exit==125 || result.exit==126 || result.exit==127)
                    prepared.failure="SYSTEM_ERROR";
                else if(result.timedOut || result.exit!=0 || result.output.exceeded || result.error.exceeded)
                    prepared.failure="COMPILATION_ERROR";
                if("COMPILATION_ERROR".equals(prepared.failure))
                    prepared.publicDiagnostic=JudgeDiagnostic.compilation(language,code,result.error.text()+"\n"+result.output.text());
            }
        } catch(Exception e) {
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            prepared.failure="SYSTEM_ERROR";
            prepared.diagnostic=e.toString();
        }
        return prepared;
    }

    public final class Prepared implements AutoCloseable {
        private final Language language;
        private final String mode;
        private final String imageId;
        private final boolean generic;
        private final String submittedSource;
        private Path dir,source,signature;
        private String volume,compiler,failure,diagnostic="";
        private JudgeDiagnostic publicDiagnostic;
        private String testOutput;
        private boolean captureTestOutput;
        private boolean closed;
        private Prepared(Language language,String mode,String imageId,boolean generic,String submittedSource) {
            this.language=language;this.mode=mode;this.imageId=imageId;this.generic=generic;this.submittedSource=submittedSource;
        }
        public String failure() { return failure; }
        /** 운영 API로 노출하지 않습니다. 테스트/운영 진단용 컴파일 메시지이며 최대 64KiB입니다. */
        public String diagnostic() { return diagnostic; }
        /** 원문 대신 허용 목록으로 만든 안전한 컴파일 오류 정보입니다. */
        public JudgeDiagnostic publicDiagnostic() {return publicDiagnostic;}
        public record TestOutput(SubmissionService.JudgeResult result,String actual) {}
        /** 사용자 제공 입력 전용입니다. 비공개 채점 경로는 stdout을 수집/반환하지 않습니다. */
        public TestOutput runForTesting(String input,String expected,int millis,int memory) {
            testOutput=null;captureTestOutput=true;
            try {
                var result=run(input,expected,millis,memory,null);
                // 손상된 UTF-8의 대체 문자도 응답에서는 UTF-8 바이트 상한을 지킵니다.
                // 원시 바이트 64KiB가 디코딩 뒤 192KiB로 팽창하는 경우를 제한합니다.
                if(testOutput!=null && testOutput.getBytes(StandardCharsets.UTF_8).length>65536) {
                    int offset=0,bytes=0;
                    while(offset<testOutput.length()) {
                        int point=testOutput.codePointAt(offset),size=point<=0x7f?1:point<=0x7ff?2:point<=0xffff?3:4;
                        if(bytes+size>65536)break;
                        bytes+=size;offset+=Character.charCount(point);
                    }
                    testOutput=testOutput.substring(0,offset);
                    result=new SubmissionService.JudgeResult(null,"RUNTIME_ERROR",result.elapsedMs(),
                        new JudgeDiagnostic("RUN",JudgeDiagnostic.Kind.OUTPUT_LIMIT,List.of()));
                }
                return new TestOutput(result,testOutput);
            }
            finally {captureTestOutput=false;testOutput=null;}
        }
        private List<String> mounts(boolean readOnly) {
            var result=new ArrayList<>(List.of("--mount","type=bind,src="+source.toAbsolutePath()+
                ",dst=/source/"+language.fileName()+",readonly"));
            if(signature!=null) result.addAll(List.of("--mount","type=bind,src="+signature.toAbsolutePath()+
                ",dst=/source/signature.json,readonly"));
            if(volume!=null) result.addAll(List.of("--mount","type=volume,src="+volume+",dst=/work,volume-nocopy"+(readOnly?",readonly":"")));
            return result;
        }
        public SubmissionService.JudgeResult run(String input,String expected,int millis,int memory,Long caseId) {
            if(closed) throw new IllegalStateException("Closed submission");
            if(failure!=null) return new SubmissionService.JudgeResult(caseId,failure,0,publicDiagnostic);
            String name="cobweb-run-"+UUID.randomUUID();
            long start=System.nanoTime();
            try {
                // 숫자 토큰으로 확장된 최대64MiB 함수 입력도 읽기 전용 root 대신 제한 tmpfs에만 둡니다.
                var command=container(name,memory,Math.max(1,(millis+999)/1000),generic?96:32);
                command.add("-i");
                command.addAll(mounts(true));
                // timeout은 컨테이너가 시작한 뒤에 실행되어 Docker 기동 시간과 관계없이
                // 모든 언어(Python/SQL 포함)의 실제 실행 벽시계를 제한합니다.
                command.addAll(List.of("-e","DOTNET_GCHeapHardLimit="+Integer.toHexString(memory*1024*1024/2),
                    "-e","DOTNET_PROCESSOR_COUNT=1","--entrypoint","/usr/bin/timeout",imageId,
                    "--signal=KILL",Double.toString(millis/1000.0)+"s",
                    "python3","-I","/opt/judge/runner.py","run",language.name(),mode,String.valueOf(memory)));
                // 대용량 함수 결과만 16MiB까지 받습니다. 일반 입출력/진단·컴파일 로그는 64KiB를 유지합니다.
                var result=execute(command,input,millis+5000L,generic?16*1024*1024:65536);
                diagnostic=result.error.text();
                String status;
                if(result.timedOut) status="TIME_LIMIT_EXCEEDED";
                else if(result.exit==125 || result.exit==126 || result.exit==127) status="SYSTEM_ERROR";
                else if(result.output.exceeded || result.error.exceeded) status="RUNTIME_ERROR";
                else if(result.exit==137) status=oomKilled(name)?"MEMORY_LIMIT_EXCEEDED":"TIME_LIMIT_EXCEEDED";
                else if(result.exit==124 || result.exit==152) status="TIME_LIMIT_EXCEEDED";
                else if(result.exit!=0) status="RUNTIME_ERROR";
                else status=(generic?matchesGenericResult(result.output.text,expected)
                    :result.output.text.strip().equals(expected.strip()))?"ACCEPTED":"WRONG_ANSWER";
                if(captureTestOutput && !"SYSTEM_ERROR".equals(status))testOutput=result.output.text();
                JudgeDiagnostic safe="RUNTIME_ERROR".equals(status)?JudgeDiagnostic.runtime(language,submittedSource,
                    result.error.text(),result.output.exceeded || result.error.exceeded):null;
                return new SubmissionService.JudgeResult(caseId,status,
                    (int)Math.min(Integer.MAX_VALUE,(System.nanoTime()-start)/1000000),safe);
            } catch(Exception e) {
                if(e instanceof InterruptedException) Thread.currentThread().interrupt();
                diagnostic=e.toString();
                return new SubmissionService.JudgeResult(caseId,"SYSTEM_ERROR",0);
            } finally { cleanup("docker","rm","-f",name); }
        }
        @Override public void close() {
            if(closed) return;
            closed=true;
            // 모든 테스트 컨테이너가 종료된 뒤 컴파일 컨테이너와 해당 제출의 볼륨만 제거합니다.
            if(compiler!=null) cleanup("docker","rm","-f",compiler);
            if(volume!=null) cleanup("docker","volume","rm",volume);
            if(dir!=null) try {
                if(source!=null) Files.deleteIfExists(source);
                if(signature!=null) Files.deleteIfExists(signature);
                Files.deleteIfExists(dir);
            } catch(IOException ignored) { /* 컨테이너는 호스트 디렉터리에 쓸 수 없습니다. */ }
        }
    }
    /** 문자열·배열 순서는 정확하게, 유한한 실수만 절대/상대 오차 1e-9 이내로 비교합니다. */
    static boolean matchesGenericResult(String actual,String expected) {
        try {
            var mapper=new tools.jackson.databind.ObjectMapper();
            // 큰 배열을 EOF 검사 때문에 두 번 트리로 만들지 않습니다. 두 값 모두 같은 파서로 끝까지 검사합니다.
            var left=singleGenericJson(mapper,actual);
            var right=singleGenericJson(mapper,expected);
            return sameGenericValue(left,right);
        } catch(Exception invalidOutput) { return false; }
    }
    private static tools.jackson.databind.JsonNode singleGenericJson(tools.jackson.databind.ObjectMapper mapper,String text) {
        if(text==null || text.length()>16*1024*1024) throw new IllegalArgumentException("Invalid generic JSON size");
        try(var parser=mapper.reader().with(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).createParser(text)) {
            var value=mapper.readTree(parser);
            if(value==null || parser.nextToken()!=null) throw new IllegalArgumentException("Expected one generic JSON value");
            return value;
        }
    }
    private static boolean sameGenericValue(tools.jackson.databind.JsonNode actual,tools.jackson.databind.JsonNode expected) {
        if(actual==null || expected==null || actual.isNull() || expected.isNull()) return false;
        if(expected.isNumber()) {
            if(!actual.isNumber()) return false;
            // 32비트 정수의 큰 값에서 상대 오차로 1~2 차이를 정답 처리하지 않습니다.
            if(actual.isIntegralNumber() && expected.isIntegralNumber())
                return actual.bigIntegerValue().equals(expected.bigIntegerValue());
            double a=actual.doubleValue(),e=expected.doubleValue();
            if(!Double.isFinite(a) || !Double.isFinite(e)) return false;
            return Math.abs(a-e)<=1e-9*Math.max(1,Math.max(Math.abs(a),Math.abs(e)));
        }
        if(expected.isString()) return actual.isString() && actual.stringValue().equals(expected.stringValue());
        if(expected.isBoolean()) return actual.isBoolean() && actual.booleanValue()==expected.booleanValue();
        if(expected.isArray()) {
            if(!actual.isArray() || actual.size()!=expected.size()) return false;
            for(int index=0;index<expected.size();index++)
                if(!sameGenericValue(actual.get(index),expected.get(index))) return false;
            return true;
        }
        return false;
    }
    /** inspect 실패 시에는 캐시를 끄고 원래 격리 채점 경로를 유지합니다. 네트워크 조회는 하지 않습니다. */
    public String imageIdentity() {
        try {
            var result=execute(List.of("docker","image","inspect","--format","{{.Id}}",image),"",5000);
            requireSuccess(result);
            String value=result.output.text().strip();
            return value.matches("sha256:[0-9a-f]{64}")?value:null;
        } catch(Exception ignored) {
            if(ignored instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }
    /** 이미지 밖에 있는 실제 판정/비교/lease 코드도 바뀌면 기존 판정을 재사용하지 않습니다. */
    public static String engineFingerprint() {
        try {
            var digest=MessageDigest.getInstance("SHA-256");
            digest.update(System.getProperty("java.runtime.version","").getBytes(StandardCharsets.UTF_8));
            digest.update(System.getProperty("java.vendor","").getBytes(StandardCharsets.UTF_8));
            var classes=new TreeMap<String,Class<?>>();
            var pending=new ArrayDeque<Class<?>>(List.of(DockerJudge.class,JudgeWorker.class,
                SubmissionService.class,JudgeLease.class,Language.class,JudgeDiagnostic.class,
                com.drewdrew0414.domain.problem.ProblemService.class,
                com.drewdrew0414.domain.problem.FunctionSignature.class));
            while(!pending.isEmpty()) {
                var type=pending.removeFirst();
                if(classes.putIfAbsent(type.getName(),type)==null) pending.addAll(List.of(type.getDeclaredClasses()));
            }
            for(Class<?> type:classes.values()) {
                digest.update(type.getName().getBytes(StandardCharsets.UTF_8));
                try(var stream=type.getResourceAsStream("/"+type.getName().replace('.','/')+".class")) {
                    if(stream==null) return null;
                    digest.update(stream.readAllBytes());
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch(Exception ignored) { return null; }
    }
    private ArrayList<String> container(String name,int memory,int cpuSeconds,int tempMb) {
        return new ArrayList<>(List.of("docker","run","--name",name,
            "--network=none","--read-only","--cap-drop=ALL","--security-opt=no-new-privileges",
            "--pids-limit=128","--memory="+memory+"m","--memory-swap="+memory+"m","--cpus=1",
            "--ulimit","cpu="+cpuSeconds+":"+(cpuSeconds+1),
            "--ulimit","fsize=67108864:67108864","--user=65534:65534","--log-driver=none",
            "--tmpfs","/tmp:rw,noexec,nosuid,size="+tempMb+"m,mode=1777"));
    }
    private record CapturedOutput(String text,boolean exceeded) {}
    private record ProcessResult(int exit,boolean timedOut,CapturedOutput output,CapturedOutput error) {}
    private static ProcessResult execute(List<String> command,String input,long timeout) throws Exception {
        return execute(command,input,timeout,65536);
    }
    private static ProcessResult execute(List<String> command,String input,long timeout,int outputLimit) throws Exception {
        Process child=new ProcessBuilder(command).start();
        // 블로킹 파이프는 공용 풀과 분리하고, stderr는 항상 64KiB만 보관합니다.
        ExecutorService io=Executors.newVirtualThreadPerTaskExecutor();
        try {
            var out=CompletableFuture.supplyAsync(()->bounded(child.getInputStream(),outputLimit),io);
            var err=CompletableFuture.supplyAsync(()->bounded(child.getErrorStream(),65536),io);
            CompletableFuture.runAsync(()-> {
                try(var stdin=child.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
                catch(IOException ignored) { /* 제출이 입력을 읽기 전에 종료할 수 있습니다. */ }
            },io);
            if(!child.waitFor(timeout,TimeUnit.MILLISECONDS))
                return new ProcessResult(-1,true,new CapturedOutput("",false),new CapturedOutput("",false));
            return new ProcessResult(child.exitValue(),false,out.get(2,TimeUnit.SECONDS),err.get(2,TimeUnit.SECONDS));
        } finally { child.destroyForcibly();io.shutdownNow(); }
    }
    private static CapturedOutput bounded(InputStream stream,int maximum) {
        try(stream) {
            var saved=new ByteArrayOutputStream();
            byte[] buffer=new byte[4096];int n;
            while((n=stream.read(buffer))!=-1) {
                if(saved.size()+n>maximum) return new CapturedOutput("",true);
                saved.write(buffer,0,n);
            }
            return new CapturedOutput(saved.toString(StandardCharsets.UTF_8),false);
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }
    private static void requireSuccess(ProcessResult result) throws IOException {
        if(result.timedOut || result.exit!=0) throw new IOException("Docker: "+result.error.text());
    }
    private static boolean oomKilled(String name) throws Exception {
        var result=execute(List.of("docker","inspect","--format","{{.State.OOMKilled}}",name),"",3000);
        requireSuccess(result);
        return result.output.text().trim().equals("true");
    }
    private static void cleanup(String... command) {
        // 인터럽트된 채점에서도 컨테이너 정리를 시도하고 호출자의 인터럽트 상태는 복구합니다.
        boolean interrupted=Thread.interrupted();
        try { requireSuccess(execute(List.of(command),"",5000)); }
        catch(InterruptedException ignored) { interrupted=true; }
        catch(Exception ignored) { /* 고유한 이름의 자원만 정리합니다. */ }
        finally { if(interrupted) Thread.currentThread().interrupt(); }
    }
}
