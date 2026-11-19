package com.drewdrew0414.domain.submission;

import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class SubmissionHistory {
    private final NamedParameterJdbcTemplate db;
    private static final int PAGE_SIZE = 15;
    private static final String SCOPE = " FROM submissions s WHERE s.user_id=:uid AND s.contest_id IS NULL";
    private static final String ROWS = """
        SELECT s.id,p.problem_number,p.problem_title,s.language,s.status,s.score,s.execution_time_ms,s.submitted_at
        """;
    private static final String FILTERED = """
         FROM submissions s JOIN problems p ON p.id=s.problem_id
         WHERE s.user_id=:uid AND s.contest_id IS NULL
           AND (:q='' OR strpos(lower(concat(p.problem_number,' ',p.problem_title)),lower(:q))>0)
           AND (:result='' OR s.status=:result) AND (:language='' OR s.language=:language)
        """;

    public record Page(List<Map<String,Object>> items,int page,long totalPages,long matches,
                       long total,long accepted,long pending,List<String> languages) {}

    private MapSqlParameterSource parameters(CustomUserDetails user,String q,String result,String language) {
        return new MapSqlParameterSource().addValue("uid",ProblemService.requireUser(user))
            .addValue("q",q.trim()).addValue("result",result).addValue("language",language);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Page page(CustomUserDetails user,String q,String result,String language,int requestedPage) {
        var args=parameters(user,q,result,language);
        var summary=db.queryForMap("""
            SELECT count(*) AS total, count(*) FILTER (WHERE status='ACCEPTED') AS accepted,
                   count(*) FILTER (WHERE status IN ('QUEUED','RUNNING')) AS pending
            """+SCOPE,args);
        long matches=db.queryForObject("SELECT count(*)"+FILTERED,args,Long.class);
        long pages=matches==0?0:1+(matches-1)/PAGE_SIZE;
        int page=(int)Math.min(Math.max(0,requestedPage),Math.max(0,pages-1));
        args.addValue("offset",(long)page*PAGE_SIZE).addValue("size",PAGE_SIZE);
        var rows=db.queryForList(ROWS+FILTERED+" ORDER BY s.id DESC LIMIT :size OFFSET :offset",args);
        var languages=db.queryForList("SELECT DISTINCT language"+SCOPE+" ORDER BY language",args,String.class);
        return new Page(rows,page,pages,matches,((Number)summary.get("total")).longValue(),
            ((Number)summary.get("accepted")).longValue(),((Number)summary.get("pending")).longValue(),languages);
    }

    public List<Map<String,Object>> exportBatch(CustomUserDetails user,String q,String result,String language,Long before) {
        var args=parameters(user,q,result,language);
        String cursor=before==null?"":" AND s.id < :before";
        args.addValue("before",before);
        return db.queryForList(ROWS+FILTERED+cursor+" ORDER BY s.id DESC LIMIT 500",args);
    }
}
