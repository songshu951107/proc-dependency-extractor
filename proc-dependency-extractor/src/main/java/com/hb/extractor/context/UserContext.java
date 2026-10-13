package com.hb.extractor.context;

import lombok.Data;

public class UserContext {

    @Data
    public static class CurrentUser {
        private Long userId;
        private String username;
        private String roleCode;
    }

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    public static void set(CurrentUser user) { HOLDER.set(user); }
    public static CurrentUser get() { return HOLDER.get(); }
    public static void clear() { HOLDER.remove(); }
    public static boolean isAdmin() {
        CurrentUser u = HOLDER.get();
        return u != null && "ADMIN".equals(u.getRoleCode());
    }
}