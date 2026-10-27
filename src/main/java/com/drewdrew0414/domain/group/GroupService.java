package com.drewdrew0414.domain.group;

import com.drewdrew0414.global.security.CustomUserDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.drewdrew0414.global.exception.ApiException;

import java.util.*;

/** 그룹 소속과 역할을 관리합니다. 권한 판단은 매 요청마다 DB의 현재 역할을 읽습니다. */
@Service @RequiredArgsConstructor
public class GroupService {
    public static final String SYSTEM_GROUP_KEY="COBWEB_DEFAULT";
    private static final int MAX_MEMBERSHIPS=3;
    private static final int MAX_ROLES=8;
    private static final Set<String> BUILTIN_KEYS=Set.of("OWNER","MANAGER","AUTHOR","CONTEST_MANAGER","MEMBER");
    private final JdbcTemplate db;

    public static long requireUser(CustomUserDetails user) {
        if(user==null || !user.isEnabled()) throw new ApiException(HttpStatus.UNAUTHORIZED);
        return user.getUserId();
    }
    public static boolean operator(CustomUserDetails user) {
        return user!=null && user.isEnabled() && user.getAuthorities().stream()
            .anyMatch(a -> Set.of("ROLE_OPERATOR","ROLE_DEVELOPER").contains(a.getAuthority()));
    }
    private static boolean enabled(Object value) {
        return value instanceof Boolean b ? b : value instanceof Number n && n.intValue()!=0;
    }
    private static int offset(int page) {
        if(page<0 || page>10000) throw new ApiException(HttpStatus.BAD_REQUEST,"잘못된 페이지입니다.");
        return page*30;
    }
    private static String name(String value) {
        String result=value==null?"":value.trim();
        if(result.isEmpty()) throw new ApiException(HttpStatus.BAD_REQUEST,"이름을 입력해 주세요.");
        return result;
    }
    private Map<String,Object> group(long gid,boolean lock) {
        var rows=db.queryForList("SELECT id,group_name,group_description,status,created_at,system_key FROM study_groups WHERE id=?"+
            (lock?" FOR UPDATE":""),gid);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"그룹을 찾을 수 없습니다.");
        return rows.getFirst();
    }
    private static boolean systemGroup(Map<String,Object> group) {
        return SYSTEM_GROUP_KEY.equals(group.get("system_key"));
    }
    /** 내장 그룹에는 사용자 소유자가 없고 역할 위임은 플랫폼 운영자만 할 수 있습니다. */
    private static void requireSystemRoleAdministrator(Map<String,Object> group,CustomUserDetails user) {
        if(systemGroup(group) && !operator(user))
            throw new ApiException(HttpStatus.FORBIDDEN,"기본 그룹의 역할은 플랫폼 운영자만 관리할 수 있습니다.");
    }
    private Map<String,Object> role(long gid,long roleId) {
        var rows=db.queryForList("SELECT * FROM group_roles WHERE group_id=? AND id=?",gid,roleId);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"역할을 찾을 수 없습니다.");
        return rows.getFirst();
    }
    private Map<String,Object> member(long gid,long uid) {
        var rows=db.queryForList("""
            SELECT gm.user_id,gm.group_id,gm.role_id,gm.permission,gr.builtin_key,gr.role_name
            FROM group_members gm JOIN group_roles gr ON gr.id=gm.role_id AND gr.group_id=gm.group_id
            WHERE gm.group_id=? AND gm.user_id=? FOR UPDATE
            """,gid,uid);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"그룹 회원을 찾을 수 없습니다.");
        return rows.getFirst();
    }
    private static boolean owner(Map<String,Object> membership) {
        return "OWNER".equals(membership.get("builtin_key")) || "OWNER".equals(membership.get("permission"));
    }
    private static Set<GroupPermission> permissions(Map<String,Object> role) {
        var result=EnumSet.noneOf(GroupPermission.class);
        for(var permission:GroupPermission.values()) if(enabled(role.get(permission.column()))) result.add(permission);
        return result;
    }
    private static List<String> permissionNames(Collection<GroupPermission> permissions) {
        return permissions.stream().map(Enum::name).toList();
    }
    private Set<GroupPermission> permissions(long gid,CustomUserDetails user) {
        long uid=requireUser(user);
        var rows=db.queryForList("""
            SELECT gr.* FROM group_members gm
            JOIN group_roles gr ON gr.id=gm.role_id AND gr.group_id=gm.group_id
            JOIN study_groups g ON g.id=gm.group_id AND g.status='ACCEPTED'
            JOIN users u ON u.id=gm.user_id AND u.status='ACTIVE'
            WHERE gm.group_id=? AND gm.user_id=?
            """,gid,uid);
        var result=EnumSet.noneOf(GroupPermission.class);
        if(!rows.isEmpty()) result.addAll(permissions(rows.getFirst()));
        if(operator(user)) for(var permission:GroupPermission.values())
            if(permission!=GroupPermission.TAKE_CONTESTS) result.add(permission);
        return result;
    }
    public boolean can(long gid,CustomUserDetails user,GroupPermission permission) {
        if(user==null || !user.isEnabled() || permission==null) return false;
        if(operator(user) && permission!=GroupPermission.TAKE_CONTESTS) return true;
        return permissions(gid,user).contains(permission);
    }
    public void requirePermission(long gid,CustomUserDetails user,GroupPermission permission) {
        requireUser(user);
        if(!can(gid,user,permission)) throw new ApiException(HttpStatus.FORBIDDEN,"그룹 권한이 없습니다.");
    }
    public void requireMember(long gid,CustomUserDetails user) {
        long uid=requireUser(user);
        if(operator(user)) return;
        // 소속 행이 남아 있어도 승인되지 않은 그룹·정지 계정의 권한은 되살리지 않습니다.
        if(db.queryForObject("""
            SELECT COUNT(*) FROM group_members gm
            JOIN study_groups g ON g.id=gm.group_id AND g.status='ACCEPTED'
            JOIN users u ON u.id=gm.user_id AND u.status='ACTIVE'
            WHERE gm.group_id=? AND gm.user_id=?
            """,Long.class,gid,uid)==0)
            throw new ApiException(HttpStatus.FORBIDDEN,"그룹 회원이 아닙니다.");
    }

    public List<Map<String,Object>> publicList(int page,String search) {
        var rows=db.queryForList("""
            SELECT g.id,g.group_name,g.group_description,g.system_key,
                (SELECT COUNT(*) FROM group_members gm WHERE gm.group_id=g.id) AS member_count
            FROM study_groups g WHERE g.status='ACCEPTED' AND g.group_name LIKE ?
            ORDER BY g.id DESC LIMIT 30 OFFSET ?
            ""","%"+Objects.toString(search,"")+"%",offset(page));
        return rows.stream().map(r -> {
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("id",r.get("id")); item.put("groupName",r.get("group_name"));
            item.put("memberCount",r.get("member_count"));
            item.put("systemGroup",systemGroup(r));
            return item;
        }).toList();
    }
    public List<Map<String,Object>> mine(CustomUserDetails user) {
        long uid=requireUser(user);
        var rows=db.queryForList("""
            SELECT g.id,g.group_name,g.group_description,g.system_key,gr.role_name,gr.id AS role_id,
                (SELECT COUNT(*) FROM group_members all_members WHERE all_members.group_id=g.id) AS member_count
            FROM group_members gm JOIN study_groups g ON g.id=gm.group_id
            JOIN group_roles gr ON gr.group_id=gm.group_id AND gr.id=gm.role_id
            WHERE gm.user_id=? ORDER BY g.id DESC
            """,uid);
        List<Map<String,Object>> result=new ArrayList<>();
        for(var row:rows) {
            long gid=((Number)row.get("id")).longValue();
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("id",gid); item.put("groupName",row.get("group_name"));
            item.put("description",row.get("group_description")); item.put("roleName",row.get("role_name"));
            item.put("systemGroup",systemGroup(row));
            item.put("permissions",permissionNames(permissions(gid,user)));
            item.put("memberCount",row.get("member_count")); result.add(item);
        }
        return result;
    }
    public Map<String,Object> detail(long gid,CustomUserDetails user) {
        requireMember(gid,user);
        var row=group(gid,false);
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("id",row.get("id")); summary.put("groupName",row.get("group_name"));
        summary.put("description",row.get("group_description")); summary.put("status",row.get("status"));
        summary.put("systemGroup",systemGroup(row));
        summary.put("memberCount",db.queryForObject("SELECT COUNT(*) FROM group_members WHERE group_id=?",Long.class,gid));
        return Map.of("group",summary,"permissions",permissionNames(permissions(gid,user)),
            "roles",roles(gid,user),"members",members(gid,user));
    }
    public List<Map<String,Object>> members(long gid,CustomUserDetails user) {
        requireMember(gid,user);
        var rows=db.queryForList("""
            SELECT gm.user_id,u.username,gm.role_id,gr.role_name,gr.builtin_key,gm.permission
            FROM group_members gm JOIN users u ON u.id=gm.user_id
            JOIN group_roles gr ON gr.group_id=gm.group_id AND gr.id=gm.role_id
            WHERE gm.group_id=? ORDER BY gm.member_number
            """,gid);
        return rows.stream().map(r -> {
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("userId",r.get("user_id")); item.put("username",r.get("username"));
            item.put("roleId",r.get("role_id")); item.put("roleName",r.get("role_name"));
            item.put("owner",owner(r)); return item;
        }).toList();
    }
    public List<Map<String,Object>> roles(long gid,CustomUserDetails user) {
        requireMember(gid,user);
        var rows=db.queryForList("SELECT * FROM group_roles WHERE group_id=? ORDER BY id",gid);
        return rows.stream().map(r -> {
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("id",r.get("id")); item.put("name",r.get("role_name"));
            item.put("builtinKey",r.get("builtin_key"));
            item.put("permissions",permissionNames(permissions(r))); return item;
        }).toList();
    }
    public List<Map<String,Object>> problems(long gid,int page,String search,CustomUserDetails user) {
        requirePermission(gid,user,GroupPermission.VIEW_PROBLEMS);
        boolean staff=can(gid,user,GroupPermission.EDIT_PROBLEMS) ||
            can(gid,user,GroupPermission.REVIEW_PROBLEMS) ||
            can(gid,user,GroupPermission.MANAGE_CONTESTS) ||
            can(gid,user,GroupPermission.VIEW_CONTEST_RESULTS);
        var rows=db.queryForList("""
            SELECT p.problem_number,p.problem_title,p.rank_type,p.rank_int,
                   p.submission_count,p.accepted_count,
                   EXISTS(SELECT 1 FROM contest_problems used WHERE used.problem_id=p.id) AS used_in_contest
            FROM problems p WHERE p.group_id=? AND p.visibility='GROUP' AND p.status='PUBLISHED'
              AND p.problem_title LIKE ?
              AND (?=TRUE OR p.author_id=? OR NOT EXISTS
                  (SELECT 1 FROM contest_problems cp WHERE cp.problem_id=p.id))
            ORDER BY p.problem_number LIMIT 30 OFFSET ?
            """,gid,"%"+Objects.toString(search,"")+"%",staff,user.getUserId(),offset(page));
        return rows.stream().map(r -> Map.<String,Object>of(
            "problemNumber",r.get("problem_number"),"problemTitle",r.get("problem_title"),
            "rankType",r.get("rank_type"),"rankInt",r.get("rank_int"),
            "submissionCount",r.get("submission_count"),"acceptedCount",r.get("accepted_count"),
            "usedInContest",enabled(r.get("used_in_contest")))).toList();
    }
    public List<Map<String,Object>> reviewQueue(long gid,int page,String search,CustomUserDetails user) {
        requireUser(user);
        if(!can(gid,user,GroupPermission.REVIEW_PROBLEMS) &&
            !can(gid,user,GroupPermission.EDIT_PROBLEMS))
            throw new ApiException(HttpStatus.FORBIDDEN,"그룹 문제 관리 권한이 없습니다.");
        var rows=db.queryForList("""
            SELECT p.problem_number,p.problem_title,p.status,p.visibility AS current_visibility,
                   p.author_id,u.username AS author_username,p.updated_at
            FROM problems p JOIN users u ON u.id=p.author_id
            WHERE p.group_id=? AND p.problem_title LIKE ?
            ORDER BY p.updated_at DESC,p.id DESC LIMIT 30 OFFSET ?
            """,gid,"%"+Objects.toString(search,"")+"%",offset(page));
        return rows.stream().map(r -> Map.<String,Object>of(
            "problemNumber",r.get("problem_number"),"problemTitle",r.get("problem_title"),
            "status",r.get("status"),"currentVisibility",r.get("current_visibility"),
            "authorId",r.get("author_id"),
            "authorUsername",r.get("author_username"),"updatedAt",r.get("updated_at"))).toList();
    }

    @Transactional
    public long create(GroupRequests.Write request,CustomUserDetails user) {
        long uid=requireUser(user);
        var users=db.queryForList("SELECT solved_count FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE",uid);
        if(users.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED);
        if(((Number)users.getFirst().get("solved_count")).intValue()<50)
            throw new ApiException(HttpStatus.FORBIDDEN,"그룹 생성에는 해결한 문제 50개가 필요합니다.");
        membershipRoom(uid);
        String groupName=name(request.name());
        try {
            db.update("INSERT INTO study_groups(group_name,group_description,status) VALUES(?,?,'ACCEPTED')",
                groupName,request.description().trim());
        } catch(DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT,"이미 사용 중인 그룹 이름입니다.");
        }
        long gid=db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
        insertBuiltinRoles(gid);
        long ownerId=builtinRoleId(gid,"OWNER");
        db.update("""
            INSERT INTO group_members(user_id,group_id,member_number,permission,role_id)
            VALUES(?,?,1,'OWNER',?)
            """,uid,gid,ownerId);
        return gid;
    }
    private void membershipRoom(long uid) {
        // 모든 계정에 자동 부여되는 내장 그룹은 일반 그룹 세 개의 가입 한도에 포함하지 않습니다.
        long count=db.queryForObject("""
            SELECT COUNT(*) FROM group_members gm JOIN study_groups g ON g.id=gm.group_id
            WHERE gm.user_id=? AND g.system_key IS NULL
            """,Long.class,uid);
        if(count>=MAX_MEMBERSHIPS) throw new ApiException(HttpStatus.CONFLICT,"기본 그룹을 제외한 일반 그룹은 최대 3개입니다.");
    }
    private long builtinRoleId(long gid,String key) {
        return db.queryForObject("SELECT id FROM group_roles WHERE group_id=? AND builtin_key=?",Long.class,gid,key);
    }
    private void insertBuiltinRoles(long gid) {
        db.update("""
            INSERT INTO group_roles(group_id,role_name,builtin_key,can_view_problems,can_create_problems,
              can_edit_problems,can_review_problems,can_manage_members,can_manage_roles,can_manage_group,
              can_create_contests,can_manage_contests,can_view_contest_results,can_take_contests)
            VALUES(?,'소유자','OWNER',1,1,1,1,1,1,1,1,1,1,1)
            """,gid);
        db.update("""
            INSERT INTO group_roles(group_id,role_name,builtin_key,can_view_problems,can_create_problems,
              can_edit_problems,can_review_problems,can_manage_members,can_manage_group,
              can_create_contests,can_manage_contests,can_view_contest_results,can_take_contests)
            VALUES(?,'관리자','MANAGER',1,1,1,1,1,1,1,1,1,1)
            """,gid);
        db.update("""
            INSERT INTO group_roles(group_id,role_name,builtin_key,can_view_problems,can_create_problems,
              can_edit_problems,can_take_contests) VALUES(?,'출제자','AUTHOR',1,1,1,1)
            """,gid);
        db.update("""
            INSERT INTO group_roles(group_id,role_name,builtin_key,can_view_problems,can_create_contests,
              can_manage_contests,can_view_contest_results,can_take_contests)
            VALUES(?,'코테 관리자','CONTEST_MANAGER',1,1,1,1,1)
            """,gid);
        db.update("""
            INSERT INTO group_roles(group_id,role_name,builtin_key,can_view_problems,can_take_contests)
            VALUES(?,'회원','MEMBER',1,1)
            """,gid);
    }
    @Transactional
    public void join(long gid,CustomUserDetails user) {
        long uid=requireUser(user);
        // 생성과 다른 그룹 가입도 같은 사용자 행을 잠가 3그룹 상한을 보장합니다.
        var users=db.queryForList("SELECT id FROM users WHERE id=? AND status='ACTIVE' FOR UPDATE",uid);
        if(users.isEmpty()) throw new ApiException(HttpStatus.UNAUTHORIZED);
        var g=group(gid,true);
        if(!"ACCEPTED".equals(g.get("status"))) throw new ApiException(HttpStatus.CONFLICT,"참여할 수 없는 그룹입니다.");
        if(db.queryForObject("SELECT COUNT(*) FROM group_members WHERE group_id=? AND user_id=?",Long.class,gid,uid)>0) return;
        if(systemGroup(g)) {
            // 정상 계정은 users INSERT 트리거로 이미 가입됩니다. 누락 복구에도 같은 고유 사용자 번호를 사용합니다.
            db.update("INSERT INTO group_members(user_id,group_id,member_number,permission,role_id) VALUES(?,?,?,'MEMBER',?)",
                uid,gid,uid,builtinRoleId(gid,"MEMBER"));
            return;
        }
        membershipRoom(uid);
        long number=db.queryForObject("SELECT COALESCE(MAX(member_number),0)+1 FROM group_members WHERE group_id=?",Long.class,gid);
        db.update("INSERT INTO group_members(user_id,group_id,member_number,permission,role_id) VALUES(?,?,?,'MEMBER',?)",
            uid,gid,number,builtinRoleId(gid,"MEMBER"));
    }
    @Transactional
    public void leave(long gid,CustomUserDetails user) {
        long uid=requireUser(user);
        db.queryForList("SELECT id FROM users WHERE id=? FOR UPDATE",uid);
        if(systemGroup(group(gid,true)))
            throw new ApiException(HttpStatus.CONFLICT,"기본 그룹은 탈퇴할 수 없습니다.");
        var membership=member(gid,uid);
        if(owner(membership)) throw new ApiException(HttpStatus.CONFLICT,"소유자는 그룹을 탈퇴할 수 없습니다.");
        db.update("DELETE FROM group_members WHERE group_id=? AND user_id=?",gid,uid);
    }
    @Transactional
    public void update(long gid,GroupRequests.Write request,CustomUserDetails user) {
        requireUser(user);
        var existing=group(gid,true);
        requirePermission(gid,user,GroupPermission.MANAGE_GROUP);
        if(systemGroup(existing) && !Objects.equals(existing.get("group_name"),name(request.name())))
            throw new ApiException(HttpStatus.CONFLICT,"기본 그룹의 이름은 변경할 수 없습니다.");
        try {
            db.update("UPDATE study_groups SET group_name=?,group_description=? WHERE id=?",
                name(request.name()),request.description().trim(),gid);
        } catch(DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT,"이미 사용 중인 그룹 이름입니다.");
        }
    }
    @Transactional
    public void changeMemberRole(long gid,long targetUid,long roleId,CustomUserDetails user) {
        requireUser(user);
        var group=group(gid,true);
        requirePermission(gid,user,GroupPermission.MANAGE_MEMBERS);
        requireSystemRoleAdministrator(group,user);
        var target=member(gid,targetUid);
        if(owner(target)) throw new ApiException(HttpStatus.CONFLICT,"소유자 역할은 변경할 수 없습니다.");
        var next=role(gid,roleId);
        if("OWNER".equals(next.get("builtin_key")))
            throw new ApiException(HttpStatus.CONFLICT,"소유자 역할은 양도할 수 없습니다.");
        if(!operator(user) && !permissions(gid,user).containsAll(permissions(next)))
            throw new ApiException(HttpStatus.FORBIDDEN,"자신보다 높은 권한의 역할을 지정할 수 없습니다.");
        String legacy="MANAGER".equals(next.get("builtin_key"))?"ADMIN":"MEMBER";
        db.update("UPDATE group_members SET role_id=?,permission=? WHERE group_id=? AND user_id=?",
            roleId,legacy,gid,targetUid);
    }
    @Transactional
    public void removeMember(long gid,long targetUid,CustomUserDetails user) {
        requireUser(user);
        var group=group(gid,true);
        requirePermission(gid,user,GroupPermission.MANAGE_MEMBERS);
        if(systemGroup(group))
            throw new ApiException(HttpStatus.CONFLICT,"기본 그룹의 회원은 추방할 수 없습니다.");
        var target=member(gid,targetUid);
        if(owner(target)) throw new ApiException(HttpStatus.CONFLICT,"소유자는 제거할 수 없습니다.");
        db.update("DELETE FROM group_members WHERE group_id=? AND user_id=?",gid,targetUid);
    }
    @Transactional
    public long createRole(long gid,GroupRequests.RoleWrite request,CustomUserDetails user) {
        requireUser(user);
        var group=group(gid,true);
        requirePermission(gid,user,GroupPermission.MANAGE_ROLES);
        requireSystemRoleAdministrator(group,user);
        requireAssignablePermissions(gid,request,user);
        long count=db.queryForObject("SELECT COUNT(*) FROM group_roles WHERE group_id=?",Long.class,gid);
        if(count>=MAX_ROLES) throw new ApiException(HttpStatus.CONFLICT,"역할은 최대 8개입니다.");
        var values=new ArrayList<Object>(); values.add(gid); values.add(name(request.name()));
        for(var permission:GroupPermission.values()) values.add(request.permissions().contains(permission));
        String columns=Arrays.stream(GroupPermission.values()).map(GroupPermission::column).reduce((a,b)->a+","+b).orElseThrow();
        String placeholders=String.join(",",Collections.nCopies(GroupPermission.values().length,"?"));
        try {
            db.update("INSERT INTO group_roles(group_id,role_name,"+columns+") VALUES(?,?,"+placeholders+")",values.toArray());
        } catch(DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT,"이미 사용 중인 역할 이름입니다.");
        }
        return db.queryForObject("SELECT LAST_INSERT_ID()",Long.class);
    }
    @Transactional
    public void updateRole(long gid,long roleId,GroupRequests.RoleWrite request,CustomUserDetails user) {
        requireUser(user);
        var group=group(gid,true);
        requirePermission(gid,user,GroupPermission.MANAGE_ROLES);
        requireSystemRoleAdministrator(group,user);
        var existing=role(gid,roleId);
        if("OWNER".equals(existing.get("builtin_key")))
            throw new ApiException(HttpStatus.CONFLICT,"소유자 역할은 수정할 수 없습니다.");
        requireAssignablePermissions(gid,request,user);
        var args=new ArrayList<Object>(); args.add(name(request.name()));
        var updates=new ArrayList<String>(); updates.add("role_name=?");
        for(var permission:GroupPermission.values()) {
            updates.add(permission.column()+"=?"); args.add(request.permissions().contains(permission));
        }
        args.add(gid); args.add(roleId);
        try {
            db.update("UPDATE group_roles SET "+String.join(",",updates)+" WHERE group_id=? AND id=?",args.toArray());
        } catch(DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT,"이미 사용 중인 역할 이름입니다.");
        }
    }
    private void requireAssignablePermissions(long gid,GroupRequests.RoleWrite request,CustomUserDetails user) {
        if(request==null || request.permissions()==null)
            throw new ApiException(HttpStatus.BAD_REQUEST,"역할 권한을 확인해주세요.");
        if(!operator(user) && !permissions(gid,user).containsAll(request.permissions()))
            throw new ApiException(HttpStatus.FORBIDDEN,"자신보다 높은 권한을 역할에 부여할 수 없습니다.");
    }
    @Transactional
    public void deleteRole(long gid,long roleId,CustomUserDetails user) {
        requireUser(user);
        var group=group(gid,true);
        requirePermission(gid,user,GroupPermission.MANAGE_ROLES);
        requireSystemRoleAdministrator(group,user);
        var existing=role(gid,roleId);
        if(existing.get("builtin_key")!=null && BUILTIN_KEYS.contains(existing.get("builtin_key")))
            throw new ApiException(HttpStatus.CONFLICT,"기본 역할은 삭제할 수 없습니다.");
        long assigned=db.queryForObject("SELECT COUNT(*) FROM group_members WHERE group_id=? AND role_id=?",Long.class,gid,roleId);
        if(assigned>0) throw new ApiException(HttpStatus.CONFLICT,"사용 중인 역할은 삭제할 수 없습니다.");
        db.update("DELETE FROM group_roles WHERE group_id=? AND id=?",gid,roleId);
    }
}
