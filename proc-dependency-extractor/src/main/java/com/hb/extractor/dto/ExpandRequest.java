package com.hb.extractor.dto;

import lombok.Data;

import java.util.List;

@Data
public class ExpandRequest {
    private String nodeId;
    private String direction;   // UPSTREAM / DOWNSTREAM
    private Integer depth = 1;
    private List<String> operations;
}
