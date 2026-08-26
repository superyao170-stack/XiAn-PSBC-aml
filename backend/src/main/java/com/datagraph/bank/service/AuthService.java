
package com.datagraph.bank.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagraph.bank.dto.LoginRequest;
import com.datagraph.bank.dto.LoginResponse;
import com.datagraph.bank.dto.MenuDTO;
import com.datagraph.bank.entity.SysMenu;
import com.datagraph.bank.entity.SysRole;
import com.datagraph.bank.entity.SysUser;
import com.datagraph.bank.mapper.SysMenuMapper;
import com.datagraph.bank.mapper.SysRoleMapper;
import com.datagraph.bank.mapper.SysUserMapper;
import com.datagraph.bank.security.JwtTokenProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class AuthService {
    private static final Set<String> RETAINED_MENU_PATHS = Set.of(
            "/overview", "/analysis/upload",
            "/case", "/case/list", "/case/processing", "/case/processing/report",
            "/case/processing/framework", "/case/processing/similarity",
            "/case/processing/approval", "/case/graph",
            "/graph", "/graph/visualize", "/graph/hidden-risk", "/graph/association-clues",
            "/system", "/system/roles", "/system/menus");

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysMenuMapper menuMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;

    public AuthService(SysUserMapper userMapper, SysRoleMapper roleMapper,
                       SysMenuMapper menuMapper, PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider) {
        this.userMapper = userMapper;
        this.roleMapper = roleMapper;
        this.menuMapper = menuMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
    }

    public LoginResponse login(LoginRequest request) {
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername())
                .eq(SysUser::getDeleted, false));

        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("用户名或密码错误");
        }

        SysRole role = roleMapper.selectOne(new LambdaQueryWrapper<SysRole>()
                .eq(SysRole::getRoleCode, user.getRoleCode()));

        List<SysMenu> menus = menuMapper.selectList(new LambdaQueryWrapper<SysMenu>()
                .eq(SysMenu::getVisible, true)
                .orderByAsc(SysMenu::getSortOrder));

        List<String> permissions = role != null && role.getPermissions() != null
                ? Arrays.asList(role.getPermissions())
                : Collections.emptyList();
        menus = filterRetainedMenus(menus, user.getRoleCode());

        LoginResponse response = new LoginResponse();
        response.setToken(tokenProvider.generateToken(user.getUsername(), user.getRoleCode(), user.getBankCode()));
        response.setRefreshToken(tokenProvider.generateRefreshToken(user.getUsername()));
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setRoleCode(user.getRoleCode());
        response.setBankCode(user.getBankCode());
        response.setPermissions(permissions);
        response.setMenus(buildMenuTree(menus));

        return response;
    }

    private List<SysMenu> filterRetainedMenus(List<SysMenu> menus, String roleCode) {
        if (!Set.of("sadmin", "badmin").contains(roleCode)) return Collections.emptyList();
        return menus.stream()
                .filter(menu -> RETAINED_MENU_PATHS.contains(menu.getPath()))
                .filter(menu -> "sadmin".equals(roleCode) || !menu.getPath().startsWith("/system"))
                .toList();
    }

    private List<MenuDTO> buildMenuTree(List<SysMenu> menus) {
        Map<Long, MenuDTO> menuMap = new HashMap<>();
        List<MenuDTO> rootMenus = new ArrayList<>();

        for (SysMenu menu : menus) {
            MenuDTO dto = new MenuDTO();
            dto.setId(menu.getId());
            dto.setParentId(menu.getParentId());
            dto.setMenuName(menu.getMenuName());
            dto.setPath(menu.getPath());
            dto.setComponent(menu.getComponent());
            dto.setIcon(menu.getIcon());
            dto.setSortOrder(menu.getSortOrder());
            dto.setType(menu.getType());
            dto.setPermission(menu.getPermission());
            dto.setChildren(new ArrayList<>());
            menuMap.put(menu.getId(), dto);
        }

        for (MenuDTO dto : menuMap.values()) {
            if (dto.getParentId() == 0) {
                rootMenus.add(dto);
            } else {
                MenuDTO parent = menuMap.get(dto.getParentId());
                if (parent != null) {
                    parent.getChildren().add(dto);
                }
            }
        }

        sortMenuTree(rootMenus);

        return rootMenus;
    }

    private void sortMenuTree(List<MenuDTO> menus) {
        menus.sort(Comparator.comparing(MenuDTO::getSortOrder));
        menus.forEach(menu -> sortMenuTree(menu.getChildren()));
    }
}
