
package com.datagraph.bank.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datagraph.bank.common.response.CommonResult;
import com.datagraph.bank.common.response.PageResult;
import com.datagraph.bank.entity.SysDictData;
import com.datagraph.bank.entity.SysMenu;
import com.datagraph.bank.entity.SysRole;
import com.datagraph.bank.entity.SysUser;
import com.datagraph.bank.mapper.SysDictDataMapper;
import com.datagraph.bank.mapper.SysMenuMapper;
import com.datagraph.bank.mapper.SysRoleMapper;
import com.datagraph.bank.mapper.SysUserMapper;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/system")
@CrossOrigin(origins = "*")
public class SystemController {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysMenuMapper menuMapper;
    private final SysDictDataMapper dictDataMapper;
    private final PasswordEncoder passwordEncoder;

    public SystemController(SysUserMapper userMapper, SysRoleMapper roleMapper,
                           SysMenuMapper menuMapper, SysDictDataMapper dictDataMapper,
                           PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.menuMapper = menuMapper;
        this.dictDataMapper = dictDataMapper;
        this.passwordEncoder = passwordEncoder;
    }

    @GetMapping("/users")
    public CommonResult<PageResult<SysUser>> getUsers(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        Page<SysUser> page = new Page<>(pageNum, pageSize);
        userMapper.selectPage(page, new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getDeleted, false)
                .orderByDesc(SysUser::getCreatedAt));
        return CommonResult.success(PageResult.of(page.getRecords(), page.getTotal(), pageNum, pageSize));
    }

    @GetMapping("/users/{id}")
    public CommonResult<SysUser> getUserById(@PathVariable Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null || Boolean.TRUE.equals(user.getDeleted())) {
            return CommonResult.error(404, "用户不存在");
        }
        return CommonResult.success(user);
    }

    @PostMapping("/users")
    public CommonResult<SysUser> createUser(@RequestBody SysUser user) {
        if (user.getUsername() == null || user.getUsername().isBlank()
                || user.getPassword() == null || user.getPassword().isBlank()) {
            return CommonResult.error(400, "用户名和密码不能为空");
        }
        if (userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, user.getUsername())
                .eq(SysUser::getDeleted, false)) > 0) {
            return CommonResult.error(409, "用户名已存在");
        }
        CommonResult<SysUser> roleError = validateUserRole(user.getRoleCode(), user.getBankCode());
        if (roleError != null) return roleError;
        user.setPassword(passwordEncoder.encode(user.getPassword()));
        user.setStatus(user.getStatus() == null || user.getStatus());
        user.setDeleted(false);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        return CommonResult.success(user);
    }

    @PutMapping("/users/{id}")
    public CommonResult<SysUser> updateUser(@PathVariable Long id, @RequestBody SysUser input) {
        SysUser user = userMapper.selectById(id);
        if (user == null || Boolean.TRUE.equals(user.getDeleted())) {
            return CommonResult.error(404, "用户不存在");
        }
        String nextRole = input.getRoleCode() == null ? user.getRoleCode() : input.getRoleCode();
        String nextBank = input.getBankCode() == null ? user.getBankCode() : input.getBankCode();
        CommonResult<SysUser> roleError = validateUserRole(nextRole, nextBank);
        if (roleError != null) return roleError;
        if (input.getNickname() != null) user.setNickname(input.getNickname());
        if (input.getEmail() != null) user.setEmail(input.getEmail());
        if (input.getPhone() != null) user.setPhone(input.getPhone());
        user.setRoleCode(nextRole);
        user.setBankCode(nextBank);
        if (input.getInstitutionId() != null) user.setInstitutionId(input.getInstitutionId());
        if (input.getStatus() != null) user.setStatus(input.getStatus());
        if (input.getPassword() != null && !input.getPassword().isBlank()) {
            user.setPassword(passwordEncoder.encode(input.getPassword()));
        }
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return CommonResult.success(user);
    }

    @DeleteMapping("/users/{id}")
    public CommonResult<Void> deleteUser(@PathVariable Long id) {
        SysUser user = userMapper.selectById(id);
        if (user == null || Boolean.TRUE.equals(user.getDeleted())) {
            return CommonResult.error(404, "用户不存在");
        }
        user.setDeleted(true);
        user.setStatus(false);
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.updateById(user);
        return CommonResult.success(null);
    }

    @GetMapping("/roles")
    public CommonResult<List<SysRole>> getRoles() {
        List<SysRole> roles = roleMapper.selectList(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getDeleted, false));
        return CommonResult.success(roles);
    }

    @PostMapping("/roles")
    public CommonResult<SysRole> createRole(@RequestBody SysRole role) {
        if (role.getRoleCode() == null || role.getRoleCode().isBlank()) {
            return CommonResult.error(400, "角色编码不能为空");
        }
        if (roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, role.getRoleCode())
                .eq(SysRole::getDeleted, false)) > 0) {
            return CommonResult.error(409, "角色编码已存在");
        }
        role.setDeleted(false);
        role.setCreatedAt(LocalDateTime.now());
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.insert(role);
        return CommonResult.success(role);
    }

    @PutMapping("/roles/{id}")
    public CommonResult<SysRole> updateRole(@PathVariable Long id, @RequestBody SysRole input) {
        SysRole role = roleMapper.selectById(id);
        if (role == null || Boolean.TRUE.equals(role.getDeleted())) return CommonResult.error(404, "角色不存在");
        role.setRoleName(input.getRoleName());
        role.setDescription(input.getDescription());
        role.setMenus(input.getMenus());
        role.setPermissions(input.getPermissions());
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.updateById(role);
        return CommonResult.success(role);
    }

    @DeleteMapping("/roles/{id}")
    public CommonResult<Void> deleteRole(@PathVariable Long id) {
        SysRole role = roleMapper.selectById(id);
        if (role == null || Boolean.TRUE.equals(role.getDeleted())) return CommonResult.error(404, "角色不存在");
        long assignedUsers = userMapper.selectCount(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getRoleCode, role.getRoleCode())
                .eq(SysUser::getDeleted, false));
        if (assignedUsers > 0) {
            return CommonResult.error(409, "该角色仍关联 " + assignedUsers + " 个用户，请先调整用户角色");
        }
        role.setDeleted(true);
        role.setUpdatedAt(LocalDateTime.now());
        roleMapper.updateById(role);
        return CommonResult.success(null);
    }

    private CommonResult<SysUser> validateUserRole(String roleCode, String bankCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return CommonResult.error(400, "用户角色不能为空");
        }
        long roleCount = roleMapper.selectCount(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, roleCode)
                .eq(SysRole::getDeleted, false));
        if (roleCount == 0) {
            return CommonResult.error(400, "所选角色不存在或已停用");
        }
        if ("badmin".equals(roleCode) && (bankCode == null || bankCode.isBlank())) {
            return CommonResult.error(400, "银行管理员必须绑定银行代码");
        }
        return null;
    }

    @GetMapping("/menus")
    public CommonResult<List<SysMenu>> getMenus() {
        List<SysMenu> menus = menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .orderByAsc(SysMenu::getParentId)
                .orderByAsc(SysMenu::getSortOrder)
                .orderByAsc(SysMenu::getId));
        return CommonResult.success(menus);
    }

    @PostMapping("/menus")
    @Transactional
    public CommonResult<SysMenu> createMenu(@RequestBody SysMenu menu) {
        CommonResult<SysMenu> validation = validateMenu(menu, null);
        if (validation != null) return validation;
        normalizeMenu(menu);
        menu.setVisible(menu.getVisible() == null || menu.getVisible());
        menu.setDeleted(false);
        menu.setCreatedAt(LocalDateTime.now());
        menu.setUpdatedAt(LocalDateTime.now());
        menuMapper.insert(menu);
        return CommonResult.success(menu);
    }

    @PutMapping("/menus/{id}")
    @Transactional
    public CommonResult<SysMenu> updateMenu(@PathVariable Long id, @RequestBody SysMenu input) {
        SysMenu menu = menuMapper.selectById(id);
        if (menu == null || Boolean.TRUE.equals(menu.getDeleted())) return CommonResult.error(404, "菜单不存在");
        CommonResult<SysMenu> validation = validateMenu(input, id);
        if (validation != null) return validation;
        normalizeMenu(input);
        input.setId(id);
        input.setDeleted(false);
        input.setCreatedAt(menu.getCreatedAt());
        if (input.getVisible() == null) input.setVisible(menu.getVisible());
        input.setUpdatedAt(LocalDateTime.now());
        menuMapper.updateById(input);
        return CommonResult.success(menuMapper.selectById(id));
    }

    @DeleteMapping("/menus/{id}")
    @Transactional
    public CommonResult<Void> deleteMenu(@PathVariable Long id) {
        SysMenu menu = menuMapper.selectById(id);
        if (menu == null || Boolean.TRUE.equals(menu.getDeleted())) return CommonResult.error(404, "菜单不存在");
        List<SysMenu> allMenus = menuMapper.selectList(new LambdaQueryWrapper<SysMenu>());
        Set<Long> deletedIds = new HashSet<>();
        collectDescendantIds(id, allMenus, deletedIds);
        LocalDateTime now = LocalDateTime.now();
        for (SysMenu target : allMenus) {
            if (!deletedIds.contains(target.getId())) continue;
            target.setDeleted(true);
            target.setVisible(false);
            target.setUpdatedAt(now);
            menuMapper.updateById(target);
        }
        return CommonResult.success(null);
    }

    private CommonResult<SysMenu> validateMenu(SysMenu menu, Long currentId) {
        if (menu == null || menu.getMenuName() == null || menu.getMenuName().isBlank()) {
            return CommonResult.error(400, "菜单名称不能为空");
        }
        String type = menu.getType() == null ? "MENU" : menu.getType().trim().toUpperCase();
        if (!Set.of("DIRECTORY", "MENU", "BUTTON").contains(type)) {
            return CommonResult.error(400, "菜单类型必须为目录、菜单或按钮");
        }
        String path = menu.getPath() == null ? "" : menu.getPath().trim();
        if (!"BUTTON".equals(type) && path.isBlank()) {
            return CommonResult.error(400, "目录和菜单必须填写路径");
        }
        if (!path.isBlank() && !path.startsWith("/")) {
            return CommonResult.error(400, "菜单路径必须以 / 开头");
        }
        long duplicatePath = menuMapper.selectCount(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getPath, path)
                .ne(currentId != null, SysMenu::getId, currentId));
        if (!path.isBlank() && duplicatePath > 0) return CommonResult.error(409, "菜单路径已存在");

        long parentId = menu.getParentId() == null ? 0L : menu.getParentId();
        if (currentId != null && parentId == currentId) return CommonResult.error(400, "菜单不能作为自己的父菜单");
        if (parentId != 0) {
            SysMenu parent = menuMapper.selectById(parentId);
            if (parent == null || Boolean.TRUE.equals(parent.getDeleted())) {
                return CommonResult.error(400, "父菜单不存在");
            }
            if ("BUTTON".equalsIgnoreCase(parent.getType())) {
                return CommonResult.error(400, "按钮不能包含子菜单");
            }
            if (currentId != null && descendantIds(currentId).contains(parentId)) {
                return CommonResult.error(400, "不能将菜单移动到自己的子菜单下");
            }
        }
        return null;
    }

    private void normalizeMenu(SysMenu menu) {
        menu.setParentId(menu.getParentId() == null ? 0L : menu.getParentId());
        menu.setMenuName(menu.getMenuName().trim());
        menu.setPath(menu.getPath() == null ? "" : menu.getPath().trim());
        menu.setComponent(menu.getComponent() == null ? "" : menu.getComponent().trim());
        menu.setIcon(menu.getIcon() == null ? "" : menu.getIcon().trim());
        menu.setPermission(menu.getPermission() == null ? "" : menu.getPermission().trim());
        menu.setType(menu.getType() == null ? "MENU" : menu.getType().trim().toUpperCase());
        menu.setSortOrder(menu.getSortOrder() == null ? 0 : menu.getSortOrder());
    }

    private Set<Long> descendantIds(Long id) {
        Set<Long> ids = new HashSet<>();
        collectDescendantIds(id, menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()), ids);
        ids.remove(id);
        return ids;
    }

    private void collectDescendantIds(Long id, List<SysMenu> menus, Set<Long> result) {
        if (!result.add(id)) return;
        List<Long> children = new ArrayList<>();
        for (SysMenu item : menus) {
            if (id.equals(item.getParentId())) children.add(item.getId());
        }
        children.forEach(child -> collectDescendantIds(child, menus, result));
    }

    @GetMapping("/dict/{dictType}")
    public CommonResult<List<SysDictData>> getDictData(@PathVariable String dictType) {
        List<SysDictData> data = dictDataMapper.selectList(new LambdaQueryWrapper<SysDictData>()
                .eq(SysDictData::getDictType, dictType)
                .eq(SysDictData::getStatus, true)
                .orderByAsc(SysDictData::getSortOrder));
        return CommonResult.success(data);
    }

    @PostMapping("/dict/{dictType}")
    public CommonResult<SysDictData> createDictData(@PathVariable String dictType, @RequestBody SysDictData data) {
        data.setDictType(dictType);
        data.setStatus(data.getStatus() == null || data.getStatus());
        data.setCreatedAt(LocalDateTime.now());
        data.setUpdatedAt(LocalDateTime.now());
        dictDataMapper.insert(data);
        return CommonResult.success(data);
    }

    @PutMapping("/dict/{dictType}/{id}")
    public CommonResult<SysDictData> updateDictData(@PathVariable String dictType, @PathVariable Long id,
                                                     @RequestBody SysDictData input) {
        SysDictData data = dictDataMapper.selectById(id);
        if (data == null || !dictType.equals(data.getDictType())) return CommonResult.error(404, "字典项不存在");
        data.setDictLabel(input.getDictLabel());
        data.setDictValue(input.getDictValue());
        data.setSortOrder(input.getSortOrder());
        if (input.getStatus() != null) data.setStatus(input.getStatus());
        data.setUpdatedAt(LocalDateTime.now());
        dictDataMapper.updateById(data);
        return CommonResult.success(data);
    }

    @DeleteMapping("/dict/{dictType}/{id}")
    public CommonResult<Void> deleteDictData(@PathVariable String dictType, @PathVariable Long id) {
        SysDictData data = dictDataMapper.selectById(id);
        if (data == null || !dictType.equals(data.getDictType())) return CommonResult.error(404, "字典项不存在");
        dictDataMapper.deleteById(id);
        return CommonResult.success(null);
    }
}
