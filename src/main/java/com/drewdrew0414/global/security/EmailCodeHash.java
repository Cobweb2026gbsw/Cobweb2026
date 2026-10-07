package com.drewdrew0414.global.security;

import com.drewdrew0414.domain.user.entity.VerificationPurpose;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/** 8자리 코드는 엔트로피가 낮으므로 단순 SHA-256 대신 서버 전용 HMAC 지문을 저장합니다. */
@Component
public class EmailCodeHash {
    private final byte[] key;

    public EmailCodeHash(@Value("${jwt.secret}") String masterSecret) {
        this.key = hmac(masterSecret.getBytes(StandardCharsets.UTF_8), "cobweb-email-code-v1");
    }

    public String hash(String email, VerificationPurpose purpose, String code) {
        String value = email.toLowerCase(Locale.ROOT) + "\0" + purpose.name() + "\0" + code;
        return HexFormat.of().formatHex(hmac(key, value));
    }

    public boolean matches(String savedHash, String email, VerificationPurpose purpose, String code) {
        return MessageDigest.isEqual(savedHash.getBytes(StandardCharsets.US_ASCII),
                hash(email, purpose, code).getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", e);
        }
    }
}
