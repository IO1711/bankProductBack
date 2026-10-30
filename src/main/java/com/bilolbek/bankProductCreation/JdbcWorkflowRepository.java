package com.bilolbek.bankProductCreation;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Relational persistence for requests and their documents, signatures, reviews, and audit trail. */
@Repository
@Transactional
public class JdbcWorkflowRepository implements WorkflowRepository {
    private final JdbcTemplate jdbc;
    JdbcWorkflowRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    @Override public WorkflowModels.Request add(WorkflowModels.Request request) {
        request.id=next("workflow_request_id_seq");
        request.version=0;
        jdbc.update("""
            INSERT INTO workflow_requests(id,version,type,status,creator_id,creator_department_id,created_at,
                reason,cycle,notice_assignee,executor_department_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,request.id,request.version,request.type,request.status,request.creatorId,
            request.creatorDepartmentId,request.createdAt,request.reason,request.cycle,
            request.noticeAssignee,request.executorDepartmentId);
        saveChildren(request);
        return request;
    }

    @Override public void save(WorkflowModels.Request request) {
        int updated=jdbc.update("""
            UPDATE workflow_requests SET version=version+1,type=?,status=?,creator_id=?,creator_department_id=?,
                created_at=?,reason=?,cycle=?,notice_assignee=?,executor_department_id=?
            WHERE id=? AND version=?
            """,request.type,request.status,request.creatorId,request.creatorDepartmentId,
            request.createdAt,request.reason,request.cycle,request.noticeAssignee,
            request.executorDepartmentId,request.id,request.version);
        if(updated!=1) throw WorkflowService.error(HttpStatus.CONFLICT,"Request changed concurrently; reload and retry");
        request.version++;
        saveChildren(request);
    }

    @Override @Transactional(readOnly=true) public WorkflowModels.Request get(long id) {
        return load(id,false);
    }

    @Override public WorkflowModels.Request getForUpdate(long id) { return load(id,true); }

    @Override @Transactional(readOnly=true) public List<WorkflowModels.Request> all() {
        return jdbc.query("SELECT id FROM workflow_requests ORDER BY id DESC",(rs,row)->rs.getLong(1))
            .stream().map(this::get).toList();
    }

    @Override @Transactional(readOnly=true) public WorkflowModels.Request findByDocumentId(long documentId) {
        List<Long> ids=jdbc.query("SELECT request_id FROM workflow_documents WHERE id=?",
            (rs,row)->rs.getLong(1),documentId);
        return ids.isEmpty()?null:get(ids.getFirst());
    }

    @Override @Transactional(readOnly=true) public WorkflowModels.Request findBySignatureCode(String code) {
        List<Long> ids=jdbc.query("""
            SELECT d.request_id FROM workflow_signatures s
            JOIN workflow_documents d ON d.id=s.document_id WHERE s.code=?
            """,(rs,row)->rs.getLong(1),code);
        return ids.isEmpty()?null:get(ids.getFirst());
    }

    @Override public long documentId() { return next("workflow_document_id_seq"); }
    @Override public long reviewId() { return next("workflow_review_id_seq"); }

    private long next(String sequence) {
        Long id=jdbc.queryForObject("SELECT nextval('"+sequence+"')",Long.class);
        if(id==null) throw new IllegalStateException("Database sequence returned no ID");
        return id;
    }

    private WorkflowModels.Request load(long id,boolean lock) {
        List<WorkflowModels.Request> results=jdbc.query("""
            SELECT id,version,type,status,creator_id,creator_department_id,created_at,reason,cycle,
                notice_assignee,executor_department_id FROM workflow_requests WHERE id=?
            """+(lock?" FOR UPDATE":""),REQUEST_MAPPER,id);
        if(results.isEmpty()) return null;
        WorkflowModels.Request request=results.getFirst();
        request.receiverDepartments=jdbc.query("""
            SELECT department_id FROM request_receivers WHERE request_id=? ORDER BY sort_order
            """,(rs,row)->rs.getString(1),id);
        request.participantIds.addAll(jdbc.query("SELECT user_id FROM request_participants WHERE request_id=?",
            (rs,row)->rs.getString(1),id));
        request.documents=jdbc.query("""
            SELECT id,cycle,kind,name,source_path,pdf_path,sha256,uploaded_by,created_at,size_bytes,deleted
            FROM workflow_documents WHERE request_id=? ORDER BY id
            """,DOCUMENT_MAPPER,id);
        for(var document:request.documents) document.signatures=jdbc.query("""
            SELECT id,code,user_id,full_name,position,signed_at,sha256,version_path,signed_input_sha256,
                signer_pinfl,certificate_serial,verification_mode,pkcs7
            FROM workflow_signatures WHERE document_id=? ORDER BY id
            """,SIGNATURE_MAPPER,document.id);
        request.reviews=jdbc.query("""
            SELECT id,department_id,assigned_user_id,conclusion,status,document_id
            FROM workflow_reviews WHERE request_id=? ORDER BY id
            """,REVIEW_MAPPER,id);
        request.history=jdbc.query("""
            SELECT id,at,actor,action,detail,ip,cycle,document_id
            FROM workflow_events WHERE request_id=? ORDER BY id
            """,EVENT_MAPPER,id);
        return request;
    }

    private void saveChildren(WorkflowModels.Request request) {
        jdbc.update("DELETE FROM request_receivers WHERE request_id=?",request.id);
        for(int index=0;index<request.receiverDepartments.size();index++)
            jdbc.update("INSERT INTO request_receivers(request_id,sort_order,department_id) VALUES (?,?,?)",
                request.id,index,request.receiverDepartments.get(index));
        jdbc.update("DELETE FROM request_participants WHERE request_id=?",request.id);
        for(String userId:request.participantIds)
            jdbc.update("INSERT INTO request_participants(request_id,user_id) VALUES (?,?)",request.id,userId);

        for(var document:request.documents) {
            if(document.id==0) document.id=documentId();
            int updated=jdbc.update("""
                UPDATE workflow_documents SET cycle=?,kind=?,name=?,source_path=?,pdf_path=?,sha256=?,
                    uploaded_by=?,created_at=?,size_bytes=?,deleted=? WHERE id=? AND request_id=?
                """,document.cycle,document.kind,document.name,document.sourcePath,document.pdfPath,
                document.sha256,document.uploadedBy,document.createdAt,document.size,document.deleted,
                document.id,request.id);
            if(updated==0) jdbc.update("""
                INSERT INTO workflow_documents(id,request_id,cycle,kind,name,source_path,pdf_path,sha256,
                    uploaded_by,created_at,size_bytes,deleted) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,document.id,request.id,document.cycle,document.kind,document.name,document.sourcePath,
                document.pdfPath,document.sha256,document.uploadedBy,document.createdAt,document.size,document.deleted);
            for(var signature:document.signatures) if(signature.id==0) {
                signature.id=next("workflow_signature_id_seq");
                jdbc.update("""
                    INSERT INTO workflow_signatures(id,document_id,code,user_id,full_name,position,signed_at,
                        sha256,version_path,signed_input_sha256,signer_pinfl,certificate_serial,verification_mode,pkcs7)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,signature.id,document.id,signature.code,signature.userId,signature.fullName,
                    signature.position,signature.at,signature.sha256,signature.versionPath,
                    signature.signedInputSha256,signature.signerPinfl,signature.certificateSerial,
                    signature.verificationMode,signature.pkcs7);
            }
        }
        jdbc.update("DELETE FROM workflow_reviews WHERE request_id=?",request.id);
        for(var review:request.reviews) {
            if(review.id==0) review.id=reviewId();
            jdbc.update("""
                INSERT INTO workflow_reviews(id,request_id,department_id,assigned_user_id,conclusion,status,document_id)
                VALUES (?,?,?,?,?,?,?)
                """,review.id,request.id,review.departmentId,review.assignedUserId,
                review.conclusion,review.status,review.documentId);
        }
        for(var event:request.history) if(event.id==0) {
            event.id=next("workflow_event_id_seq");
            jdbc.update("""
                INSERT INTO workflow_events(id,request_id,at,actor,action,detail,ip,cycle,document_id)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,event.id,request.id,event.at,event.actor,event.action,event.detail,
                event.ip,event.cycle,event.documentId);
        }
    }

    private static final RowMapper<WorkflowModels.Request> REQUEST_MAPPER=(rs,row)->{
        var request=new WorkflowModels.Request();
        request.id=rs.getLong("id"); request.version=rs.getLong("version");
        request.type=rs.getString("type"); request.status=rs.getString("status");
        request.creatorId=rs.getString("creator_id"); request.creatorDepartmentId=rs.getString("creator_department_id");
        request.createdAt=rs.getString("created_at"); request.reason=rs.getString("reason");
        request.cycle=rs.getInt("cycle"); request.noticeAssignee=rs.getString("notice_assignee");
        request.executorDepartmentId=rs.getString("executor_department_id");
        return request;
    };
    private static final RowMapper<WorkflowModels.Document> DOCUMENT_MAPPER=(rs,row)->{
        var document=new WorkflowModels.Document();
        document.id=rs.getLong("id"); document.cycle=rs.getInt("cycle"); document.kind=rs.getString("kind");
        document.name=rs.getString("name"); document.sourcePath=rs.getString("source_path");
        document.pdfPath=rs.getString("pdf_path"); document.sha256=rs.getString("sha256");
        document.uploadedBy=rs.getString("uploaded_by"); document.createdAt=rs.getString("created_at");
        document.size=rs.getLong("size_bytes"); document.deleted=rs.getBoolean("deleted");
        return document;
    };
    private static final RowMapper<WorkflowModels.Signature> SIGNATURE_MAPPER=(rs,row)->{
        var signature=new WorkflowModels.Signature();
        signature.id=rs.getLong("id"); signature.code=rs.getString("code");
        signature.userId=rs.getString("user_id"); signature.fullName=rs.getString("full_name");
        signature.position=rs.getString("position"); signature.at=rs.getString("signed_at");
        signature.sha256=rs.getString("sha256"); signature.versionPath=rs.getString("version_path");
        signature.signedInputSha256=rs.getString("signed_input_sha256");
        signature.signerPinfl=rs.getString("signer_pinfl");
        signature.certificateSerial=rs.getString("certificate_serial");
        signature.verificationMode=rs.getString("verification_mode"); signature.pkcs7=rs.getString("pkcs7");
        return signature;
    };
    private static final RowMapper<WorkflowModels.ReviewTask> REVIEW_MAPPER=(rs,row)->{
        var review=new WorkflowModels.ReviewTask();
        review.id=rs.getLong("id"); review.departmentId=rs.getString("department_id");
        review.assignedUserId=rs.getString("assigned_user_id"); review.conclusion=rs.getString("conclusion");
        review.status=rs.getString("status"); review.documentId=nullableLong(rs,"document_id");
        return review;
    };
    private static final RowMapper<WorkflowModels.Event> EVENT_MAPPER=(rs,row)->{
        var event=new WorkflowModels.Event();
        event.id=rs.getLong("id"); event.at=rs.getString("at"); event.actor=rs.getString("actor");
        event.action=rs.getString("action"); event.detail=rs.getString("detail"); event.ip=rs.getString("ip");
        event.cycle=rs.getInt("cycle"); event.documentId=nullableLong(rs,"document_id");
        return event;
    };
    private static Long nullableLong(ResultSet rs,String column) throws SQLException {
        long value=rs.getLong(column);
        return rs.wasNull()?null:value;
    }
}
