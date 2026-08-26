
package com.datagraph.bank.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(name = "LoginRequest", description = "系统登录请求")
public class LoginRequest {

    @Schema(description = "用户名", example = "sadmin", requiredMode = Schema.RequiredMode.REQUIRED)
    private String username;
    @Schema(description = "密码", example = "sadmin@123", format = "password",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String password;
}
