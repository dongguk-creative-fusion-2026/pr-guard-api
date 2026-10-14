package com.prguard.pipeline;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** X-Hub-Signature-256 확인: 받은 본문 그대로의 HMAC-SHA256. */
final class WebhookSignature {

    private static final String PREFIX = "sha256=";

    private WebhookSignature() {
    }

    static boolean valid(String secret, byte[] body, String header) {
        if (header == null || !header.startsWith(PREFIX)) {
            return false;
        }
        byte[] given;
        try {
            given = HexFormat.of().parseHex(header.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            return false;
        }
        // 길이 · 내용 모두 일정 시간 비교
        return MessageDigest.isEqual(sign(secret, body), given);
    }

    static String header(String secret, byte[] body) {
        return PREFIX + HexFormat.of().formatHex(sign(secret, body));
    }

    private static byte[] sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
