package com.drewdrew0414.domain.submission;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 제출 코드 하나를 매 테스트마다 새 컨테이너에서 실행합니다. 네트워크/쓰기/권한/프로세스 수를 제한하며
 * 정답은 컨테이너에 전달하지 않고 호스트에서 비교합니다. Docker가 실패해도 호스트 직접 실행으로 우회하지 않습니다.
 * 운영에서는 Docker 데몬을 보유한 전용 워커 호스트를 사용해야 합니다.
 */
@Component
public class DockerJudge {
    private final String image;
    private final Path root;
    public DockerJudge(@Value("${judge.image:cobweb-python:local}") String image,
                       @Value("${judge.workspace:/tmp/cobweb-judge}") String root) {
        this.image=image; this.root=Path.of(root);
    }
    private static final String FUNCTION_WRAPPER = """
        import sys, json, contextlib
        args = json.load(sys.stdin)
        scope = {"__name__": "__submission__"}
        with contextlib.redirect_stdout(sys.stderr):
            exec(compile(open("/work/solution.py").read(), "solution.py", "exec"), scope)
            value = scope["sol"](args["matrix"], tuple(args["target"]))
        if type(value) is not bool:
            raise TypeError("sol must return bool")
        print(json.dumps(value))
        """;
    public SubmissionService.JudgeResult run(String code,String input,String expected,String mode,int millis,int memory,Long caseId) {
        String name="cobweb-"+UUID.randomUUID();
        Path dir=null; Process process=null;
        // 스트림 읽기는 블로킹 작업입니다. 공용 풀이 작으면 stdout/stderr가 슬롯을 모두
        // 차지하여 stdin 전송이 실행되지 않으므로 케이스별 가상 스레드 실행기를 사용합니다.
        ExecutorService io=Executors.newVirtualThreadPerTaskExecutor();
        long start=System.nanoTime();
        try {
            Files.createDirectories(root);
            dir=Files.createTempDirectory(root,"case-");
            Files.setPosixFilePermissions(dir,java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
            Path source=dir.resolve("solution.py");
            Files.writeString(source,code,StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(source,java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
            List<String> command=new ArrayList<>(List.of("docker","run","--name",name,
                "--network=none","--read-only","--cap-drop=ALL","--security-opt=no-new-privileges",
                "--pids-limit=32","--memory="+memory+"m","--memory-swap="+memory+"m","--cpus=1",
                "--ulimit","cpu="+Math.max(1,(millis+999)/1000)+":"+(Math.max(1,(millis+999)/1000)+1),"--ulimit","fsize=1048576:1048576",
                "--user=65534:65534","--log-driver=none","-i",
                "--mount","type=bind,src="+source.toAbsolutePath()+",dst=/work/solution.py,readonly",
                image,"python","-I"));
            if("FUNCTION".equals(mode)) command.addAll(List.of("-c",FUNCTION_WRAPPER));
            else command.add("/work/solution.py");
            process=new ProcessBuilder(command).start();
            final Process child=process;
            // 스트림을 동시에 비우되 저장량은 64KiB로 제한하여 무한 출력으로 서버 메모리가 고갈되지 않게 합니다.
            var out=CompletableFuture.supplyAsync(()->bounded(child.getInputStream()),io);
            var err=CompletableFuture.supplyAsync(()->bounded(child.getErrorStream()),io);
            // 입력을 읽지 않는 제출도 제한 시간에 종료되도록 파이프 쓰기는 별도 작업에서 처리합니다.
            CompletableFuture.runAsync(()-> {
                try(var stdin=child.getOutputStream()) { stdin.write(input.getBytes(StandardCharsets.UTF_8)); }
                catch(IOException ignored) { /* 제출이 먼저 종료하여 파이프를 닫을 수 있습니다. */ }
            },io);
            if(!process.waitFor(millis+5000L,TimeUnit.MILLISECONDS))
                return new SubmissionService.JudgeResult(caseId,"TIME_LIMIT_EXCEEDED",millis);
            var output=out.get(2,TimeUnit.SECONDS);
            var error=err.get(2,TimeUnit.SECONDS);
            int exit=process.exitValue();
            String status;
            if(exit==125 || exit==126 || exit==127) status="SYSTEM_ERROR";
            // 출력 초과를 문자열로 치환하면 그 문자열이 정답인 경우 오답을 정답 처리할 수 있습니다.
            else if(output.exceeded() || error.exceeded()) status="RUNTIME_ERROR";
            // SIGKILL은 메모리 초과뿐 아니라 CPU hard limit에도 발생합니다. Docker의 OOM 상태를 확인해 구분합니다.
            else if(exit==137) status=oomKilled(name)?"MEMORY_LIMIT_EXCEEDED":"TIME_LIMIT_EXCEEDED";
            else if(exit==152) status="TIME_LIMIT_EXCEEDED";
            else if(exit!=0) status="RUNTIME_ERROR";
            else status=output.text().strip().equals(expected.strip())?"ACCEPTED":"WRONG_ANSWER";
            return new SubmissionService.JudgeResult(caseId,status,(int)Math.min(Integer.MAX_VALUE,(System.nanoTime()-start)/1000000));
        } catch(Exception e) {
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new SubmissionService.JudgeResult(caseId,"SYSTEM_ERROR",0);
        } finally {
            if(process!=null) process.destroyForcibly();
            try { var cleanup=new ProcessBuilder("docker","rm","-f",name).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if(!cleanup.waitFor(5,TimeUnit.SECONDS)) cleanup.destroyForcibly();
            } catch(Exception ignored) { /* 고유한 컨테이너 이름만 정리하며 다른 작업은 건드리지 않습니다. */ }
            if(dir!=null) try { Files.deleteIfExists(dir.resolve("solution.py")); Files.deleteIfExists(dir); } catch(IOException ignored) {}
            io.shutdownNow();
        }
    }
    private record CapturedOutput(String text, boolean exceeded) {}
    private static CapturedOutput bounded(InputStream stream) {
        try(stream) {
            ByteArrayOutputStream saved=new ByteArrayOutputStream(); byte[] buffer=new byte[4096]; int n;
            while((n=stream.read(buffer))!=-1) {
                if(saved.size()+n>65536) return new CapturedOutput("",true);
                saved.write(buffer,0,n);
            }
            return new CapturedOutput(saved.toString(StandardCharsets.UTF_8),false);
        } catch(IOException e) { throw new UncheckedIOException(e); }
    }
    private static boolean oomKilled(String name) throws Exception {
        var inspect=new ProcessBuilder("docker","inspect","--format","{{.State.OOMKilled}}",name).start();
        if(!inspect.waitFor(3,TimeUnit.SECONDS)) { inspect.destroyForcibly(); throw new IOException("inspect timeout"); }
        if(inspect.exitValue()!=0) throw new IOException("inspect failed");
        return new String(inspect.getInputStream().readAllBytes(),StandardCharsets.UTF_8).trim().equals("true");
    }
}
