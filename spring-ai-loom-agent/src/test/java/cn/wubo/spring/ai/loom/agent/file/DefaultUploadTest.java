package cn.wubo.spring.ai.loom.agent.file;

import cn.wubo.spring.ai.loom.agent.document.IDocumentRead;
import cn.wubo.spring.ai.loom.agent.document.IFileDocument;
import cn.wubo.spring.ai.loom.agent.knowledge.IKnowledge;
import cn.wubo.spring.ai.loom.agent.model.FileDocumentRecord;
import cn.wubo.spring.ai.loom.agent.model.FileRecord;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * DefaultUpload 单元测试 —— 重名序号、MIME 兜底、知识库上传链路、非知识库删除。
 */
@DisplayName("DefaultUpload 单元测试")
class DefaultUploadTest {

 @TempDir
 Path tempDir;

 private IFile file;
 private IFileDocument fileDocument;
 private IFileStorage fileStorage;
 private IDocumentRead documentRead;
 private VectorStore vectorStore;
 private DefaultUpload upload;

 /** 单例 provider(知识腿依赖在场)。 */
 private static <T> org.springframework.beans.factory.ObjectProvider<T> providerOf(T bean) {
  return new org.springframework.beans.factory.ObjectProvider<>() {
   @Override
   public T getObject() {
    return bean;
   }

   @Override
   public T getIfAvailable() {
    return bean;
   }

   @Override
   public T getIfUnique() {
    return bean;
   }
  };
 }

 /** 空 provider(降级部署:无 embedding/VectorStore)。 */
 private static <T> org.springframework.beans.factory.ObjectProvider<T> emptyProvider(Class<T> type) {
  return new org.springframework.beans.factory.ObjectProvider<>() {
   @Override
   public T getObject() {
    throw new org.springframework.beans.factory.NoSuchBeanDefinitionException(type);
   }

   @Override
   public T getIfAvailable() {
    return null;
   }

   @Override
   public T getIfUnique() {
    return null;
   }
  };
 }

 @BeforeEach
 void setUp() {
 UserContextHolder.setCurrentUser("alice");
 file = mock(IFile.class);
 fileDocument = mock(IFileDocument.class);
 documentRead = mock(IDocumentRead.class);
 vectorStore = mock(VectorStore.class);
 IKnowledge knowledge = mock(IKnowledge.class);
 fileStorage = mock(IFileStorage.class);
 upload = new DefaultUpload(file, fileDocument,
  providerOf(documentRead), providerOf(vectorStore),
  knowledge, fileStorage, tempDir.toString());
 }

 @AfterEach
 void tearDown() {
 UserContextHolder.clear();
 }

 private static InputStream in(String s) {
 return new ByteArrayInputStream(s.getBytes());
 }

 @Test
 @DisplayName("重名上传：file.txt → file(1).txt → file(2).txt 且磁盘真实存在")
 void duplicateNamesGetSequentialSuffix() {
 upload.upload(in("a"), "file.txt", "text/plain");
 upload.upload(in("b"), "file.txt", "text/plain");
 upload.upload(in("c"), "file.txt", "text/plain");

 ArgumentCaptor<FileRecord> captor = ArgumentCaptor.forClass(FileRecord.class);
 verify(file, times(3)).insert(captor.capture(), anyString());
 List<String> names = captor.getAllValues().stream().map(FileRecord::fileName).toList();
 assertEquals(List.of("file.txt", "file(1).txt", "file(2).txt"), names);

 assertTrue(Files.exists(tempDir.resolve("alice").resolve("file").resolve("file.txt")));
 assertTrue(Files.exists(tempDir.resolve("alice").resolve("file").resolve("file(1).txt")));
 assertTrue(Files.exists(tempDir.resolve("alice").resolve("file").resolve("file(2).txt")));
 }

 @Test
 @DisplayName("MIME 兜底：octet-stream 时按扩展名推断（md → text/markdown）")
 void mimeFallbackFromExtension() {
 upload.upload(in("# hi"), "notes.md", "application/octet-stream");

 ArgumentCaptor<FileRecord> captor = ArgumentCaptor.forClass(FileRecord.class);
 verify(file).insert(captor.capture(), anyString());
 assertEquals("text/markdown", captor.getValue().mimeType());
 }

 @Test
 @DisplayName("知识库上传：向量化 + file_info(usage=knowledge) + file_document 关联")
 void uploadWithKnowledgeWiresVectorAndMetadata() {
 when(fileStorage.save(anyString(), anyString(), any(), anyString())).thenReturn("loc-1");
 when(fileStorage.read("loc-1")).thenReturn("content".getBytes());
 IDocumentRead documentRead = mock(IDocumentRead.class);
 // 重新装配以注入可控 documentRead
 DefaultUpload u = new DefaultUpload(file, fileDocument,
  providerOf(documentRead), providerOf(vectorStore),
  mock(IKnowledge.class), fileStorage, tempDir.toString());
 when(documentRead.read(any(), anyString())).thenReturn(List.of(new Document("d1"), new Document("d2")));

 String fileId = u.uploadWithKnowledge(in("content"), "doc.txt", "text/plain", "kb-1");

 verify(vectorStore, times(1)).add(any());
 ArgumentCaptor<FileRecord> rec = ArgumentCaptor.forClass(FileRecord.class);
 verify(file).insert(rec.capture(), anyString());
 assertEquals("knowledge", rec.getValue().usage());
 assertEquals("kb-1", rec.getValue().knowledgeId());
 assertEquals(fileId, rec.getValue().id());
 @SuppressWarnings("unchecked")
 ArgumentCaptor<List<FileDocumentRecord>> docs = ArgumentCaptor.forClass(List.class);
 verify(fileDocument).insert(docs.capture());
 assertEquals(2, docs.getValue().size());
 assertTrue(docs.getValue().stream().allMatch(d -> d.fileId().equals(fileId)));
 }

 @Test
 @DisplayName("非知识库删除：磁盘文件被删 + file 记录删除")
 void deleteNonKnowledgeRemovesDiskFile() {
 String fileId = upload.upload(in("data"), "gone.txt", "text/plain");
 ArgumentCaptor<FileRecord> captor = ArgumentCaptor.forClass(FileRecord.class);
 verify(file).insert(captor.capture(), anyString());
 FileRecord record = captor.getValue();
 assertTrue(Files.exists(Path.of(record.path())));
 when(file.getById(fileId, "alice")).thenReturn(record);

 upload.delete(fileId);

 assertFalse(Files.exists(Path.of(record.path())), "磁盘文件应被删除");
 verify(file).delete(fileId, "alice");
 }

 @Test
 @DisplayName("降级部署(无 VectorStore):聊天附件腿照常,知识腿抛 503 业务错误")
 void degradedDeploymentChatLegWorksKnowledgeLegThrows503() {
 DefaultUpload degraded = new DefaultUpload(file, fileDocument,
  emptyProvider(IDocumentRead.class), emptyProvider(VectorStore.class),
  mock(IKnowledge.class), fileStorage, tempDir.toString());

 // 聊天附件腿不依赖 RAG:落盘 + file_info 照常
 String fileId = degraded.upload(in("hello"), "chat.txt", "text/plain");
 assertNotNull(fileId);
 verify(file).insert(any(FileRecord.class), anyString());
 assertTrue(Files.exists(tempDir.resolve("alice").resolve("file").resolve("chat.txt")));

 // 知识腿:依赖门前置,抛 503 业务错误(不落孤儿存储)
 cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException ex =
  assertThrows(cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException.class,
   () -> degraded.uploadWithKnowledge(in("c"), "doc.txt", "text/plain", "kb-1"));
 assertTrue(ex.getMessage().contains("知识空间"), "错误文案应指明知识空间未启用: " + ex.getMessage());
 verifyNoInteractions(fileStorage);
 }

 @Test
 @DisplayName("降级部署删除知识库文件:跳过向量清理,元数据/存储照常清")
 void degradedDeploymentDeleteSkipsVectorCleanup() {
 DefaultUpload degraded = new DefaultUpload(file, fileDocument,
  emptyProvider(IDocumentRead.class), emptyProvider(VectorStore.class),
  mock(IKnowledge.class), fileStorage, tempDir.toString());
 FileRecord record = new FileRecord("fid-1", "kb-1", "doc.txt", 3L,
  java.time.LocalDateTime.now(), "loc-1", "knowledge", "text/plain");
 when(file.getById("fid-1", "alice")).thenReturn(record);
 when(fileDocument.getListByFileId("fid-1")).thenReturn(List.of(new FileDocumentRecord("fid-1", "d1")));

 degraded.delete("fid-1");

 verify(fileDocument).deleteByFileId("fid-1");
 verify(fileStorage).delete("fid-1");
 // 无 VectorStore 可清:不抛错、流程走完
 }
}
