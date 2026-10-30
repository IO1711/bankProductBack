package com.bilolbek.bankProductCreation;

import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Application-facing queries and command orchestration for the demo HTTP API. */
@Service
@Transactional(rollbackFor=Exception.class)
public class ApiService {
    record Download(String location,String name,String mediaType) { }
    record PackageEntry(String location,String name) { }
    record Verification(String fullName,String position,String signedAt,String sha256,boolean intact,boolean active,
                        int cycle,int currentRequestCycle,String verificationMode) { }

    private final WorkflowRepository store;
    private final WorkflowService workflow;
    private final OrganizationDirectory directory;
    private final DocumentService documents;
    ApiService(WorkflowRepository store,WorkflowService workflow,OrganizationDirectory directory,DocumentService documents) {
        this.store=store; this.workflow=workflow; this.directory=directory; this.documents=documents;
    }

    private BankUser user(BankUser principal) {
        if(principal==null) throw WorkflowService.error(HttpStatus.UNAUTHORIZED,"Authentication required");
        BankUser user=directory.user(principal.user_id);
        if(user==null) throw WorkflowService.error(HttpStatus.UNAUTHORIZED,"User no longer exists in the host directory");
        return user;
    }
    private WorkflowModels.Request visible(long id,BankUser user) {
        WorkflowModels.Request request=workflow.get(id);
        if(!workflow.visible(request,user)) throw WorkflowService.error(HttpStatus.FORBIDDEN,"Not a participant");
        return request;
    }
    private WorkflowModels.Request visibleForUpdate(long id,BankUser user) {
        WorkflowModels.Request request=store.getForUpdate(id);
        if(request==null) throw WorkflowService.error(HttpStatus.NOT_FOUND,"Request not found");
        if(!workflow.visible(request,user)) throw WorkflowService.error(HttpStatus.FORBIDDEN,"Not a participant");
        return request;
    }
    Map<String,Object> me(BankUser principal) {
        BankUser user=user(principal);
        BankDepartment department=directory.department(user.department_id);
        if(department==null) throw WorkflowService.error(HttpStatus.BAD_GATEWAY,"User department is missing from the host directory");
        return Map.of("user",ApiMapper.user(user),"department",ApiMapper.department(department),"authenticated",true);
    }
    List<Map<String,Object>> departments(BankUser principal) {
        user(principal); return directory.departments().stream().map(ApiMapper::department).toList();
    }
    List<Map<String,Object>> employees(String id,BankUser principal) {
        user(principal);
        if(directory.department(id)==null) throw WorkflowService.error(HttpStatus.NOT_FOUND,"Department not found");
        return directory.employees(id).stream().map(ApiMapper::user).toList();
    }
    Map<String,Object> create(String type,BankUser principal,String ip) {
        BankUser user=user(principal);
        if(!List.of("loan","deposit").contains(type)) throw WorkflowService.error(HttpStatus.BAD_REQUEST,"type must be loan or deposit");
        if(user.is_department_head || user.is_chairman || directory.head(user.department_id)==null)
            throw WorkflowService.error(HttpStatus.FORBIDDEN,"Creator must be an employee whose department has a separate head");
        return ApiMapper.detail(workflow.create(type,user,ip),user,workflow,directory);
    }
    List<Map<String,Object>> list(String scope,BankUser principal) {
        BankUser user=user(principal);
        if(!List.of("mine","inbox","completed").contains(scope)) throw WorkflowService.error(HttpStatus.BAD_REQUEST,"Invalid scope");
        return store.all().stream().filter(request->switch(scope) {
                case "mine" -> request.creatorId.equals(user.user_id);
                case "inbox" -> !workflow.actions(request,user).isEmpty();
                case "completed" -> request.status.equals("COMPLETED") && workflow.visible(request,user);
                default -> false;
            })
            .sorted(Comparator.comparingLong((WorkflowModels.Request request)->request.id).reversed())
            .map(request->ApiMapper.summary(request,user,workflow,directory)).toList();
    }
    Map<String,Object> detail(long id,BankUser principal) {
        BankUser user=user(principal); return ApiMapper.detail(visible(id,user),user,workflow,directory);
    }
    Map<String,Object> upload(long id,String kind,String name,byte[] bytes,BankUser principal,String ip) throws Exception {
        BankUser user=user(principal);
        return ApiMapper.document(workflow.upload(visibleForUpdate(id,user),user,kind,name,bytes,ip));
    }
    void delete(long id,BankUser principal,String ip) {
        BankUser user=user(principal);
        WorkflowModels.Request request=findRequestForDocument(id);
        workflow.delete(visibleForUpdate(request.id,user),user,id,ip);
    }
    Download download(long id,boolean original,BankUser principal,String ip) {
        BankUser user=user(principal);
        WorkflowModels.Request request=findRequestForDocument(id);
        request=visibleForUpdate(request.id,user);
        WorkflowModels.Document document=workflow.document(request,id);
        String location=original || document.pdfPath==null?document.sourcePath:document.pdfPath;
        if(!documents.exists(location)) throw WorkflowService.error(HttpStatus.NOT_FOUND,"Stored file not found");
        String name=original || document.pdfPath==null?document.name:document.name.replaceAll("(?i)\\.docx?$",".pdf");
        workflow.recordDownload(request,user,document,ip);
        return new Download(location,name,location.endsWith(".pdf")?"application/pdf":"application/octet-stream");
    }
    private WorkflowModels.Request findRequestForDocument(long id) {
        WorkflowModels.Request request=store.findByDocumentId(id);
        if(request==null) throw WorkflowService.error(HttpStatus.NOT_FOUND,"Document not found");
        return request;
    }
    Map<String,Object> prepare(long id,String action,Map<String,Object> body,BankUser principal) throws Exception {
        BankUser user=user(principal);
        return workflow.prepare(visibleForUpdate(id,user),user,action,body);
    }
    Map<String,Object> action(long id,String action,Map<String,Object> body,BankUser principal,String ip) throws Exception {
        BankUser user=user(principal);
        return ApiMapper.detail(workflow.act(visibleForUpdate(id,user),user,action,body,ip),user,workflow,directory);
    }
    Map<String,Long> notifications(BankUser principal) {
        BankUser user=user(principal);
        long count=store.all().stream().filter(request->!workflow.actions(request,user).isEmpty()).count();
        return Map.of("count",count);
    }
    List<Map<String,Object>> audit(BankUser principal) {
        BankUser user=user(principal);
        if(!user.is_chairman) throw WorkflowService.error(HttpStatus.FORBIDDEN,"Chairman demo role required");
        return store.all().stream().flatMap(request->request.history.stream().map(event->{
            Map<String,Object> result=new LinkedHashMap<>(ApiMapper.event(event));
            result.put("request_id",request.id); result.put("user_id",event.actor);
            return result;
        })).toList();
    }
    List<PackageEntry> packageEntries(long id,BankUser principal,String ip) {
        BankUser user=user(principal);
        WorkflowModels.Request request=visibleForUpdate(id,user);
        if(!request.status.equals("COMPLETED")) throw WorkflowService.error(HttpStatus.CONFLICT,"Final package is not ready");
        Set<Long> finalIds=new LinkedHashSet<>();
        WorkflowModels.Document passport=workflow.latest(request,"passport");
        WorkflowModels.Document notice=workflow.latest(request,"notice");
        if(passport!=null) finalIds.add(passport.id);
        for(WorkflowModels.ReviewTask review:request.reviews) if(review.documentId!=null) finalIds.add(review.documentId);
        if(notice!=null) finalIds.add(notice.id);
        List<PackageEntry> result=new ArrayList<>();
        for(WorkflowModels.Document document:request.documents) if(finalIds.contains(document.id) && !document.deleted && document.pdfPath!=null) {
            if(!documents.exists(document.pdfPath)) throw WorkflowService.error(HttpStatus.NOT_FOUND,"Stored file not found");
            result.add(new PackageEntry(document.pdfPath,document.kind+"-"+document.id+".pdf"));
        }
        if(result.size()!=finalIds.size() || passport==null || notice==null) throw WorkflowService.error(HttpStatus.CONFLICT,"Final package is incomplete");
        workflow.recordPackageDownload(request,user,ip);
        return List.copyOf(result);
    }
    Verification verify(String code) throws Exception {
        WorkflowModels.Request request=store.findBySignatureCode(code);
        if(request!=null) for(WorkflowModels.Document document:request.documents)
            for(WorkflowModels.Signature signature:document.signatures) if(signature.code.equals(code)) {
                boolean intact=documents.exists(signature.versionPath) &&
                    documents.hashStored(signature.versionPath).equals(signature.sha256);
                boolean selected=document.kind.equals("review")
                    ? request.reviews.stream().anyMatch(review->Objects.equals(review.documentId,document.id))
                    : workflow.latest(request,document.kind)==document;
                return new Verification(signature.fullName,signature.position,signature.at,signature.sha256,
                    intact,document.cycle==request.cycle && !document.deleted && selected,document.cycle,request.cycle,
                    signature.verificationMode==null?"LEGACY_DEMO":signature.verificationMode);
            }
        throw WorkflowService.error(HttpStatus.NOT_FOUND,"Verification code not found");
    }
    void copyStored(String location,OutputStream output) throws IOException { documents.copyStored(location,output); }
}
