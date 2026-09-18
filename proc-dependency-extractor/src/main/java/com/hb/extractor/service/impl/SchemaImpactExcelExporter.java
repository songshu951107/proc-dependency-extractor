package com.hb.extractor.service.impl;

import com.hb.extractor.dto.AffectedTableVO;
import com.hb.extractor.dto.ReportImpactVO;
import com.hb.extractor.dto.SchemaImpactRowVO;
import com.hb.extractor.dto.SchemaImpactSummaryVO;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;

/**
 * 批量 schema 下游影响结果导出为 5-sheet Excel。
 * Sheet1 汇总矩阵 / Sheet2 受影响表明细 / Sheet3 受影响存储过程明细 / Sheet4 受影响报表明细 / Sheet5 起点表被引用报表明细。
 */
@Component
public class SchemaImpactExcelExporter {

    public void export(SchemaImpactSummaryVO summary, OutputStream out) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            XSSFSheet s1 = wb.createSheet("汇总矩阵");
            writeHeader(s1, new String[]{"表名", "被影响表数", "受影响存储过程数", "涉及报表数", "起点表被引用报表数"});
            for (SchemaImpactRowVO row : summary.getRows()) {
                Row r = s1.createRow(s1.getLastRowNum() + 1);
                r.createCell(0).setCellValue(row.getTable());
                r.createCell(1).setCellValue(orZero(row.getAffectedTableCount()));
                r.createCell(2).setCellValue(orZero(row.getAffectedProcCount()));
                r.createCell(3).setCellValue(orZero(row.getAffectedReportCount()));
                r.createCell(4).setCellValue(orZero(row.getSelfReportCount()));
            }

            XSSFSheet s2 = wb.createSheet("受影响表明细");
            writeHeader(s2, new String[]{"起点表", "受影响表", "到达的proc"});
            for (SchemaImpactRowVO row : summary.getRows()) {
                for (AffectedTableVO a : row.getAffectedTables()) {
                    Row r = s2.createRow(s2.getLastRowNum() + 1);
                    r.createCell(0).setCellValue(row.getTable());
                    r.createCell(1).setCellValue(a.getName());
                    r.createCell(2).setCellValue(a.getProcName() == null ? "" : a.getProcName());
                }
            }

            XSSFSheet s3 = wb.createSheet("受影响存储过程明细");
            writeHeader(s3, new String[]{"起点表", "受影响存储过程"});
            for (SchemaImpactRowVO row : summary.getRows()) {
                for (String p : row.getAffectedProcs()) {
                    Row r = s3.createRow(s3.getLastRowNum() + 1);
                    r.createCell(0).setCellValue(row.getTable());
                    r.createCell(1).setCellValue(p);
                }
            }

            XSSFSheet s4 = wb.createSheet("受影响报表明细");
            writeHeader(s4, new String[]{"起点表", "报表文件", "报表名称", "数据集", "连接"});
            for (SchemaImpactRowVO row : summary.getRows()) {
                for (ReportImpactVO.ReportRef ref : row.getAffectedReports()) {
                    Row r = s4.createRow(s4.getLastRowNum() + 1);
                    r.createCell(0).setCellValue(row.getTable());
                    r.createCell(1).setCellValue(ref.getFile());
                    r.createCell(2).setCellValue(ref.getName() == null ? "" : ref.getName());
                    r.createCell(3).setCellValue(ref.getDatasetName() == null ? "" : ref.getDatasetName());
                    r.createCell(4).setCellValue(ref.getConnection() == null ? "" : ref.getConnection());
                }
            }

            XSSFSheet s5 = wb.createSheet("起点表被引用报表明细");
            writeHeader(s5, new String[]{"起点表", "报表文件", "报表名称", "数据集", "连接"});
            for (SchemaImpactRowVO row : summary.getRows()) {
                for (ReportImpactVO.ReportRef ref : row.getSelfReports()) {
                    Row r = s5.createRow(s5.getLastRowNum() + 1);
                    r.createCell(0).setCellValue(row.getTable());
                    r.createCell(1).setCellValue(ref.getFile());
                    r.createCell(2).setCellValue(ref.getName() == null ? "" : ref.getName());
                    r.createCell(3).setCellValue(ref.getDatasetName() == null ? "" : ref.getDatasetName());
                    r.createCell(4).setCellValue(ref.getConnection() == null ? "" : ref.getConnection());
                }
            }

            wb.write(out);
        }
    }

    private int orZero(Integer v) {
        return v == null ? 0 : v;
    }

    private void writeHeader(XSSFSheet sheet, String[] head) {
        Row r = sheet.createRow(0);
        for (int i = 0; i < head.length; i++) r.createCell(i).setCellValue(head[i]);
    }
}