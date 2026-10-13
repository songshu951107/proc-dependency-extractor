package com.hb.extractor.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AnalyticsMapper {

    @Select("SELECT COUNT(*) FROM sys_login_log WHERE success=1 AND DATE(login_time)=CURDATE()")
    long todayLoginCount();

    @Select("SELECT COUNT(DISTINCT user_id) FROM sys_login_log WHERE success=1 AND DATE(login_time)=CURDATE()")
    long todayActiveUsers();

    @Select("SELECT COUNT(*) FROM sys_page_log WHERE DATE(visit_time)=CURDATE()")
    long todayPageViews();

    @Select("SELECT COUNT(*) FROM sys_login_log WHERE success=0 AND DATE(login_time)=CURDATE()")
    long todayLoginFail();

    @Select("SELECT DATE(login_time) d, COUNT(*) c FROM sys_login_log " +
            "WHERE success=1 AND login_time >= DATE_SUB(CURDATE(), INTERVAL 29 DAY) " +
            "GROUP BY DATE(login_time) ORDER BY d")
    List<Map<String, Object>> loginTrend30d();

    @Select("SELECT page_name name, COUNT(*) c FROM sys_page_log " +
            "WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL 29 DAY) " +
            "GROUP BY page_name ORDER BY c DESC LIMIT 10")
    List<Map<String, Object>> pageTop();

    @Select("SELECT u.username, u.real_name realName, " +
            "(SELECT COUNT(*) FROM sys_login_log l WHERE l.user_id=u.id AND l.success=1) loginCount, " +
            "(SELECT MAX(login_time) FROM sys_login_log l WHERE l.user_id=u.id AND l.success=1) lastLogin, " +
            "(SELECT COUNT(*) FROM sys_page_log p WHERE p.user_id=u.id) pageViews " +
            "FROM sys_user u ORDER BY loginCount DESC")
    List<Map<String, Object>> userActivity();
}