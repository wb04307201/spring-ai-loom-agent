package cn.wubo.spring.ai.loom.agent.skill;

import cn.wubo.spring.ai.loom.agent.model.SkillRecord;

import java.util.List;

public interface ISkillStorage {

    List<SkillRecord> list(String username);

    int save(SkillRecord skill,String username);

    SkillRecord get(String name, String username);

    int remove(String name, String username);
}
