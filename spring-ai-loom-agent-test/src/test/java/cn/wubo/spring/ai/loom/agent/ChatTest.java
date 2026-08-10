package cn.wubo.spring.ai.loom.agent;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.util.MimeTypeUtils;
import reactor.core.publisher.Flux;

import java.io.File;

@Slf4j
@SpringBootTest(classes = LoomAgentTestApplication.class)
class ChatTest {

    @Autowired
    private ChatModel chatModel;

    @Test
    void test() throws InterruptedException {
        ChatClient chatClient = ChatClient.builder(chatModel).build();
        var imageResource1 = new FileSystemResource(new File("./test/img1.jpg"));
        var imageResource2 = new FileSystemResource(new File("./test/img2.jpg"));

        Flux<String> response = chatClient
                .prompt()
                .user(u -> u.text("都是什么?")
                        .media(MimeTypeUtils.IMAGE_JPEG, imageResource1)
                        .media(MimeTypeUtils.IMAGE_JPEG, imageResource2)
                )
                .stream()
                .content();

        response.subscribe(log::info);

        Thread.sleep(1000 * 30);
    }
}
