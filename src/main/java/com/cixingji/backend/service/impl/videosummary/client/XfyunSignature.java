package com.cixingji.backend.service.impl.videosummary.client;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public final class XfyunSignature {

    private XfyunSignature() {
    }

    public static String create(String appId, String timestamp, String secretKey) {
        try {
            MessageDigest md5 = MessageDigest.getInstance("MD5");
            byte[] digest = md5.digest((appId + timestamp).getBytes(StandardCharsets.UTF_8));
            StringBuilder md5Hex = new StringBuilder();
            for (byte value : digest) {
                md5Hex.append(String.format("%02x", value & 0xff));
            }

            Mac hmacSha1 = Mac.getInstance("HmacSHA1");
            hmacSha1.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] signature = hmacSha1.doFinal(md5Hex.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signature);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Required signing algorithm is unavailable", e);
        } catch (Exception e) {
            throw new IllegalStateException("Could not create XFYun signature", e);
        }
    }
}
