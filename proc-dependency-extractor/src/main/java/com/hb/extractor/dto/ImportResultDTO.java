package com.hb.extractor.dto;
import lombok.Data; import java.util.ArrayList; import java.util.List;
@Data public class ImportResultDTO {
    private int successCount;
    private int skipCount;
    private List<String> skipReasons = new ArrayList<>();
}