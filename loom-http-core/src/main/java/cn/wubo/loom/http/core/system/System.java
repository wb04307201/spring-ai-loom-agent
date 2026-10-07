package cn.wubo.loom.http.core.system;

import cn.wubo.loom.http.core.endpoint.Endpoint;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class System {
    private String name;
    private String description;
    private String baseUrl;
    private String authProfile;
    private OpenApi openapi = new OpenApi();
    private List<Endpoint> endpoints = new ArrayList<>();

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OpenApi {
        private String source;
        private String format = "auto";
        private String refresh = "onDemand";
        private String lastLoadedAt;
        private String etag;

        public String getSource() { return source; }
        public void setSource(String s) { this.source = s; }
        public String getFormat() { return format; }
        public void setFormat(String f) { this.format = f; }
        public String getRefresh() { return refresh; }
        public void setRefresh(String r) { this.refresh = r; }
        public String getLastLoadedAt() { return lastLoadedAt; }
        public void setLastLoadedAt(String t) { this.lastLoadedAt = t; }
        public String getEtag() { return etag; }
        public void setEtag(String e) { this.etag = e; }
    }

    public String getName() { return name; }
    public void setName(String n) { this.name = n; }
    public String getDescription() { return description; }
    public void setDescription(String d) { this.description = d; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String b) { this.baseUrl = b; }
    public String getAuthProfile() { return authProfile; }
    public void setAuthProfile(String a) { this.authProfile = a; }
    public OpenApi getOpenapi() { return openapi; }
    public void setOpenapi(OpenApi o) { this.openapi = o; }
    public List<Endpoint> getEndpoints() { return endpoints; }
    public void setEndpoints(List<Endpoint> e) { this.endpoints = e; }
}
