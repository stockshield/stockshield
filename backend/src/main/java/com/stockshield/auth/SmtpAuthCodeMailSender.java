package com.stockshield.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Spring Mail(SMTP)로 인증 코드를 보낸다 (USR_AUTH_001 구현방안, FDS_LGN_001 구현방안).
 * 시간 제한(연결 3초·읽기 5초·쓰기 5초)은 application.yml의 spring.mail.properties에 있다.
 * 코드 값은 로그에 남기지 않는다 (SYS_SEC_001 #1).
 */
@Component
public class SmtpAuthCodeMailSender implements AuthCodeMailSender {

    private final JavaMailSender mailSender;
    private final String from;

    public SmtpAuthCodeMailSender(JavaMailSender mailSender, @Value("${spring.mail.username:}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void send(String to, String code, AuthPurpose purpose) {
        SimpleMailMessage message = new SimpleMailMessage();
        if (!from.isBlank()) {
            message.setFrom(from);
        }
        message.setTo(to);
        message.setSubject("[StockShield] 인증 코드 " + code);
        message.setText(body(code, purpose));
        mailSender.send(message);
    }

    private static String body(String code, AuthPurpose purpose) {
        String reason = purpose == AuthPurpose.SIGNUP
                ? "StockShield 회원가입 이메일 인증 코드입니다."
                : "처음 보는 IP 또는 기기에서 StockShield 로그인이 시도되었습니다. 본인이 맞으면 아래 코드를 입력하세요.";
        return reason + "\n\n인증 코드: " + code
                + "\n\n코드는 5분 동안만 쓸 수 있고, 5회 틀리면 무효가 됩니다."
                + "\n본인이 요청하지 않았다면 코드를 아무에게도 알려 주지 말고 이 메일을 무시하세요.";
    }
}
