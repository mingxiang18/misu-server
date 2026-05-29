package com.misu.fileServer.service.support;

import com.misu.common.constant.HttpStatus;
import com.misu.common.exception.ServiceException;
import com.misu.fileServer.domain.entity.FileMapping;
import com.misu.security.constant.UserRole;
import com.misu.security.dto.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link FileAuthorityChecker} 边界单测：纯 JUnit5 + Mockito（此处无需 mock collaborators）。
 *
 * <p>角色判定经 {@code AuthorityUtil.hasAuthority} / 当前用户经 {@code LoginMessageUtil.getLoginUser()}，
 * 二者都静态读 {@link SecurityContextHolder}。故直接往 SecurityContext 塞一个携带角色 GrantedAuthority
 * 的 {@link LoginUser} principal 造登录态，无需 mockStatic；{@link AfterEach} 清理。</p>
 *
 * <p>核心断言：权限不足一律抛 {@link ServiceException} 且 {@code getCode()==403}（FORBIDDEN），
 * 严格区分于 401（UNAUTHORIZED，未登录）。</p>
 */
class FileAuthorityCheckerTest {

    private static final Long ME = 42L;
    private static final Long OTHER = 99L;

    private final FileAuthorityChecker checker = new FileAuthorityChecker();

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 造登录态：principal=LoginUser，authorities=指定角色（AuthorityUtil 读 token 的 authorities）。 */
    private void loginAs(Long userId, String... roles) {
        LoginUser loginUser = new LoginUser(userId, "u" + userId, List.of(roles));
        List<GrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, authorities));
    }

    private FileMapping mapping(Integer openType, String ownerUserId) {
        FileMapping m = new FileMapping();
        m.setOpenType(openType);
        m.setUserId(ownerUserId);
        return m;
    }

    // ===================== checkPublicWriteAuthority =====================

    @Test
    void checkPublicWriteAuthority_public_nonAdmin_throwsForbidden() {
        loginAs(ME, UserRole.USER);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> checker.checkPublicWriteAuthority(1));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
        // 必须是 403 而不是 401
        assertEquals(403, ex.getCode());
    }

    @Test
    void checkPublicWriteAuthority_public_admin_passes() {
        loginAs(ME, UserRole.ADMIN);
        assertDoesNotThrow(() -> checker.checkPublicWriteAuthority(1));
    }

    @Test
    void checkPublicWriteAuthority_public_fileAdmin_passes() {
        loginAs(ME, UserRole.FILE_ADMIN);
        assertDoesNotThrow(() -> checker.checkPublicWriteAuthority(1));
    }

    @Test
    void checkPublicWriteAuthority_private_nonAdmin_passes() {
        // openType=0（私人）不校验管理员
        loginAs(ME, UserRole.USER);
        assertDoesNotThrow(() -> checker.checkPublicWriteAuthority(0));
    }

    @Test
    void checkPublicWriteAuthority_nullOpenType_passes() {
        loginAs(ME, UserRole.USER);
        assertDoesNotThrow(() -> checker.checkPublicWriteAuthority(null));
    }

    // ===================== checkAdminViewAuthority =====================

    @Test
    void checkAdminViewAuthority_admin_passes() {
        loginAs(ME, UserRole.ADMIN);
        assertDoesNotThrow(() -> checker.checkAdminViewAuthority());
    }

    @Test
    void checkAdminViewAuthority_fileAdmin_passes() {
        loginAs(ME, UserRole.FILE_ADMIN);
        assertDoesNotThrow(() -> checker.checkAdminViewAuthority());
    }

    @Test
    void checkAdminViewAuthority_nonAdmin_throwsForbidden() {
        loginAs(ME, UserRole.USER);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> checker.checkAdminViewAuthority());
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
        assertEquals(403, ex.getCode());
    }

    @Test
    void checkAdminViewAuthority_noAuthentication_throwsForbidden() {
        // 无登录态 → 权限列表空 → 同样是 403（门控按权限不足判，不抛 401）
        ServiceException ex = assertThrows(ServiceException.class,
                () -> checker.checkAdminViewAuthority());
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
    }

    // ===================== ensureMappingOwnership =====================

    @Test
    void ensureMappingOwnership_privateOwnedBySelf_passes() {
        loginAs(ME, UserRole.USER);
        assertDoesNotThrow(() -> checker.ensureMappingOwnership(mapping(0, ME.toString())));
    }

    @Test
    void ensureMappingOwnership_privateOwnedByOther_throwsForbidden() {
        loginAs(ME, UserRole.USER);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> checker.ensureMappingOwnership(mapping(0, OTHER.toString())));
        assertEquals(HttpStatus.FORBIDDEN, ex.getCode());
        assertEquals(403, ex.getCode());
    }

    @Test
    void ensureMappingOwnership_publicMapping_skipsOwnershipCheck() {
        // 公共 mapping（openType=1）：本方法不校验归属（由 checkPublicWriteAuthority 覆盖），
        // 即便 mapping 属他人也放行，且不读 LoginUser。
        loginAs(ME, UserRole.USER);
        assertDoesNotThrow(() -> checker.ensureMappingOwnership(mapping(1, OTHER.toString())));
    }

    @Test
    void ensureMappingOwnership_nullOpenType_skipsCheck() {
        loginAs(ME, UserRole.USER);
        assertDoesNotThrow(() -> checker.ensureMappingOwnership(mapping(null, OTHER.toString())));
    }

    @Test
    void ensureMappingOwnership_privateNotLoggedIn_throwsUnauthorized() {
        // 私人 mapping 但无登录态 → 抛 401（UNAUTHORIZED），与上面权限不足的 403 严格分开
        SecurityContextHolder.clearContext();
        ServiceException ex = assertThrows(ServiceException.class,
                () -> checker.ensureMappingOwnership(mapping(0, ME.toString())));
        assertEquals(HttpStatus.UNAUTHORIZED, ex.getCode());
        assertEquals(401, ex.getCode());
    }

    @Test
    void httpStatusConstants_distinguish403From401() {
        // 防回归：确认 403/401 两个常量确实不同
        assertEquals(403, HttpStatus.FORBIDDEN);
        assertEquals(401, HttpStatus.UNAUTHORIZED);
    }
}
