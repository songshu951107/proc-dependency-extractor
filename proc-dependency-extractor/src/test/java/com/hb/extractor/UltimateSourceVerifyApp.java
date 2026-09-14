package com.hb.extractor;

import com.hb.extractor.dto.FieldMappingVO;
import com.hb.extractor.entity.ProcInfo;
import com.hb.extractor.entity.TableFieldMapping;
import com.hb.extractor.entity.TableLineage;
import com.hb.extractor.mapper.ProcInfoMapper;
import com.hb.extractor.mapper.TableFieldMappingMapper;
import com.hb.extractor.mapper.TableLineageMapper;
import com.hb.extractor.service.LineageService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import java.time.LocalDateTime;

/**
 * 场景3端到端验证：临时表多层链 + DECODE 多字段表达式，验证最终来源是否卡在临时表。
 * 直接启动 Spring 上下文，构造测试数据后调用 LineageService.getFieldMapping。
 *
 * 注意：不使用 @SpringBootApplication，避免与 ProcExtractorApplication 冲突导致
 * @SpringBootTest 找到多个 @SpringBootConfiguration。改用 @Component + @Profile("verify")，
 * 由主程序的 @SpringBootApplication(scanBasePackages = "com.hb.extractor") 扫描到 @Bean。
 * 仅在 verify profile 激活时加载（main 启动时通过 --spring.profiles.active=verify 激活），
 * 避免 @SpringBootTest 启动时 CommandLineRunner 自动执行污染数据库。
 */
@Component
@Profile("verify")
public class UltimateSourceVerifyApp {

    public static void main(String[] args) {
        SpringApplication.run(ProcExtractorApplication.class, args).close();
    }

    @Bean
    CommandLineRunner run(LineageService lineageService,
                          ProcInfoMapper procInfoMapper,
                          TableLineageMapper tableLineageMapper,
                          TableFieldMappingMapper tableFieldMappingMapper) {
        return args -> {
            String procName = "test_ultimate_trace_proc";
            // 清理残留
            tableFieldMappingMapper.delete(new LambdaQueryWrapper<TableFieldMapping>()
                    .eq(TableFieldMapping::getProcName, procName));
            tableLineageMapper.delete(new LambdaQueryWrapper<TableLineage>()
                    .eq(TableLineage::getProcName, procName));

            // 建存储过程
            ProcInfo p = new ProcInfo();
            p.setProcName(procName);
            p.setProcFileName(procName + ".py");
            p.setFilePath("/tmp/" + procName + ".py");
            p.setUpdateTarget("CDM.TARGET_TABLE");
            p.setTableCount(1);
            p.setTempTableCount(2);
            p.setExtractTime(LocalDateTime.now());
            procInfoMapper.insert(p);
            Long procId = p.getId();

            // 链路：F_A → VT_C → VT_B → TARGET_TABLE
            Long e1 = mkEdge(tableLineageMapper, procId, procName, "F_A", "FDM.F_A", "VT_C", "CDM.VT_C", 1, 1, "CREATE TEMP");
            Long e2 = mkEdge(tableLineageMapper, procId, procName, "VT_C", "CDM.VT_C", "VT_B", "CDM.VT_B", 1, 2, "CREATE TEMP");
            Long e3 = mkEdge(tableLineageMapper, procId, procName, "VT_B", "CDM.VT_B", "TARGET_TABLE", "CDM.TARGET_TABLE", 0, 3, "INSERT SELECT");

            // VT_C.M_AVG ← F_A.m_avg（最终来源应是 F_A.m_avg）
            mkMap(tableFieldMappingMapper, e1, procName, "M_AVG", 1, "T01.m_avg", "T01", "F_A");
            // VT_B.kh_mavg ← DECODE(T7.M_AVG, T8.B, 0)：多字段表达式，真实依赖 VT_C.M_AVG
            mkMap(tableFieldMappingMapper, e2, procName, "kh_mavg", 1, "DECODE(T7.M_AVG, T8.B, 0)", "T7", "VT_C");
            // TARGET_TABLE.out_field ← VT_B.kh_mavg
            mkMap(tableFieldMappingMapper, e3, procName, "out_field", 1, "T9.kh_mavg", "T9", "VT_B");

            FieldMappingVO vo = lineageService.getFieldMapping(procName, "TARGET_TABLE", true);
            FieldMappingVO.FieldItem item = vo.getFields().get(0);

            System.out.println("=== 端到端溯源结果 ===");
            System.out.println("targetField      = " + item.getTargetField());
            System.out.println("direct source    = " + item.getDirectMappings().get(0).getSourceTable()
                    + " / " + item.getDirectMappings().get(0).getSourceExpr());
            FieldMappingVO.UltimateSource us = item.getUltimateSource();
            if (us == null) {
                System.out.println("ultimateSource   = null（未溯源到）");
            } else {
                System.out.println("ultimateSource.sourceTable     = " + us.getSourceTable());
                System.out.println("ultimateSource.sourceTableType = " + us.getSourceTableType());
                System.out.println("ultimateSource.sourceField     = " + us.getSourceField());
                System.out.println("ultimateSource.path            = " + us.getPath());
            }

            boolean bug = us != null && "TEMP".equals(us.getSourceTableType());
            System.out.println();
            System.out.println(bug
                    ? "[复现BUG] 最终来源仍是临时表 " + us.getSourceTable() + "（应为 F_A）"
                    : "[正常] 最终来源 = " + (us == null ? "null" : us.getSourceTable()));

            // 清理
            tableFieldMappingMapper.delete(new LambdaQueryWrapper<TableFieldMapping>()
                    .eq(TableFieldMapping::getProcName, procName));
            tableLineageMapper.delete(new LambdaQueryWrapper<TableLineage>()
                    .eq(TableLineage::getProcName, procName));
            procInfoMapper.deleteById(procId);
        };
    }

    private Long mkEdge(TableLineageMapper m, Long procId, String procName,
                        String sShort, String sFull, String tShort, String tFull,
                        int isTemp, int seq, String op) {
        TableLineage e = new TableLineage();
        e.setProcId(procId);
        e.setProcName(procName);
        e.setSourceTable(sFull);
        e.setSourceTableShort(sShort);
        e.setTargetTable(tFull);
        e.setTargetTableShort(tShort);
        e.setTargetIsTemp(isTemp);
        e.setOperation(op);
        e.setSqlBlockSeq(seq);
        e.setExtractTime(LocalDateTime.now());
        m.insert(e);
        return e.getId();
    }

    private void mkMap(TableFieldMappingMapper m, Long lineageId, String procName,
                       String targetField, int order, String sourceExpr, String alias, String srcTableShort) {
        TableFieldMapping fm = new TableFieldMapping();
        fm.setLineageId(lineageId);
        fm.setProcName(procName);
        fm.setTargetField(targetField);
        fm.setFieldOrder(order);
        fm.setSourceExpr(sourceExpr);
        fm.setSourceAlias(alias);
        fm.setSourceTableShort(srcTableShort);
        fm.setExtractTime(LocalDateTime.now());
        m.insert(fm);
    }
}
