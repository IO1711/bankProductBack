package com.bilolbek.bankProductCreation;

import java.util.*;

/** Keeps the PDF specification's JSON field names out of the workflow model. */
final class ApiMapper {
    private ApiMapper() { }

    static Map<String,Object> user(BankUser user) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("user_id",user.user_id); result.put("full_name",user.full_name);
        result.put("pinfl",user.pinfl); result.put("position",user.position);
        result.put("department_id",user.department_id); result.put("is_department_head",user.is_department_head);
        result.put("is_chairman",user.is_chairman); result.put("language",user.language);
        return result;
    }
    static Map<String,Object> department(BankDepartment department) {
        return Map.of("id",department.id(),"name",department.name(),"head_user_id",department.head_user_id());
    }
    static Map<String,Object> document(WorkflowModels.Document document) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",document.id); result.put("document_id",document.id); result.put("cycle",document.cycle);
        result.put("kind",document.kind); result.put("file_name",document.name); result.put("size",document.size);
        result.put("sha256",document.sha256);
        result.put("format",document.pdfPath!=null?"pdf":document.name.toLowerCase(Locale.ROOT).endsWith(".docx")?"docx":"doc");
        result.put("uploaded_by",document.uploadedBy); result.put("created_at",document.createdAt);
        result.put("signed_by",document.signatures.stream().map(signature->Map.of(
            "full_name",signature.fullName,"signed_at",signature.at,"code",signature.code,
            "sha256",signature.sha256,"signed_input_sha256",signature.signedInputSha256==null?"":signature.signedInputSha256,
            "certificate_serial",signature.certificateSerial==null?"":signature.certificateSerial,
            "verification_mode",signature.verificationMode==null?"LEGACY_DEMO":signature.verificationMode)).toList());
        return result;
    }
    static Map<String,Object> summary(WorkflowModels.Request request,BankUser user,WorkflowService workflow,OrganizationDirectory directory) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",request.id); result.put("type",request.type); result.put("cycle",request.cycle);
        BankUser creator=directory.user(request.creatorId);
        result.put("status",request.status); result.put("creator",creator==null?request.creatorId:creator.full_name);
        result.put("created_at",request.createdAt); result.put("available_actions",workflow.actions(request,user));
        return result;
    }
    static Map<String,Object> detail(WorkflowModels.Request request,BankUser user,WorkflowService workflow,OrganizationDirectory directory) {
        Map<String,Object> result=new LinkedHashMap<>(summary(request,user,workflow,directory));
        BankUser creator=directory.user(request.creatorId);
        result.put("creator_detail",Map.of("user_id",request.creatorId,
            "full_name",creator==null?request.creatorId:creator.full_name,"department_id",request.creatorDepartmentId));
        result.put("reason",request.reason); result.put("receiver_departments",request.receiverDepartments);
        result.put("notice_assignee",request.noticeAssignee); result.put("executor_department_id",request.executorDepartmentId);
        result.put("documents",request.documents.stream().filter(document->!document.deleted).map(ApiMapper::document).toList());
        result.put("review_tasks",request.reviews.stream().map(ApiMapper::review).toList());
        result.put("history",request.history.stream().map(ApiMapper::event).toList());
        return result;
    }
    static Map<String,Object> review(WorkflowModels.ReviewTask task) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("id",task.id); result.put("department_id",task.departmentId);
        result.put("assigned_user_id",task.assignedUserId); result.put("conclusion",task.conclusion);
        result.put("status",task.status); result.put("document_id",task.documentId);
        return result;
    }
    static Map<String,Object> event(WorkflowModels.Event event) {
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("at",event.at); result.put("actor",event.actor); result.put("action",event.action);
        result.put("detail",event.detail); result.put("ip",event.ip);
        result.put("cycle",event.cycle); result.put("document_id",event.documentId);
        return result;
    }
}
