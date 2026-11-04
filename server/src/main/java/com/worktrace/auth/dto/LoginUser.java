package com.worktrace.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 存入 HttpSession 的最小用户信息（不含密码）。
 * Spring Session JDBC 以 JDK 序列化落库，必须实现 Serializable 并固定 serialVersionUID。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 在 HttpSession 中的属性名 */
    public static final String SESSION_KEY = "LOGIN_USER";

    private Long id;

    private String username;

    private String nickname;
}
