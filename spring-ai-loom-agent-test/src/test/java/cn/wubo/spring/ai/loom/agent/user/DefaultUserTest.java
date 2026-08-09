package cn.wubo.spring.ai.loom.agent.user;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pins the username-validation contract for {@link DefaultUser#createUser}.
 *
 * <p>Bug history: the schedule-task namespace format is
 * {@code loom-sched-{username}-{uuid36}-{name}}, and the frontend's
 * {@code _shortName()} parser splits on the FIRST {@code '-'} to strip the
 * username segment. If a username contained a dash, the heuristic would
 * slice the wrong prefix and corrupt the short-name. This test locks in the
 * rejection so the invariant cannot silently regress.</p>
 */
class DefaultUserTest {

 private JdbcTemplate jdbc;
 private DefaultUser users;

 @BeforeEach
 void setUp() {
 DriverManagerDataSource ds = new DriverManagerDataSource();
 ds.setDriverClassName("org.h2.Driver");
 ds.setUrl("jdbc:h2:mem:test-user-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
 ds.setUsername("sa");
 ds.setPassword("");
 jdbc = new JdbcTemplate(ds);
 jdbc.execute("""
 create table user_info (
 id bigint auto_increment primary key,
 username varchar(64) not null unique,
 nickname varchar(64) not null,
 password varchar(255) not null,
 type varchar(20) not null
 )
 """);
 Cache cache = new ConcurrentMapCache("sessions");
 users = new DefaultUser(jdbc, new BCryptPasswordEncoder(), cache);
 }

 @Test
 void createUser_rejectsUsernameContainingDash() {
 // 'wb-043' mirrors the bug-report example. createUser must throw before
 // any DB write so the heuristic-breaking username never lands.
 assertThatThrownBy(() -> users.createUser("wb-043", "nick", "password123", "USER"))
 .isInstanceOf(LoomAgentRuntimeException.class)
 .hasMessageContaining("'-'");
 }

 @Test
 void createUser_acceptsUsernameWithoutDash() {
 // Sanity: a normal username still goes through. We don't assert on the
 // post-DB state — only that no exception escapes.
 users.createUser("wb043", "nick", "password123", "USER");
 Integer count = jdbc.queryForObject(
 "SELECT COUNT(*) FROM user_info WHERE username = ?", Integer.class, "wb043");
 assertThat(count).isEqualTo(1);
 }

 @Test
 void createUser_rejectsDashBeforeExistenceCheck() {
 // The dash check must run BEFORE the "用户名已存在" existence check, so the
 // error message points at the root cause rather than a misleading
 // duplicate-username error.
 // First seed a user without dash, then try createUser with a dashed
 // username that does NOT exist.
 assertThatThrownBy(() -> users.createUser("has-dash", "nick", "password123", "USER"))
 .isInstanceOf(LoomAgentRuntimeException.class)
 .hasMessageContaining("'-'")
 .hasMessageNotContaining("用户名已存在");
 }

 @Test
 void createUser_rejectsOversizedPassword() {
 // Defensive cap (MAX_PASSWORD=128) blocks a hostile admin POST from
 // pushing a multi-MB plaintext through BCrypt and burning CPU.
 String huge = "x".repeat(129);
 assertThatThrownBy(() -> users.createUser("wb-over", "nick", huge, "USER"))
 .isInstanceOf(LoomAgentRuntimeException.class)
 .hasMessageContaining("密码 ≤ 128");
 }

 @Test
 void createUser_rejectsOversizedUsername() {
 // Mirror the password cap: a 65-char username would slip past the
 // initial blank check but blow up at the DB layer. Cap at 64
 // characters in line with the user_info schema.
 String huge = "x".repeat(65);
 assertThatThrownBy(() -> users.createUser(huge, "nick", "password123", "USER"))
 .isInstanceOf(LoomAgentRuntimeException.class)
 .hasMessageContaining("用户名 ≤ 64");
 }
}