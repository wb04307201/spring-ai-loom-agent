package cn.wubo.spring.ai.loom.agent.file.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * DatabaseFileStorage 单元测试
 * <p>
 * 覆盖：
 * 1. save 保存文件内容并返回 fileId
 * 2. read 读取文件内容
 * 3. delete 删除文件
 * 4. deleteByKnowledgeId 按知识库删除所有文件
 */
@DisplayName("DatabaseFileStorage 单元测试")
class DatabaseFileStorageTest {

 private JdbcTemplate jdbcTemplate;
 private DatabaseFileStorage storage;

 @BeforeEach
 void setUp() {
 jdbcTemplate = mock(JdbcTemplate.class);
 storage = new DatabaseFileStorage(jdbcTemplate);
 }

 @Test
 @DisplayName("save 保存文件内容并返回 UUID fileId")
 void testSave_returnsFileId() {
 byte[] content = "test content".getBytes();
 InputStream inputStream = new ByteArrayInputStream(content);

 when(jdbcTemplate.update(anyString(), anyString(), any(byte[].class), anyString(), anyString()))
 .thenReturn(1);

 String fileId = storage.save("kb-1", "test.txt", inputStream, "text/plain");

 assertNotNull(fileId);
 assertFalse(fileId.isEmpty());
 // UUID format: 8-4-4-4-12
 assertTrue(fileId.length() == 36, "fileId should be UUID format: " + fileId);

 verify(jdbcTemplate).update(
 contains("INSERT INTO loom_file_content"),
 eq(fileId),
 eq(content),
 eq("text/plain"),
 eq("kb-1"));
 }

 @Test
 @DisplayName("save 保存的文件内容正确")
 void testSave_contentMatches() {
 byte[] expectedContent = "hello world".getBytes();
 ByteArrayInputStream inputStream = new ByteArrayInputStream(expectedContent);

 when(jdbcTemplate.update(anyString(), anyString(), any(byte[].class), anyString(), anyString()))
 .thenReturn(1);

 String fileId = storage.save("kb-1", "hello.txt", inputStream, "text/plain");
 assertNotNull(fileId);

 verify(jdbcTemplate).update(
 contains("INSERT INTO loom_file_content"),
 eq(fileId),
 eq(expectedContent),
 eq("text/plain"),
 eq("kb-1"));
 }

 @Test
 @DisplayName("read 读取文件内容")
 void testRead_returnsContent() {
 byte[] expectedContent = "stored content".getBytes();

 when(jdbcTemplate.queryForObject(anyString(), any(Object[].class), eq(byte[].class)))
 .thenReturn(expectedContent);

 byte[] result = storage.read("test-file-id");

 assertArrayEquals(expectedContent, result);
 verify(jdbcTemplate).queryForObject(
 eq("SELECT content FROM loom_file_content WHERE file_id = ?"),
 any(Object[].class),
 eq(byte[].class));
 }

 @Test
 @DisplayName("read 返回空内容时正确处理")
 void testRead_emptyContent() {
 when(jdbcTemplate.queryForObject(anyString(), any(Object[].class), eq(byte[].class)))
 .thenReturn(new byte[0]);

 byte[] result = storage.read("empty-file");

 assertNotNull(result);
 assertEquals(0, result.length);
 }

 @Test
 @DisplayName("delete 删除指定文件")
 void testDelete_removesFile() {
 when(jdbcTemplate.update(eq("DELETE FROM loom_file_content WHERE file_id = ?"), eq("file-123")))
 .thenReturn(1);

 storage.delete("file-123");

 verify(jdbcTemplate).update(
 "DELETE FROM loom_file_content WHERE file_id = ?",
 "file-123");
 }

 @Test
 @DisplayName("deleteByKnowledgeId 删除知识库所有文件")
 void testDeleteByKnowledgeId() {
 when(jdbcTemplate.update(eq("DELETE FROM loom_file_content WHERE knowledge_id = ?"), eq("kb-1")))
 .thenReturn(2);

 storage.deleteByKnowledgeId("kb-1");

 verify(jdbcTemplate).update(
 "DELETE FROM loom_file_content WHERE knowledge_id = ?",
 "kb-1");
 }

 @Test
 @DisplayName("deleteByKnowledgeId 空知识库时直接删除无异常")
 void testDeleteByKnowledgeId_emptyKnowledgeBase() {
 when(jdbcTemplate.update(eq("DELETE FROM loom_file_content WHERE knowledge_id = ?"), eq("kb-empty")))
 .thenReturn(0);

 storage.deleteByKnowledgeId("kb-empty");

 // DELETE is executed directly (0 rows affected for empty KB)
 verify(jdbcTemplate).update(
 "DELETE FROM loom_file_content WHERE knowledge_id = ?",
 "kb-empty");
 }

 @Test
 @DisplayName("save 流读取失败抛出异常")
 void testSave_streamReadFailure() {
 InputStream badStream = new InputStream() {
 @Override
 public int read() {
 throw new RuntimeException("stream error");
 }
 };

 assertThrows(RuntimeException.class, () ->
 storage.save("kb-1", "fail.txt", badStream, "text/plain"));
 }

 @Test
 @DisplayName("不同 MIME 类型正确传递")
 void testSave_differentMimeTypes() {
 when(jdbcTemplate.update(anyString(), anyString(), any(byte[].class), anyString(), anyString()))
 .thenReturn(1);

 // PDF
 storage.save("kb-1", "doc.pdf", new ByteArrayInputStream(new byte[0]), "application/pdf");
 verify(jdbcTemplate).update(anyString(), anyString(), any(byte[].class), eq("application/pdf"), anyString());

 // Reset and test text/csv
 reset(jdbcTemplate);
 when(jdbcTemplate.update(anyString(), anyString(), any(byte[].class), anyString(), anyString()))
 .thenReturn(1);

 storage.save("kb-2", "data.csv", new ByteArrayInputStream(new byte[0]), "text/csv");
 verify(jdbcTemplate).update(anyString(), anyString(), any(byte[].class), eq("text/csv"), anyString());
 }
}
