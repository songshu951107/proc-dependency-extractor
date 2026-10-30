package com.hb.extractor.service;

import com.hb.extractor.dto.TransformScriptVO;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 脚本转换产物只读服务（目录约定见规格 §4） */
public interface TransformService {
    List<TransformScriptVO> listScripts();
    Map<String, Object> getReport(String name);
    String readFile(String name, String ver);   // ver: source | converted
    Path upload(MultipartFile file);            // 存 input/，同名覆盖
    Path resolveDownload(String name, String type); // type: converted|md|html
}
