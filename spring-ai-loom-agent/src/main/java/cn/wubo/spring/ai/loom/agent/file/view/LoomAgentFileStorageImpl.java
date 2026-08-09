package cn.wubo.spring.ai.loom.agent.file.view;

import cn.wubo.file.view.storage.IFileStorage;
import cn.wubo.file.view.storage.dto.FileStorageInfo;
import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.user.UserContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
public class LoomAgentFileStorageImpl implements IFileStorage {

 private final JdbcTemplate jdbcTemplate;

 public LoomAgentFileStorageImpl(JdbcTemplate jdbcTemplate) {
 this.jdbcTemplate = jdbcTemplate;
 }

 @Override
 public FileStorageInfo upload(String fileName, byte[] content, String mimeType) {
 throw new UnsupportedOperationException();
 }

 /**
 * Username-scoped lookup. Without the WHERE username = ? predicate, any
 * authenticated user could read another user's file metadata (and bytes,
 * via /wopi/files/{id}/contents) just by guessing the row id.
 *
 * <p>Fix for BUG-RBAC-FILE-WOPI: previously the SQL only filtered by
 * id, so cross-user access via the wopi / file-view path succeeded.
 * Username is sourced from {@link UserContextHolder} populated upstream by
 * {@code AuthenticationFilter}; tests verify that an empty/null context
 * still refuses (no row matches {@code username = '' OR NULL}).
 */
 @Override
 public FileStorageInfo findById(String id) {
 String username = UserContextHolder.getCurrentUser();
 if (username == null || username.isBlank()) {
 // No authenticated user in context → refuse with 404 (not 500).
 // Cross-user attempts land here too once AuthenticationFilter
 // populates the right username (BUG-RBAC-FILE-WOPI fix).
 throw new LoomAgentRuntimeException(404, "文件不存在");
 }
 try {
 return jdbcTemplate.queryForObject(
 "SELECT id, file_name, size, mime_type, path FROM file_info"
 + " WHERE id = ? AND username = ?",
 (rs, rowNum) -> new FileStorageInfo(
 rs.getString("id"),
 rs.getString("file_name"),
 rs.getLong("size"),
 rs.getString("mime_type"),
 rs.getString("path"),
 "1"
 ),
 id, username
 );
 } catch (Exception e) {
 // Either id not found OR id belongs to another user → uniformly
 // 404. Do NOT leak whether the id exists (oracle guard).
 throw new LoomAgentRuntimeException(404, "文件不存在");
 }
 }

 @Override
 public List<FileStorageInfo> list() {
 throw new UnsupportedOperationException();
 }

 @Override
 public byte[] getContentByLocation(String location) {
 Path path = Path.of(location);
 try {
 return Files.readAllBytes(path);
 } catch (IOException e) {
 throw new LoomAgentRuntimeException(e);
 }
 }

 @Override
 public Boolean deleteById(String id) {
 throw new UnsupportedOperationException();
 }
}
