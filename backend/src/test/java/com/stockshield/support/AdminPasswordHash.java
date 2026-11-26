package com.stockshield.support;

import java.io.BufferedReader;
import java.io.Console;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 최초 관리자 비밀번호의 BCrypt 해시를 만든다. 실행: .\gradlew.bat adminPasswordHash --console=plain -q
 * 앱(PasswordEncoderConfig)과 같은 BCryptPasswordEncoder(강도 10)라 $2a$10$로 시작하는 60자가 나온다.
 * 출력한 해시만 infra/.env의 ADMIN_PWD_HASH에 넣고 비밀번호 원문은 어디에도 적지 않는다.
 */
public final class AdminPasswordHash {

    /** 두 번 읽을 때 같은 버퍼를 써야 첫 줄을 읽으며 둘째 줄까지 삼키지 않는다 */
    private static final BufferedReader STDIN =
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

    private AdminPasswordHash() {
    }

    public static void main(String[] args) throws Exception {
        String password = read("관리자 비밀번호: ");
        String again = read("한 번 더: ");
        if (password == null || password.isEmpty() || !password.equals(again)) {
            System.err.println("두 번 입력한 비밀번호가 다르거나 비어 있습니다.");
            System.exit(1);
        }
        if (password.length() > 72) {
            System.err.println("BCrypt는 72바이트까지만 비교합니다. 72자 이하로 정하세요.");
            System.exit(1);
        }
        System.out.println(new BCryptPasswordEncoder().encode(password));
    }

    private static String read(String prompt) throws Exception {
        Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword(prompt);
            return chars == null ? null : new String(chars);
        }
        // Gradle로 실행하면 콘솔이 없어 입력이 화면에 보인다. 명령 기록에는 남지 않는다
        System.out.print(prompt);
        System.out.flush();
        return STDIN.readLine();
    }
}
