package com.bilolbek.bankProductCreation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Simulates an external E-IMZO system; its tokens are deliberately not PKCS#7. */
@Component
@ConditionalOnProperty(name="app.eimzo.mode",havingValue="demo",matchIfMissing=true)
public final class DemoEimzoProvider implements SignatureVerificationProvider {
    private static final Base64.Encoder ENCODER=Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER=Base64.getUrlDecoder();
    private final byte[] secret=new byte[32];

    DemoEimzoProvider() { new SecureRandom().nextBytes(secret); }

    @Override public String mode() { return "DEMO"; }

    String issue(String sessionId,String pinfl,byte[] dataToSign) {
        String payload=sessionId+":"+pinfl+":"+HexFormat.of().formatHex(dataToSign)+":"+Instant.now().getEpochSecond();
        byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
        return "DEMO."+ENCODER.encodeToString(bytes)+"."+ENCODER.encodeToString(mac(bytes));
    }

    @Override public VerifiedSignature verify(String pkcs7,byte[] dataToSign,String signingSessionId) {
        try {
            String[] parts=pkcs7.split("\\.",-1);
            if(parts.length!=3 || !parts[0].equals("DEMO")) throw new IllegalArgumentException();
            byte[] payload=DECODER.decode(parts[1]);
            if(!MessageDigest.isEqual(mac(payload),DECODER.decode(parts[2]))) throw new IllegalArgumentException();
            String[] fields=new String(payload,StandardCharsets.UTF_8).split(":",-1);
            if(fields.length!=4 || !fields[0].equals(signingSessionId) || !fields[2].equals(HexFormat.of().formatHex(dataToSign)))
                throw new IllegalArgumentException();
            long issued=Long.parseLong(fields[3]);
            long now=Instant.now().getEpochSecond();
            if(issued>now+30 || issued<now-300) throw new IllegalArgumentException();
            return new VerifiedSignature(fields[1],"DEMO-CERT-"+fields[1],Instant.ofEpochSecond(issued),
                fields[2],"DEMO");
        } catch (IllegalArgumentException e) {
            throw WorkflowService.error(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid demo signature");
        }
    }

    private byte[] mac(byte[] data) {
        try {
            Mac mac=Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret,"HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) { throw new IllegalStateException("Demo signer unavailable",e); }
    }
}
