package cn.wubo.spring.ai.loom.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {
        org.springframework.ai.model.ollama.autoconfigure.OllamaChatAutoConfiguration.class
})
@EnableScheduling
public class LoomAgentTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoomAgentTestApplication.class, args);
    }

}
