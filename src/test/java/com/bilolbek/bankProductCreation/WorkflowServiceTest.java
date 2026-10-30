package com.bilolbek.bankProductCreation;

import com.bilolbek.bankProductCreation.config.AppProperties;
import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.flywaydb.core.Flyway;

class WorkflowServiceTest {
    @TempDir Path temporary;
    private static byte[] demoDocx() throws Exception {
        var bytes=new ByteArrayOutputStream();
        try(var zip=new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<?xml version='1.0'?><Types xmlns='http://schemas.openxmlformats.org/package/2006/content-types'><Default Extension='rels' ContentType='application/vnd.openxmlformats-package.relationships+xml'/><Default Extension='xml' ContentType='application/xml'/><Override PartName='/word/document.xml' ContentType='application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml'/></Types>".getBytes()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("_rels/.rels"));
            zip.write("<?xml version='1.0'?><Relationships xmlns='http://schemas.openxmlformats.org/package/2006/relationships'><Relationship Id='rId1' Type='http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument' Target='word/document.xml'/></Relationships>".getBytes()); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<?xml version='1.0'?><w:document xmlns:w='http://schemas.openxmlformats.org/wordprocessingml/2006/main'><w:body><w:p><w:r><w:t>Demo passport</w:t></w:r></w:p></w:body></w:document>".getBytes()); zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    private WorkflowRepository store;
    private DriverManagerDataSource database;
    private DemoHostProvider directory;
    private WorkflowService flow;
    private DocumentService documents;
    private SigningService signing;
    private DemoSigningService demoSigning;
    private AppProperties properties() { return new AppProperties(temporary,URI.create("http://localhost:8080")); }
    private void setup() throws Exception {
        database=new DriverManagerDataSource("jdbc:h2:mem:workflow_"+UUID.randomUUID().toString().replace("-","")+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        Flyway.configure().dataSource(database).load().migrate();
        store=new JdbcWorkflowRepository(new JdbcTemplate(database));
        directory=new DemoHostProvider();
        DemoEimzoProvider provider=new DemoEimzoProvider();
        signing=new SigningService(provider);
        demoSigning=new DemoSigningService(signing,provider,directory);
        documents=new DocumentService(new DemoWordToPdfConverter(),new LocalDocumentFileStorage(properties()),
            new DemoAntivirusScanner(),properties());
        flow=new WorkflowService(store,documents,signing,directory);
    }
    private BankUser user(String id) { return directory.user(id); }
    private WorkflowModels.Document upload(WorkflowModels.Request r,String user,String kind) throws Exception {
        return flow.upload(r,user(user),kind,kind+".docx",demoDocx(),"127.0.0.1");
    }
    private void signed(WorkflowModels.Request r,String user,String action,Map<String,Object> fields) throws Exception {
        Map<String,Object> prepared=flow.prepare(r,user(user),action,fields);
        Map<String,Object> body=new HashMap<>(fields);
        String sessionId=String.valueOf(prepared.get("sign_session_id"));
        body.put("sign_session_id",sessionId);
        body.put("pkcs7",demoSigning.sign(sessionId,user(user)).get("pkcs7"));
        flow.act(r,user(user),action,body,"127.0.0.1");
    }
    private void unsigned(WorkflowModels.Request r,String user,String action,Map<String,Object> fields) throws Exception {
        flow.act(r,user(user),action,fields,"127.0.0.1");
    }
    @Test void completeParallelReviewFlowAndPersistState() throws Exception {
        setup(); var r=flow.create("loan",user("u-creator"),"127.0.0.1");
        assertFalse(flow.visible(r,user("u-chair")));
        var passport=upload(r,"u-creator","passport");
        signed(r,"u-creator","submit_passport",Map.of("document_id",passport.id));
        signed(r,"u-head","head_approve",Map.of());
        assertEquals("PASSPORT_APPROVED",r.status);
        assertEquals(2,passport.signatures.size());
        assertTrue(Files.size(Path.of(passport.pdfPath))>100);
        assertEquals(DocumentService.hash(Path.of(passport.pdfPath)),passport.sha256);
        upload(r,"u-creator","letter");
        var letterSession=flow.prepare(r,user("u-creator"),"submit_letter",Map.of());
        String sessionId=String.valueOf(letterSession.get("sign_session_id"));
        String letterSignature=String.valueOf(demoSigning.sign(sessionId,user("u-creator")).get("pkcs7"));
        assertThrows(ResponseStatusException.class,()->unsigned(r,"u-creator","submit_letter",Map.of("sign_session_id",sessionId,"pkcs7",letterSignature)));
        assertEquals("PASSPORT_APPROVED",r.status);
        unsigned(r,"u-creator","submit_letter",Map.of("sign_session_id",sessionId,"pkcs7",letterSignature,"department_ids",List.of("D-12","D-15")));
        signed(r,"u-head","letter_approve",Map.of());
        assertEquals("REVIEW_ASSIGNING",r.status);
        unsigned(r,"u-risk-head","assign",Map.of("user_id","u-risk"));
        unsigned(r,"u-legal-head","assign",Map.of("user_id","u-legal"));
        assertEquals(2,r.reviews.size());
        assertThrows(ResponseStatusException.class,()->upload(r,"u-creator","review"));
        var risk=upload(r,"u-risk","review");
        signed(r,"u-risk","submit_review",Map.of("document_id",risk.id,"conclusion","positive"));
        long riskTask=r.reviews.stream().filter(t->t.departmentId.equals("D-12")).findFirst().orElseThrow().id;
        signed(r,"u-risk-head","review_head_sign",Map.of("review_task_id",riskTask));
        assertNotEquals("CREATOR_HEAD_ASSIGNING",r.status);
        var legal=upload(r,"u-legal","review");
        signed(r,"u-legal","submit_review",Map.of("document_id",legal.id,"conclusion","positive"));
        long legalTask=r.reviews.stream().filter(t->t.departmentId.equals("D-15")).findFirst().orElseThrow().id;
        signed(r,"u-legal-head","review_head_sign",Map.of("review_task_id",legalTask));
        assertEquals("CREATOR_HEAD_ASSIGNING",r.status);
        unsigned(r,"u-head","assign",Map.of("user_id","u-notice"));
        upload(r,"u-notice","notice");
        signed(r,"u-notice","submit_notice",Map.of());
        signed(r,"u-head","notice_approve",Map.of());
        assertTrue(flow.visible(r,user("u-chair")));
        signed(r,"u-chair","chairman_approve",Map.of("executor_department_id","D-20"));
        assertEquals("COMPLETED",r.status);
        assertTrue(flow.visible(r,user("u-exec")));
        var http=new MockHttpServletRequest();
        var packageStream=new ApiController(new ApiService(store,flow,directory,documents)).pack(r.id,user("u-exec"),http).getBody();
        assertNotNull(packageStream);
        var packageOutput=new ByteArrayOutputStream(); packageStream.writeTo(packageOutput);
        byte[] packageBytes=packageOutput.toByteArray();
        int entries=0;
        try(var zip=new ZipInputStream(new ByteArrayInputStream(packageBytes))) {
            while(zip.getNextEntry()!=null) { entries++; zip.closeEntry(); }
        }
        assertEquals(4,entries);
        assertEquals("COMPLETED",new JdbcWorkflowRepository(new JdbcTemplate(database)).get(r.id).status);
    }
    @Test void rejectionRequiresReasonAndStartsNewCycle() throws Exception {
        setup(); var r=flow.create("deposit",user("u-creator"),"127.0.0.1");
        upload(r,"u-creator","passport"); signed(r,"u-creator","submit_passport",Map.of());
        assertThrows(ResponseStatusException.class,()->unsigned(r,"u-head","head_reject",Map.of()));
        unsigned(r,"u-head","head_reject",Map.of("reason","Needs correction"));
        assertEquals("PASSPORT_REJECTED",r.status); assertEquals(2,r.cycle);
        var code=r.documents.getFirst().signatures.getFirst().code;
        assertFalse(new ApiService(store,flow,directory,documents).verify(code).active());
        assertTrue(flow.actions(r,user("u-creator")).contains("submit_passport"));
        assertEquals(1,r.documents.size()); assertEquals(1,r.documents.getFirst().cycle);
    }
    @Test void submitPassport_afterBadSignature_canRetrySameSession() throws Exception {
        setup(); var r=flow.create("loan",user("u-creator"),"127.0.0.1");
        assertThrows(ResponseStatusException.class,()->flow.upload(r,user("u-creator"),"passport","broken.docx",new byte[]{'P','K',3,4},"127.0.0.1"));
        upload(r,"u-creator","passport");
        var prepared=flow.prepare(r,user("u-creator"),"submit_passport",Map.of());
        String session=String.valueOf(prepared.get("sign_session_id"));
        assertThrows(ResponseStatusException.class,()->unsigned(r,"u-creator","submit_passport",Map.of("sign_session_id",session,"pkcs7","invalid")));
        assertEquals("DRAFT",r.status);
        unsigned(r,"u-creator","submit_passport",Map.of("sign_session_id",session,"pkcs7",demoSigning.sign(session,user("u-creator")).get("pkcs7")));
        assertEquals("PASSPORT_WAIT_HEAD",r.status);
    }
    @Test void negativeReview_waitsForHeadSignature_thenStartsNewCycle() throws Exception {
        setup(); var r=flow.create("loan",user("u-creator"),"127.0.0.1");
        upload(r,"u-creator","passport"); signed(r,"u-creator","submit_passport",Map.of());
        signed(r,"u-head","head_approve",Map.of());
        upload(r,"u-creator","letter");
        signed(r,"u-creator","submit_letter",Map.of("department_ids",List.of("D-12")));
        signed(r,"u-head","letter_approve",Map.of());
        unsigned(r,"u-risk-head","assign",Map.of("user_id","u-risk"));
        upload(r,"u-risk","review");
        signed(r,"u-risk","submit_review",Map.of("conclusion","negative"));
        assertEquals(1,r.cycle);
        assertEquals("REVIEW_WAIT_HEAD",r.status);
        signed(r,"u-risk-head","review_head_sign",Map.of("review_task_id",r.reviews.getFirst().id));
        assertEquals(2,r.cycle); assertEquals("NEGATIVE_REVIEW",r.status);
        assertTrue(r.documents.stream().allMatch(d->d.cycle==1));
        assertTrue(flow.visible(r,user("u-risk")));
    }
}
