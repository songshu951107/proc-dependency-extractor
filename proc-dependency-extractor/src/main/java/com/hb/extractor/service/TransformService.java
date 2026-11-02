package com.hb.extractor.service;

import com.hb.extractor.dto.TransformScriptVO;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 脚本转换产物服务（目录约定见规格 §4；转换/删除由网页触发，调用离线引擎） */
public interface TransformService {
    List<TransformScriptVO> listScripts();
    Map<String, Object> getReport(String name);
    String readFile(String name, String ver);   // ver: source | converted
    Path upload(MultipartFile file);            // 存 input/，同名覆盖
    Path resolveDownload(String name, String type); // type: converted|md|html
    void convert(String name);                  // 调 Python 引擎转换单脚本（同步）
    void delete(String name);                   // 删除 input 源脚本与 output 产物
}
