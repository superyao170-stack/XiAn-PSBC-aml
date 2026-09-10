package com.datagraph.bank.controller;

import com.datagraph.bank.common.response.CommonResult;
import com.datagraph.bank.dto.LoginRequest;
import com.datagraph.bank.dto.LoginResponse;
import com.datagraph.bank.dto.RegisterRequest;
import com.datagraph.bank.security.CurrentUser;
import com.datagraph.bank.security.UserPrincipal;
import com.datagraph.bank.service.AuditService;
import com.datagraph.bank.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@CrossOrigin(origins = "*")
public class AuthController {
    private final AuthService authService;
    private final AuditService auditService;
    private final CurrentUser currentUser;
    private final JdbcTemplate jdbc;

    public AuthController(AuthService authService, AuditService auditService, CurrentUser currentUser, JdbcTemplate jdbc) {
        this.authService = authService;
        this.auditService = auditService;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    @PostMapping("/login")
    @Operation(
            summary = "0. 登录并获取 JWT",
            description = "输入系统用户名和密码。成功后复制响应 data.token，点击 Swagger UI 右上角 Authorize 并粘贴 token。")
    @SecurityRequirements
    public CommonResult<LoginResponse> login(@RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        try {
            LoginResponse response = authService.login(request);
            auditService.login(request.getUsername(), response.getBankCode(), servletRequest.getRemoteAddr(),
                    servletRequest.getHeader("User-Agent"), true, null);
            return CommonResult.success(response);
        } catch (RuntimeException exception) {
            auditService.login(request.getUsername(), null, servletRequest.getRemoteAddr(),
                    servletRequest.getHeader("User-Agent"), false, exception.getMessage());
            throw exception;
        }
    }

    @PostMapping("/register")
    @SecurityRequirements
    public CommonResult<Void> register(@RequestBody RegisterRequest request) {
        authService.register(request);
        return CommonResult.success("注册成功，请登录", null);
    }

    @PostMapping("/logout")
    public CommonResult<Void> logout() {
        return CommonResult.success(null);
    }

    @GetMapping("/me")
    public CommonResult<java.util.Map<String,Object>> getCurrentUser() {
        var rows = jdbc.queryForList("SELECT username,nickname,bank_code,role_code,email,phone FROM sys_user WHERE username=? AND deleted=false", currentUser.username());
        if (rows.isEmpty()) return CommonResult.error(404, "用户不存在");
        return CommonResult.success(rows.get(0));
    }

    @PutMapping("/me")
    public CommonResult<Void> updateCurrentUser(@RequestBody Map<String,Object> body) {
        String nickname = body.get("nickname") == null ? null : String.valueOf(body.get("nickname"));
        String bankCode = body.get("bankCode") == null ? null : String.valueOf(body.get("bankCode"));
        jdbc.update("UPDATE sys_user SET nickname=COALESCE(?,nickname), bank_code=COALESCE(?,bank_code), updated_at=CURRENT_TIMESTAMP WHERE username=?",
                nickname, bankCode, currentUser.username());
        return CommonResult.success(null);
    }
}
