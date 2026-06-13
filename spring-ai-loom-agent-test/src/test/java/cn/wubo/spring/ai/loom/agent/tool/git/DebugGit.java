package cn.wubo.spring.ai.loom.agent.tool.git;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

class DebugGit {
    @Test
    void debugPath() {
        Path p = Paths.get("/etc/malicious").normalize();
        System.out.println("p = " + p);
    }

    @Test
    void debugGetWorkingDir() throws Exception {
        Path tmp = Files.createTempDirectory("dbg-wd-");
        LoomAgentProperties props = new LoomAgentProperties();
        props.setFileBasePath(tmp.getParent().toString());
        DefaultGitTool tool = new DefaultGitTool(props);
        Map<String, Object> m = new HashMap<>();
        m.put("username", tmp.getFileName().toString());
        m.put("gitWorkingDir", "/etc/malicious");
        ToolContext ctx = new ToolContext(m);
        try {
            String r = tool.gitStatus(true, ctx);
            System.out.println("UNEXPECTED: " + r);
        } catch (Exception e) {
            System.out.println("GOT EX: " + e.getClass().getSimpleName() + ":" + e.getMessage());
        }
    }
}
