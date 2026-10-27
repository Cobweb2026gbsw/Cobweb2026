package com.drewdrew0414;

import com.drewdrew0414.domain.problem.FunctionSignature;
import com.drewdrew0414.domain.problem.ProblemRequests;
import com.drewdrew0414.domain.problem.ProblemService;
import com.drewdrew0414.domain.user.entity.Role;
import com.drewdrew0414.domain.user.entity.User;
import com.drewdrew0414.global.security.CustomUserDetails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 큰 고정 입력만 전용 DB의 롤백 트랜잭션에서 저장합니다. 실제 서비스 DB는 사용하지 않습니다. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test") @Transactional
class GenericLargeAuthoringIntegrationTest {
    @Autowired JdbcTemplate db;
    @Autowired ProblemService problems;
    private CustomUserDetails owner;
    private String input;
    @BeforeEach void setup(){
        String name="lg"+UUID.randomUUID().toString().replace("-","").substring(0,12);
        db.update("INSERT INTO users(username,password) VALUES(?,'test-only')",name);
        var entity=User.builder().username(name).password("test-only").build();
        ReflectionTestUtils.setField(entity,"id",db.queryForObject("SELECT id FROM users WHERE username=?",Long.class,name));
        ReflectionTestUtils.setField(entity,"role",Role.USER);owner=new CustomUserDetails(entity);
        input="{\"args\":[["+"1234567890,".repeat(249999)+"1234567890]]}";
    }
    private ProblemRequests.Write request(String mode,int count){
        var signature=new FunctionSignature("sol",List.of(new FunctionSignature.Parameter("numbers","int[]")),"bool");
        return new ProblemRequests.Write("Large "+UUID.randomUUID(),"Body","Input","Output","32-bit", "ELITE",5,10000,512,mode,
            List.of(new ProblemRequests.Example("{\"args\":[[1]]}","true","example")),
            Collections.nCopies(count,new ProblemRequests.TestCase(input,"true")),List.of("PYTHON"),List.of(),null,null,
            List.of(),null,"PRIVATE",List.of(),false,mode.equals("FUNCTION")?signature:null);
    }
    @Test void typedCaseLargerThanTwoMiBIsSavedWithoutTruncation(){
        int number=problems.create(request("FUNCTION",1),owner);
        assertEquals(input,db.queryForObject("SELECT t.input_data FROM problem_test_cases t JOIN problems p ON p.id=t.problem_id WHERE p.problem_number=?",String.class,number));
    }
    @Test void privateCaseBundleOverThirtyTwoMiBIsRejectedBeforeCreatingAProblem(){
        long before=db.queryForObject("SELECT COUNT(*) FROM problems",Long.class);
        assertEquals(400,assertThrows(ResponseStatusException.class,()->problems.create(request("FUNCTION",13),owner)).getStatusCode().value());
        assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM problems",Long.class));
    }
    @Test void stdinAuthoringDoesNotInheritTheGenericLargerEnvelope(){
        assertEquals(400,assertThrows(ResponseStatusException.class,()->problems.create(request("STDIN",1),owner)).getStatusCode().value());
    }
}
