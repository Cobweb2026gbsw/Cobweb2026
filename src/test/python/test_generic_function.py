"""Codec/generator tests only: submitted code is never executed on the host."""
import importlib.util
import hashlib
import io
import math
from pathlib import Path
import unittest
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[3] / "judge" / "generic_function.py"
SPEC = importlib.util.spec_from_file_location("generic_function", MODULE)
generic = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(generic)


def signature(kind="int", parameters=None):
    return {"name":"sol", "parameters":parameters if parameters is not None else [{"name":"a","type":kind}], "returnType":kind}


class GenericFunctionTest(unittest.TestCase):
    def test_rejects_nonfinite_duplicate_and_oversized_json(self):
        for text in ['{"args":[NaN]}','{"args":[Infinity]}','{"args":[],"args":[]}',
                     '{"args":[]} {}','"'+'a'*generic.MAX_JSON_BYTES+'"']:
            with self.subTest(text=text[:80]),self.assertRaises(ValueError):
                generic.load_json(io.StringIO(text))

    def test_all_value_types_round_trip(self):
        for kind,values in {
            "int":[-2147483648,0,2147483647],"float":[-1.25,0.0,1e100,1e-100],
            "bool":[False,True],"string":["","space quote\" newline\n\x00"],
            "int[]":[[],[-2147483648,0,2147483647]],"string[]":[[],["","a b","\"\n\x00"]],
            "int[][]":[[],[[]],[[1,-2],[],[-2147483648,2147483647]]],
            "string[][]":[[],[[]],[["a",""],[],["\"\n\x00"]]],
        }.items():
            for value in values:
                with self.subTest(kind=kind,value=value):
                    self.assertEqual(value,generic.decode(" ".join(generic.encode(value,kind)).encode(),kind))

    def test_rejects_wrong_dynamic_types(self):
        for value,kind in [(True,"int"),(1,"bool"),(1.5,"int"),("1","float"),(float("nan"),"float"),
                           (math.inf,"float"),([True],"int[]"),([1],"string[]"),("한글","string"),
                           (2147483648,"int"),(-2147483649,"int"),([[True]],"int[][]"),
                           ([["한글"]],"string[][]"),([1],"int[][]"),([[1]],"string[][]"),(10**400,"float")]:
            with self.subTest(value=value,kind=kind),self.assertRaises(ValueError):
                generic.encode(value,kind)

    def test_empty_argument_list(self):
        self.assertEqual(b"\n",generic.payload({"args":[]},signature(parameters=[])))

    def test_four_typed_arguments(self):
        spec=signature(parameters=[{"name":"a","type":"int"},{"name":"b","type":"float"},
                                   {"name":"c","type":"bool"},{"name":"d","type":"string"}])
        self.assertEqual(b'-2 1.25 1 3 97 32 10\n',generic.payload({"args":[-2,1.25,True,"a \n"]},spec))

    def test_rejects_invalid_input_shapes_and_types(self):
        for value in [[],{}, {"args":"x"},{"args":[]},{"args":[True]}, {"args":[1],"extra":1}]:
            with self.subTest(value=value),self.assertRaises(ValueError):
                generic.payload(value,signature())

    def test_rejects_untrusted_signatures(self):
        bad=[None,{},signature(parameters="a"),signature(parameters=[{"name":"a","type":"int"}]*2),
             signature(parameters=[{"name":"a","type":"int"}]*5),signature(parameters=[{"name":"x;abort()","type":"int"}]),
             signature(parameters=[{"name":"a","type":"ptr"}]),dict(signature(),name="sol();exit()"),dict(signature(),returnType="void"),
             dict(signature(),extra="ignored"),signature(parameters=[{"name":"A","type":"int"}]),
             signature(parameters=[{"name":"class","type":"int"}]),signature(parameters=[{"name":"a_length","type":"int"}]),
             signature(parameters=[{"name":"aa","type":"int"},{"name":"aA","type":"int"}])]
        for value in bad:
            with self.subTest(value=value),self.assertRaises(ValueError):
                generic.signature(value)

    def test_portable_reserved_identifiers_match_native_languages(self):
        for name in ["result","synchronized","strictfp","dyn","pub","where","select","chan","checked",
                     "div","program","constructor","co_await","require_once","mixin"]:
            with self.subTest(name=name),self.assertRaises(ValueError):
                generic.signature(signature(parameters=[{"name":name,"type":"int"}]))
        for name in ["value","length"]:
            generic.signature(signature(parameters=[{"name":name,"type":"int"}]))

    def test_rejects_malformed_outputs(self):
        for wire,kind in [(b"","int"),(b"1 2","int"),(b"1.5","int"),(b"2","bool"),(b"nan","float"),
                          (b"inf","float"),(b"-1","int[]"),(b"1 128","string"),(b"2 97","string"),
                          (b"1 -2147483649","int[]"),(b"1 1 255","string[]")]:
            with self.subTest(wire=wire,kind=kind),self.assertRaises(ValueError):
                generic.decode(wire,kind)

    def test_limits_match_public_signature_contract(self):
        generic.validate("a"*16384,"string")
        generic.validate([0]*generic.MAX_ARRAY_SLOTS,"int[]")
        for value,kind in [("a"*16385,"string"),([0]*(generic.MAX_ARRAY_SLOTS+1),"int[]")]:
            with self.assertRaises(ValueError):generic.validate(value,kind)
        for wire,kind in [(b"16385","string"),(b"1000001","int[]")]:
            with self.assertRaises(ValueError):generic.decode(wire,kind)

    def test_nested_and_multiple_argument_slots_share_one_budget(self):
        # Every inner element and outer row counts, including empty rows.
        with patch.object(generic,"MAX_ARRAY_SLOTS",5):
            generic.validate([[1,2],[]],"int[][]")
            with self.assertRaises(ValueError):generic.validate([[1,2],[3,4]],"int[][]")
            with self.assertRaises(ValueError):generic.decode(b'2 2 1 2 2 3 4',"int[][]")
            spec=signature(parameters=[{"name":"a","type":"int[]"},{"name":"b","type":"int[]"}])
            self.assertEqual(b'3 1 2 3 2 4 5\n',generic.payload({"args":[[1,2,3],[4,5]]},spec))
            with self.assertRaises(ValueError):generic.payload({"args":[[1,2,3],[4,5,6]]},spec)

    def test_six_parameter_contract_and_nested_types(self):
        parameters=[{"name":name,"type":kind} for name,kind in zip("abcdef",generic.TYPES[2:])]
        generic.signature(signature("int[][]",parameters))
        with self.assertRaises(ValueError):generic.signature(signature(parameters=parameters+[{"name":"g","type":"int"}]))
        with self.assertRaises(ValueError):generic.signature(signature("int[][][]"))

    def test_one_million_integer_input_round_trips_beyond_old_64k_limit(self):
        values=[i%97 for i in range(generic.MAX_ARRAY_SLOTS)]
        spec=signature("int[]")
        data=generic.payload({"args":[values]},spec)
        self.assertGreater(len(data),65536)
        self.assertLess(len(data),generic.MAX_WIRE_BYTES)
        self.assertTrue(values==generic.decode(data,"int[]"),"One-million integer codec result mismatch")

    def test_wire_limits_fail_before_unbounded_output_or_join(self):
        with patch.object(generic,"MAX_WIRE_BYTES",8):
            with self.assertRaises(ValueError):generic.payload({"args":[[1234,5678]]},signature("int[]"))
            with self.assertRaises(ValueError):generic.decode(b'2 1234 5678',"int[]")

    def test_final_json_boundary_does_not_append_an_extra_stdout_byte(self):
        spec=signature("int[]",parameters=[])
        for limit,expected in [(3,"[1]"),(2,None)]:
            output=io.StringIO()
            with patch.object(generic,"MAX_JSON_BYTES",limit),patch.object(generic,"load_json",return_value={"args":[]}), \
                    patch.object(generic,"generate",return_value="/tmp/wrapper.py"), \
                    patch.object(generic.subprocess,"Popen") as process,patch.object(generic.sys,"stdout",output):
                child=process.return_value.__enter__.return_value
                child.stdout=io.BytesIO(b"1 1")
                child.wait.return_value=0
                if expected is None:
                    with self.assertRaises(ValueError):generic.run("PYTHON",spec,512,[],[],[])
                else:
                    generic.run("PYTHON",spec,512,[],[],[])
            self.assertEqual(expected or "",output.getvalue())

    def test_del_character_uses_one_ascii_byte_at_the_smallest_json_boundary(self):
        spec=signature("string",parameters=[])
        for limit,expected in [(3,'"\x7f"'),(2,None)]:
            output=io.StringIO()
            with patch.object(generic,"MAX_JSON_BYTES",limit),patch.object(generic,"load_json",return_value={"args":[]}), \
                    patch.object(generic,"generate",return_value="/tmp/wrapper.py"), \
                    patch.object(generic.subprocess,"Popen") as process,patch.object(generic.sys,"stdout",output):
                child=process.return_value.__enter__.return_value
                child.stdout=io.BytesIO(b"1 127")
                child.wait.return_value=0
                if expected is None:
                    with self.assertRaises(ValueError):generic.run("PYTHON",spec,512,[],[],[])
                else:
                    generic.run("PYTHON",spec,512,[],[],[])
            self.assertEqual(expected or "",output.getvalue())

    def test_del_output_accepts_the_exact_full_sixteen_mib_json_boundary(self):
        # This is a mock of a container result, not host execution of a solution.
        # Build/hash the fixture in small chunks; never invoke a huge recursive diff.
        prefix_rows=1023
        full='\x7f'*16384
        remaining=generic.MAX_JSON_BYTES-(prefix_rows*(16384+3)+1)-3
        values=[full]*prefix_rows+['\x7f'*remaining]
        stream=io.BytesIO();stream.write(str(len(values)).encode()+b" ")
        expected_hash=hashlib.sha256();expected_hash.update(b"[")
        expected_bytes=2
        for index,value in enumerate(values):
            stream.write(str(len(value)).encode()+b" "+b"127 "*len(value))
            if index:
                expected_hash.update(b",");expected_bytes+=1
            expected_hash.update(b'"');expected_hash.update(value.encode("ascii"));expected_hash.update(b'"')
            expected_bytes+=len(value)+2
        expected_hash.update(b"]")
        wire=stream.getvalue();stream.close()
        self.assertEqual(generic.MAX_JSON_BYTES,expected_bytes)
        self.assertLessEqual(len(wire),generic.MAX_WIRE_BYTES)

        class HashOutput:
            size=0
            def __init__(self):self.hash=hashlib.sha256()
            def write(self,text):
                encoded=text.encode("ascii");self.size+=len(encoded);self.hash.update(encoded)
                return len(text)
        output=HashOutput()
        with patch.object(generic,"load_json",return_value={"args":[]}), \
                patch.object(generic,"generate",return_value="/tmp/wrapper.py"), \
                patch.object(generic.subprocess,"Popen") as process,patch.object(generic.sys,"stdout",output):
            child=process.return_value.__enter__.return_value
            child.stdout=io.BytesIO(wire);child.wait.return_value=0
            generic.run("PYTHON",signature("string[]",parameters=[]),512,[],[],[])
        self.assertEqual(generic.MAX_JSON_BYTES,output.size)
        self.assertEqual(expected_hash.hexdigest(),output.hash.hexdigest())

    def test_generates_all_fifteen_without_executing_submission(self):
        for language in generic.LANGUAGES:
            for kind in generic.TYPES:
                with self.subTest(language=language,kind=kind):
                    with patch.object(Path,"read_text",return_value="// submitted source\n"),patch.object(Path,"write_text") as writer:
                        output=generic.generate(language,signature(kind),"/tmp")
                    self.assertTrue(output.startswith("/tmp/"))
                    code=writer.call_args.args[0]
                    self.assertTrue(code)
                    self.assertNotIn("NAME",code)
                    self.assertNotIn("ARGS",code)
                    self.assertIn("sol",code)

    def test_native_counted_array_abi(self):
        spec=signature("string[]")
        c=generic._c(spec)
        self.assertIn("CWString* cwArg0=cwSA(&cwArg0N)",c)
        self.assertIn("sol(cwArg0, cwArg0N, &cwCount)",c)
        llvm=generic._c(spec,llvm=True)
        self.assertIn("extern CWString* sol(CWString*, int, int*)",llvm)

    def test_ragged_native_matrix_abi_and_managed_aliases(self):
        for kind,c_type in [("int[][]","CWIntArray"),("string[][]","CWStringArray")]:
            with self.subTest(kind=kind):
                native=generic._c(signature(kind))
                self.assertIn(c_type+"* cwArg0",native)
                self.assertIn("sol(cwArg0, cwArg0N, &cwCount)",native)
                self.assertIn("cwResult[i].length",native)
                self.assertIn("extern "+c_type+"* sol("+c_type+"*, int, int*)",generic._c(signature(kind),llvm=True))
        self.assertIn("TIntMatrix=array of TIntArray",generic._pascal(signature("int[][]")))
        self.assertIn("Array<Array<String>> = sol(",generic._kotlin(signature("string[][]")))

    def test_dynamic_writers_do_not_spread_million_element_arrays(self):
        spec=signature("int[]")
        for code in [generic._js(spec,""),generic._ruby(spec),generic._php(spec),generic._dart(spec)]:
            self.assertNotIn("flatMap",code)
            self.assertNotIn("flat_map",code)
            self.assertNotIn("array_push",code)
            self.assertIn("1000000",code)

    def test_high_expansion_string_wire_does_not_materialize_all_numeric_tokens(self):
        # Codec and the dynamic language readers must not split the entire numeric stream.
        # One valid ASCII string[] can otherwise contain over sixteen million tiny tokens.
        spec=signature("string[]")
        for code in [generic._python(spec),generic._js(spec,""),generic._ruby(spec),generic._dart(spec)]:
            self.assertNotIn(".split(",code)
            self.assertNotIn(".read.split",code)
        self.assertIn("Buffer.allocUnsafe(cwI())",generic._js(spec,""))
        self.assertNotIn("s+=String.fromCharCode",generic._js(spec,""))
        self.assertIn("cwFS.writeSync(1,a,offset,a.length-offset)",generic._js(spec,""))
        self.assertNotIn("process.stdout.write",generic._js(spec,""))
        self.assertIn("Console.OpenStandardInput()",generic._csharp(spec))
        self.assertNotIn("Console.In.ReadToEnd()",generic._csharp(spec))
        self.assertEqual(["a"*16384]*3,generic.decode(generic.payload({"args":[["a"*16384]*3]},spec),"string[]"))

    def test_real_docker_harness_mismatch_never_builds_a_recursive_unittest_diff(self):
        harness_path=Path(__file__).with_name("test_generic_nested_docker.py")
        harness_spec=importlib.util.spec_from_file_location("nested_harness_safety",harness_path)
        harness=importlib.util.module_from_spec(harness_spec)
        harness_spec.loader.exec_module(harness)
        case=harness.GenericNestedDockerTest()
        # The container call is mocked; no submitted solution or reference runs on the host.
        # In particular, TestCase.assertEqual must not receive the potentially huge arrays.
        with patch.object(case,"container",return_value=b"[2,2,2]"), \
                patch.object(case,"assertEqual",side_effect=AssertionError("recursive diff attempted")):
            with self.assertRaises(AssertionError) as failure:
                case.check("PYTHON","int[]",[[1,1,1]])
        message=str(failure.exception)
        self.assertIn("result mismatch",message)
        self.assertIn("sha256=",message)
        self.assertLess(len(message),300)

    def test_kotlin_generic_return_does_not_merge_greater_than_and_assignment(self):
        code=generic._kotlin(signature("string[]"))
        self.assertIn("val cwResult:Array<String> = sol(",code)
        self.assertNotIn("Array<String>=",code)


if __name__=="__main__":unittest.main()
