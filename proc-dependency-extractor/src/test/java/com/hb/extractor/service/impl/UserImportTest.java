package com.hb.extractor.service.impl;

import com.hb.extractor.dto.ImportResultDTO;
import com.hb.extractor.dto.UserSaveRequest;
import com.hb.extractor.entity.SysUser;
import com.hb.extractor.mapper.SysUserMapper;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
class UserImportTest {

    @Autowired UserServiceImpl userService;

    @Test
    void skipsDuplicateUsername() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (Workbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet();
            Row header = sheet.createRow(0);
            String[] cols = {"用户名","姓名","角色","部门","邮箱","手机","状态"};
            for (int i = 0; i < cols.length; i++) header.createCell(i).setCellValue(cols[i]);
            // 第一行：与内置 admin 重复 -> 跳过
            Row r1 = sheet.createRow(1);
            r1.createCell(0).setCellValue("admin");
            r1.createCell(1).setCellValue("重复");
            r1.createCell(2).setCellValue("普通用户");
            // 第二行：新用户 -> 成功
            Row r2 = sheet.createRow(2);
            r2.createCell(0).setCellValue("newuser01");
            r2.createCell(1).setCellValue("新用户");
            r2.createCell(2).setCellValue("普通用户");
            wb.write(bos);
        }
        MockMultipartFile file = new MockMultipartFile("file", "u.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bos.toByteArray());

        ImportResultDTO result = userService.importExcel(file);

        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getSkipCount());
        assertTrue(result.getSkipReasons().get(0).contains("admin"));
    }
}