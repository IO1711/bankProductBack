package com.bilolbek.bankProductCreation;

import com.bilolbek.bankProductCreation.config.AppProperties;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentPipelineTest {
    @TempDir Path directory;
    private AppProperties properties() { return new AppProperties(directory,URI.create("http://localhost:8080")); }

    @Test void demoConverter_whenExecutableMissing_createsLabeledPlaceholder() throws Exception {
        Path result=new DemoWordToPdfConverter("missing-bank-demo-converter")
            .convert(new ByteArrayInputStream(new byte[]{1,2,3}),"passport.docx");
        try(PDDocument pdf=PDDocument.load(result.toFile())) {
            assertThat(pdf.getNumberOfPages()).isEqualTo(1);
            assertThat(new org.apache.pdfbox.text.PDFTextStripper().getText(pdf)).contains("DEMO PDF PLACEHOLDER");
        } finally { Files.deleteIfExists(result); }
    }

    @Test void demoScanner_whenMarkerPresent_rejectsBeforeStorage() throws Exception {
        var storage=new LocalDocumentFileStorage(properties());
        var service=new DocumentService(new DemoWordToPdfConverter(),storage,new DemoAntivirusScanner(),properties());
        assertThatThrownBy(()->service.saveUpload("DEMO_VIRUS".getBytes(),"passport.docx"))
            .isInstanceOf(ResponseStatusException.class);
        try(var files=Files.list(directory.resolve("files"))) { assertThat(files).isEmpty(); }
    }

    @Test void realMode_withoutConverter_rejectsStartup() {
        new ApplicationContextRunner()
            .withPropertyValues("app.document.mode=real")
            .withBean(AppProperties.class,this::properties)
            .withUserConfiguration(DemoWordToPdfConverter.class,LocalDocumentFileStorage.class,DemoAntivirusScanner.class)
            .withBean(DocumentService.class)
            .run(context->{
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure()).hasStackTraceContaining("WordToPdfConverter");
            });
    }
}
