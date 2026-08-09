package cn.wubo.spring.ai.loom.agent.tool.time;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultTimeTool 单元测试
 * <p>
 * 覆盖：
 * 1. getCurrentTime 默认时区（Asia/Shanghai）
 * 2. getCurrentTime 自定义时区（America/New_York、UTC）
 * 3. getCurrentTime 无效时区返回错误信息
 * 4. convertTime 跨时区转换（Shanghai → New York）
 * 5. convertTime 同一时区转换
 * 6. convertTime 错误时间格式（DateTimeParseException）
 * 7. convertTime 无效时区返回错误信息
 */
@DisplayName("DefaultTimeTool 单元测试")
class DefaultTimeToolTest {

 private DefaultTimeTool tool;

 @BeforeEach
 void setUp() {
 LoomAgentProperties props = new LoomAgentProperties();
 props.setTimezone("Asia/Shanghai");
 tool = new DefaultTimeTool(props);
 }

 @Test
 @DisplayName("getCurrentTime 使用默认时区 Asia/Shanghai")
 void getCurrentTime_usesDefaultTimezone() {
 String result = tool.getCurrentTime(null);
 assertNotNull(result);
 assertTrue(result.contains("Asia/Shanghai"), "结果应包含默认时区名: " + result);
 assertTrue(result.startsWith("当前时间："), "结果应以'当前时间：'开头: " + result);
 }

 @Test
 @DisplayName("getCurrentTime 使用空字符串时回退默认时区")
 void getCurrentTime_emptyStringFallsBackToDefault() {
 String result = tool.getCurrentTime("");
 assertTrue(result.contains("Asia/Shanghai"), "结果应包含默认时区名: " + result);
 }

 @Test
 @DisplayName("getCurrentTime 支持指定 IANA 时区")
 void getCurrentTime_respectsGivenTimezone() {
 String ny = tool.getCurrentTime("America/New_York");
 assertTrue(ny.contains("America/New_York"), "结果应包含指定时区: " + ny);

 String utc = tool.getCurrentTime("UTC");
 assertTrue(utc.contains("UTC"), "结果应包含 UTC: " + utc);
 }

 @Test
 @DisplayName("getCurrentTime 无效时区返回错误信息而非抛异常")
 void getCurrentTime_invalidTimezoneReturnsError() {
 String result = tool.getCurrentTime("Not/Real_Zone");
 assertTrue(result.startsWith("获取当前时间失败："), "应返回错误信息: " + result);
 }

 @Test
 @DisplayName("convertTime 跨时区转换 Shanghai → New York")
 void convertTime_shanghaiToNewYork() {
 String result = tool.convertTime("Asia/Shanghai", "14:00", "America/New_York");
 assertNotNull(result);
 assertTrue(result.contains("Asia/Shanghai"), "结果应包含源时区: " + result);
 assertTrue(result.contains("America/New_York"), "结果应包含目标时区: " + result);
 // Shanghai 14:00 对应 New York 01:00（夏令时）或 02:00（标准时）
 assertTrue(result.matches(".*\\b(01|02):00\\b.*"), "结果应包含转换后时间 01:00 或 02:00: " + result);
 }

 @Test
 @DisplayName("convertTime 同一时区时间格式正确")
 void convertTime_sameTimezoneFormatOk() {
 String result = tool.convertTime("Asia/Shanghai", "09:30", "Asia/Shanghai");
 assertTrue(result.contains("09:30"), "结果应保留时间 09:30: " + result);
 }

 @Test
 @DisplayName("convertTime 时间格式错误返回友好提示")
 void convertTime_invalidTimeFormatReturnsFriendlyMessage() {
 String result = tool.convertTime("Asia/Shanghai", "下午2点", "America/New_York");
 assertEquals("时间格式错误，请使用 HH:MM 格式（24小时制），例如 14:30", result);
 }

 @Test
 @DisplayName("convertTime 无效时区返回错误信息")
 void convertTime_invalidTimezoneReturnsError() {
 String result = tool.convertTime("Not/A_Zone", "12:00", "Asia/Shanghai");
 assertTrue(result.startsWith("时区转换失败："), "应返回错误信息: " + result);
 }
}
