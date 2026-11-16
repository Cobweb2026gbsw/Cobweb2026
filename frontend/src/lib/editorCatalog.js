// 편집 보조용 어휘입니다. 외부 언어 서버에 소스를 보내거나 컴파일 결과를 가장하지 않습니다.
// 예약어/주요 내장 이름과 문법 스니펫을 추천하며 프로젝트 전체 타입 추론은 하지 않습니다.
const words = value => [...new Set(value.trim().split(/\s+/).filter(Boolean))];
const entry = (keywords, builtins, snippets) => ({keywords: words(keywords), builtins: words(builtins), snippets});
const snippet = (label, template, detail) => ({label, template, detail});
const cKeywords = '_Alignas _Alignof _Atomic _Bool _Complex _Generic _Imaginary _Noreturn _Static_assert _Thread_local alignas alignof asm auto bool break case char const continue default do double else enum extern false float for goto if inline int long nullptr register restrict return short signed sizeof static static_assert struct switch thread_local true typedef typeof typeof_unqual union unsigned void volatile while';
export const EDITOR_CATALOG = {
  PYTHON: entry('False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield match case type',
    'abs all any ascii bin bool breakpoint bytearray bytes callable chr classmethod compile complex delattr dict dir divmod enumerate eval exec filter float format frozenset getattr globals hasattr hash help hex id input int isinstance issubclass iter len list locals map max memoryview min next object oct open ord pow print property range repr reversed round set setattr slice sorted staticmethod str sum super tuple type vars zip __import__', [
      snippet('def 함수', 'def ${name}(${args}):\n    ${pass}', '함수 선언'),
      snippet('for 반복', 'for ${item} in ${items}:\n    ${pass}', '반복문'),
      snippet('if 조건', 'if ${condition}:\n    ${pass}', '조건문'),
      snippet('try 예외', 'try:\n    ${pass}\nexcept ${Exception} as ${error}:\n    ${pass}', '예외 처리')]),
  C: entry(cKeywords, 'NULL EOF stdin stdout stderr printf fprintf scanf sscanf puts putchar getchar fgets malloc calloc realloc free memcpy memmove memset memcmp strlen strcmp strcpy strncpy strcat qsort bsearch abs', [
    snippet('for 반복', 'for (int ${i} = 0; ${i} < ${n}; ${i}++) {\n    ${}\n}', '계수 반복'),
    snippet('if 조건', 'if (${condition}) {\n    ${}\n}', '조건문'),
    snippet('함수 선언', '${int} ${name}(${void}) {\n    return ${0};\n}', '함수 선언')]),
  CPP: entry(`${cKeywords} alignas alignof and and_eq bitand bitor catch char8_t char16_t char32_t class compl concept consteval constexpr constinit const_cast co_await co_return co_yield decltype delete dynamic_cast explicit export friend mutable namespace new noexcept not not_eq operator or or_eq private protected public reinterpret_cast requires static_cast template this throw try typeid typename using virtual wchar_t xor xor_eq`,
    'std cin cout cerr endl string vector array deque list map unordered_map set unordered_set stack queue priority_queue pair tuple sort stable_sort reverse lower_bound upper_bound binary_search min max swap accumulate iota begin end push_back emplace_back size empty', [
      snippet('for 범위 반복', 'for (const auto& ${item} : ${items}) {\n    ${}\n}', '범위 기반 반복'),
      snippet('for 반복', 'for (int ${i} = 0; ${i} < ${n}; ++${i}) {\n    ${}\n}', '계수 반복'),
      snippet('if 조건', 'if (${condition}) {\n    ${}\n}', '조건문')]),
  JAVA: entry('abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while true false null exports module open opens provides requires to transitive uses with var yield record sealed permits non-sealed when',
    'System String StringBuilder Integer Long Double Boolean Math Arrays Collections ArrayList LinkedList HashMap HashSet TreeMap TreeSet PriorityQueue ArrayDeque Scanner BufferedReader InputStreamReader StringTokenizer println print parseInt parseLong size length add get put containsKey', [
      snippet('for 반복', 'for (int ${i} = 0; ${i} < ${n}; ${i}++) {\n    ${}\n}', '계수 반복'),
      snippet('if 조건', 'if (${condition}) {\n    ${}\n}', '조건문'),
      snippet('메서드 선언', 'public static ${int} ${name}(${args}) {\n    return ${0};\n}', '메서드')]),
  JAVASCRIPT: entry('as async await break case catch class const continue debugger default delete do else export extends false finally for from function get if import in instanceof let new null of return set static super switch this throw true try typeof undefined var void while with yield implements interface package private protected public enum',
    'console log Math JSON Number String Boolean BigInt Array Object Map Set WeakMap WeakSet Promise Symbol RegExp Date Error TypeError parseInt parseFloat isNaN Infinity NaN require module exports process Buffer push pop shift unshift map filter reduce forEach sort slice splice includes indexOf join split trim length', [
      snippet('function 함수', 'function ${name}(${args}) {\n    ${}\n}', '함수 선언'),
      snippet('for of 반복', 'for (const ${item} of ${items}) {\n    ${}\n}', '반복문'),
      snippet('if 조건', 'if (${condition}) {\n    ${}\n}', '조건문'),
      snippet('arrow 함수', 'const ${name} = (${args}) => {\n    ${}\n};', '화살표 함수')]),
  TYPESCRIPT: entry('abstract any as asserts async await bigint boolean break case catch class const constructor continue debugger declare default delete do else enum export extends false finally for from function get global if implements import in infer instanceof interface intrinsic is keyof let module namespace never new null number object of out override private protected public readonly require return satisfies set static string super switch symbol this throw true try type typeof undefined unique unknown using var void while with yield',
    'console Math JSON Number String Boolean BigInt Array Object Map Set Promise Record Partial Required Pick Omit Exclude Extract NonNullable ReturnType Parameters Readonly Awaited Buffer process require module', [
      snippet('function 함수', 'function ${name}(${args}): ${void} {\n    ${}\n}', '타입이 있는 함수'),
      snippet('interface 선언', 'interface ${Name} {\n    ${value}: ${number};\n}', '인터페이스'),
      snippet('for of 반복', 'for (const ${item} of ${items}) {\n    ${}\n}', '반복문')]),
  CSHARP: entry('abstract as base bool break byte case catch char checked class const continue decimal default delegate do double else enum event explicit extern false finally fixed float for foreach goto if implicit in int interface internal is lock long namespace new null object operator out override params private protected public readonly ref return sbyte sealed short sizeof stackalloc static string struct switch this throw true try typeof uint ulong unchecked unsafe ushort using virtual void volatile while add alias and ascending async await by descending dynamic equals field file from get global group init into join let managed nameof nint not notnull nuint on or orderby partial record remove required scoped select set unmanaged value var when where with yield',
    'Console Math Convert Array String StringBuilder List Dictionary HashSet Queue Stack PriorityQueue Enumerable ReadLine WriteLine Parse TryParse Select Where OrderBy ToArray ToList Count Length Add', [
      snippet('foreach 반복', 'foreach (var ${item} in ${items}) {\n    ${}\n}', '열거 반복'),
      snippet('메서드 선언', 'public static ${int} ${Name}(${args}) {\n    return ${0};\n}', '메서드'),
      snippet('if 조건', 'if (${condition}) {\n    ${}\n}', '조건문')]),
  GO: entry('break default func interface select case defer go map struct chan else goto package switch const fallthrough if range type continue for import return var true false nil iota',
    'append cap clear close complex copy delete imag len make max min new panic print println real recover bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 uint16 uint32 uint64 uintptr fmt bufio os sort strings strconv Println Fscan Atoi Itoa', [
      snippet('func 함수', 'func ${name}(${args}) ${int} {\n    return ${0}\n}', '함수 선언'),
      snippet('for range 반복', 'for ${index}, ${value} := range ${items} {\n    ${}\n}', '범위 반복'),
      snippet('if 조건', 'if ${condition} {\n    ${}\n}', '조건문')]),
  RUST: entry('as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while abstract become box do final gen macro override priv try typeof unsized virtual yield union macro_rules',
    'std core alloc String Vec Option Some None Result Ok Err Box HashMap HashSet BTreeMap BTreeSet VecDeque BinaryHeap println print format vec panic assert assert_eq dbg collect iter into_iter map filter enumerate unwrap expect parse push pop len is_empty i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str', [
      snippet('fn 함수', 'fn ${name}(${args}) -> ${i32} {\n    ${0}\n}', '함수 선언'),
      snippet('for 반복', 'for ${item} in ${items} {\n    ${}\n}', '반복문'),
      snippet('match 분기', 'match ${value} {\n    ${pattern} => ${result},\n    _ => ${default},\n}', '패턴 분기')]),
  KOTLIN: entry('as break class continue do else false for fun if in interface is null object package return super this throw true try typealias typeof val var when while by catch constructor delegate dynamic field file finally get import init param property receiver set setparam where actual abstract annotation companion const crossinline data enum expect external final infix inline inner internal lateinit noinline open operator out override private protected public reified sealed suspend tailrec vararg value context',
    'Int Long Double Float Boolean Char String Unit Any Nothing List MutableList Map MutableMap Set MutableSet Array IntArray LongArray Pair Triple println print readln readLine listOf mutableListOf mapOf mutableMapOf setOf mutableSetOf arrayOf intArrayOf sortedBy map filter fold sum maxOf minOf repeat until downTo step', [
      snippet('fun 함수', 'fun ${name}(${args}): ${Int} {\n    return ${0}\n}', '함수 선언'),
      snippet('for 반복', 'for (${item} in ${items}) {\n    ${}\n}', '반복문'),
      snippet('when 분기', 'when (${value}) {\n    ${case} -> ${result}\n    else -> ${default}\n}', '분기')]),
  SQL: entry('ABORT ACTION ADD AFTER ALL ALTER ALWAYS ANALYZE AND AS ASC ATTACH AUTOINCREMENT BEFORE BEGIN BETWEEN BY CASCADE CASE CAST CHECK COLLATE COLUMN COMMIT CONFLICT CONSTRAINT CREATE CROSS CURRENT CURRENT_DATE CURRENT_TIME CURRENT_TIMESTAMP DATABASE DEFAULT DEFERRABLE DEFERRED DELETE DESC DETACH DISTINCT DO DROP EACH ELSE END ESCAPE EXCEPT EXCLUDE EXCLUSIVE EXISTS EXPLAIN FAIL FILTER FIRST FOLLOWING FOR FOREIGN FROM FULL GENERATED GLOB GROUP GROUPS HAVING IF IGNORE IMMEDIATE IN INDEX INDEXED INITIALLY INNER INSERT INSTEAD INTERSECT INTO IS ISNULL JOIN KEY LAST LEFT LIKE LIMIT MATCH MATERIALIZED NATURAL NO NOT NOTHING NOTNULL NULL NULLS OF OFFSET ON OR ORDER OTHERS OUTER OVER PARTITION PLAN PRAGMA PRECEDING PRIMARY QUERY RAISE RANGE RECURSIVE REFERENCES REGEXP REINDEX RELEASE RENAME REPLACE RESTRICT RETURNING RIGHT ROLLBACK ROW ROWS SAVEPOINT SELECT SET TABLE TEMP TEMPORARY THEN TIES TO TRANSACTION TRIGGER UNBOUNDED UNION UNIQUE UPDATE USING VACUUM VALUES VIEW VIRTUAL WHEN WHERE WINDOW WITH WITHOUT',
    'COUNT SUM AVG MIN MAX ABS COALESCE IFNULL NULLIF LENGTH LOWER UPPER TRIM LTRIM RTRIM SUBSTR SUBSTRING REPLACE ROUND RANDOM GROUP_CONCAT TOTAL DATE TIME DATETIME STRFTIME JULIANDAY UNIXEPOCH ROW_NUMBER RANK DENSE_RANK LAG LEAD FIRST_VALUE LAST_VALUE NTH_VALUE NTILE JSON JSON_EXTRACT JSON_ARRAY JSON_OBJECT', [
      snippet('SELECT 조회', 'SELECT ${columns}\nFROM ${table}\nWHERE ${condition};', '조회 전용 SQL'),
      snippet('GROUP BY 집계', 'SELECT ${column}, COUNT(*) AS ${count}\nFROM ${table}\nGROUP BY ${column};', '집계'),
      snippet('WITH CTE', 'WITH ${name} AS (\n    SELECT ${columns} FROM ${table}\n)\nSELECT * FROM ${name};', '공통 테이블 식')]),
  RUBY: entry('BEGIN END alias and begin break case class def defined? do else elsif end ensure false for if in module next nil not or redo rescue retry return self super then true undef unless until when while yield __FILE__ __LINE__ __ENCODING__',
    'puts print p gets chomp split map select reject reduce inject each each_with_index times upto downto sort sort_by reverse join Array Hash String Integer Float Set Range Enumerable Math nil? empty? length size push pop shift unshift to_i to_f to_s', [
      snippet('def 함수', 'def ${name}(${args})\n    ${nil}\nend', '메서드 선언'),
      snippet('each 반복', '${items}.each do |${item}|\n    ${}\nend', '블록 반복'),
      snippet('if 조건', 'if ${condition}\n    ${}\nend', '조건문')]),
  LLVM_IR: entry('define declare ret br switch indirectbr invoke callbr resume catchswitch catchret cleanupret unreachable fneg add fadd sub fsub mul fmul udiv sdiv fdiv urem srem frem shl lshr ashr and or xor extractelement insertelement shufflevector extractvalue insertvalue alloca load store fence cmpxchg atomicrmw getelementptr trunc zext sext fptrunc fpext fptoui fptosi uitofp sitofp ptrtoint inttoptr bitcast addrspacecast icmp fcmp phi select freeze call va_arg landingpad catchpad cleanuppad to tail musttail notail void half bfloat float double fp128 x86_fp80 ppc_fp128 label metadata token type opaque ptr addrspace inbounds nuw nsw exact fast nnan ninf nsz arcp contract afn reassoc eq ne ugt uge ult ule sgt sge slt sle oeq ogt oge olt ole one ord uno ueq une true false null zeroinitializer undef poison global constant private internal available_externally linkonce weak common appending extern_weak linkonce_odr weak_odr external dso_local dso_preemptable hidden protected default dllimport dllexport thread_local localdynamic initialexec localexec unnamed_addr local_unnamed_addr section partition comdat align gc prefix prologue personality noalias nocapture nonnull readonly readnone writeonly nounwind noundef uwtable alwaysinline noinline optnone optsize minsize sanitize_address signext zeroext sret byval byref inalloca preallocated dereferenceable dereferenceable_or_null immarg inreg nest returned swiftself swifterror vscale x i1 i8 i16 i32 i64 i128 source_filename target datalayout triple attributes uselistorder uselistorder_bb atomic volatile syncscope monotonic acquire release acq_rel seq_cst',
    'printf scanf malloc free llvm.memcpy llvm.memmove llvm.memset llvm.trap llvm.assume', [
      snippet('define 함수', 'define i32 @${name}(i32 %${x}) {\nentry:\n    ret i32 ${0}\n}', '함수 정의'),
      snippet('conditional br', 'br i1 %${condition}, label %${yes}, label %${no}', '조건 분기'),
      snippet('getelementptr', '%${result} = getelementptr inbounds ${type}, ptr %${pointer}, i64 ${index}', '주소 계산')]),
  PASCAL: entry('absolute abstract and array as asm assembler automated begin case cdecl class const constructor contains default deprecated destructor dispid dispinterface div do downto dynamic else end except experimental export exports external far file final finalization finally for forward function generic goto helper if implementation implements in index inherited initialization inline interface interrupt is label library local message mod name near nil nodefault not object of on operator or out overload override package packed pascal platform private procedure program property protected public published raise read readonly record register reintroduce repeat requires resourcestring safecall sealed set shl shr softfloat specialize static stdcall stored strict string then threadvar to try type unit unsafe until uses var varargs virtual while with write writeonly xor',
    'Integer Int64 LongInt QWord Boolean Char String Real Double Byte Word Cardinal True False Read ReadLn Write WriteLn Length SetLength Inc Dec Abs Sqr Sqrt Ord Chr Str Val Pos Copy Delete Insert Low High Assigned SizeOf Halt', [
      snippet('function 함수', 'function ${Name}(${x}: Integer): Integer;\nbegin\n    Result := ${0};\nend;', '함수 선언'),
      snippet('for 반복', 'for ${i} := ${0} to ${n} do\nbegin\n    ${}\nend;', '반복문'),
      snippet('if 조건', 'if ${condition} then\nbegin\n    ${}\nend;', '조건문')]),
  PHP: entry('__halt_compiler abstract and array as break callable case catch class clone const continue declare default die do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile enum eval exit extends final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require require_once return static switch throw trait try unset use var while xor yield yield_from true false null parent self',
    'strlen count sizeof explode implode trim intval floatval strval array_map array_filter array_reduce array_keys array_values array_sum array_slice array_merge array_fill range sort rsort usort in_array array_search min max abs floor ceil sqrt fgets fread fscanf STDIN STDOUT PHP_INT_MAX json_decode json_encode', [
      snippet('function 함수', 'function ${name}(${args}): ${int} {\n    return ${0};\n}', '함수 선언'),
      snippet('foreach 반복', 'foreach (${items} as ${item}) {\n    ${}\n}', '반복문'),
      snippet('if 조건', 'if (${condition}) {\n    ${}\n}', '조건문')]),
  DART: entry('abstract as assert async augment await base break case catch class const continue covariant default deferred do dynamic else enum export extends extension external factory false final finally for Function get hide if implements import in interface is late library mixin native new null of on operator part required rethrow return sealed set show static super switch sync this throw true try type typedef var void when while with yield',
    'int double num bool String List Map Set Iterable Object Never Null Future Stream print stdin stdout readLineSync split map where fold reduce toList toSet parse tryParse length isEmpty isNotEmpty add remove contains sort compareTo', [
      snippet('함수 선언', '${int} ${name}(${args}) {\n    return ${0};\n}', '함수 선언'),
      snippet('for in 반복', 'for (final ${item} in ${items}) {\n    ${}\n}', '반복문'),
      snippet('if 조건', 'if (${condition}) {\n    ${}\n}', '조건문')]),
  UMJUNSIK: entry('어떻게 엄 어 식? 식! 식ㅋ 동탄 준 화이팅! 이 사람이름이냐ㅋㅋ', '', [
    snippet('프로그램 틀', '어떻게\n${}\n이 사람이름이냐ㅋㅋ', '공식 v2 프로그램 경계'),
    snippet('정수 입력', '엄식?', '첫 번째 변수에 정수 입력'),
    snippet('정수 출력', '식${어}!', '정수 출력'),
    snippet('조건 분기', '동탄${어}?${준..}', '값이 0이면 명령 실행')])
};
export const LANGUAGE_IDS = Object.freeze(Object.keys(EDITOR_CATALOG));
// 공백을 포함하는 엄랭의 프로그램 경계는 하나의 문법 항목입니다.
EDITOR_CATALOG.UMJUNSIK.keywords=['어떻게','엄','어','식?','식!','식ㅋ','동탄','준','화이팅!','이 사람이름이냐ㅋㅋ'];
export const CATALOG_SOURCES = Object.freeze([
  'https://codemirror.net/docs/ref/',
  'https://github.com/codemirror/legacy-modes',
  'https://www.sqlite.org/lang_keywords.html',
  'https://llvm.org/docs/LangRef.html',
  'https://github.com/rycont/umjunsik-lang/blob/e973f9d22b9803ee86b53d8e60f1b65ab08c7547/README.md'
]);
