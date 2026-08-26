package com.datagraph.bank.security;

public record UserPrincipal(String username, String roleCode, String bankCode) {
}
