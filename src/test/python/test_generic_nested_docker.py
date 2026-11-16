"""Opt-in real-container ABI/scale regression; never execute a solution on the host.

GENERIC_NESTED_DOCKER_TEST=true GENERIC_JUDGE_IMAGE=cobweb-languages:local \
python3 -I -B -m unittest discover -s src/test/python -p test_generic_nested_docker.py -v
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest
import uuid

LANGUAGES=("PYTHON","C","CPP","JAVA","JAVASCRIPT","TYPESCRIPT","CSHARP","GO","RUST",
           "KOTLIN","RUBY","LLVM_IR","PASCAL","PHP","DART")
EXTENSIONS=dict(zip(LANGUAGES,("py","c","cpp","java","js","ts","cs","go","rs","kt","rb","ll","pas","php","dart")))


def wire_stress_character():
    code=os.environ.get("GENERIC_WIRE_STRESS_CODE")
    return chr(int(code)) if code is not None else os.environ.get("GENERIC_WIRE_STRESS_CHAR","a")


def source(language,kind,count=False):
    result="int" if count else kind
    native={"int[]":"int*","string[]":"CWString*","int[][]":"CWIntArray*","string[][]":"CWStringArray*"}[kind]
    managed={"int[]":"int[]","string[]":"String[]","int[][]":"int[][]","string[][]":"String[][]"}
    if language=="PYTHON":return "def sol(a): return "+("len(a)" if count else "a")
    if language=="JAVASCRIPT":return "function sol(a){return "+("a.length" if count else "a")+";}"
    if language=="TYPESCRIPT":
        t=kind.replace("int","number")
        return "export function sol(a:"+t+"):"+("number" if count else t)+"{return "+("a.length" if count else "a")+";}"
    if language=="C":return ("int" if count else native)+" sol("+native+" a,int n"+("){return n;}" if count else ",int *result_length){*result_length=n;return a;}")
    if language=="CPP":
        t={"int[]":"std::vector<int>","string[]":"std::vector<std::string>","int[][]":"std::vector<std::vector<int>>","string[][]":"std::vector<std::vector<std::string>>"}[kind]
        return ("int" if count else t)+" sol("+t+" a){return "+("(int)a.size()" if count else "a")+";}"
    if language in ("JAVA","CSHARP"):
        t=managed[kind] if language=="JAVA" else kind
        return "public class "+("Main" if language=="JAVA" else "Solution")+"{public static "+("int" if count else t)+" sol("+t+" a){return "+("a.length" if language=="JAVA" and count else "a.Length" if count else "a")+";}}"
    if language=="GO":
        t={"int[]":"[]int","string[]":"[]string","int[][]":"[][]int","string[][]":"[][]string"}[kind]
        return "package main\nfunc sol(a "+t+") "+("int" if count else t)+"{return "+("len(a)" if count else "a")+"}"
    if language=="RUST":
        t={"int[]":"Vec<i32>","string[]":"Vec<String>","int[][]":"Vec<Vec<i32>>","string[][]":"Vec<Vec<String>>"}[kind]
        return "fn sol(a:"+t+")->"+("i32" if count else t)+"{"+("a.len() as i32" if count else "a")+"}"
    if language=="KOTLIN":
        t={"int[]":"IntArray","string[]":"Array<String>","int[][]":"Array<IntArray>","string[][]":"Array<Array<String>>"}[kind]
        return "fun sol(a:"+t+"):"+("Int" if count else t)+" = "+("a.size" if count else "a")
    if language=="RUBY":return "def sol(a)\n "+("a.length" if count else "a")+"\nend"
    if language=="PHP":return "<?php function sol($a){return "+("count($a)" if count else "$a")+";}"
    if language=="DART":
        t={"int[]":"List<int>","string[]":"List<String>","int[][]":"List<List<int>>","string[][]":"List<List<String>>"}[kind]
        return ("int" if count else t)+" sol("+t+" a)=>"+("a.length" if count else "a")+";"
    if language=="PASCAL":
        t={"int[]":"TIntArray","string[]":"TStringArray","int[][]":"TIntMatrix","string[][]":"TStringMatrix"}[kind]
        return "function sol(a:"+t+"):"+("LongInt" if count else t)+";begin Result:="+("Length(a)" if count else "a")+";end;"
    if language=="LLVM_IR":return "define i32 @sol(ptr %a,i32 %n){ret i32 %n}" if count else "define ptr @sol(ptr %a,i32 %n,ptr %result_length){store i32 %n,ptr %result_length\nret ptr %a}"
    raise ValueError(language)


@unittest.skipUnless(os.environ.get("GENERIC_NESTED_DOCKER_TEST")=="true","Explicit real-Docker opt-in required")
class GenericNestedDockerTest(unittest.TestCase):
    image=os.environ.get("GENERIC_JUDGE_IMAGE","cobweb-languages:local")

    @classmethod
    def setUpClass(cls):
        # Fail closed instead of letting `docker run` pull a missing tag, and pin the suite
        # against tag changes while another local process prepares a newer image.
        inspected=subprocess.run(["docker","image","inspect",cls.image,"--format","{{.Id}}"],capture_output=True,text=True,timeout=15,check=True)
        cls.image=inspected.stdout.strip()
        if not cls.image.startswith("sha256:") or len(cls.image)!=71:
            raise ValueError("Invalid local judge image identity")
        print("Nested ABI image: "+cls.image,flush=True)
        if os.environ.get("GENERIC_WORST_WIRE_TEST")=="true":
            character=wire_stress_character()
            if len(character)!=1 or ord(character)>127:
                raise ValueError("Wire stress character must be one ASCII byte")
            print("Wire stress ASCII code: "+str(ord(character)),flush=True)

    def container(self,source_dir,work_dir,language,phase,data=None):
        name="cobweb-nested-"+uuid.uuid4().hex
        args=["docker","run","--rm","--name",name,"--network=none","--read-only","--cap-drop=ALL",
              "--security-opt=no-new-privileges","--pids-limit=128","--cpus=1",
              "--memory="+("1024m" if phase=="compile" else "512m"),"--memory-swap="+("1024m" if phase=="compile" else "512m"),
              "-e","DOTNET_GCHeapHardLimit="+("20000000" if phase=="compile" else "10000000"),
              "-e","DOTNET_PROCESSOR_COUNT=1",
              "--tmpfs=/tmp:rw,noexec,nosuid,nodev,size="+("256m" if phase=="compile" else "96m"),
              "--mount","type=bind,source="+str(source_dir)+",target=/source,readonly",
              "--mount","type=bind,source="+str(work_dir)+",target=/work"+(",readonly" if phase=="run" else ""),
              "-i",self.image,phase,language,"FUNCTION","512"]
        try:
            started=time.monotonic()
            run=subprocess.run(args,input=data,stdout=subprocess.PIPE,stderr=subprocess.PIPE,timeout=120 if phase=="compile" else self.runtime_seconds)
            self.timings.append((phase,time.monotonic()-started))
            self.assertEqual(0,run.returncode,language+" "+phase+": "+run.stderr.decode(errors="replace")[:3000]+run.stdout[:500].decode(errors="replace"))
            self.assertLessEqual(len(run.stdout),16*1024*1024)
            return run.stdout
        finally:
            # The exact random name belongs only to this invocation; no project containers are targeted.
            subprocess.run(["docker","rm","-f",name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,timeout=15)

    def check(self,language,kind,values,count=False,runtime_seconds=None):
        self.timings=[]
        self.runtime_seconds=runtime_seconds or float(os.environ.get("GENERIC_RUNTIME_TIMEOUT_SECONDS","45"))
        with tempfile.TemporaryDirectory(prefix="cobweb-nested-") as folder:
            root=Path(folder).resolve();src=root/"source";work=root/"work";src.mkdir();work.mkdir();work.chmod(0o777)
            root.chmod(0o755);src.chmod(0o755)
            filename=("Main" if language in ("JAVA","CSHARP","KOTLIN") else "main")+"."+EXTENSIONS[language]
            (src/filename).write_text(source(language,kind,count),encoding="utf-8")
            spec={"name":"sol","parameters":[{"name":"a","type":kind}],"returnType":"int" if count else kind}
            (src/"signature.json").write_text(json.dumps(spec),encoding="utf-8")
            started=time.monotonic()
            if language not in ("PYTHON","JAVASCRIPT","RUBY","PHP"):
                self.container(src,work,language,"compile")
            for value in values:
                data=json.dumps({"args":[value]},ensure_ascii=False,separators=(',',':')).encode()
                actual=json.loads(self.container(src,work,language,"run",data))
                expected=len(value) if count else value
                if expected!=actual:
                    # unittest's recursive list diff can consume gigabytes for a million
                    # mismatches. Never render a large solution/result into a failure log.
                    def fingerprint(item):
                        encoded=json.dumps(item,separators=(',',':'),ensure_ascii=False).encode()
                        return type(item).__name__+" bytes="+str(len(encoded))+" sha256="+hashlib.sha256(encoded).hexdigest()
                    self.fail(language+" "+kind+" result mismatch; expected "+fingerprint(expected)+"; actual "+fingerprint(actual))
            compile_seconds=sum(seconds for phase,seconds in self.timings if phase=="compile")
            max_run=max(seconds for phase,seconds in self.timings if phase=="run")
            print(language+" "+kind+(" count" if count else " identity")+" "+str(len(values))+" cases "+format(time.monotonic()-started,'.2f')+"s; compile="+format(compile_seconds,'.2f')+"s max-run="+format(max_run,'.2f')+"s",flush=True)


def add_cases():
    for language in LANGUAGES:
        def nested_int(self,l=language):self.check(l,"int[][]",[[[1,-2],[],[-2147483648,2147483647]],[],[[]]])
        def nested_string(self,l=language):self.check(l,"string[][]",[[["a b",""],[],["quote\"\n\u0000"]],[],[[]]])
        def million_count(self,l=language):self.check(l,"int[]",[[2147483647]*1_000_000],count=True)
        def million_identity(self,l=language):self.check(l,"int[]",[[2147483647]*1_000_000])
        for suffix,method in (("nested_int",nested_int),("nested_string",nested_string),("million_count",million_count),("million_identity",million_identity)):
            setattr(GenericNestedDockerTest,"test_"+language.lower()+"_"+suffix,method)
        if os.environ.get("GENERIC_WORST_WIRE_TEST")=="true":
            def worst_wire(self,l=language):self.check(l,"string[]",[[wire_stress_character()*16384]*1023],runtime_seconds=45)
            setattr(GenericNestedDockerTest,"test_"+language.lower()+"_worst_wire",worst_wire)


add_cases()
if __name__=="__main__":unittest.main()
