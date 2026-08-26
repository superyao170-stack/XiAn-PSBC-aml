
package com.datagraph.bank.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

@Data
@Schema(name = "LoginResponse", description = "登录成功后的用户身份和令牌")
public class LoginResponse {

    @Schema(description = "调用业务接口时使用的 JWT；粘贴到 Swagger Authorize", example = "eyJhbGciOiJIUzI1NiJ9...")
    private String token;
    @Schema(description = "刷新令牌")
    private String refreshToken;
    private String username;
    private String nickname;
    private String roleCode;
    private String bankCode;
    private List<String> permissions;
    private List<MenuDTO> menus;
}
