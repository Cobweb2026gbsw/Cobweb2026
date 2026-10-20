package com.drewdrew0414.domain.problem;

import com.drewdrew0414.domain.group.ContestService;
import com.drewdrew0414.domain.group.GroupService;
import com.drewdrew0414.global.security.CustomUserDetails;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProblemLockOrderTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final ProblemService service=new ProblemService(db,new ObjectMapper(),mock(GroupService.class),
        mock(ContestService.class));
    private final CustomUserDetails operator=operator();

    @Test void everyExistingProblemMutationLocksUserBeforeProblem() {
        List<Consumer<CustomUserDetails>> mutations=List.of(
            user -> service.update(1001,null,user),
            user -> service.requestReview(1001,user),
            user -> service.review(1001,new ProblemRequests.Review("ARCHIVED","검토"),user),
            user -> service.setVisibility(1001,new ProblemRequests.Visibility("PRIVATE","검토"),user));

        for(var mutation:mutations) {
            reset(db);
            when(db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",42L))
                .thenReturn(List.of(Map.of("id",42L)));
            assertThrows(ResponseStatusException.class,() -> mutation.accept(operator));
            var order=inOrder(db);
            order.verify(db).queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",42L);
            order.verify(db).queryForList("SELECT id FROM problems WHERE problem_number=? FOR UPDATE",1001);
        }
    }

    @Test void creatingAProblemLocksAuthorBeforeTitleAndNumberAllocation() {
        when(db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",42L))
            .thenReturn(List.of(Map.of("id",42L)));
        when(db.queryForObject(contains("title_scope=?"),eq(Long.class),eq(0L),eq("제목")))
            .thenReturn(1L);
        var request=new ProblemRequests.Write("제목","본문","입력","출력",null,"INITIATE",1,
            1000,128,"STDIN",List.of(),List.of());

        assertThrows(ResponseStatusException.class,() -> service.create(request,operator));

        var order=inOrder(db);
        order.verify(db).queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",42L);
        order.verify(db).queryForObject(contains("title_scope=?"),eq(Long.class),eq(0L),eq("제목"));
        verify(db,never()).queryForObject(contains("next_number"),eq(Integer.class));
    }

    private static CustomUserDetails operator() {
        var user=mock(CustomUserDetails.class);
        when(user.isEnabled()).thenReturn(true);
        when(user.getUserId()).thenReturn(42L);
        when(user.getAuthorities()).thenAnswer(invocation ->
            List.of(new SimpleGrantedAuthority("ROLE_OPERATOR")));
        return user;
    }
}
