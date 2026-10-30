package com.bilolbek.bankProductCreation;

import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Owns short-lived signing challenges and applies identity/document checks to provider results. */
@Service
public class SigningService {
    record Session(long requestId,String action,String userId,long documentId,String hash,Instant expires) { }
    private final Map<String,Session> sessions=new HashMap<>();
    private final SignatureVerificationProvider provider;

    SigningService(SignatureVerificationProvider provider) {
        this.provider=provider;
    }

    synchronized Map<String,Object> prepare(long requestId,String action,BankUser user,long documentId,String hash) {
        String id=UUID.randomUUID().toString();
        sessions.put(id,new Session(requestId,action,user.user_id,documentId,hash,Instant.now().plusSeconds(300)));
        return Map.of("sign_session_id",id,"data_to_sign",Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hash)),
            "hash_alg","SHA-256","document_id",documentId,"demo",provider.mode().equals("DEMO"));
    }

    synchronized Session demoSession(String sessionId,BankUser user) {
        Session session=validSession(sessionId);
        if(!session.userId().equals(user.user_id)) throw WorkflowService.error(HttpStatus.FORBIDDEN,"Signing session belongs to another user");
        return session;
    }

    synchronized SignatureVerificationProvider.VerifiedSignature verify(String sessionId,long requestId,String action,
            BankUser user,long documentId,String currentHash,String pkcs7) {
        Session session=validSession(sessionId);
        if(session.requestId()!=requestId || !session.action().equals(action) || !session.userId().equals(user.user_id))
            throw WorkflowService.error(HttpStatus.UNPROCESSABLE_ENTITY,"Signing session does not match this action or user");
        if(session.documentId()!=documentId || !session.hash().equals(currentHash))
            throw WorkflowService.error(HttpStatus.CONFLICT,"Document changed after prepare");
        byte[] data=HexFormat.of().parseHex(session.hash());
        var verified=provider.verify(pkcs7,data,sessionId);
        if(verified.pinfl()==null || !verified.pinfl().equals(user.pinfl))
            throw WorkflowService.error(HttpStatus.UNPROCESSABLE_ENTITY,"Certificate PINFL does not match the current user");
        if(verified.signedDataSha256()==null || !verified.signedDataSha256().equals(session.hash()))
            throw WorkflowService.error(HttpStatus.UNPROCESSABLE_ENTITY,"Signed data does not match the prepared document");
        if(verified.certificateSerial()==null || verified.certificateSerial().isBlank() || verified.signedAt()==null)
            throw WorkflowService.error(HttpStatus.UNPROCESSABLE_ENTITY,"Incomplete certificate verification result");
        return verified;
    }

    synchronized void consume(String sessionId) { sessions.remove(sessionId); }

    private Session validSession(String id) {
        Session session=sessions.get(id);
        if(session==null || !Instant.now().isBefore(session.expires()))
            throw WorkflowService.error(HttpStatus.UNPROCESSABLE_ENTITY,"Invalid or expired signing session");
        return session;
    }
}
