package com.hb.extractor.service;

import com.hb.extractor.dto.FieldMappingVO;
import com.hb.extractor.entity.ProcInfo;
import com.hb.extractor.entity.TableFieldMapping;
import com.hb.extractor.entity.TableLineage;
import com.hb.extractor.mapper.ProcInfoMapper;
import com.hb.extractor.mapper.TableFieldMappingMapper;
import com.hb.extractor.mapper.TableLineageMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证场景3：字段名匹配不上导致"最终来源表仍为临时表"。
 *
 * 构造多层临时表链 F_A → VT_C → VT_B → TARGET_TABLE，关键在 VT_B→VT_C 段：
 *   VT_C 的字段 M_AVG 来自 F_A；
 *   VT_B 引用 VT_C 时使用多字段表达式 DECODE(T7.M_AVG, T8.B, 0)。
 *
 * 现状：LineageServiceImpl.extractFieldFromExpr 仍用正则取"最后一个 alias.field"，
 *      对 DECODE(T7.M_AVG, T8.B, 0) 会取 T8.B 而非 T7.M_AVG，导致递归进 VT_C 时
 *      用 "b" 匹配 target_field=M_AVG 失败，最终兜底返回临时表 VT_C 作为最终来源。
 *
 * 该 bug 与 JsqlParser 升级无关（LineageServiceImpl 溯源链路仍用正则），
 * 待后续用 AST 重构 extractFieldFromExpr 时一并修复。
 */
@SpringBootTest
@Disabled("已知 bug：extractFieldFromExpr 正则取末位字段，DECODE 多字段表达式溯源失败；待 AST 重构溯源链路时修复")
class UltimateSourceTraceTest {

    @Autowired
    private LineageService lineageService;
    @Autowired
    private ProcInfoMapper procInfoMapper;
    @Autowired
    private TableLineageMapper tableLineageMapper;
    @Autowired
    private TableFieldMappingMapper tableFieldMappingMapper;

    private Long procId;
    private final String procName = "test_ultimate_trace_proc";

    @AfterEach
    void cleanup() {
        tableFieldMappingMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TableFieldMapping>()
                .eq(TableFieldMapping::getProcName, procName));
        tableLineageMapper.delete(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<TableLineage>()
                .eq(TableLineage::getProcName, procName));
        if (procId != null) {
            procInfoMapper.deleteById(procId);
            procId = null;
        }
    }

    private void createProc() {
        ProcInfo p = new ProcInfo();
        p.setProcName(procName);
        p.setProcFileName(procName + ".py");
        p.setFilePath("/tmp/" + procName + ".py");
        p.setUpdateTarget("CDM.TARGET_TABLE");
        p.setTableCount(1);
        p.setTempTableCount(2);
        p.setExtractTime(LocalDateTime.now());
        procInfoMapper.insert(p);
        procId = p.getId();
    }

    private Long createEdge(String sourceShort, String sourceFull,
                            String targetShort, String targetFull,
                            int isTemp, int seq, String operation) {
        TableLineage edge = new TableLineage();
        edge.setProcId(procId);
        edge.setProcName(procName);
        edge.setSourceTable(sourceFull);
        edge.setSourceTableShort(sourceShort);
        edge.setTargetTable(targetFull);
        edge.setTargetTableShort(targetShort);
        edge.setTargetIsTemp(isTemp);
        edge.setOperation(operation);
        edge.setSqlBlockSeq(seq);
        edge.setExtractTime(LocalDateTime.now());
        tableLineageMapper.insert(edge);
        return edge.getId();
    }

    private void createMapping(Long lineageId, String targetField, int order,
                               String sourceExpr, String alias, String sourceTableShort) {
        TableFieldMapping m = new TableFieldMapping();
        m.setLineageId(lineageId);
        m.setProcName(procName);
        m.setTargetField(targetField);
        m.setFieldOrder(order);
        m.setSourceExpr(sourceExpr);
        m.setSourceAlias(alias);
        m.setSourceTableShort(sourceTableShort);
        m.setExtractTime(LocalDateTime.now());
        tableFieldMappingMapper.insert(m);
    }

    @Test
    void testDecodeMultiFieldExprBreaksTrace() {
        createProc();
        // 链路：F_A → VT_C → VT_B → TARGET_TABLE
        Long e1 = createEdge("F_A", "FDM.F_A", "VT_C", "CDM.VT_C", 1, 1, "CREATE TEMP");
        Long e2 = createEdge("VT_C", "CDM.VT_C", "VT_B", "CDM.VT_B", 1, 2, "CREATE TEMP");
        Long e3 = createEdge("VT_B", "CDM.VT_B", "TARGET_TABLE", "CDM.TARGET_TABLE", 0, 3, "INSERT SELECT");

        // VT_C.M_AVG ← F_A.m_avg（最终来源应是 F_A）
        createMapping(e1, "M_AVG", 1, "T01.m_avg", "T01", "F_A");
        // VT_B.kh_mavg ← DECODE(T7.M_AVG, T8.B, 0)：多字段表达式，真实依赖 VT_C.M_AVG
        createMapping(e2, "kh_mavg", 1, "DECODE(T7.M_AVG, T8.B, 0)", "T7", "VT_C");
        // TARGET_TABLE.out_field ← VT_B.kh_mavg
        createMapping(e3, "out_field", 1, "T9.kh_mavg", "T9", "VT_B");

        FieldMappingVO vo = lineageService.getFieldMapping(procName, "TARGET_TABLE", true);
        assertNotNull(vo);
        assertEquals(1, vo.getFields().size());

        FieldMappingVO.FieldItem item = vo.getFields().get(0);
        assertNotNull(item.getUltimateSource(), "应能溯源到最终来源");

        System.out.println("=== 溯源结果 ===");
        System.out.println("ultimateSource.sourceTable = " + item.getUltimateSource().getSourceTable());
        System.out.println("ultimateSource.sourceTableType = " + item.getUltimateSource().getSourceTableType());
        System.out.println("ultimateSource.sourceField = " + item.getUltimateSource().getSourceField());
        System.out.println("path = " + item.getUltimateSource().getPath());

        // 期望：最终来源应为 F_A.m_avg（非临时表）
        assertEquals("F_A", item.getUltimateSource().getSourceTable(),
                "最终来源表应为 F_A，而非卡在临时表 VT_C/VT_B");
        assertEquals("SOURCE", item.getUltimateSource().getSourceTableType(),
                "最终来源类型应为 SOURCE");
        assertEquals("m_avg", item.getUltimateSource().getSourceField());
    }
}
