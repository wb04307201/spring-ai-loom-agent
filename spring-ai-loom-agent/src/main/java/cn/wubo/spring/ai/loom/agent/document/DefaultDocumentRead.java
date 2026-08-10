package cn.wubo.spring.ai.loom.agent.document;

import org.springframework.ai.document.Document;
import org.springframework.ai.reader.ExtractedTextFormatter;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.core.io.Resource;

import java.util.List;

public class DefaultDocumentRead implements IDocumentRead {

    private final TokenTextSplitter tokenTextSplitter;
    private final ExtractedTextFormatter extractedTextFormatter;

    public DefaultDocumentRead() {
        // 创建一个分词器，用于将文本拆分为多个块
        this.tokenTextSplitter = TokenTextSplitter.builder().build();
        // 配置提取文本格式化器，设置各种文本处理选项
        this.extractedTextFormatter = ExtractedTextFormatter.builder().build();
    }

    @Override
    public List<Document> read(Resource fileResource, String knowledgeId) {
        TikaDocumentReader tikaDocumentReader = new TikaDocumentReader(fileResource, extractedTextFormatter);
        List<Document> documentList = tikaDocumentReader.read();
        List<Document> documents = tokenTextSplitter.apply(documentList);
        documents
                .forEach(document -> {
                    document.getMetadata().put("type", "knowledge");
                    document.getMetadata().put("knowledgeId", knowledgeId);
                });

        return documents;
    }
}
