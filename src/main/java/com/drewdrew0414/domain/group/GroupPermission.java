package com.drewdrew0414.domain.group;

/** 그룹 역할에 부여할 수 있는 기능 권한입니다. */
public enum GroupPermission {
    VIEW_PROBLEMS("can_view_problems"),
    CREATE_PROBLEMS("can_create_problems"),
    EDIT_PROBLEMS("can_edit_problems"),
    REVIEW_PROBLEMS("can_review_problems"),
    MANAGE_MEMBERS("can_manage_members"),
    MANAGE_ROLES("can_manage_roles"),
    MANAGE_GROUP("can_manage_group"),
    CREATE_CONTESTS("can_create_contests"),
    MANAGE_CONTESTS("can_manage_contests"),
    VIEW_CONTEST_RESULTS("can_view_contest_results"),
    TAKE_CONTESTS("can_take_contests");

    private final String column;
    GroupPermission(String column) { this.column=column; }
    String column() { return column; }
}
