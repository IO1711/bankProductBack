package com.bilolbek.bankProductCreation;

import com.bilolbek.bankProductCreation.config.AppProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import tools.jackson.databind.json.JsonMapper;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ApiHttpTest {
    private static final Path DATA_DIR;
    static { try { DATA_DIR=Files.createTempDirectory("bank-api-http-"); }
        catch(Exception e) { throw new ExceptionInInitializerError(e); } }
    @DynamicPropertySource static void dataDir(DynamicPropertyRegistry registry) {
        registry.add("app.data-dir",DATA_DIR::toString);
        registry.add("app.public-base-url",()->"https://demo.bank.uz");
    }
    @Autowired MockMvc mvc;
    @Autowired AppProperties properties;
    @Autowired WorkflowRepository store;
    @Autowired DocumentService documents;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired SigningService signing;
    @Autowired DemoHostProvider host;
    @Autowired JsonMapper json;

    private RequestPostProcessor as(String id) {
        return authentication(UsernamePasswordAuthenticationToken.authenticated(host.user(id),null,
            List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE"))));
    }

    @Test void configuration_withOverrides_bindsTypedProperties() {
        org.assertj.core.api.Assertions.assertThat(properties.dataDir()).isEqualTo(DATA_DIR);
        org.assertj.core.api.Assertions.assertThat(properties.publicBaseUrl().toString()).isEqualTo("https://demo.bank.uz");
    }
    @Test void configuration_withNonHttpVerificationUrl_isRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(()->new AppProperties(DATA_DIR,URI.create("file:///tmp/verify")))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void createRequest_withLoanType_returnsSpecificationFields() throws Exception {
        mvc.perform(post("/api/requests").with(as("u-creator")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"loan\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.type").value("loan"))
            .andExpect(jsonPath("$.status").value("DRAFT"))
            .andExpect(jsonPath("$.available_actions[0]").value("submit_passport"))
            .andExpect(jsonPath("$.creator_detail.pinfl").doesNotExist());
    }
    @Test void uploadedDocument_canBeDownloadedThroughStorageAdapter() throws Exception {
        var created=mvc.perform(post("/api/requests").with(as("u-creator")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"loan\"}"))
            .andExpect(status().isCreated()).andReturn();
        long requestId=json.readTree(created.getResponse().getContentAsString()).get("id").asLong();
        var bytes=new java.io.ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes()); zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("word/document.xml"));
            zip.write("<document/>".getBytes()); zip.closeEntry();
        }
        byte[] original=bytes.toByteArray();
        var uploaded=mvc.perform(multipart("/api/requests/{id}/documents",requestId)
                .file(new MockMultipartFile("file","passport.docx",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",original))
                .param("kind","passport").with(as("u-creator")).with(csrf()))
            .andExpect(status().isCreated()).andReturn();
        long documentId=json.readTree(uploaded.getResponse().getContentAsString()).get("id").asLong();
        var download=mvc.perform(get("/api/documents/{id}/download",documentId).param("original","true")
                .with(as("u-creator")))
            .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(download)).andExpect(status().isOk())
            .andExpect(content().bytes(original));
    }

    @Test void createRequest_withInvalidType_returnsProblem400() throws Exception {
        mvc.perform(post("/api/requests").with(as("u-creator")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"other\"}"))
            .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.status").value(400));
    }
    @Test void createRequest_asChairman_returnsProblem403() throws Exception {
        mvc.perform(post("/api/requests").with(as("u-chair")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"loan\"}"))
            .andExpect(status().isForbidden()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
    @Test void createRequest_withMalformedJson_returnsProblem400() throws Exception {
        mvc.perform(post("/api/requests").with(as("u-creator")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{not-json"))
            .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
    @Test void createRequest_withoutType_returnsProblem400() throws Exception {
        mvc.perform(post("/api/requests").with(as("u-creator")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
    @Test void findRequest_whenMissing_returnsProblem404() throws Exception {
        mvc.perform(get("/api/requests/999999").with(as("u-creator")))
            .andExpect(status().isNotFound()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
    @Test void currentUser_withOnlyFormerDemoHeader_returnsProblem401() throws Exception {
        mvc.perform(get("/api/me").header("X-Demo-User","u-creator"))
            .andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
    @Test void writeWithoutCsrfToken_isForbiddenEvenWhenAuthenticated() throws Exception {
        mvc.perform(post("/api/requests").with(as("u-creator"))
                .contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"loan\"}"))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
    @Test void ssoTicket_createsSessionAndCannotBeReused() throws Exception {
        MvcResult csrfResponse=mvc.perform(get("/api/auth/csrf"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty()).andReturn();
        String csrfToken=json.readTree(csrfResponse.getResponse().getContentAsString()).get("token").asText();
        MockHttpSession session=(MockHttpSession)csrfResponse.getRequest().getSession(false);
        MvcResult ticketResponse=mvc.perform(post("/api/demo/host/tickets").session(session)
                .header("X-CSRF-TOKEN",csrfToken).contentType(MediaType.APPLICATION_JSON)
                .content("{\"user_id\":\"u-creator\"}"))
            .andExpect(status().isOk()).andReturn();
        String ticket=json.readTree(ticketResponse.getResponse().getContentAsString()).get("ticket").asText();
        String ticketBody="{\"ticket\":\""+ticket+"\"}";
        MvcResult login=mvc.perform(post("/api/auth/sso").session(session)
                .header("X-CSRF-TOKEN",csrfToken).contentType(MediaType.APPLICATION_JSON).content(ticketBody))
            .andExpect(status().isOk()).andExpect(jsonPath("$.user.user_id").value("u-creator")).andReturn();
        MockHttpSession authenticated=(MockHttpSession)login.getRequest().getSession(false);
        mvc.perform(get("/api/me").session(authenticated)).andExpect(status().isOk())
            .andExpect(jsonPath("$.user.user_id").value("u-creator"));
        mvc.perform(post("/api/auth/sso").session(authenticated)
                .header("X-CSRF-TOKEN",csrfToken).contentType(MediaType.APPLICATION_JSON).content(ticketBody))
            .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/logout").session(authenticated).header("X-CSRF-TOKEN",csrfToken))
            .andExpect(status().isOk()).andExpect(jsonPath("$.authenticated").value(false));
        org.assertj.core.api.Assertions.assertThat(authenticated.isInvalid()).isTrue();
    }
    @Test void demoEimzoSign_returnsTokenForPreparedUserOnly() throws Exception {
        String hash="b".repeat(64);
        String session=String.valueOf(signing.prepare(100,"submit_passport",host.user("u-creator"),200,hash).get("sign_session_id"));
        String request="{\"sign_session_id\":\""+session+"\"}";
        mvc.perform(post("/api/demo/eimzo/sign").with(as("u-head")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isForbidden());
        mvc.perform(post("/api/demo/eimzo/sign").with(as("u-creator")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(request))
            .andExpect(status().isOk()).andExpect(jsonPath("$.pkcs7").isNotEmpty())
            .andExpect(jsonPath("$.demo").value(true));
    }
    @Test void createRequest_withUnsupportedMethod_returnsProblem405() throws Exception {
        mvc.perform(put("/api/requests").with(as("u-creator")).with(csrf()))
            .andExpect(status().isMethodNotAllowed()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
    @Test void completedRequests_areListedOnlyForVisibleParticipants() throws Exception {
        WorkflowModels.Request request=new WorkflowModels.Request();
        request.type="loan"; request.status="COMPLETED";
        request.creatorId="u-creator"; request.creatorDepartmentId="D-07";
        request.executorDepartmentId="D-20"; request.createdAt="2026-10-28T00:00:00Z";
        store.add(request);

        mvc.perform(get("/api/requests?scope=completed").with(as("u-exec")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(request.id));
        mvc.perform(get("/api/requests?scope=completed").with(as("u-legal")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id == " + request.id + ")]").isEmpty());
    }
    @Test void workflowWrite_rollsBackWhenTransactionFails() {
        WorkflowModels.Request request=new WorkflowModels.Request();
        request.type="loan"; request.creatorId="u-creator";
        request.creatorDepartmentId="D-07"; request.createdAt="2026-10-29T00:00:00Z";
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            store.add(request);
            throw new IllegalStateException("force rollback");
        }));
        assertNull(store.get(request.id));
    }
    @Test void staleRequestVersion_isRejected() {
        WorkflowModels.Request request=new WorkflowModels.Request();
        request.type="deposit"; request.creatorId="u-creator";
        request.creatorDepartmentId="D-07"; request.createdAt="2026-10-29T00:00:00Z";
        store.add(request);
        WorkflowModels.Request first=store.get(request.id),stale=store.get(request.id);
        first.status="PASSPORT_WAIT_HEAD";
        store.save(first);
        stale.status="COMPLETED";
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.save(stale));
        assertEquals("PASSPORT_WAIT_HEAD",store.get(request.id).status);
    }
    @Test void uploadedFile_isRemovedWhenTransactionRollsBack() {
        var created=new java.util.concurrent.atomic.AtomicReference<Path>();
        assertThrows(IllegalStateException.class,()->new TransactionTemplate(transactionManager).executeWithoutResult(status->{
            try { created.set(Path.of(documents.saveUpload(new byte[]{1,2,3},"rollback.docx"))); }
            catch(java.io.IOException error) { throw new RuntimeException(error); }
            throw new IllegalStateException("force rollback");
        }));
        assertNotNull(created.get());
        assertFalse(Files.exists(created.get()));
    }
}
