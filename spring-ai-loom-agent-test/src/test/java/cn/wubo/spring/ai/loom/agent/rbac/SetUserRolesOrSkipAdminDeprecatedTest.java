package cn.wubo.spring.ai.loom.agent.rbac;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * §3(spec 2026-09-08-askuser-followups-design.md):
 * setUserRolesOrSkipAdmin 的名字是历史残留 —— M5 删除 admin 短路后行为等同
 * setUserRoles。admin 现已可在控制台被分配角色(strict RBAC 闭环)。
 * 该方法标记 @Deprecated,下一 minor 版本删除(T3 v1 shim policy:1 minor version)。
 * 本测试锁死注解存在,防止误删注解或误删方法时无人察觉。
 */
@DisplayName("setUserRolesOrSkipAdmin @Deprecated 契约")
class SetUserRolesOrSkipAdminDeprecatedTest {

    @Test
    void interfaceMethodIsDeprecated() throws NoSuchMethodException {
        Method m = IRoleService.class.getMethod("setUserRolesOrSkipAdmin", String.class, List.class);
        assertThat(m.isAnnotationPresent(Deprecated.class))
                .as("IRoleService.setUserRolesOrSkipAdmin 必须标 @Deprecated(名字误导,行为等同 setUserRoles)")
                .isTrue();
    }

    @Test
    void implMethodIsDeprecated() throws NoSuchMethodException {
        Method m = DefaultRoleService.class.getMethod("setUserRolesOrSkipAdmin", String.class, List.class);
        assertThat(m.isAnnotationPresent(Deprecated.class))
                .as("DefaultRoleService.setUserRolesOrSkipAdmin 必须标 @Deprecated")
                .isTrue();
    }
}
