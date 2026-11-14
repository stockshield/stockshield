package com.stockshield.support;

import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;

import org.springframework.mail.MailSendException;

import com.stockshield.auth.AuthCodeMailSender;
import com.stockshield.auth.AuthPurpose;

/**
 * 가짜 메일 발송기(@Primary). 보낸 코드를 테스트가 꺼내고, 실패하게 만들 수 있다 (연결 합의서 12절).
 * 메일 장애는 이 발송기가 MailSendException을 던지게 해서 만든다(P7과 같은 방식).
 */
public class FakeAuthCodeMailSender implements AuthCodeMailSender {

    public record Sent(String to, String code, AuthPurpose purpose) {
    }

    private final Deque<Sent> sent = new ConcurrentLinkedDeque<>();
    private volatile boolean failing;

    @Override
    public void send(String to, String code, AuthPurpose purpose) {
        if (failing) {
            throw new MailSendException("테스트: 메일 서버 장애");
        }
        sent.add(new Sent(to, code, purpose));
    }

    /** 그 주소로 가장 최근에 보낸 코드 */
    public String lastCodeTo(String to) {
        Iterator<Sent> it = sent.descendingIterator();
        while (it.hasNext()) {
            Sent s = it.next();
            if (s.to().equals(to)) {
                return s.code();
            }
        }
        throw new AssertionError("이 주소로 보낸 메일이 없습니다: " + to);
    }

    public long countTo(String to) {
        return sent.stream().filter(s -> s.to().equals(to)).count();
    }

    public void setFailing(boolean failing) {
        this.failing = failing;
    }

    public void reset() {
        failing = false;
    }
}
