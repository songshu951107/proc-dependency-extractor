package com.hb.extractor.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface AnalyticsMapper {

    @Select("SELECT COUNT(*) FROM sys_login_log WHERE success=1 " +
            "AND login_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)")
    long loginCount(@Param("daysOff") int daysOff);

    @Select("SELECT COUNT(*) FROM sys_page_log " +
            "WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)")
    long pageViews(@Param("daysOff") int daysOff);

    @Select("SELECT COUNT(DISTINCT uid) FROM (" +
            "  SELECT user_id uid FROM sys_login_log WHERE success=1 AND login_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)" +
            "  UNION" +
            "  SELECT user_id uid FROM sys_page_log WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)" +
            ") t")
    long activeUsers(@Param("daysOff") int daysOff);

    @Select("SELECT day d, SUM(succ) loginSucc, COUNT(DISTINCT uid) dau FROM (" +
            "  SELECT DATE(login_time) day, user_id uid, 1 succ FROM sys_login_log " +
            "    WHERE success=1 AND login_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)" +
            "  UNION ALL" +
            "  SELECT DATE(visit_time) day, user_id uid, 0 succ FROM sys_page_log " +
            "    WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)" +
            ") t GROUP BY day ORDER BY day")
    List<Map<String, Object>> trend(@Param("daysOff") int daysOff);

    @Select("SELECT h hour, COUNT(*) c FROM (" +
            "  SELECT HOUR(login_time) h FROM sys_login_log " +
            "    WHERE success=1 AND login_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)" +
            "  UNION ALL" +
            "  SELECT HOUR(visit_time) h FROM sys_page_log " +
            "    WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY)" +
            ") t GROUP BY h ORDER BY h")
    List<Map<String, Object>> hourDistribution(@Param("daysOff") int daysOff);

    @Select("SELECT page_name name, COUNT(*) c FROM sys_page_log " +
            "WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY) " +
            "GROUP BY page_name ORDER BY c DESC LIMIT 10")
    List<Map<String, Object>> pageTop(@Param("daysOff") int daysOff);

    @Select("SELECT page_name name, COUNT(*) c FROM sys_page_log " +
            "WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY) " +
            "GROUP BY page_name ORDER BY c DESC LIMIT #{limit}")
    List<Map<String, Object>> topPages(@Param("daysOff") int daysOff, @Param("limit") int limit);

    @Select("SELECT page_name pageName, DATE(visit_time) d, COUNT(*) c FROM sys_page_log " +
            "WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY) " +
            "AND page_name IN (SELECT page_name FROM (" +
            "  SELECT page_name FROM sys_page_log WHERE visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY) " +
            "  GROUP BY page_name ORDER BY COUNT(*) DESC LIMIT #{limit}) x) " +
            "GROUP BY page_name, DATE(visit_time) ORDER BY d")
    List<Map<String, Object>> pageTrend(@Param("daysOff") int daysOff, @Param("limit") int limit);

    // 每个用户仅一次聚合（LEFT JOIN 派生表），而非逐用户 4 个相关子查询；
    // 语义保持不变：loginCount/pageViews 限窗口内，lastActive 取全时段最近活跃
    @Select("SELECT u.id, u.username, u.real_name realName, " +
            "IFNULL(lg.c, 0) loginCount, IFNULL(pv.c, 0) pageViews, " +
            "GREATEST(IFNULL(lg.lastLogin, '1970-01-01'), IFNULL(pv.lastVisit, '1970-01-01')) lastActive " +
            "FROM sys_user u " +
            "LEFT JOIN (" +
            "  SELECT user_id, " +
            "    SUM(CASE WHEN login_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY) THEN 1 ELSE 0 END) c, " +
            "    MAX(login_time) lastLogin " +
            "  FROM sys_login_log WHERE success=1 GROUP BY user_id" +
            ") lg ON lg.user_id = u.id " +
            "LEFT JOIN (" +
            "  SELECT user_id, " +
            "    SUM(CASE WHEN visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY) THEN 1 ELSE 0 END) c, " +
            "    MAX(visit_time) lastVisit " +
            "  FROM sys_page_log GROUP BY user_id" +
            ") pv ON pv.user_id = u.id " +
            "WHERE u.username LIKE CONCAT('%', IFNULL(#{keyword},''), '%') " +
            "   OR u.real_name LIKE CONCAT('%', IFNULL(#{keyword},''), '%') " +
            "ORDER BY pageViews DESC")
    List<Map<String, Object>> userActivity(@Param("daysOff") int daysOff, @Param("keyword") String keyword);

    @Select("SELECT u.username, u.real_name realName, SUM(lg) loginCount, SUM(pv) pageViews FROM (" +
            "  SELECT user_id, 1 lg, 0 pv FROM sys_login_log WHERE success=1 AND DATE(login_time)=#{date}" +
            "  UNION ALL" +
            "  SELECT user_id, 0 lg, 1 pv FROM sys_page_log WHERE DATE(visit_time)=#{date}" +
            ") t JOIN sys_user u ON u.id=t.user_id " +
            "GROUP BY u.id, u.username, u.real_name ORDER BY pageViews DESC")
    List<Map<String, Object>> dayActiveUsers(@Param("date") String date);

    @Select("SELECT page_name name, COUNT(*) c FROM sys_page_log " +
            "WHERE DATE(visit_time)=#{date} GROUP BY page_name ORDER BY c DESC LIMIT 10")
    List<Map<String, Object>> dayPageTop(@Param("date") String date);

    @Select("SELECT u.username, u.real_name realName, COUNT(p.id) visits, MAX(p.visit_time) lastVisit " +
            "FROM sys_page_log p JOIN sys_user u ON u.id=p.user_id " +
            "WHERE p.page_name=#{pageName} AND p.visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY) " +
            "GROUP BY u.id, u.username, u.real_name ORDER BY visits DESC")
    List<Map<String, Object>> pageUsers(@Param("pageName") String pageName, @Param("daysOff") int daysOff);

    @Select("SELECT '登录' type, l.login_time time, '登录成功' detail FROM sys_login_log l " +
            "  WHERE l.user_id=#{userId} AND l.success=1 AND l.login_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY) " +
            "UNION ALL " +
            "SELECT '页面访问' type, p.visit_time time, p.page_name detail FROM sys_page_log p " +
            "  WHERE p.user_id=#{userId} AND p.visit_time >= DATE_SUB(CURDATE(), INTERVAL #{daysOff} DAY) " +
            "ORDER BY time DESC LIMIT 200")
    List<Map<String, Object>> userEvents(@Param("userId") Long userId, @Param("daysOff") int daysOff);
}
