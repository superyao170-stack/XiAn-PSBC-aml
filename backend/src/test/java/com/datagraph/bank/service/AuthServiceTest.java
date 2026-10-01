package com.datagraph.bank.service;

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
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AuthServiceTest {

    @Test
    void registerCreatesReadOnlyViewerWithEncodedPassword() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        when(userMapper.selectCount(any())).thenReturn(0L);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        AuthService service = new AuthService(userMapper, mock(SysRoleMapper.class),
                mock(SysMenuMapper.class), passwordEncoder, mock(JwtTokenProvider.class));
        RegisterRequest request = new RegisterRequest();
        request.setUsername("case_reader");
        request.setPassword("reader123");
        request.setNickname("案例读者");

        service.register(request);

        var captor = org.mockito.ArgumentCaptor.forClass(SysUser.class);
        verify(userMapper).insert(captor.capture());
        assertEquals("viewer", captor.getValue().getRoleCode());
        assertEquals("encoded-password", captor.getValue().getPassword());
        assertTrue(captor.getValue().getStatus());
    }

    @Test
    void registerRejectsWeakPassword() {
        AuthService service = new AuthService(mock(SysUserMapper.class), mock(SysRoleMapper.class),
                mock(SysMenuMapper.class), mock(PasswordEncoder.class), mock(JwtTokenProvider.class));
        RegisterRequest request = new RegisterRequest();
        request.setUsername("case_reader");
        request.setPassword("onlyletters");
        assertThrows(IllegalArgumentException.class, () -> service.register(request));
    }

    @Test
    void loginReturnsCurrentUploadProcessingAndIndicatorMenuPaths() {
        SysUserMapper userMapper = mock(SysUserMapper.class);
        SysRoleMapper roleMapper = mock(SysRoleMapper.class);
        SysMenuMapper menuMapper = mock(SysMenuMapper.class);
        PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
        JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
        AuthService service = new AuthService(
                userMapper, roleMapper, menuMapper, passwordEncoder, tokenProvider);

        SysUser user = new SysUser();
        user.setUsername("sadmin");
        user.setPassword("encoded");
        user.setRoleCode("sadmin");
        user.setDeleted(false);
        SysRole role = new SysRole();
        role.setRoleCode("sadmin");
        role.setPermissions(new String[0]);
        when(userMapper.selectOne(any())).thenReturn(user);
        when(roleMapper.selectOne(any())).thenReturn(role);
        when(passwordEncoder.matches("password", "encoded")).thenReturn(true);
        when(tokenProvider.generateToken("sadmin", "sadmin", null)).thenReturn("token");
        when(tokenProvider.generateRefreshToken("sadmin")).thenReturn("refresh");
        when(menuMapper.selectList(any())).thenReturn(List.of(
                menu(28L, 0L, "/analysis/upload", 2),
                menu(61L, 0L, "/case", 3),
                menu(62L, 61L, "/case/processing", 2),
                menu(63L, 62L, "/case/processing/report", 1),
                menu(90L, 0L, "/system", 99),
                menu(94L, 90L, "/system/indicators", 4),
                menu(99L, 0L, "/retired/path", 99)));

        LoginRequest request = new LoginRequest();
        request.setUsername("sadmin");
        request.setPassword("password");
        LoginResponse response = service.login(request);

        List<String> paths = flatten(response.getMenus());
        assertTrue(paths.contains("/analysis/upload"));
        assertTrue(paths.contains("/case/processing"));
        assertTrue(paths.contains("/case/processing/report"));
        assertTrue(paths.contains("/system/indicators"));
        assertTrue(!paths.contains("/retired/path"));
    }

    private SysMenu menu(long id, long parentId, String path, int sortOrder) {
        SysMenu menu = new SysMenu();
        menu.setId(id);
        menu.setParentId(parentId);
        menu.setMenuName(path);
        menu.setPath(path);
        menu.setSortOrder(sortOrder);
        menu.setVisible(true);
        return menu;
    }

    private List<String> flatten(List<MenuDTO> menus) {
        return menus.stream()
                .flatMap(menu -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(menu.getPath()),
                        flatten(menu.getChildren()).stream()))
                .toList();
    }
}
