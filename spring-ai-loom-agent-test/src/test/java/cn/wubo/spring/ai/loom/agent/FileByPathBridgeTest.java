package cn.wubo.spring.ai.loom.agent;

import cn.wubo.spring.ai.loom.agent.file.IFile;
import cn.wubo.spring.ai.loom.agent.model.FileRecord;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.servlet.function.ServerResponse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * /file/by-path/* 桥接端点测试 —— temp 记录自动注册、路径越界防护。
 */
@DisplayName("file by-path 桥接端点")
class FileByPathBridgeTest {

 @TempDir
 Path tempDir;

 private IFile file;
 private RouterFunction<ServerResponse> router;

 @BeforeEach
 void setUp() throws Exception {
 UserContextHolder.setCurrentUser("alice");
 Files.createDirectories(tempDir.resolve("alice"));
 Files.writeString(tempDir.resolve("alice").resolve("hello.txt"), "hi");
 file = mock(IFile.class);
 LoomAgentProperties properties = new LoomAgentProperties();
 properties.setFileBasePath(tempDir.toString());
 router = new LoomAgentConfiguration.WebConfiguration().loomAgentFileRouter(file, properties);
 }

 @AfterEach
 void tearDown() {
 UserContextHolder.clear();
 }

 private ServerResponse route(String path, String fileParam) throws Exception {
 MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", path);
 servletRequest.setRequestURI(path);
 servletRequest.setServletPath(path);
 if (fileParam != null) servletRequest.addParameter("path", fileParam);
 ServerRequest request = ServerRequest.create(servletRequest, List.of(new MappingJackson2HttpMessageConverter()));
 return router.route(request).orElseThrow().handle(request);
 }

 @Test
 @DisplayName("未登记文件：自动插入 usage=temp 记录并 302 到 /file/view/{id}")
 void unregisteredFileGetsTempRecord() throws Exception {
 when(file.getByExactPath(anyString(), anyString())).thenReturn(null);

 ServerResponse response = route("/spring/ai/loom/file/by-path/view", "hello.txt");

 // ServerResponse.temporaryRedirect = 307
 assertEquals(307, response.statusCode().value());
 ArgumentCaptor<FileRecord> captor = ArgumentCaptor.forClass(FileRecord.class);
 verify(file).insert(captor.capture(), anyString());
 assertEquals("temp", captor.getValue().usage());
 assertEquals("hello.txt", captor.getValue().fileName());
 }

 @Test
 @DisplayName("已登记文件：复用既有 id，不重复插入")
 void registeredFileReusesId() throws Exception {
 FileRecord existing = new FileRecord("known-id", null, "hello.txt", 2,
 LocalDateTime.now(), tempDir.resolve("alice").resolve("hello.txt").toString(), "conversation", "text/plain");
 when(file.getByExactPath(anyString(), anyString())).thenReturn(existing);

 ServerResponse response = route("/spring/ai/loom/file/by-path/view", "hello.txt");

 assertEquals(307, response.statusCode().value());
 verify(file, never()).insert(any(), anyString());
 }

 @Test
 @DisplayName("路径越界（../）：404 且不插入")
 void traversalIsRejected() throws Exception {
 Files.writeString(tempDir.resolve("escape.txt"), "secret");

 ServerResponse response = route("/spring/ai/loom/file/by-path/view", "../escape.txt");

 assertEquals(404, response.statusCode().value());
 verify(file, never()).insert(any(), anyString());
 }

 @Test
 @DisplayName("缺 path 参数：400")
 void missingParamIsBadRequest() throws Exception {
 ServerResponse response = route("/spring/ai/loom/file/by-path/download", null);
 assertEquals(400, response.statusCode().value());
 }

 @Test
 @DisplayName("不存在的文件：404")
 void missingFileIs404() throws Exception {
 ServerResponse response = route("/spring/ai/loom/file/by-path/view", "nope.txt");
 assertEquals(404, response.statusCode().value());
 }
}
