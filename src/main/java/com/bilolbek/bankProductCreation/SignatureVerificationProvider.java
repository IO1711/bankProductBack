package com.bilolbek.bankProductCreation;

import java.time.Instant;

/**
 * Implement this port with the bank's E-IMZO verification service. An implementation
 * must validate the detached PKCS#7 over the supplied SHA-256 bytes, certificate
 * chain, revocation/validity and any required timestamp before returning a result.
 */
public interface SignatureVerificationProvider {
    String mode();
    VerifiedSignature verify(String pkcs7, byte[] dataToSign, String signingSessionId);

    record VerifiedSignature(String pinfl, String certificateSerial, Instant signedAt,
                             String signedDataSha256, String verificationMode) { }
}
