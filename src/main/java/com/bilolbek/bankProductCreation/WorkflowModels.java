package com.bilolbek.bankProductCreation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Mutable workflow aggregate loaded and saved through WorkflowRepository. */
public final class WorkflowModels {
    private WorkflowModels() { }

    public static class Signature {
        public long id;
        public String code,userId,fullName,position,at,sha256,versionPath;
        public String signedInputSha256,signerPinfl,certificateSerial,verificationMode,pkcs7;
        public Signature() { }
        Signature(String code,BankUser user,String sha256,String versionPath,
                  SignatureVerificationProvider.VerifiedSignature verified,String pkcs7) {
            this.code=code; this.userId=user.user_id; this.fullName=user.full_name;
            this.position=user.position; this.at=verified.signedAt().toString();
            this.sha256=sha256; this.versionPath=versionPath;
            this.signedInputSha256=verified.signedDataSha256(); this.signerPinfl=verified.pinfl();
            this.certificateSerial=verified.certificateSerial(); this.verificationMode=verified.verificationMode();
            this.pkcs7=pkcs7;
        }
    }
    public static class Document {
        public long id; public int cycle; public String kind,name,sourcePath,pdfPath,sha256,uploadedBy,createdAt;
        public long size; public boolean deleted;
        public List<Signature> signatures=new ArrayList<>();
    }
    public static class ReviewTask {
        public long id; public String departmentId,assignedUserId,conclusion,status="ASSIGNING";
        public Long documentId;
    }
    public static class Event {
        public long id;
        public String at,actor,action,detail,ip;
        public Long documentId;
        public int cycle;
        public Event() { }
        Event(String actor,String action,String detail,String ip,int cycle,Long documentId) {
            this.at=Instant.now().toString(); this.actor=actor; this.action=action;
            this.detail=detail; this.ip=ip; this.cycle=cycle; this.documentId=documentId;
        }
    }
    public static class Request {
        public long id,version;
        public String type,status="DRAFT",creatorId,creatorDepartmentId,createdAt,reason;
        public int cycle=1; public String noticeAssignee,executorDepartmentId;
        public List<String> receiverDepartments=new ArrayList<>();
        public Set<String> participantIds=new HashSet<>();
        public List<Document> documents=new ArrayList<>();
        public List<ReviewTask> reviews=new ArrayList<>();
        public List<Event> history=new ArrayList<>();
    }
}
