package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.excepton.LoomAgentRuntimeException;
import cn.wubo.spring.ai.loom.agent.model.LoomAgentProperties;
import cn.wubo.spring.ai.loom.agent.model.SkillRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

@Slf4j
public class DefaultSkillStorage implements ISkillStorage {

    private final JdbcTemplate jdbcTemplate;
    List<SkillRecord> skills = new ArrayList<>();

    public DefaultSkillStorage(JdbcTemplate jdbcTemplate, List<LoomAgentProperties.SkillProperty>  skills) {
        this.jdbcTemplate = jdbcTemplate;
        for (LoomAgentProperties.SkillProperty skill : skills) {
            this.skills.add(new SkillRecord(
                    skill.getName(),
                    skill.getDescription(),
                    skill.isLoad(),
                    skill.getContent(),
                    "embed"
            ));
        }
    }

    private SkillRecord mapSkillRecord(ResultSet rs, int rowNum) throws SQLException {
        return new SkillRecord(
                rs.getString("name"),
                rs.getString("description"),
                rs.getBoolean("load"),
                rs.getString("content"),
                "db"
        );
    }


    @Override
    public List<SkillRecord> list(String username){
        List<SkillRecord> dbList = jdbcTemplate.query(
                "SELECT * FROM skill WHERE username = ?",
                this::mapSkillRecord,
                username
        );
        dbList.addAll(skills);
        return dbList;
    }

    @Override
    public int save(SkillRecord skill,String username) {
        List<SkillRecord> embedList = skills.stream().filter(e -> e.name().equals(skill.name())).toList();
        if (!embedList.isEmpty()){
            throw new LoomAgentRuntimeException("内嵌技能不允许修改");
        }
        List<SkillRecord> dbList = jdbcTemplate.query(
                "SELECT * FROM skill WHERE username = ?",
                this::mapSkillRecord,
                username
        );
        if (dbList.isEmpty()){
            return jdbcTemplate.update(
                    "INSERT INTO skill (name, description, load, content, username) VALUES (?, ?, ?, ?, ?) ",
                    skill.name(),
                    skill.description(),
                    skill.load(),
                    skill.content(),
                    username
            );
        }else {
            return jdbcTemplate.update(
                    "UPDATE skill SET description = ?, load = ?, content = ? WHERE name = ? AND username = ?",
                    skill.description(),
                    skill.load(),
                    skill.content(),
                    skill.name(),
                    username
            );
        }
    }


    @Override
    public SkillRecord get(String name, String username) {
        List<SkillRecord> embedList = skills.stream().filter(e -> e.name().equals(name)).toList();
        if (!embedList.isEmpty()){
            return  embedList.get(0);
        }
        List<SkillRecord> dbList = jdbcTemplate.query(
                "SELECT * FROM skill WHERE name = ? AND username = ?",
                this::mapSkillRecord,
                name,
                username
        );
        return dbList.get(0);

    }

    @Override
    public int remove(String name, String username) {
        List<SkillRecord> embedList = skills.stream().filter(e -> e.name().equals(name)).toList();
        if (!embedList.isEmpty()){
            throw new LoomAgentRuntimeException("内嵌技能不允许删除");
        }
        return jdbcTemplate.update(
                "DELETE FROM skill WHERE name = ? AND username = ?",
                name,
                username
        );
    }
}
