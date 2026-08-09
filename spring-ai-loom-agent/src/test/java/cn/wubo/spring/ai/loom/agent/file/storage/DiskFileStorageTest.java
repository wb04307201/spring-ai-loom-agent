package cn.wubo.spring.ai.loom.agent.file.storage;

import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DiskFileStorage 单元测试
 * <p>
 * 覆盖：
 * 1. save 保存文件到磁盘并返回路径
 * 2. read 从磁盘路径读取文件
 * 3. delete 删除磁盘文件
 * 4. deleteByKnowledgeId 按知识库目录删除
 */
@DisplayName("DiskFileStorage 单元测试")
class DiskFileStorageTest {

 @TempDir
 Path tempDir;

 private DiskFileStorage storage;
 private String knowledgeBasePath;

 @BeforeEach
 void setUp() {
 knowledgeBasePath = tempDir.resolve("knowledge").toString();
 storage = new DiskFileStorage(knowledgeBasePath);

 // Set a test user context
 UserContextHolder.setCurrentUser("testuser");
 }

 @AfterEach
 void tearDown() {
 UserContextHolder.clear();
 }

 @Test
 @DisplayName("save 保存文件到磁盘并返回路径")
 void testSave_savesFileToDisk() throws IOException {
 byte[] content = "disk test content".getBytes();

 String returnedPath = storage.save("kb-1", "test.txt",
 new ByteArrayInputStream(content), "text/plain");

 assertNotNull(returnedPath);
 assertFalse(returnedPath.isBlank());

 // Verify file was written to disk
 Path knowledgeDir = Path.of(knowledgeBasePath, "testuser", "kb-1");
 assertTrue(Files.exists(knowledgeDir), "knowledge dir should exist");
 assertTrue(Files.list(knowledgeDir).count() > 0, "should have a file in the dir");
 }

 @Test
 @DisplayName("save 重名文件自动追加序号")
 void testSave_duplicateFileNameAppendsSuffix() throws IOException {
 byte[] content = "content 1".getBytes();

 // Save first file
 String path1 = storage.save("kb-1", "report.pdf",
 new ByteArrayInputStream(content), "application/pdf");

 // Save second file with same name
 String path2 = storage.save("kb-1", "report.pdf",
 new ByteArrayInputStream(content), "application/pdf");

 assertNotEquals(path1, path2);

 // Should have 2 files in the directory
 Path knowledgeDir = Path.of(knowledgeBasePath, "testuser", "kb-1");
 assertEquals(2, Files.list(knowledgeDir).count(), "should have 2 files");
 }

 @Test
 @DisplayName("read 从磁盘路径读取文件")
 void testRead_readsFileContent() throws IOException {
 byte[] content = "readable content".getBytes();

 String savedPath = storage.save("kb-1", "read.txt",
 new ByteArrayInputStream(content), "text/plain");

 // Read and verify content
 byte[] readResult = storage.read(savedPath);
 assertArrayEquals(content, readResult);
 }

 @Test
 @DisplayName("delete 删除磁盘文件")
 void testDelete_deletesFileFromDisk() throws IOException {
 // Save a file
 String savedPath = storage.save("kb-1", "delete.txt",
 new ByteArrayInputStream(new byte[]{1, 2, 3}), "application/octet-stream");

 // Verify file exists
 Path path = Path.of(savedPath);
 assertTrue(Files.exists(path), "file should exist before delete");

 // Delete it
 storage.delete(savedPath);
 assertFalse(Files.exists(path), "file should not exist after delete");
 }

 @Test
 @DisplayName("deleteByKnowledgeId 删除整个知识库目录")
 void testDeleteByKnowledgeId_deletesKnowledgeDir() throws IOException {
 // Create some files
 storage.save("kb-1", "file1.txt", new ByteArrayInputStream(new byte[1]), "text/plain");
 storage.save("kb-1", "file2.txt", new ByteArrayInputStream(new byte[2]), "text/plain");
 storage.save("kb-2", "file3.txt", new ByteArrayInputStream(new byte[3]), "text/plain");

 Path kb1Dir = Path.of(knowledgeBasePath, "testuser", "kb-1");
 assertTrue(Files.exists(kb1Dir), "kb-1 dir should exist");

 // Delete kb-1
 storage.deleteByKnowledgeId("kb-1");

 assertFalse(Files.exists(kb1Dir), "kb-1 dir should be deleted");

 // kb-2 should still exist
 Path kb2Dir = Path.of(knowledgeBasePath, "testuser", "kb-2");
 assertTrue(Files.exists(kb2Dir), "kb-2 dir should still exist");
 }

 @Test
 @DisplayName("deleteByKnowledgeId 空知识库不报错")
 void testDeleteByKnowledgeId_emptyKnowledgeBaseNoError() {
 assertDoesNotThrow(() -> storage.deleteByKnowledgeId("kb-nonexistent"));
 }

 @Test
 @DisplayName("read 文件不存在时抛出异常")
 void testRead_nonExistentFileThrowsException() {
 assertThrows(RuntimeException.class, () -> storage.read("/non/existent/path/file.txt"));
 }
}
