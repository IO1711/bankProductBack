package com.bilolbek.bankProductCreation;

import java.io.Serializable;
import java.time.Instant;
import java.util.*;

/** Legacy serialized state format, retained only for explicit data import. */
public class DemoStore {
    public static class Signature implements Serializable {
        private static final long serialVersionUID=-4508791406243816108L;
        public String code, userId, fullName, position, at, sha256, versionPath;
        public String signedInputSha256, signerPinfl, certificateSerial, verificationMode, pkcs7;
        Signature(String code, BankUser user, String sha256, String versionPath,
                  SignatureVerificationProvider.VerifiedSignature verified,String pkcs7) {
            this.code=code; this.userId=user.user_id; this.fullName=user.full_name;
            this.position=user.position; this.at=verified.signedAt().toString();
            this.sha256=sha256; this.versionPath=versionPath;
            this.signedInputSha256=verified.signedDataSha256(); this.signerPinfl=verified.pinfl();
            this.certificateSerial=verified.certificateSerial(); this.verificationMode=verified.verificationMode();
            this.pkcs7=pkcs7;
        }
    }
    public static class Document implements Serializable {
        private static final long serialVersionUID=8074647388245884126L;
        public long id; public int cycle; public String kind, name, sourcePath, pdfPath, sha256, uploadedBy, createdAt;
        public long size; public boolean deleted;
        public List<Signature> signatures=new ArrayList<>();
    }
    public static class ReviewTask implements Serializable {
        private static final long serialVersionUID=-8970627474507289091L;
        public long id; public String departmentId, assignedUserId, conclusion, status="ASSIGNING";
        public Long documentId;
    }
    public static class Event implements Serializable {
        private static final long serialVersionUID=-8224880433758078160L;
        public String at, actor, action, detail, ip;
        public Long documentId;
        public int cycle;
        Event(String actor, String action, String detail, String ip) {
            this(actor,action,detail,ip,1,null);
        }
        Event(String actor,String action,String detail,String ip,int cycle,Long documentId) {
            this.at=Instant.now().toString(); this.actor=actor; this.action=action;
            this.detail=detail; this.ip=ip; this.cycle=cycle; this.documentId=documentId;
        }
    }
    public static class Request implements Serializable {
        private static final long serialVersionUID=-3629138176731832249L;
        public long id; public String type, status="DRAFT", creatorId, creatorDepartmentId, createdAt, reason;
        public int cycle=1; public String noticeAssignee, executorDepartmentId;
        public List<String> receiverDepartments=new ArrayList<>();
        public Set<String> participantIds=new HashSet<>();
        public List<Document> documents=new ArrayList<>();
        public List<ReviewTask> reviews=new ArrayList<>();
        public List<Event> history=new ArrayList<>();
    }
    static class State implements Serializable {
        private static final long serialVersionUID=4274629132566491889L;
        long nextRequest=1, nextDocument=1, nextReview=1;
        List<Request> requests=new ArrayList<>();
    }

}
