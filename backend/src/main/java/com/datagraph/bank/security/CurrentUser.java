package com.datagraph.bank.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    public static final String DEFAULT_BANK_CODE = "中国测试银行";
    public UserPrincipal principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw new IllegalStateException("Authenticated user context is unavailable");
        }
        return principal;
    }

    public String username() {
        return principal().username();
    }

    public String roleCode() {
        return principal().roleCode();
    }

    public boolean isBankAdmin() {
        return "badmin".equals(roleCode());
    }

    public boolean hasAnyRole(String... roles) {
        return java.util.Arrays.asList(roles).contains(roleCode());
    }

    public void requireAnyRole(String... roles) {
        if (!hasAnyRole(roles)) throw new SecurityException("Current role cannot perform this operation");
    }

    public String requiredBankCode() {
        String bankCode = principal().bankCode();
        if (bankCode == null || bankCode.isBlank()) {
            throw new IllegalStateException("Bank administrator is not bound to a bank");
        }
        return bankCode;
    }

    public String scopedBankCode(String requestedBankCode) {
        if (!isBankAdmin()) {
            if (requestedBankCode != null && !requestedBankCode.isBlank()) return requestedBankCode;
            String profileBank = principal().bankCode();
            return profileBank == null || profileBank.isBlank() ? DEFAULT_BANK_CODE : profileBank;
        }
        String ownBank = requiredBankCode();
        if (requestedBankCode != null && !requestedBankCode.isBlank() && !ownBank.equals(requestedBankCode)) {
            throw new SecurityException("Cross-bank access is forbidden");
        }
        return ownBank;
    }

    public void requireAccessToBank(String bankCode) {
        if (isBankAdmin() && !requiredBankCode().equals(bankCode)) {
            throw new SecurityException("Cross-bank access is forbidden");
        }
    }
}
