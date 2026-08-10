package cn.wubo.spring.ai.loom.agent.tool.time;

import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public class DefaultTimeTool implements ITimeTool {

    private final String defaultTimezone;

    public DefaultTimeTool(LoomAgentProperties properties) {
        this.defaultTimezone = properties.getTimezone();
    }

    @Tool(description = "获取指定时区的当前时间")
    @Override
    public String getCurrentTime(
            @ToolParam(description = "IANA 时区名称，如 America/New_York、Europe/London、Asia/Shanghai。不传则使用默认时区", required = false) String timezone) {
        try {
            ZoneId zone = (timezone == null || timezone.isBlank()) ? ZoneId.of(defaultTimezone) : ZoneId.of(timezone);
            ZonedDateTime now = ZonedDateTime.now(zone);
            return String.format("当前时间：%s (%s)",
                    now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), zone.getId());
        } catch (Exception e) {
            return "获取当前时间失败：" + e.getMessage();
        }
    }

    @Tool(description = "在不同时区之间转换时间")
    @Override
    public String convertTime(
            @ToolParam(description = "源时区，IANA 时区名称，如 America/New_York、Europe/London、Asia/Shanghai") String sourceTimezone,
            @ToolParam(description = "时间，24小时制 HH:MM 格式") String time,
            @ToolParam(description = "目标时区，IANA 时区名称，如 America/New_York、Europe/London、Asia/Shanghai") String targetTimezone) {
        try {
            ZoneId sourceZone = ZoneId.of(sourceTimezone);
            ZoneId targetZone = ZoneId.of(targetTimezone);
            LocalTime localTime = LocalTime.parse(time, DateTimeFormatter.ofPattern("HH:mm"));
            ZonedDateTime zonedDateTime = localTime.atDate(LocalDateTime.now(sourceZone).toLocalDate()).atZone(sourceZone);
            ZonedDateTime converted = zonedDateTime.withZoneSameInstant(targetZone);
            return String.format("%s %s (%s) 转换为 %s %s (%s)",
                    time, sourceTimezone, sourceZone.getRules().getOffset(zonedDateTime.toInstant()),
                    converted.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")), targetTimezone,
                    targetZone.getRules().getOffset(converted.toInstant()));
        } catch (DateTimeParseException e) {
            return "时间格式错误，请使用 HH:MM 格式（24小时制），例如 14:30";
        } catch (Exception e) {
            return "时区转换失败：" + e.getMessage();
        }
    }
}
