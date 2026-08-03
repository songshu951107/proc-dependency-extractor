package com.hb.extractor.parser;

import com.hb.extractor.parser.dto.CellBinding;
import com.hb.extractor.parser.dto.DatasetInfo;
import com.hb.extractor.parser.dto.ReportMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;

/**
 * 帆软报表 XML 解析器
 * 解析 .cpt / .frm / .xml 文件，提取数据集和单元格绑定
 */
@Slf4j
@Component
public class CptXmlParser {

    private final DocumentBuilderFactory docFactory = createFactory();

    private DocumentBuilderFactory createFactory() {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        try {
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        } catch (Exception ignored) {
        }
        return f;
    }

    /**
     * 解析报表 XML
     * @param xml XML 文本
     * @param fileName 文件名（如 公司类存款明细表.cpt）
     */
    public ReportMetadata parse(String xml, String fileName) {
        ReportMetadata metadata = new ReportMetadata();
        metadata.setName(extractName(fileName));
        metadata.setType(extractType(fileName));

        try {
            DocumentBuilder builder = docFactory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));

            parseDatasets(doc, metadata);
            parseCellBindings(doc, metadata);

        } catch (Exception e) {
            log.error("解析报表 XML 失败 file={}: {}", fileName, e.getMessage());
            throw new RuntimeException("XML 解析失败: " + e.getMessage(), e);
        }

        return metadata;
    }

    private String extractName(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx > 0 ? fileName.substring(0, idx) : fileName;
    }

    private String extractType(String fileName) {
        int idx = fileName.lastIndexOf('.');
        return idx > 0 ? fileName.substring(idx + 1).toLowerCase() : "unknown";
    }

    private void parseDatasets(Document doc, ReportMetadata metadata) {
        NodeList tableDataList = doc.getElementsByTagName("TableData");
        for (int i = 0; i < tableDataList.getLength(); i++) {
            Element tableData = (Element) tableDataList.item(i);
            // 跳过无 name 属性的 TableData（如 Dictionary 中的 NameTableData 引用）
            String name = tableData.getAttribute("name");
            if (name == null || name.isEmpty()) continue;
            DatasetInfo ds = new DatasetInfo();
            ds.setName(name);

            // 提取数据源连接名
            NodeList connNodes = tableData.getElementsByTagName("Connection");
            if (connNodes.getLength() > 0) {
                Element conn = (Element) connNodes.item(0);
                NodeList dbNames = conn.getElementsByTagName("DatabaseName");
                if (dbNames.getLength() > 0) {
                    ds.setConnection(dbNames.item(0).getTextContent().trim());
                }
            }

            // 提取 SQL
            NodeList queryNodes = tableData.getElementsByTagName("Query");
            if (queryNodes.getLength() > 0) {
                ds.setSql(queryNodes.item(0).getTextContent().trim());
            }

            metadata.getDatasets().add(ds);
        }
    }

    private void parseCellBindings(Document doc, ReportMetadata metadata) {
        NodeList cellList = doc.getElementsByTagName("C");
        for (int i = 0; i < cellList.getLength(); i++) {
            Element cell = (Element) cellList.item(i);
            String cAttr = cell.getAttribute("c");
            String rAttr = cell.getAttribute("r");
            if (cAttr.isEmpty() || rAttr.isEmpty()) continue;

            // 查找 DSColumn 子节点
            Element oElement = findChildElement(cell, "O");
            if (oElement == null || !"DSColumn".equals(oElement.getAttribute("t"))) continue;

            Element attributes = findChildElement(oElement, "Attributes");
            if (attributes == null) continue;

            CellBinding binding = new CellBinding();
            binding.setCellPos(toCellPos(Integer.parseInt(cAttr), Integer.parseInt(rAttr)));
            binding.setDsName(attributes.getAttribute("dsName"));
            binding.setColumnName(attributes.getAttribute("columnName"));

            if (binding.getDsName() != null && !binding.getDsName().isEmpty()
                    && binding.getColumnName() != null && !binding.getColumnName().isEmpty()) {
                metadata.getCellBindings().add(binding);
            }
        }
    }

    private Element findChildElement(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node.getNodeType() == Node.ELEMENT_NODE && tagName.equals(node.getNodeName())) {
                return (Element) node;
            }
        }
        return null;
    }

    /** c=0, r=2 → A3（列号转字母，行号+1） */
    private String toCellPos(int col, int row) {
        StringBuilder sb = new StringBuilder();
        int n = col;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + n % 26));
            n = n / 26 - 1;
        }
        sb.append(row + 1);
        return sb.toString();
    }
}
