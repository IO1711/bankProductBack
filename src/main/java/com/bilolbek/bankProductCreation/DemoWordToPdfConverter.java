package com.bilolbek.bankProductCreation;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Uses LibreOffice when present; only demo mode may create a labeled placeholder. */
@Component
@ConditionalOnProperty(name="app.document.mode",havingValue="demo",matchIfMissing=true)
public class DemoWordToPdfConverter implements WordToPdfConverter {
    private final String executable;
    DemoWordToPdfConverter() { this("soffice"); }
    DemoWordToPdfConverter(String executable) { this.executable=executable; }

    @Override public Path convert(InputStream word,String originalName) throws Exception {
        Path directory=Files.createTempDirectory("bank-word-convert-");
        String extension=originalName.toLowerCase(Locale.ROOT).endsWith(".docx")?".docx":".doc";
        Path source=directory.resolve("input"+extension);
        Path converted=directory.resolve("input.pdf");
        try {
            Files.copy(word,source);
            Process process;
            try {
                process=new ProcessBuilder(executable,"-env:UserInstallation="+directory.resolve("profile").toUri(),
                    "--headless","--convert-to","pdf","--outdir",directory.toString(),source.toString())
                    .redirectErrorStream(true).redirectOutput(directory.resolve("conversion.log").toFile()).start();
            } catch(IOException unavailable) {
                return placeholder(originalName);
            }
            boolean finished;
            try { finished=process.waitFor(30,TimeUnit.SECONDS); }
            catch(InterruptedException interrupted) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Document conversion interrupted",interrupted);
            }
            if(!finished) {
                process.destroyForcibly();
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Document conversion timed out");
            }
            if(process.exitValue()!=0 || !Files.isRegularFile(converted) || Files.size(converted)==0)
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"DOC/DOCX conversion failed");
            Path result=Files.createTempFile("bank-converted-", ".pdf");
            try { Files.copy(converted,result,StandardCopyOption.REPLACE_EXISTING); }
            catch(Exception failure) { Files.deleteIfExists(result); throw failure; }
            return result;
        } finally {
            try(var paths=Files.walk(directory)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path->{
                    try { Files.deleteIfExists(path); } catch(IOException ignored) { }
                });
            }
        }
    }

    private Path placeholder(String originalName) throws IOException {
        Path result=Files.createTempFile("bank-demo-placeholder-", ".pdf");
        try(PDDocument pdf=new PDDocument()) {
            PDPage page=new PDPage(PDRectangle.A4); pdf.addPage(page);
            try(PDPageContentStream canvas=new PDPageContentStream(pdf,page)) {
                line(canvas,"DEMO PDF PLACEHOLDER",48,760);
                line(canvas,"Original DOC/DOCX: "+originalName,48,720);
                line(canvas,"LibreOffice is unavailable in this demo environment.",48,690);
                line(canvas,"Download the original file to inspect its content.",48,660);
            }
            pdf.save(result.toFile());
            return result;
        } catch(IOException failure) {
            Files.deleteIfExists(result);
            throw failure;
        }
    }

    private void line(PDPageContentStream canvas,String value,float x,float y) throws IOException {
        String ascii=value.replaceAll("[^\\x20-\\x7E]", "?");
        if(ascii.length()>95) ascii=ascii.substring(0,95);
        canvas.beginText(); canvas.setFont(PDType1Font.HELVETICA,11);
        canvas.newLineAtOffset(x,y); canvas.showText(ascii); canvas.endText();
    }
}
