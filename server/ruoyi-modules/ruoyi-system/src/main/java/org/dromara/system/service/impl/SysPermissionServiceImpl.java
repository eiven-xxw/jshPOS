package org.dromara.system.service.impl;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.constant.TenantConstants;
import org.dromara.common.core.service.PermissionService;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.system.service.ISysMenuService;
import org.dromara.system.service.ISysPermissionService;
import org.dromara.system.service.ISysRoleService;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 用户权限处理
 *
 * @author ruoyi
 */
@RequiredArgsConstructor
@Service
public class SysPermissionServiceImpl implements ISysPermissionService, PermissionService {

    private final ISysRoleService roleService;
    private final ISysMenuService menuService;

    /**
     * 获取角色数据权限
     *
     * @param userId  用户id
     * @return 角色权限信息
     */
    @Override
    public Set<String> getRolePermission(Long userId) {
        Set<String> roles = new HashSet<>();
        // 管理员拥有所有权限
        if (LoginHelper.isSuperAdmin(userId)) {
            roles.add(TenantConstants.SUPER_ADMIN_ROLE_KEY);
        } else {
            roles.addAll(roleService.selectRolePermissionByUserId(userId));
        }
        return roles;
    }

    /**
     * 获取菜单数据权限
     *
     * @param userId  用户id
     * @return 菜单权限信息
     */
    @Override
    public Set<String> getMenuPermission(Long userId) {
        Set<String> perms = new HashSet<>();
        // 管理员拥有所有权限
        if (LoginHelper.isSuperAdmin(userId)) {
            // RuoYi 使用三段权限，鲸熵汇 Owner 同时存在两段权限；Sa-Token 的
            // 三段通配符不会匹配两段权限，因此两种形态必须分别显式授权。
            perms.add(SystemConstants.ALL_PERMISSION);
            perms.add(SystemConstants.DOMAIN_ALL_PERMISSION);
        } else {
            perms.addAll(menuService.selectMenuPermsByUserId(userId));
        }
        return perms;
    }
}
