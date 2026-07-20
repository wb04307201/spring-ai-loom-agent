package cn.wubo.spring.ai.loom.agent.user;

import cn.wubo.spring.ai.loom.agent.model.AdminConversationView;
import cn.wubo.spring.ai.loom.agent.model.ConversationRecord;
import cn.wubo.spring.ai.loom.agent.model.UserConversationRecord;

import java.util.List;

public interface IUserConversation {

    /** 用户视角的列表：过滤掉已软删的 */
    List<ConversationRecord> getList();

    boolean exists(UserConversationRecord userConversationRecord);

    int insert(UserConversationRecord userConversationRecord);

    /** 创建并持久化一个属于当前用户的空会话。 */
    ConversationRecord create(String title);

    /** 仅允许当前用户重命名自己的会话。 */
    int rename(String conversationId, String title);

    /** 软删：设置 deleted_at = now()，不删除记录 */
    int deleteById(String conversationId);

    /** 管理员视角：列出该用户所有会话（含已软删 + content_cleaned 标记） */
    List<AdminConversationView> adminListByUsername(String username);

    /** 管理员：列出所有 content_cleaned=false 的会话（含未软删 + 已软删未清理） */
    List<AdminConversationView> listAllCleanable();

    /** 管理员：清理某个会话的消息内容（删 SPRING_AI_CHAT_MEMORY + 标记 content_cleaned；
     *  若尚未软删则同时设 deleted_at = now()。
     *  chat_token_usage 永久保留（计费依据），不动） */
    int cleanContentForUserConv(String username, String conversationId);
}
