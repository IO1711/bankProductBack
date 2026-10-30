package com.bilolbek.bankProductCreation;

import com.bilolbek.bankProductCreation.config.AppProperties;
import java.io.ObjectOutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class LegacyStateImporterTest {
    @TempDir Path directory;

    @Test void importsSerializedRequestsOnlyOnceAndRemapsDocumentReferences() throws Exception {
        DemoStore.State state=new DemoStore.State();
        var request=new DemoStore.Request();
        request.id=91; request.type="loan"; request.creatorId="u-creator";
        request.creatorDepartmentId="D-07"; request.createdAt="2026-10-29T00:00:00Z";
        var document=new DemoStore.Document();
        document.id=73; document.kind="passport"; document.name="test.docx";
        document.sourcePath="/tmp/test.docx"; document.sha256="a".repeat(64);
        document.uploadedBy="u-creator"; document.createdAt=request.createdAt;
        document.size=123; document.cycle=1;
        request.documents.add(document);
        var review=new DemoStore.ReviewTask();
        review.id=19; review.departmentId="D-12"; review.documentId=73L;
        request.reviews.add(review);
        request.history.add(new DemoStore.Event("u-creator","upload_passport","test.docx","127.0.0.1",1,73L));
        state.requests.add(request);
        Path legacy=directory.resolve("state.bin");
        try(var output=new ObjectOutputStream(Files.newOutputStream(legacy))) { output.writeObject(state); }

        var dataSource=new DriverManagerDataSource("jdbc:h2:mem:legacy_"+UUID.randomUUID().toString().replace("-","")+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        Flyway.configure().dataSource(dataSource).load().migrate();
        WorkflowRepository repository=new JdbcWorkflowRepository(new JdbcTemplate(dataSource));
        var importer=new LegacyStateImporter(repository,new AppProperties(directory,URI.create("http://localhost:8080")),true);
        importer.run(new DefaultApplicationArguments(new String[0]));
        importer.run(new DefaultApplicationArguments(new String[0]));

        assertEquals(1,repository.all().size());
        var imported=repository.all().getFirst();
        assertEquals("loan",imported.type);
        assertEquals(imported.documents.getFirst().id,imported.reviews.getFirst().documentId);
        assertEquals(imported.documents.getFirst().id,imported.history.getFirst().documentId);
        assertTrue(Files.exists(legacy));
    }
}
