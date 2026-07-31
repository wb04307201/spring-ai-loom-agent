package cn.wubo.spring.ai.loom.agent.file;

import cn.wubo.spring.ai.loom.agent.model.FileRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DefaultFileDownloadTest {

    private IFile mockFileService;
    private IFileStorage mockFileStorage;
    private DefaultFileDownload fileDownload;

    @BeforeEach
    void setUp() {
        mockFileService = mock(IFile.class);
        mockFileStorage = mock(IFileStorage.class);
        fileDownload = new DefaultFileDownload(mockFileService, mockFileStorage);
    }

    @Test
    void getDownloadUrl_shouldReturnCorrectUrl() {
        String fileId = "test-file-id-123";
        String url = fileDownload.getDownloadUrl(fileId);
        assertEquals("/spring/ai/loom/api/file/" + fileId + "/download", url);
    }

    @Test
    void getPreviewUrl_shouldReturnCorrectUrl() {
        String fileId = "test-file-id-456";
        String url = fileDownload.getPreviewUrl(fileId);
        assertEquals("/spring/ai/loom/api/file/" + fileId + "/preview", url);
    }

    @Test
    void getFileRecord_shouldReturnRecordWhenFileExists() {
        FileRecord expectedRecord = new FileRecord(
                "file-1", "kb-1", "test.pdf", 1024,
                LocalDateTime.now(), "/path/to/file", "knowledge", "application/pdf"
        );
        when(mockFileService.getById("file-1", "testuser")).thenReturn(expectedRecord);

        FileRecord result = fileDownload.getFileRecord("file-1", "testuser");

        assertEquals(expectedRecord, result);
        verify(mockFileService).getById("file-1", "testuser");
    }

    @Test
    void getFileRecord_shouldThrowWhenFileNotFound() {
        when(mockFileService.getById("nonexistent", "testuser")).thenReturn(null);

        assertThrows(IllegalArgumentException.class, () ->
                fileDownload.getFileRecord("nonexistent", "testuser"));
    }

    @Test
    void readFileContent_shouldReturnFileBytes() {
        FileRecord record = new FileRecord(
                "file-1", "kb-1", "test.pdf", 4,
                LocalDateTime.now(), "storage-location-uuid", "knowledge", "application/pdf"
        );
        byte[] expectedContent = new byte[]{1, 2, 3, 4};
        when(mockFileService.getById("file-1", "testuser")).thenReturn(record);
        when(mockFileStorage.read("storage-location-uuid")).thenReturn(expectedContent);

        byte[] result = fileDownload.readFileContent("file-1", "testuser");

        assertArrayEquals(expectedContent, result);
        verify(mockFileStorage).read("storage-location-uuid");
    }

    @Test
    void readFileContent_shouldUsePathFromRecord() {
        // Simulates database storage: path is a UUID
        FileRecord dbRecord = new FileRecord(
                "file-2", "kb-1", "doc.docx", 2048,
                LocalDateTime.now(), "uuid-abcd-1234", "knowledge", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        );
        byte[] content = new byte[]{(byte) 0xDE, (byte) 0xAD};
        when(mockFileService.getById("file-2", "testuser")).thenReturn(dbRecord);
        when(mockFileStorage.read("uuid-abcd-1234")).thenReturn(content);

        byte[] result = fileDownload.readFileContent("file-2", "testuser");

        assertArrayEquals(content, result);
    }
}
