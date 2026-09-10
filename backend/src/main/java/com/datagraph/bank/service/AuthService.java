
package com.datagraph.bank.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.datagraph.bank.dto.LoginRequest;
import com.datagraph.bank.dto.LoginResponse;
import com.datagraph.bank.dto.MenuDTO;
import com.datagraph.bank.dto.RegisterRequest;
import com.datagraph.bank.entity.SysMenu;
import com.datagraph.bank.entity.SysRole;
import com.datagraph.bank.entity.SysUser;
import com.datagraph.bank.mapper.SysMenuMapper;
import com.datagraph.bank.mapper.SysRoleMapper;
import com.datagraph.bank.mapper.SysUserMapper;
import com.datagraph.bank.security.JwtTokenProvider;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class AuthService {
    public static final String VIEWER_ROLE = "viewer";
    private static final Set<String> RETAINED_MENU_PATHS = Set.of(
            "/overview", "/analysis/upload",
            "/case", "/case/list", "/case/processing", "/case/processing/report",
            "/case/processing/framework", "/case/processing/similarity",
            "/case/processing/approval", "/case/graph",
            "/graph", "/graph/visualize", "/graph/hidden-risk", "/graph/association-clues",
            "/system", "/system/roles", "/system/menus", "/system/event-metadata");

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
        if (request == null || request.getUsername() == null || request.getPassword() == null) {
            throw new IllegalArgumentException("请输入用户名和密码");
        }
        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getUsername, request.getUsername().trim())
                .eq(SysUser::getDeleted, false));

        if (user == null || Boolean.FALSE.equals(user.getStatus())
                || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
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

    @Transactional
    public void register(RegisterRequest request) {
        if (request == null) throw new IllegalArgumentException("注册信息不能为空");
        String username = normalized(request.getUsername());
        String password = request.getPassword() == null ? "" : request.getPassword();
        String nickname = normalized(request.getNickname());
        String email = normalized(request.getEmail());
        if (username == null || !username.matches("[A-Za-z0-9_]{4,32}")) {
            throw new IllegalArgumentException("用户名须为4—32位字母、数字或下划线");
        }
        if (password.length() < 8 || password.length() > 72
                || !password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            throw new IllegalArgumentException("密码须为8—72位，并同时包含字母和数字");
        }
        if (nickname != null && nickname.length() > 64) throw new IllegalArgumentException("昵称不能超过64个字符");
        if (email != null && (email.length() > 128 || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) {
            throw new IllegalArgumentException("邮箱格式不正确");
        }
        Long existing = userMapper.selectCount(new LambdaQueryWrapper<SysUser>().eq(SysUser::getUsername, username));
        if (existing != null && existing > 0) throw new IllegalArgumentException("用户名已被使用");
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setNickname(nickname == null ? username : nickname);
        user.setEmail(email);
        user.setStatus(true);
        user.setRoleCode(VIEWER_ROLE);
        user.setDeleted(false);
        userMapper.insert(user);
    }

    private String normalized(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private List<SysMenu> filterRetainedMenus(List<SysMenu> menus, String roleCode) {
        if (VIEWER_ROLE.equals(roleCode)) {
            return menus.stream().filter(menu -> Set.of("/case", "/case/list").contains(menu.getPath())).toList();
        }
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
