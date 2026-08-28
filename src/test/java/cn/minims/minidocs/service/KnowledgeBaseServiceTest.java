package cn.minims.minidocs.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import cn.minims.minidocs.extension.KnowledgeBase;
import org.junit.jupiter.api.Test;

/**
 * 私有知识库资源级权限判定（{@link KnowledgeBaseService#canAccess}）单元测试。
 *
 * <p>覆盖公开/私有、用户/匿名、成员/非成员/创建者/管理者等审核要求的场景。
 * 该方法为纯静态判定，不依赖扩展存储与安全上下文，可直接构造对象验证。
 *
 * @author Cosolar
 */
class KnowledgeBaseServiceTest {

    private KnowledgeBase publicKb() {
        var kb = new KnowledgeBase();
        var spec = new KnowledgeBase.Spec();
        spec.setPublicVisible(true);
        kb.setSpec(spec);
        return kb;
    }

    private KnowledgeBase privateKbOfCreatorMembers(String creator, List<String> members) {
        var kb = new KnowledgeBase();
        var spec = new KnowledgeBase.Spec();
        spec.setPublicVisible(false);
        spec.setCreatorName(creator);
        spec.setMembers(members);
        kb.setSpec(spec);
        return kb;
    }

    @Test
    void publicKbAccessibleByAnyoneIncludingAnonymous() {
        var kb = publicKb();
        // 未登录（匿名）
        assertTrue(KnowledgeBaseService.canAccess(kb, "", false), "匿名应可读公开知识库");
        // 任意登录用户
        assertTrue(KnowledgeBaseService.canAccess(kb, "zhangsan", false),
            "任意登录用户应可读公开知识库");
        // 匿名 + 管理标记（实际匿名不携带管理权限，此处验证判定不误拒）
        assertTrue(KnowledgeBaseService.canAccess(kb, "", true), "公开知识库不受管理标记影响");
    }

    @Test
    void privateKbDeniedForAnonymous() {
        var kb = privateKbOfCreatorMembers("alice", List.of("bob"));
        assertFalse(KnowledgeBaseService.canAccess(kb, "", false),
            "匿名不应可读私有知识库");
    }

    @Test
    void privateKbAllowsCreator() {
        var kb = privateKbOfCreatorMembers("alice", List.of("bob"));
        assertTrue(KnowledgeBaseService.canAccess(kb, "alice", false),
            "创建者应可读自己的私有知识库");
    }

    @Test
    void privateKbAllowsMember() {
        var kb = privateKbOfCreatorMembers("alice", List.of("bob"));
        assertTrue(KnowledgeBaseService.canAccess(kb, "bob", false),
            "成员应可读私有知识库");
    }

    @Test
    void privateKbDeniedForNonMember() {
        var kb = privateKbOfCreatorMembers("alice", List.of("bob"));
        // 既非创建者也非成员，且无管理权限
        assertFalse(KnowledgeBaseService.canAccess(kb, "mallory", false),
            "非成员非创建者不应可读私有知识库");
    }

    @Test
    void privateKbAccessibleWhenManagePrivilegeGranted() {
        var kb = privateKbOfCreatorMembers("alice", List.of("bob"));
        assertTrue(KnowledgeBaseService.canAccess(kb, "somebody-else", true),
            "具备管理权限者即便非成员亦应可读私有知识库");
    }

    @Test
    void privateKbWithoutMembersOnlyCreatorCanAccess() {
        var kb = privateKbOfCreatorMembers("alice", null);
        assertTrue(KnowledgeBaseService.canAccess(kb, "alice", false));
        assertFalse(KnowledgeBaseService.canAccess(kb, "bob", false),
            "未配置成员时非创建者不应可读");
    }
}