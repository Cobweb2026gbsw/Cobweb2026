package com.drewdrew0414.domain.problem;

import com.drewdrew0414.global.security.CustomUserDetails;
import com.drewdrew0414.domain.submission.Language;
import com.drewdrew0414.domain.group.GroupPermission;
import com.drewdrew0414.domain.group.GroupService;
import com.drewdrew0414.domain.group.ContestService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.drewdrew0414.global.exception.ApiException;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 문제 저장과 접근 권한을 담당합니다. 기존 인증은 JPA를 유지하고,
 * 목록·통계·잠금이 필요한 새 기능은 명시적인 SQL로 처리합니다.
 * SELECT * 응답을 피하여 비공개 테스트와 관리용 필드가 사용자에게 노출되지 않게 합니다.
 */
@Service @RequiredArgsConstructor
public class ProblemService {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final GroupService groups;
    private final ContestService contests;
    public static boolean operator(CustomUserDetails user) {
        return user != null && user.isEnabled() && user.getAuthorities().stream()
            .anyMatch(a -> Set.of("ROLE_OPERATOR","ROLE_DEVELOPER").contains(a.getAuthority()));
    }
    public static long requireUser(CustomUserDetails user) {
        if (user == null || !user.isEnabled()) throw new ApiException(HttpStatus.UNAUTHORIZED);
        return user.getUserId();
    }
    public static void requireOperator(CustomUserDetails user) {
        requireUser(user);
        if (!operator(user)) throw new ApiException(HttpStatus.FORBIDDEN);
    }
    /** 상태 이력의 checker_id FK와 제출 접수의 사용자→문제 잠금 순서를 일치시킵니다. */
    private long lockUser(CustomUserDetails user) {
        long uid=requireUser(user);
        if(db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",uid).isEmpty())
            throw new ApiException(HttpStatus.UNAUTHORIZED);
        return uid;
    }
    public static boolean functionAbiCompatible(Map<String,Object> problem) {
        if(problem.get("function_signature")!=null) return true;
        Object value=problem.get("function_abi_compatible");
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue()!=0;
    }
    public List<Map<String,Object>> list(int page, String search) {
        return list(page,search,null);
    }
    public List<Map<String,Object>> list(int page,String search,CustomUserDetails user) {
        return list(page,search,null,"all",user);
    }
    public List<Map<String,Object>> list(int page,String search,List<String> difficulty,String solved,
                                         CustomUserDetails user) {
        return listingRows(listingQuery(search,difficulty,solved,user,false),page);
    }
    /** 개수와 행은 같은 읽기 스냅샷에서 조회하며, 기본 배열 API는 변경하지 않습니다. */
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ListingPage listPage(int page,String search,List<String> difficulty,String solved,
                                CustomUserDetails user) {
        return listingPage(listingQuery(search,difficulty,solved,user,false),page);
    }
    public Map<String,Object> permissions(CustomUserDetails user) {
        requireUser(user);
        return Map.of("canCreate",true,"canManage",operator(user));
    }
    public List<Map<String,Object>> mine(int page,String search,CustomUserDetails user) {
        return mine(page,search,null,"all",user);
    }
    public List<Map<String,Object>> mine(int page,String search,List<String> difficulty,String solved,
                                         CustomUserDetails user) {
        return listingRows(listingQuery(search,difficulty,solved,user,true),page);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public ListingPage minePage(int page,String search,List<String> difficulty,String solved,
                                CustomUserDetails user) {
        return listingPage(listingQuery(search,difficulty,solved,user,true),page);
    }
    /** COUNT도 동일한 권한/필터 WHERE를 사용하므로 비공개 문제 수가 공개 목록에 섞이지 않습니다. */
    private record ListingQuery(String columns,String from,List<Object> parameters,String order,boolean authenticated) {}
    public record ListingPage(List<Map<String,Object>> items,int page,int pageSize,long totalItems,long totalPages) {}
    private ListingQuery listingQuery(String search,List<String> difficulty,String solved,
                                      CustomUserDetails user,boolean mine) {
        Long uid=user!=null && user.isEnabled()?user.getUserId():null;
        if(mine)uid=requireUser(user);
        var filters=listingFilters(difficulty,solved,uid!=null);
        // 전체 정답 수가 아니라 요청한 계정의 최초 해결 원장을 한 번 JOIN합니다.
        // (user_id,problem_id) PK 덕분에 결과 행과 COUNT 모두 중복되지 않습니다.
        String columns=mine?"""
            SELECT p.problem_number,p.problem_title,p.rank_type,p.rank_int,p.status,p.visibility,p.execution_mode,
                   p.submission_count,p.accepted_count,p.updated_at,(s.problem_id IS NOT NULL) AS solved
            """:"""
            SELECT p.problem_number,p.problem_title,p.rank_type,p.rank_int,p.submission_count,p.accepted_count,
                   (s.problem_id IS NOT NULL) AS solved
            """;
        var from=new StringBuilder("FROM problems p LEFT JOIN user_solved_problems s ON s.user_id=? AND s.problem_id=p.id ");
        List<Object> parameters=new ArrayList<>();parameters.add(uid);
        if(mine) {
            from.append("WHERE p.author_id=? AND p.problem_title ILIKE ?");parameters.add(uid);
        } else {
            from.append("WHERE p.status='PUBLISHED' AND p.visibility='PUBLIC' AND p.group_id IS NULL AND p.problem_title ILIKE ?");
        }
        parameters.add("%"+Objects.toString(search,"")+"%");
        appendListingFilters(from,parameters,filters);
        return new ListingQuery(columns,from.toString(),parameters,
            mine?" ORDER BY p.updated_at DESC,p.id DESC":" ORDER BY p.problem_number",uid!=null);
    }
    private List<Map<String,Object>> listingRows(ListingQuery query,int page) {
        return listingRowsAtOffset(query,offset(page));
    }
    private List<Map<String,Object>> listingRowsAtOffset(ListingQuery query,Number offset) {
        var parameters=new ArrayList<>(query.parameters());parameters.add(offset);
        var rows=db.queryForList(query.columns()+query.from()+query.order()+" LIMIT 30 OFFSET ?",parameters.toArray());
        return solvedRows(rows,query.authenticated());
    }
    private ListingPage listingPage(ListingQuery query,int requestedPage) {
        long total=Objects.requireNonNull(db.queryForObject("SELECT COUNT(*) "+query.from(),Long.class,query.parameters().toArray()));
        if(total<0)throw new IllegalStateException("Problem listing count must not be negative");
        // 빼기 후 나눗셈으로 ceil을 구해 long 덧셈 오버플로를 피합니다.
        long totalPages=total==0?0:(total-1)/30+1;
        int page=(int)Math.min(Math.max(requestedPage,0),Math.max(0,totalPages-1));
        // 새 메타데이터 모드는 실제 전체 페이지를 지원하고 OFFSET은 long으로 계산합니다.
        // 기존 배열 모드만 offset(page)의 10000페이지 상한을 그대로 유지합니다.
        return new ListingPage(listingRowsAtOffset(query,30L*page),page,30,total,totalPages);
    }
    private record ListingFilters(List<String> difficulties,String solved) {}
    private static ListingFilters listingFilters(List<String> difficulty,String solved,boolean authenticated) {
        var selected=difficulty==null?List.<String>of():difficulty;
        if(selected.size()>35)
            throw new ApiException(HttpStatus.BAD_REQUEST,"난이도는 최대 35개까지 선택할 수 있습니다.");
        for(String value:selected) {
            if(value==null || !value.matches("(?:INITIATE|SKILLED|ELITE|EXPERT|LEGEND|MYTHIC|ABSOLUTE):[1-5]"))
                throw new ApiException(HttpStatus.BAD_REQUEST,"난이도는 ELITE:1과 같은 등급:랭크 형식이어야 합니다.");
        }
        String status=Objects.toString(solved,"all");
        if(!Set.of("all","solved","unsolved").contains(status))
            throw new ApiException(HttpStatus.BAD_REQUEST,"해결 여부 필터를 확인해주세요.");
        if(!status.equals("all") && !authenticated)
            throw new ApiException(HttpStatus.UNAUTHORIZED,"해결 여부로 검색하려면 로그인해주세요.");
        return new ListingFilters(List.copyOf(new LinkedHashSet<>(selected)),status);
    }
    /** 등급 집합과 랭크 집합의 곱이 아니라 선택한 정확한 등급:랭크 쌍들을 OR로 묶습니다. */
    private static void appendListingFilters(StringBuilder sql,List<Object> parameters,ListingFilters filters) {
        if(!filters.difficulties().isEmpty()) {
            sql.append(" AND (");
            for(int index=0;index<filters.difficulties().size();index++) {
                if(index>0)sql.append(" OR ");
                sql.append("(p.rank_type=? AND p.rank_int=?)");
                String value=filters.difficulties().get(index);
                int separator=value.indexOf(':');
                parameters.add(value.substring(0,separator));parameters.add(Integer.parseInt(value.substring(separator+1)));
            }
            sql.append(')');
        }
        if(filters.solved().equals("solved"))sql.append(" AND s.problem_id IS NOT NULL");
        else if(filters.solved().equals("unsolved"))sql.append(" AND s.problem_id IS NULL");
    }
    /** JDBC가 SQL 조건식을 0/1로 반환해도 API 계약은 항상 JSON boolean으로 고정합니다. */
    private static List<Map<String,Object>> solvedRows(List<Map<String,Object>> rows,boolean authenticated) {
        return rows.stream().map(row->{
            Map<String,Object> result=new LinkedHashMap<>(row);
            Object solved=row.get("solved");
            result.put("solved",authenticated &&
                (Boolean.TRUE.equals(solved) || solved instanceof Number value && value.intValue()!=0));
            return result;
        }).toList();
    }
    public List<Map<String,Object>> adminList(int page,String search,String status,String visibility,
                                               CustomUserDetails user) {
        requireOperator(user);
        String state=filter(status,Set.of("DRAFT","PENDING_REVIEW","PUBLISHED","REJECTED","ARCHIVED"));
        String access=filter(visibility,Set.of("PUBLIC","PRIVATE","GROUP"));
        return db.queryForList("""
            SELECT p.problem_number,p.problem_title,p.rank_type,p.rank_int,p.status,p.visibility,
                   p.execution_mode,p.submission_count,p.accepted_count,p.updated_at,
                   u.username AS author_username
            FROM problems p JOIN users u ON u.id=p.author_id
            WHERE p.problem_title ILIKE ? AND (?='' OR p.status=?) AND (?='' OR p.visibility=?)
            ORDER BY p.updated_at DESC,p.id DESC LIMIT 30 OFFSET ?
            ""","%"+Objects.toString(search,"")+"%",state,state,access,access,offset(page));
    }
    public Map<String,Object> load(int number, CustomUserDetails user) {
        var rows = db.queryForList("SELECT * FROM problems WHERE problem_number=?", number);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND);
        var p = rows.getFirst();
        boolean owner = user != null && user.isEnabled() &&
            Objects.equals(((Number)p.get("author_id")).longValue(), user.getUserId());
        boolean published = "PUBLISHED".equals(p.get("status"));
        boolean groupViewer=false;
        boolean groupManager=false;
        if(!operator(user) && !owner && p.get("group_id")!=null && user!=null) {
            long gid=((Number)p.get("group_id")).longValue();
            groupManager=groupAllows(gid,user,GroupPermission.EDIT_PROBLEMS,GroupPermission.REVIEW_PROBLEMS);
            groupViewer=published && "GROUP".equals(p.get("visibility")) &&
                groupAllows(gid,user,GroupPermission.VIEW_PROBLEMS);
        }
        if (!operator(user) && !owner && !groupManager &&
            !(published && ((p.get("group_id")==null && "PUBLIC".equals(p.get("visibility"))) || groupViewer)))
            throw new ApiException(HttpStatus.NOT_FOUND);
        contests.requireQuestionRead(((Number)p.get("id")).longValue(),user);
        return p;
    }
    public Map<String,Object> detail(int number, CustomUserDetails user) {
        var p = load(number,user);
        Map<String,Object> result = new LinkedHashMap<>();
        for (String key : List.of("problem_number","problem_title","description","input_description",
                "output_description","constraints_text","rank_type","rank_int","time_limit_ms",
                "memory_limit_mb","execution_mode","status")) result.put(key,p.get(key));
        result.put("examples", db.queryForList(
            "SELECT input_text, output_text, explanation FROM problem_examples WHERE problem_id=? ORDER BY display_order",p.get("id")));
        var languages=db.queryForList("SELECT language FROM problem_languages WHERE problem_id=? ORDER BY language",
            String.class,p.get("id"));
        if ("FUNCTION".equals(p.get("execution_mode")) && !functionAbiCompatible(p))
            languages=languages.stream().filter("PYTHON"::equals).toList();
        result.put("languages",languages);
        // 함수 시작 코드 생성용 공개 규격 이름만 전달합니다. 레거시 문제를 체인 레이저로 추정하지 않습니다.
        putFunctionContract(result,p);
        // 시작 코드는 명시적으로 공개 등록된 행만 읽습니다. 정답 테이블은 조회하지 않습니다.
        var allowedLanguages=languages;
        result.put("starterCodes",starterCodes(p.get("id")).stream()
            .filter(code->allowedLanguages.contains(code.get("language"))).toList());
        result.put("tags",db.queryForList("""
            SELECT t.name FROM problem_tags pt JOIN tags t ON t.id=pt.tag_id
            WHERE pt.problem_id=? ORDER BY t.name
            """,String.class,p.get("id")));
        result.put("sourceName",p.get("source_name"));
        result.put("sourceUrl",p.get("source_url"));
        ProblemAuthorProjection.put(result,p,db);
        return result;
    }
    public Map<String,Object> edit(int number,CustomUserDetails user) {
        long uid=requireUser(user);
        var p=load(number,user);
        if(!operator(user)) {
            boolean owner=((Number)p.get("author_id")).longValue()==uid;
            if(p.get("group_id")==null) {
                if(!owner) throw new ApiException(HttpStatus.FORBIDDEN);
            } else {
                long gid=((Number)p.get("group_id")).longValue();
                if(!groupAllows(gid,user,GroupPermission.EDIT_PROBLEMS,GroupPermission.REVIEW_PROBLEMS) &&
                   !(owner && groupAllows(gid,user,GroupPermission.CREATE_PROBLEMS)))
                    throw new ApiException(HttpStatus.FORBIDDEN);
            }
        }
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("number",p.get("problem_number"));
        result.put("status",p.get("status"));
        result.put("visibility",p.get("requested_visibility"));
        result.put("currentVisibility",p.get("visibility"));
        result.put("groupId",p.get("group_id"));
        result.put("authorPublic",ProblemAuthorProjection.isPublic(p.get("author_public")));
        boolean usedInContest=usedInContest(((Number)p.get("id")).longValue());
        result.put("usedInContest",usedInContest);
        result.put("canEdit",!usedInContest && canEdit(p,user));
        result.put("title",p.get("problem_title"));
        result.put("description",p.get("description"));
        result.put("inputDescription",p.get("input_description"));
        result.put("outputDescription",p.get("output_description"));
        result.put("constraints",p.get("constraints_text"));
        result.put("rankType",p.get("rank_type"));
        result.put("rankInt",p.get("rank_int"));
        result.put("timeLimitMs",p.get("time_limit_ms"));
        result.put("memoryLimitMb",p.get("memory_limit_mb"));
        result.put("executionMode",p.get("execution_mode"));
        // 편집 화면도 동일한 공개 규격 이름으로 안전한 기본 시작 코드를 생성합니다.
        putFunctionContract(result,p);
        result.put("sourceName",p.get("source_name"));
        result.put("sourceUrl",p.get("source_url"));
        result.put("examples",db.queryForList("""
            SELECT input_text AS input,output_text AS output,explanation
            FROM problem_examples WHERE problem_id=? ORDER BY display_order
            """,p.get("id")));
        result.put("tests",db.queryForList("""
            SELECT input_data AS input,expected_output AS expected
            FROM problem_test_cases WHERE problem_id=? ORDER BY display_order
            """,p.get("id")));
        result.put("languages",db.queryForList("SELECT language FROM problem_languages WHERE problem_id=? ORDER BY language",
            String.class,p.get("id")));
        result.put("tags",db.queryForList("""
            SELECT t.name FROM problem_tags pt JOIN tags t ON t.id=pt.tag_id
            WHERE pt.problem_id=? ORDER BY t.name
            """,String.class,p.get("id")));
        result.put("referenceSolutions",db.queryForList("""
            SELECT language,file_name AS "fileName",source_code AS "sourceCode"
            FROM reference_solutions WHERE problem_id=? ORDER BY language
            """,p.get("id")));
        result.put("starterCodes",starterCodes(p.get("id")));
        return result;
    }
    private List<Map<String,Object>> starterCodes(Object problemId) {
        return db.queryForList("""
            SELECT language,source_code AS "sourceCode" FROM problem_starter_codes
            WHERE problem_id=? ORDER BY language
            """,problemId);
    }
    private void putFunctionContract(Map<String,Object> result,Map<String,Object> problem) {
        boolean function="FUNCTION".equals(problem.get("execution_mode"));
        var signature=function?FunctionSignature.fromJson(json,problem.get("function_signature")):null;
        result.put("functionSignature",signature);
        result.put("functionAbi",!function?null:signature!=null?"GENERIC_V1":
            functionAbiCompatible(problem)?"CHAIN_LASER_V1":null);
    }
    public Map<String,Object> adminDetail(int number,CustomUserDetails user) {
        requireOperator(user);
        return edit(number,user);
    }
    @Transactional
    public int create(ProblemRequests.Write request, CustomUserDetails user) {
        long uid = requireUser(user);
        var prepared=prepareWrite(request,request==null?null:request.groupId(),null,null);
        lockUser(user);
        if(prepared.groupId()!=null)
            groups.requirePermission(prepared.groupId(),user,GroupPermission.CREATE_PROBLEMS);
        titleAvailable(prepared.title(),prepared.groupId(),null);
        // 번호 시퀀스를 잠근 상태에서 이미 존재하는 수동 시드 번호도 건너뜁니다.
        int next = db.queryForObject("SELECT next_number FROM problem_number_sequence WHERE id=1 FOR UPDATE",Integer.class);
        int maximum = db.queryForObject("SELECT COALESCE(MAX(problem_number),999) FROM problems",Integer.class);
        int number = Math.max(next,maximum+1);
        db.update("UPDATE problem_number_sequence SET next_number=? WHERE id=1",number+1);
        try {
            db.update("""
                INSERT INTO problems(problem_number,problem_title,description,input_description,output_description,
                  constraints_text,rank_type,rank_int,time_limit_ms,memory_limit_mb,execution_mode,
                  author_id,group_id,status,visibility,requested_visibility,function_abi_compatible,source_name,source_url,author_public,function_signature)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?,?,?,?,CAST(? AS jsonb))
                """,number,prepared.title(),request.description(),request.inputDescription(),request.outputDescription(),
                    request.constraints(),request.rankType(),request.rankInt(),request.timeLimitMs(),
                    request.memoryLimitMb(),request.executionMode(),uid,prepared.groupId(),
                    prepared.groupId()==null?"PRIVATE":"GROUP",prepared.requestedVisibility(),
                    "FUNCTION".equals(request.executionMode()),
                    prepared.sourceName(),prepared.sourceUrl(),Boolean.TRUE.equals(request.authorPublic()),
                    prepared.functionSignature()==null?null:prepared.functionSignature().toJson(json));
        } catch(DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT,"이미 사용 중인 문제 제목 또는 번호입니다.");
        }
        long problemId=db.queryForObject("SELECT id FROM problems WHERE problem_number=?",Long.class,number);
        // 새 문제는 아직 자식 행이 없으므로 UPDATE와 세 번의 DELETE 없이 바로 저장합니다.
        saveContent(problemId,request,prepared);
        return number;
    }
    @Transactional
    public void update(int number, ProblemRequests.Write r, CustomUserDetails user) {
        lockUser(user);
        // 편집과 제출이 교차해 테스트 데이터가 바뀌지 않도록 사용자 다음에 문제 행을 잠급니다.
        db.queryForList("SELECT id FROM problems WHERE problem_number=? FOR UPDATE",number);
        var p = load(number,user);
        boolean admin=operator(user);
        Long groupId=p.get("group_id")==null?null:((Number)p.get("group_id")).longValue();
        if(r!=null && r.groupId()!=null && !r.groupId().equals(groupId))
            throw new ApiException(HttpStatus.CONFLICT,"문제의 소속 그룹은 변경할 수 없습니다.");
        if(!admin) {
            if(groupId!=null) {
                boolean owner=((Number)p.get("author_id")).longValue()==user.getUserId();
                if(!groupAllows(groupId,user,GroupPermission.EDIT_PROBLEMS) &&
                   !(owner && groupAllows(groupId,user,GroupPermission.CREATE_PROBLEMS)))
                    throw new ApiException(HttpStatus.FORBIDDEN);
            }
            else if(((Number)p.get("author_id")).longValue()!=user.getUserId())
                throw new ApiException(HttpStatus.FORBIDDEN);
        }
        if (admin) {
            if(groupId!=null ? !Set.of("DRAFT","REJECTED","ARCHIVED").contains(p.get("status"))
                             : !"PRIVATE".equals(p.get("visibility")))
                throw new ApiException(HttpStatus.CONFLICT,"공개 중인 문제는 수정할 수 없습니다.");
        } else if (!Set.of("DRAFT","REJECTED").contains(p.get("status")))
            throw new ApiException(HttpStatus.CONFLICT,"초안 또는 반려된 문제만 수정할 수 있습니다.");
        if(db.queryForObject("""
            SELECT COUNT(*) FROM submissions WHERE problem_id=? AND status IN ('QUEUED','RUNNING')
            """,Long.class,p.get("id"))>0)
            throw new ApiException(HttpStatus.CONFLICT,"진행 중인 제출이 있어 수정할 수 없습니다.");
        if(usedInContest(((Number)p.get("id")).longValue()))
            throw new ApiException(HttpStatus.CONFLICT,"시험에 사용된 문제는 복제하여 수정해주세요.");
        var previousSignature="FUNCTION".equals(p.get("execution_mode"))
            ?FunctionSignature.fromJson(json,p.get("function_signature")):null;
        var prepared=prepareWrite(r,groupId,(String)p.get("requested_visibility"),previousSignature);
        titleAvailable(prepared.title(),groupId,((Number)p.get("id")).longValue());
        if(prepared.references()==null) {
            var oldLanguages=db.queryForList("SELECT language FROM reference_solutions WHERE problem_id=?",
                String.class,p.get("id"));
            if(!prepared.languages().containsAll(oldLanguages))
                throw new ApiException(HttpStatus.BAD_REQUEST,"기존 참고 정답 언어가 새 허용 목록과 다릅니다.");
        }
        // 구형 클라이언트가 필드를 생략하면 보존하되, 잘못된 언어로 남는 템플릿은 조용히 삭제하지 않습니다.
        if(prepared.starters()==null) {
            var oldLanguages=db.queryForList("SELECT language FROM problem_starter_codes WHERE problem_id=?",
                String.class,p.get("id"));
            if(!prepared.languages().containsAll(oldLanguages))
                throw new ApiException(HttpStatus.BAD_REQUEST,"기존 시작 코드 언어가 새 허용 목록과 다릅니다. 시작 코드도 함께 수정해주세요.");
        }
        try {
            db.update("""
                UPDATE problems SET problem_title=?,description=?,input_description=?,output_description=?,
                 constraints_text=?,rank_type=?,rank_int=?,time_limit_ms=?,memory_limit_mb=?,execution_mode=?,
                 function_abi_compatible=?,source_name=?,source_url=?,requested_visibility=?,
                 author_public=COALESCE(?,author_public),function_signature=CAST(? AS jsonb)
                WHERE id=?
                """,prepared.title(),r.description(),r.inputDescription(),r.outputDescription(),r.constraints(),
                    r.rankType(),r.rankInt(),r.timeLimitMs(),r.memoryLimitMb(),r.executionMode(),
                    "FUNCTION".equals(r.executionMode()),prepared.sourceName(),prepared.sourceUrl(),
                    prepared.requestedVisibility(),r.authorPublic(),
                    prepared.functionSignature()==null?null:prepared.functionSignature().toJson(json),p.get("id"));
        } catch(DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT,"이미 사용 중인 문제 제목입니다.");
        }
        db.update("DELETE FROM problem_examples WHERE problem_id=?",p.get("id"));
        db.update("DELETE FROM problem_test_cases WHERE problem_id=?",p.get("id"));
        db.update("DELETE FROM problem_languages WHERE problem_id=?",p.get("id"));
        db.update("DELETE FROM problem_tags WHERE problem_id=?",p.get("id"));
        if(prepared.references()!=null)
            db.update("DELETE FROM reference_solutions WHERE problem_id=?",p.get("id"));
        if(prepared.starters()!=null)
            db.update("DELETE FROM problem_starter_codes WHERE problem_id=?",p.get("id"));
        saveContent(((Number)p.get("id")).longValue(),r,prepared);
    }
    private void validateFunctionTests(ProblemRequests.Write request,FunctionSignature signature) {
        if (!"FUNCTION".equals(request.executionMode())) {
            // DTO의 범용 함수 확장으로 기존 STDIN/SQL 제작 한도가 함께 늘어나지 않도록 유지합니다.
            for(var test:request.tests()) if(test==null || test.input()==null || test.expected()==null ||
                    test.input().length()>1000000 || test.expected().length()>1000000)
                throw new ApiException(HttpStatus.BAD_REQUEST,"일반 테스트 입력·정답은 각각 100만 자 이하여야 합니다.");
            return;
        }
        if(signature!=null) {
            // 50개 테스트가 각각 최대 크기인 요청을 누적 저장하지 않도록 묶음 전체도 제한합니다.
            long total=0;
            for(var test:request.tests()) {
                if(test==null) throw new ApiException(HttpStatus.BAD_REQUEST,"함수 테스트를 확인해주세요.");
                if(test.input()==null || test.expected()==null) throw new ApiException(HttpStatus.BAD_REQUEST,"함수 테스트를 확인해주세요.");
                total+=(long)test.input().getBytes(StandardCharsets.UTF_8).length+test.expected().getBytes(StandardCharsets.UTF_8).length;
                if(total>32L*1024*1024) throw new ApiException(HttpStatus.BAD_REQUEST,"함수 테스트 입력·정답 묶음은 UTF-8 32 MiB 이하여야 합니다.");
                signature.validateCase(json,test.input(),test.expected());
            }
            for(var example:request.examples()) {
                if(example==null) throw new ApiException(HttpStatus.BAD_REQUEST,"함수 예제를 확인해주세요.");
                signature.validateCase(json,example.input(),example.output());
            }
            return;
        }
        int index=0;
        for (var test:request.tests()) {
            index++;
            JsonNode input;
            try { input=json.readTree(test.input()); }
            catch (Exception e) { throw invalidFunctionTest(index); }
            JsonNode matrix=input==null?null:input.get("matrix");
            JsonNode target=input==null?null:input.get("target");
            if (matrix==null || !matrix.isArray() || matrix.size()!=15 ||
                target==null || !target.isArray() || target.size()!=2)
                throw invalidFunctionTest(index);
            for (JsonNode row:matrix) {
                if (!row.isArray() || row.size()!=15) throw invalidFunctionTest(index);
                for (JsonNode value:row)
                    if (!value.isIntegralNumber() || !value.canConvertToInt() ||
                        (value.intValue()!=0 && value.intValue()!=1)) throw invalidFunctionTest(index);
            }
            for (JsonNode coordinate:target)
                if (!coordinate.isIntegralNumber() || !coordinate.canConvertToInt() ||
                    coordinate.intValue()<0 || coordinate.intValue()>=15) throw invalidFunctionTest(index);
            if (!"true".equals(test.expected()) && !"false".equals(test.expected()))
                throw invalidFunctionTest(index);
        }
    }
    private static ApiException invalidFunctionTest(int index) {
        return new ApiException(HttpStatus.BAD_REQUEST,
            "FUNCTION 테스트 "+index+"번은 15×15 이진 matrix, 유효한 target 좌표, true/false 정답이 필요합니다.");
    }
    private void saveContent(long problemId, ProblemRequests.Write r,PreparedWrite prepared) {
        int order=0;
        for(var e:r.examples()) db.update(
            "INSERT INTO problem_examples(problem_id,input_text,output_text,explanation,display_order) VALUES(?,?,?,?,?)",
            problemId,e.input(),e.output(),e.explanation(),++order);
        order=0;
        for(var t:r.tests()) db.update(
            "INSERT INTO problem_test_cases(problem_id,input_data,expected_output,compare_type,display_order) VALUES(?,?,?,'EXACT',?)",
            problemId,t.input(),t.expected(),++order);
        // 초안의 실행 형식을 바꾸면 이전 형식의 언어 허용 목록도 함께 교체합니다.
        for(String language:prepared.languages())
            db.update("INSERT INTO problem_languages(problem_id,language) VALUES(?,?)",problemId,language);
        for(String tag:prepared.tags()) {
            db.update("INSERT INTO tags(name) VALUES(?) ON CONFLICT DO NOTHING",tag);
            long tagId=db.queryForObject("SELECT id FROM tags WHERE lower(name)=lower(?)",Long.class,tag);
            db.update("INSERT INTO problem_tags(problem_id,tag_id) VALUES(?,?)",problemId,tagId);
        }
        if(prepared.references()!=null) for(var ref:prepared.references())
            db.update("""
                INSERT INTO reference_solutions(problem_id,language,file_name,source_code) VALUES(?,?,?,?)
                """,problemId,ref.language(),ref.fileName(),ref.sourceCode());
        if(prepared.starters()!=null) for(var starter:prepared.starters())
            db.update("INSERT INTO problem_starter_codes(problem_id,language,source_code) VALUES(?,?,?)",
                problemId,starter.language(),starter.sourceCode());
    }
    private PreparedWrite prepareWrite(ProblemRequests.Write r,Long groupId,String priorVisibility,
                                       FunctionSignature priorSignature) {
        if(r==null || r.title()==null || r.examples()==null || r.tests()==null || r.executionMode()==null ||
           !Set.of("STDIN","FUNCTION","SQL").contains(r.executionMode()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"문제 입력을 확인해주세요.");
        // 크기 위반은 요청 오류지만 직렬화 엔진의 예기치 않은 실패를 400으로 숨기지 않습니다.
        // 그 실패는 공통 API 경계에서 진단 ID가 있는 안전한 500 응답으로 변환합니다.
        boolean generic="FUNCTION".equals(r.executionMode()) && (r.functionSignature()!=null || priorSignature!=null);
        int requestLimitMb=generic?40:2;
        if(json.writeValueAsBytes(r).length>requestLimitMb*1024*1024)
            throw new ApiException(HttpStatus.BAD_REQUEST,"문제 입력은 UTF-8 "+requestLimitMb+"MiB 이하여야 합니다.");
        if(groupId!=null && groupId<=0) throw new ApiException(HttpStatus.BAD_REQUEST,"그룹을 확인해주세요.");
        String requestedVisibility=r.visibility()!=null?r.visibility()
            : priorVisibility!=null?priorVisibility:groupId==null?"PUBLIC":"GROUP";
        if(groupId==null ? !Set.of("PUBLIC","PRIVATE").contains(requestedVisibility)
                         : !"GROUP".equals(requestedVisibility))
            throw new ApiException(HttpStatus.BAD_REQUEST,"문제 공개 범위와 그룹을 확인해주세요.");
        String title=normalizeSpace(r.title());
        if(title.isEmpty() || title.length()>200)
            throw new ApiException(HttpStatus.BAD_REQUEST,"제목은 1~200자여야 합니다.");
        List<String> requested=r.languages()==null?Language.forMode(r.executionMode()):r.languages();
        int languageLimit=Language.values().length;
        if(requested.size()>languageLimit)
            throw new ApiException(HttpStatus.BAD_REQUEST,"언어는 최대 "+languageLimit+"개입니다.");
        Set<String> languageSet=new LinkedHashSet<>();
        for(String name:requested) {
            if(name==null || name.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST,"언어를 확인해주세요.");
            Language parsed=Language.parse(name);
            parsed.requireMode(r.executionMode());
            if(!languageSet.add(parsed.name()))
                throw new ApiException(HttpStatus.BAD_REQUEST,"중복 언어입니다.");
        }
        List<String> languages=List.copyOf(languageSet);
        List<String> tags=new ArrayList<>();
        if(r.tags()!=null) {
            if(r.tags().size()>10) throw new ApiException(HttpStatus.BAD_REQUEST,"태그는 최대 10개입니다.");
            Set<String> seen=new HashSet<>();
            for(String raw:r.tags()) {
                if(raw==null) throw new ApiException(HttpStatus.BAD_REQUEST,"태그를 확인해주세요.");
                String tag=normalizeSpace(raw);
                if(tag.isEmpty() || tag.length()>50 || !seen.add(tag))
                    throw new ApiException(HttpStatus.BAD_REQUEST,"태그 길이 또는 중복을 확인해주세요.");
                tags.add(tag);
            }
        }
        String sourceName=optionalText(r.sourceName(),255);
        String sourceUrl=optionalText(r.sourceUrl(),1000);
        if(sourceUrl!=null) {
            try {
                URI uri=URI.create(sourceUrl);
                if(uri.getScheme()==null || !Set.of("http","https").contains(uri.getScheme()) || uri.getHost()==null ||
                   uri.getUserInfo()!=null) throw new IllegalArgumentException();
            } catch(IllegalArgumentException e) {
                throw new ApiException(HttpStatus.BAD_REQUEST,"출처 URL은 HTTP 또는 HTTPS여야 합니다.");
            }
        }
        List<ProblemRequests.ReferenceSolution> references=null;
        if(r.referenceSolutions()!=null) {
            if(r.referenceSolutions().size()>languageLimit)
                throw new ApiException(HttpStatus.BAD_REQUEST,"참고 정답은 최대 "+languageLimit+"개입니다.");
            references=new ArrayList<>();
            Set<String> seen=new HashSet<>();
            for(var ref:r.referenceSolutions()) {
                if(ref==null || ref.language()==null || ref.fileName()==null || ref.sourceCode()==null ||
                   ref.sourceCode().isBlank())
                    throw new ApiException(HttpStatus.BAD_REQUEST,"참고 정답을 확인해주세요.");
                String language=ref.language();
                if(!languages.contains(language) || !seen.add(language))
                    throw new ApiException(HttpStatus.BAD_REQUEST,"참고 정답 언어가 허용 목록과 다르거나 중복됩니다.");
                String fileName=ref.fileName().strip();
                if(fileName.isEmpty() || fileName.length()>120 || fileName.indexOf('/')>=0 ||
                   fileName.indexOf('\\')>=0 || fileName.codePoints().anyMatch(Character::isISOControl))
                    throw new ApiException(HttpStatus.BAD_REQUEST,"참고 정답 파일명을 확인해주세요.");
                if(ref.sourceCode().getBytes(StandardCharsets.UTF_8).length>65536)
                    throw new ApiException(HttpStatus.BAD_REQUEST,"참고 정답은 UTF-8 64KiB 이하여야 합니다.");
                references.add(new ProblemRequests.ReferenceSolution(language,fileName,ref.sourceCode()));
            }
        }
        List<ProblemRequests.StarterCode> starters=null;
        if(r.starterCodes()!=null) {
            if(r.starterCodes().size()>languageLimit)
                throw new ApiException(HttpStatus.BAD_REQUEST,"시작 코드는 최대 "+languageLimit+"개입니다.");
            starters=new ArrayList<>();
            Set<String> seen=new HashSet<>();
            for(var starter:r.starterCodes()) {
                if(starter==null || starter.language()==null || starter.sourceCode()==null || starter.sourceCode().isBlank())
                    throw new ApiException(HttpStatus.BAD_REQUEST,"시작 코드를 확인해주세요.");
                String language=starter.language();
                if(!languages.contains(language) || !seen.add(language))
                    throw new ApiException(HttpStatus.BAD_REQUEST,"시작 코드 언어가 허용 목록과 다르거나 중복됩니다.");
                if(starter.sourceCode().getBytes(StandardCharsets.UTF_8).length>65536)
                    throw new ApiException(HttpStatus.BAD_REQUEST,"시작 코드는 UTF-8 64KiB 이하여야 합니다.");
                starters.add(new ProblemRequests.StarterCode(language,starter.sourceCode()));
            }
        }
        FunctionSignature signature="FUNCTION".equals(r.executionMode())
            ?r.functionSignature()!=null?r.functionSignature().validated():priorSignature:null;
        validateFunctionTests(r,signature);
        return new PreparedWrite(title,languages,List.copyOf(tags),sourceName,sourceUrl,references,
            starters==null?null:List.copyOf(starters),groupId,requestedVisibility,signature);
    }
    private static String normalizeSpace(String text) { return text.strip().replaceAll("(?U)\\s+"," "); }
    private static String optionalText(String value,int maximum) {
        if(value==null || value.isBlank()) return null;
        String normalized=value.strip();
        if(normalized.length()>maximum) throw new ApiException(HttpStatus.BAD_REQUEST,"입력 길이를 확인해주세요.");
        return normalized;
    }
    private void titleAvailable(String title,Long groupId,Long excludeId) {
        long scope=groupId==null?0:groupId;
        Long count=excludeId==null
            ? db.queryForObject("SELECT COUNT(*) FROM problems WHERE title_scope=? AND lower(problem_title)=lower(?)",Long.class,scope,title)
            : db.queryForObject("SELECT COUNT(*) FROM problems WHERE title_scope=? AND lower(problem_title)=lower(?) AND id<>?",Long.class,
                scope,title,excludeId);
        if(count!=null && count>0)
            throw new ApiException(HttpStatus.CONFLICT,"이미 사용 중인 문제 제목입니다.");
    }
    private static String filter(String value,Set<String> allowed) {
        if(value==null || value.isBlank()) return "";
        if(!allowed.contains(value)) throw new ApiException(HttpStatus.BAD_REQUEST,"필터를 확인해주세요.");
        return value;
    }
    private static int offset(int page) { return Math.min(Math.max(page,0),10000)*30; }
    private boolean usedInContest(long problemId) {
        return db.queryForObject("SELECT COUNT(*) FROM contest_problems WHERE problem_id=?",Long.class,problemId)>0;
    }
    private boolean canEdit(Map<String,Object> p,CustomUserDetails user) {
        Long groupId=p.get("group_id")==null?null:((Number)p.get("group_id")).longValue();
        if(operator(user)) return groupId==null?"PRIVATE".equals(p.get("visibility"))
            : Set.of("DRAFT","REJECTED","ARCHIVED").contains(p.get("status"));
        if(!Set.of("DRAFT","REJECTED").contains(p.get("status"))) return false;
        if(groupId==null) return ((Number)p.get("author_id")).longValue()==user.getUserId();
        return groupAllows(groupId,user,GroupPermission.EDIT_PROBLEMS) ||
            (((Number)p.get("author_id")).longValue()==user.getUserId() &&
             groupAllows(groupId,user,GroupPermission.CREATE_PROBLEMS));
    }
    private boolean groupAllows(long groupId,CustomUserDetails user,GroupPermission... permissions) {
        for(var permission:permissions) if(groups.can(groupId,user,permission)) return true;
        return false;
    }
    private record PreparedWrite(String title,List<String> languages,List<String> tags,String sourceName,
                                 String sourceUrl,List<ProblemRequests.ReferenceSolution> references,
                                 List<ProblemRequests.StarterCode> starters,
                                 Long groupId,String requestedVisibility,FunctionSignature functionSignature) {}
    @Transactional
    public void requestReview(int number,CustomUserDetails user) {
        lockUser(user);
        db.queryForList("SELECT id FROM problems WHERE problem_number=? FOR UPDATE",number);
        var p=load(number,user);
        if(!operator(user)) {
            if(p.get("group_id")!=null) {
                long gid=((Number)p.get("group_id")).longValue();
                boolean owner=((Number)p.get("author_id")).longValue()==user.getUserId();
                if(!groupAllows(gid,user,GroupPermission.EDIT_PROBLEMS) &&
                   !(owner && groupAllows(gid,user,GroupPermission.CREATE_PROBLEMS)))
                    throw new ApiException(HttpStatus.FORBIDDEN);
            } else if(((Number)p.get("author_id")).longValue()!=user.getUserId())
                throw new ApiException(HttpStatus.FORBIDDEN);
        }
        if (!Set.of("DRAFT","REJECTED").contains(p.get("status")))
            throw new ApiException(HttpStatus.CONFLICT);
        requireReady(p);
        change(p,"PENDING_REVIEW","검수 요청",user.getUserId());
    }
    @Transactional
    public void review(int number,ProblemRequests.Review r,CustomUserDetails user) {
        lockUser(user);
        db.queryForList("SELECT id FROM problems WHERE problem_number=? FOR UPDATE",number);
        var p=load(number,user);
        if(!operator(user) && (p.get("group_id")==null ||
           !groupAllows(((Number)p.get("group_id")).longValue(),user,GroupPermission.REVIEW_PROBLEMS)))
            throw new ApiException(HttpStatus.FORBIDDEN);
        if (!"ARCHIVED".equals(r.status()) && !"PENDING_REVIEW".equals(p.get("status")))
            throw new ApiException(HttpStatus.CONFLICT);
        if ("PUBLISHED".equals(r.status())) requireReady(p);
        change(p,r.status(),r.cause(),user.getUserId());
    }
    @Transactional
    public void setVisibility(int number,ProblemRequests.Visibility request,CustomUserDetails user) {
        requireOperator(user);
        if(request==null || request.visibility()==null ||
           !Set.of("PUBLIC","PRIVATE").contains(request.visibility()))
            throw new ApiException(HttpStatus.BAD_REQUEST,"공개 범위를 확인해주세요.");
        lockUser(user);
        db.queryForList("SELECT id FROM problems WHERE problem_number=? FOR UPDATE",number);
        var p=load(number,user);
        boolean group=p.get("group_id")!=null;
        String targetStatus=group && "PRIVATE".equals(request.visibility())?"ARCHIVED"
            : "PUBLIC".equals(request.visibility())?"PUBLISHED":(String)p.get("status");
        String targetVisibility=group?"GROUP":request.visibility();
        String requested=group?"GROUP":request.visibility();
        if(Objects.equals(p.get("visibility"),targetVisibility) &&
           Objects.equals(p.get("status"),targetStatus) &&
           Objects.equals(p.get("requested_visibility"),requested)) return;
        if("PUBLIC".equals(request.visibility())) {
            requireReady(p);
        }
        db.update("""
            UPDATE problems SET visibility=?,status=?,requested_visibility=?,
                published_at=CASE WHEN ?='PUBLISHED' THEN (clock_timestamp() AT TIME ZONE 'UTC') ELSE published_at END WHERE id=?
            """,targetVisibility,targetStatus,requested,targetStatus,p.get("id"));
        String cause="공개 범위 "+request.visibility()+(request.cause()==null || request.cause().isBlank()
            ? "" : ": "+request.cause().strip());
        db.update("INSERT INTO problem_status_history(problem_id,status,cause,checker_id) VALUES(?,?,?,?)",
            p.get("id"),targetStatus,cause,user.getUserId());
    }
    private void requireReady(Map<String,Object> p) {
        if(db.queryForObject("""
            SELECT COUNT(*) FROM problem_test_cases WHERE problem_id=? AND is_active=TRUE
            """,Long.class,p.get("id"))==0)
            throw new ApiException(HttpStatus.CONFLICT,"활성 테스트 케이스가 필요합니다.");
        boolean pythonOnly="FUNCTION".equals(p.get("execution_mode")) && !functionAbiCompatible(p);
        if(db.queryForObject("""
            SELECT COUNT(*) FROM reference_solutions r JOIN problem_languages l
                ON l.problem_id=r.problem_id AND l.language=r.language
            WHERE r.problem_id=? AND (?=FALSE OR r.language='PYTHON')
                AND CHAR_LENGTH(TRIM(r.source_code))>0
            """,Long.class,p.get("id"),pythonOnly)==0)
            throw new ApiException(HttpStatus.CONFLICT,"허용 언어의 참고 정답이 필요합니다.");
    }
    private void change(Map<String,Object> p,String state,String cause,long uid) {
        String publishedVisibility=p.get("group_id")!=null?"GROUP":(String)p.get("requested_visibility");
        db.update("""
            UPDATE problems SET status=?,visibility=CASE WHEN ?='PUBLISHED' THEN ? ELSE visibility END,
                published_at=CASE WHEN ?='PUBLISHED' THEN (clock_timestamp() AT TIME ZONE 'UTC') ELSE published_at END WHERE id=?
            """,state,state,publishedVisibility,state,p.get("id"));
        db.update("INSERT INTO problem_status_history(problem_id,status,cause,checker_id) VALUES(?,?,?,?)",p.get("id"),state,cause,uid);
    }
}
