package cn.wubo.loom.http.core.endpoint;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAPI 3.x subset endpoint definition.
 * See spec section 3.5 for the schema.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class Endpoint {
    private String method;
    private String path;
    private String summary;
    private String description;
    private List<String> tags = new ArrayList<>();
    private List<Parameter> parameters = new ArrayList<>();
    private RequestBody requestBody;
    private Map<String, Response> responses = new LinkedHashMap<>();
    private Instant lastModified = Instant.now();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Parameter {
        private String name;
        private String in;
        private boolean required;
        private Map<String, Object> schema;
        private String description;

        public String getName() { return name; }
        public void setName(String n) { this.name = n; }
        public String getIn() { return in; }
        public void setIn(String i) { this.in = i; }
        public boolean isRequired() { return required; }
        public void setRequired(boolean r) { this.required = r; }
        public Map<String, Object> getSchema() { return schema; }
        public void setSchema(Map<String, Object> s) { this.schema = s; }
        public String getDescription() { return description; }
        public void setDescription(String d) { this.description = d; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class RequestBody {
        private boolean required;
        private Map<String, MediaType> content = new LinkedHashMap<>();

        public boolean isRequired() { return required; }
        public void setRequired(boolean r) { this.required = r; }
        public Map<String, MediaType> getContent() { return content; }
        public void setContent(Map<String, MediaType> c) { this.content = c; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class MediaType {
        private Map<String, Object> schema;

        public Map<String, Object> getSchema() { return schema; }
        public void setSchema(Map<String, Object> s) { this.schema = s; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Response {
        private String description;
        private Map<String, MediaType> content = new LinkedHashMap<>();

        public String getDescription() { return description; }
        public void setDescription(String d) { this.description = d; }
        public Map<String, MediaType> getContent() { return content; }
        public void setContent(Map<String, MediaType> c) { this.content = c; }
    }

    public String getMethod() { return method; }
    public void setMethod(String m) { this.method = m; }
    public String getPath() { return path; }
    public void setPath(String p) { this.path = p; }
    public String getSummary() { return summary; }
    public void setSummary(String s) { this.summary = s; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public List<String> getTags() { return tags; }
    public void setTags(List<String> t) { this.tags = t; }
    public List<Parameter> getParameters() { return parameters; }
    public void setParameters(List<Parameter> p) { this.parameters = p; }
    public RequestBody getRequestBody() { return requestBody; }
    public void setRequestBody(RequestBody rb) { this.requestBody = rb; }
    public Map<String, Response> getResponses() { return responses; }
    public void setResponses(Map<String, Response> r) { this.responses = r; }
    public Instant getLastModified() { return lastModified; }
    public void setLastModified(Instant lm) { this.lastModified = lm; }
}
