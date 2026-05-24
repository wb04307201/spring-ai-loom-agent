package cn.wubo.spring.ai.loom.agent.content;

import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.lang.Nullable;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

//@ConfigurationPropertiesBinding
public class ContentHolderConverter implements Converter<String, ContentHolder> {

    private final ResourceLoader resourceLoader;

    public ContentHolderConverter(ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
    }

    @Nullable
    @Override
    public ContentHolder convert(String source) {
        if (source == null) {
            return new ContentHolder(null);
        }

        // 判断是否以 classpath: 开头
        if (source.startsWith("classpath:")) {
            try {
                Resource resource = resourceLoader.getResource(source);
                // 读取文件内容为字符串
                String content = StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
                return new ContentHolder(content);
            } catch (IOException e) {
                throw new IllegalArgumentException("Failed to read content from " + source, e);
            }
        } else {
            // 直接作为字符串值
            return new ContentHolder(source);
        }
    }
}
