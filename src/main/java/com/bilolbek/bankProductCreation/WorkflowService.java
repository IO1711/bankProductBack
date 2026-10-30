package com.bilolbek.bankProductCreation;

import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class WorkflowService {
    private final WorkflowRepository store;
    private final DocumentService files;
    private final SigningService signing;
    private final OrganizationDirectory directory;

    WorkflowService(WorkflowRepository store, DocumentService files, SigningService signing, OrganizationDirectory directory) {
        this.store = store;
        this.files = files;
        this.signing = signing;
        this.directory = directory;
    }

    static ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    synchronized WorkflowModels.Request create(String type, BankUser creator, String ip) {
        WorkflowModels.Request request = new WorkflowModels.Request();
        request.type = type;
        request.creatorId = creator.user_id;
        request.creatorDepartmentId = creator.department_id;
        request.createdAt = Instant.now().toString();
        request.history.add(new WorkflowModels.Event(creator.user_id, "create", type, ip, 1, null));
        return store.add(request);
    }

    WorkflowModels.Request get(long id) {
        WorkflowModels.Request r = store.get(id);
        if (r == null)
            throw error(HttpStatus.NOT_FOUND, "Request not found");
        return r;
    }

    synchronized List<String> actions(WorkflowModels.Request r, BankUser u) {
        List<String> a = new ArrayList<>();
        String s = r.status;
        boolean creator = u.user_id.equals(r.creatorId), creatorHead = isHead(u, r.creatorDepartmentId);
        if (creator && List.of("DRAFT", "PASSPORT_REJECTED", "NEGATIVE_REVIEW", "CHAIRMAN_REJECTED").contains(s))
            a.add("submit_passport");
        if (creatorHead && s.equals("PASSPORT_WAIT_HEAD")) {
            a.add("head_approve");
            a.add("head_reject");
        }
        if (creator && List.of("PASSPORT_APPROVED", "LETTER_RETURNED").contains(s))
            a.add("submit_letter");
        if (creatorHead && s.equals("LETTER_WAIT_HEAD")) {
            a.add("letter_approve");
            a.add("letter_return");
        }
        if (List.of("REVIEW_ASSIGNING", "REVIEW_IN_PROGRESS", "REVIEW_WAIT_HEAD").contains(s)) {
            for (var task : r.reviews) {
                if (isHead(u, task.departmentId) && task.status.equals("ASSIGNING"))
                    add(a, "assign");
                if (u.user_id.equals(task.assignedUserId) && task.status.equals("IN_PROGRESS"))
                    add(a, "submit_review");
                if (isHead(u, task.departmentId) && task.status.equals("WAIT_HEAD"))
                    add(a, "review_head_sign");
            }
        }
        if (creatorHead && s.equals("CREATOR_HEAD_ASSIGNING"))
            a.add("assign");
        if (u.user_id.equals(r.noticeAssignee) && List.of("NOTICE_DRAFT", "NOTICE_RETURNED").contains(s))
            a.add("submit_notice");
        if (creatorHead && s.equals("NOTICE_WAIT_HEAD")) {
            a.add("notice_approve");
            a.add("notice_reject");
        }
        if (u.is_chairman && s.equals("WAIT_CHAIRMAN")) {
            a.add("chairman_approve");
            a.add("chairman_reject");
        }
        return a;
    }

    private void add(List<String> list, String action) {
        if (!list.contains(action))
            list.add(action);
    }

    private boolean isHead(BankUser u, String dep) {
        return u.is_department_head && u.department_id.equals(dep);
    }

    boolean visible(WorkflowModels.Request r, BankUser u) {
        boolean chairmanStage = u.is_chairman && List.of("WAIT_CHAIRMAN", "COMPLETED").contains(r.status);
        return chairmanStage || u.user_id.equals(r.creatorId) || isHead(u, r.creatorDepartmentId)
                || r.participantIds.contains(u.user_id)
                || u.user_id.equals(r.noticeAssignee)
                || r.reviews.stream()
                        .anyMatch(t -> t.departmentId.equals(u.department_id)
                                && (isHead(u, t.departmentId) || u.user_id.equals(t.assignedUserId)))
                || (r.status.equals("COMPLETED") && u.department_id.equals(r.executorDepartmentId));
    }

    WorkflowModels.Document latest(WorkflowModels.Request r, String kind) {
        for (int i = r.documents.size() - 1; i >= 0; i--) {
            var d = r.documents.get(i);
            if (d.cycle == r.cycle && !d.deleted && d.kind.equals(kind))
                return d;
        }
        return null;
    }

    WorkflowModels.Document document(WorkflowModels.Request r, long id) {
        return r.documents.stream().filter(d -> d.id == id && !d.deleted).findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Document not found"));
    }

    synchronized WorkflowModels.Document upload(WorkflowModels.Request r, BankUser u, String kind, String name, byte[] bytes,
            String ip) throws Exception {
        if (!visible(r, u))
            throw error(HttpStatus.FORBIDDEN, "Not a participant");
        if (!List.of("passport", "letter", "review", "notice").contains(kind))
            throw error(HttpStatus.BAD_REQUEST, "Invalid document kind");
        if (name == null || !name.toLowerCase(Locale.ROOT).matches("^[^/\\\\]+\\.docx?$"))
            throw error(HttpStatus.BAD_REQUEST, "Only DOC/DOCX files are allowed");
        if (bytes.length == 0 || bytes.length > 50L * 1024 * 1024)
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "File must be 1 byte to 50 MB");
        boolean docx = name.toLowerCase(Locale.ROOT).endsWith(".docx");
        if (!DocumentService.matchesWordFormat(bytes, docx))
            throw error(HttpStatus.BAD_REQUEST, "File contents do not match the DOC/DOCX extension");
        boolean allowed = switch (kind) {
            case "passport" -> u.user_id.equals(r.creatorId)
                    && List.of("DRAFT", "PASSPORT_REJECTED", "NEGATIVE_REVIEW", "CHAIRMAN_REJECTED").contains(r.status);
            case "letter" ->
                u.user_id.equals(r.creatorId) && List.of("PASSPORT_APPROVED", "LETTER_RETURNED").contains(r.status);
            case "review" ->
                r.reviews.stream().anyMatch(t -> u.user_id.equals(t.assignedUserId) && t.status.equals("IN_PROGRESS"));
            case "notice" ->
                u.user_id.equals(r.noticeAssignee) && List.of("NOTICE_DRAFT", "NOTICE_RETURNED").contains(r.status);
            default -> false;
        };
        if (!allowed)
            throw error(HttpStatus.FORBIDDEN, "Upload not allowed in this stage");
        WorkflowModels.Document previous = latest(r, kind);
        if (previous != null && !previous.signatures.isEmpty() && !List.of("review", "letter", "notice").contains(kind))
            throw error(HttpStatus.CONFLICT, "Signed document is immutable");
        WorkflowModels.Document d = new WorkflowModels.Document();
        d.id = store.documentId();
        d.cycle = r.cycle;
        d.kind = kind;
        d.name = name;
        d.size = bytes.length;
        d.sha256 = DocumentService.hash(bytes);
        d.sourcePath = files.saveUpload(bytes,name);
        if (previous != null && previous.uploadedBy.equals(u.user_id) && previous.signatures.isEmpty())
            previous.deleted = true;
        d.uploadedBy = u.user_id;
        d.createdAt = Instant.now().toString();
        r.documents.add(d);
        event(r, u, "upload_" + kind, name, ip, r.cycle, d.id);
        store.save(r);
        return d;
    }

    synchronized void delete(WorkflowModels.Request r, BankUser u, long docId, String ip) {
        WorkflowModels.Document d = document(r, docId);
        if (d.cycle != r.cycle || !d.uploadedBy.equals(u.user_id) || !d.signatures.isEmpty())
            throw error(HttpStatus.FORBIDDEN, "Only your unsigned current document can be deleted");
        d.deleted = true;
        event(r, u, "delete_document", String.valueOf(docId), ip, r.cycle, docId);
        store.save(r);
    }

    synchronized Map<String, Object> prepare(WorkflowModels.Request r, BankUser u, String action, Map<String, Object> body)
            throws Exception {
        if (!actions(r, u).contains(action))
            throw error(HttpStatus.CONFLICT, "Action not available");
        if (!signed(action))
            throw error(HttpStatus.BAD_REQUEST, "This action does not need a signature");
        WorkflowModels.Document d = actionDocument(r, u, action, body);
        if (d == null)
            throw error(HttpStatus.CONFLICT, "Upload the required document first");
        String hash = files.hashStored(files.ensurePdf(d));
        d.sha256 = hash;
        store.save(r);
        return signing.prepare(r.id, action, u, d.id, hash);
    }

    private boolean signed(String action) {
        return Set.of("submit_passport", "head_approve", "submit_letter", "letter_approve",
                "submit_review", "review_head_sign", "submit_notice", "notice_approve", "chairman_approve")
                .contains(action);
    }

    private WorkflowModels.Document actionDocument(WorkflowModels.Request r, BankUser u, String action,
            Map<String, Object> body) {
        String kind = switch (action) {
            case "submit_passport", "head_approve", "chairman_approve" -> "passport";
            case "submit_letter", "letter_approve" -> "letter";
            case "submit_review", "review_head_sign" -> "review";
            case "submit_notice", "notice_approve" -> "notice";
            default -> null;
        };
        if (kind == null)
            return null;
        if (action.equals("review_head_sign")) {
            WorkflowModels.ReviewTask t = reviewTask(r, number(body, "review_task_id"));
            if (!isHead(u, t.departmentId) || t.documentId == null)
                throw error(HttpStatus.FORBIDDEN, "Not your review");
            return document(r, t.documentId);
        }
        if (action.equals("submit_review")) {
            WorkflowModels.ReviewTask t = r.reviews.stream()
                    .filter(x -> u.user_id.equals(x.assignedUserId) && x.status.equals("IN_PROGRESS")).findFirst()
                    .orElse(null);
            if (t == null)
                return null;
            Object id = body.get("document_id");
            if (id != null) {
                WorkflowModels.Document d = document(r, number(body, "document_id"));
                return d.kind.equals("review") && d.uploadedBy.equals(u.user_id) && d.cycle == r.cycle ? d : null;
            }
            for (int i = r.documents.size() - 1; i >= 0; i--) {
                var d = r.documents.get(i);
                if (d.kind.equals("review") && d.cycle == r.cycle && d.uploadedBy.equals(u.user_id) && !d.deleted
                        && d.signatures.isEmpty())
                    return d;
            }
            return null;
        }
        WorkflowModels.Document d = latest(r, kind);
        if (d != null && body.containsKey("document_id") && number(body, "document_id") != d.id)
            throw error(HttpStatus.CONFLICT, "Document is not current");
        return d;
    }

    private long number(Map<String, Object> body, String key) {
        try {
            return Long.parseLong(String.valueOf(body.get(key)));
        } catch (Exception e) {
            throw error(HttpStatus.BAD_REQUEST, "Missing or invalid " + key);
        }
    }

    private String required(Map<String, Object> body, String key) {
        String v = String.valueOf(body.getOrDefault(key, "")).trim();
        if (v.isBlank())
            throw error(HttpStatus.BAD_REQUEST, key + " is required");
        return v;
    }

    private WorkflowModels.ReviewTask reviewTask(WorkflowModels.Request r, long id) {
        return r.reviews.stream().filter(t -> t.id == id).findFirst()
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Review task not found"));
    }

    synchronized WorkflowModels.Request act(WorkflowModels.Request r, BankUser u, String action, Map<String, Object> body,
            String ip) throws Exception {
        if (!actions(r, u).contains(action))
            throw error(HttpStatus.CONFLICT, "Action not available for this user and status");
        WorkflowModels.Document d = null;
        SignatureVerificationProvider.VerifiedSignature verified = null;
        String token = null;
        String pkcs7 = null;
        if (signed(action)) {
            token = required(body, "sign_session_id");
            pkcs7 = required(body, "pkcs7");
            d = actionDocument(r, u, action, body);
            if (d == null || d.pdfPath == null)
                throw error(HttpStatus.CONFLICT, "Document changed after prepare");
            String current = files.hashStored(d.pdfPath);
            verified = signing.verify(token, r.id, action, u, d.id, current, pkcs7);
            if (d.signatures.stream().anyMatch(s -> s.userId.equals(u.user_id)))
                throw error(HttpStatus.CONFLICT, "This user already signed the document");
        }
        validateActionParameters(r, u, action, body);
        String version = null;
        String code = null;
        String versionHash = null;
        if (d != null) {
            code = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            version = files.signedVersion(d, u, code, verified.verificationMode());
            versionHash = files.hashStored(version);
        }
        int actionCycle = r.cycle;
        switch (action) {
            case "submit_passport" -> {
                r.reason = null;
                r.status = "PASSPORT_WAIT_HEAD";
            }
            case "head_approve" -> r.status = "PASSPORT_APPROVED";
            case "head_reject" -> {
                r.reason = required(body, "reason");
                newCycle(r, "PASSPORT_REJECTED");
            }
            case "submit_letter" -> {
                Object raw = body.get("department_ids");
                if (!(raw instanceof List<?> list) || list.isEmpty())
                    throw error(HttpStatus.BAD_REQUEST, "Choose receiving departments");
                LinkedHashSet<String> deps = new LinkedHashSet<>();
                for (Object item : list) {
                    String dep = String.valueOf(item);
                    if (directory.department(dep) == null || dep.equals(r.creatorDepartmentId))
                        throw error(HttpStatus.BAD_REQUEST, "Invalid receiving department");
                    deps.add(dep);
                }
                r.reason = null;
                r.receiverDepartments = new ArrayList<>(deps);
                r.status = "LETTER_WAIT_HEAD";
            }
            case "letter_approve" -> {
                r.reviews.clear();
                for (String dep : r.receiverDepartments) {
                    var t = new WorkflowModels.ReviewTask();
                    t.id = store.reviewId();
                    t.departmentId = dep;
                    r.reviews.add(t);
                    r.participantIds.add(directory.head(dep).user_id);
                }
                r.status = "REVIEW_ASSIGNING";
            }
            case "letter_return" -> {
                r.reason = required(body, "reason");
                r.status = "LETTER_RETURNED";
            }
            case "assign" -> assign(r, u, required(body, "user_id"));
            case "submit_review" -> {
                String conclusion = required(body, "conclusion");
                if (!List.of("positive", "negative").contains(conclusion))
                    throw error(HttpStatus.BAD_REQUEST, "Invalid conclusion");
                var t = r.reviews.stream()
                        .filter(x -> u.user_id.equals(x.assignedUserId) && x.status.equals("IN_PROGRESS")).findFirst()
                        .orElseThrow();
                t.documentId = d.id;
                t.conclusion = conclusion;
                t.status = "WAIT_HEAD";
                r.status = "REVIEW_WAIT_HEAD";
            }
            case "review_head_sign" -> {
                var t = reviewTask(r, number(body, "review_task_id"));
                if (!t.status.equals("WAIT_HEAD"))
                    throw error(HttpStatus.CONFLICT, "Review is not awaiting signature");
                t.status = "DONE";
                if (r.reviews.stream().allMatch(x -> x.status.equals("DONE"))) {
                    if (r.reviews.stream().anyMatch(x -> "negative".equals(x.conclusion)))
                        newCycle(r, "NEGATIVE_REVIEW");
                    else
                        r.status = "CREATOR_HEAD_ASSIGNING";
                } else
                    r.status = "REVIEW_IN_PROGRESS";
            }
            case "submit_notice" -> {
                r.reason = null;
                r.status = "NOTICE_WAIT_HEAD";
            }
            case "notice_approve" -> {
                r.participantIds.add("u-chair");
                r.status = "WAIT_CHAIRMAN";
            }
            case "notice_reject" -> {
                r.reason = required(body, "reason");
                r.status = "NOTICE_RETURNED";
            }
            case "chairman_approve" -> {
                String dep = required(body, "executor_department_id");
                if (directory.department(dep) == null)
                    throw error(HttpStatus.BAD_REQUEST, "Invalid executor department");
                r.executorDepartmentId = dep;
                r.status = "COMPLETED";
            }
            case "chairman_reject" -> {
                r.reason = required(body, "reason");
                newCycle(r, "CHAIRMAN_REJECTED");
            }
            default -> throw error(HttpStatus.BAD_REQUEST, "Unknown action");
        }
        if (d != null) {
            d.pdfPath = version;
            d.sha256 = versionHash;
            d.signatures.add(new WorkflowModels.Signature(code, u, versionHash, version, verified, pkcs7));
        }
        event(r, u, action, auditDetail(r, action, body), ip, actionCycle, d == null ? null : d.id);
        store.save(r);
        if (token != null) {
            String usedToken=token;
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { signing.consume(usedToken); }
                });
            } else signing.consume(usedToken);
        }
        return r;
    }

    private void validateActionParameters(WorkflowModels.Request r, BankUser u, String action, Map<String, Object> body) {
        switch (action) {
            case "head_reject", "letter_return", "notice_reject", "chairman_reject" -> required(body, "reason");
            case "submit_letter" -> {
                Object raw = body.get("department_ids");
                if (!(raw instanceof List<?> list) || list.isEmpty())
                    throw error(HttpStatus.BAD_REQUEST, "Choose receiving departments");
                for (Object item : list) {
                    String dep = String.valueOf(item);
                    if (directory.department(dep) == null || dep.equals(r.creatorDepartmentId)
                            || directory.head(dep) == null)
                        throw error(HttpStatus.BAD_REQUEST, "Invalid receiving department");
                }
            }
            case "letter_approve" -> {
                if (r.receiverDepartments.isEmpty()
                        || r.receiverDepartments.stream().anyMatch(dep -> directory.head(dep) == null))
                    throw error(HttpStatus.CONFLICT, "No valid receiving departments");
            }
            case "assign" -> {
                String id = required(body, "user_id");
                BankUser target = directory.user(id);
                if (target == null || !target.department_id.equals(u.department_id) || target.is_department_head)
                    throw error(HttpStatus.BAD_REQUEST, "Choose an employee in your department");
                if (!r.status.equals("CREATOR_HEAD_ASSIGNING") && r.reviews.stream()
                        .noneMatch(t -> t.departmentId.equals(u.department_id) && t.status.equals("ASSIGNING")))
                    throw error(HttpStatus.CONFLICT, "No assignment pending");
            }
            case "submit_review" -> {
                if (!List.of("positive", "negative").contains(required(body, "conclusion")))
                    throw error(HttpStatus.BAD_REQUEST, "Invalid conclusion");
            }
            case "review_head_sign" -> {
                var task = reviewTask(r, number(body, "review_task_id"));
                if (!task.status.equals("WAIT_HEAD") || !isHead(u, task.departmentId))
                    throw error(HttpStatus.CONFLICT, "Review is not awaiting your signature");
            }
            case "chairman_approve" -> {
                if (directory.department(required(body, "executor_department_id")) == null)
                    throw error(HttpStatus.BAD_REQUEST, "Invalid executor department");
            }
            default -> {
            }
        }
    }

    private String auditDetail(WorkflowModels.Request request, String action, Map<String, Object> body) {
        return switch (action) {
            case "head_reject", "letter_return", "notice_reject", "chairman_reject" -> request.reason;
            case "submit_letter" -> String.join(",", request.receiverDepartments);
            case "assign" -> String.valueOf(body.get("user_id"));
            case "submit_review" -> String.valueOf(body.get("conclusion"));
            case "review_head_sign" -> String.valueOf(body.get("review_task_id"));
            case "chairman_approve" -> request.executorDepartmentId;
            default -> "";
        };
    }

    private void assign(WorkflowModels.Request r, BankUser head, String userId) {
        BankUser target = directory.user(userId);
        if (target == null || !target.department_id.equals(head.department_id) || target.is_department_head)
            throw error(HttpStatus.BAD_REQUEST, "Choose an employee in your department");
        if (r.status.equals("CREATOR_HEAD_ASSIGNING")) {
            r.noticeAssignee = userId;
            r.participantIds.add(userId);
            r.status = "NOTICE_DRAFT";
            return;
        }
        var task = r.reviews.stream()
                .filter(t -> t.departmentId.equals(head.department_id) && t.status.equals("ASSIGNING")).findFirst()
                .orElseThrow(() -> error(HttpStatus.CONFLICT, "No assignment pending"));
        task.assignedUserId = userId;
        r.participantIds.add(userId);
        task.status = "IN_PROGRESS";
        r.status = "REVIEW_IN_PROGRESS";
    }

    private void newCycle(WorkflowModels.Request r, String status) {
        r.cycle++;
        r.status = status;
        r.reviews.clear();
        r.receiverDepartments.clear();
        r.noticeAssignee = null;
        r.executorDepartmentId = null;
    }

    synchronized void recordDownload(WorkflowModels.Request r, BankUser u, WorkflowModels.Document d, String ip) {
        event(r, u, "download_document", d.name, ip, r.cycle, d.id);
        store.save(r);
    }

    synchronized void recordPackageDownload(WorkflowModels.Request r, BankUser u, String ip) {
        event(r, u, "download_package", "", ip, r.cycle, null);
        store.save(r);
    }

    private void event(WorkflowModels.Request r, BankUser u, String action, String detail, String ip, int cycle,
            Long documentId) {
        r.history.add(new WorkflowModels.Event(u.user_id, action, detail, ip, cycle, documentId));
    }
}
