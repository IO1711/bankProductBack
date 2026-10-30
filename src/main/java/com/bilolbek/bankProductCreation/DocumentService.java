package com.bilolbek.bankProductCreation;

import com.bilolbek.bankProductCreation.config.AppProperties;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.qrcode.QRCodeWriter;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.springframework.stereotype.Service;

@Service
public class DocumentService {
    private final WordToPdfConverter converter;
    private final DocumentFileStorage storage;
    private final AntivirusScanner scanner;
    private final String publicBaseUrl;
    DocumentService(WordToPdfConverter converter,DocumentFileStorage storage,
                    AntivirusScanner scanner,AppProperties properties) {
        this.converter=converter;
        this.storage=storage;
        this.scanner=scanner;
        publicBaseUrl=properties.publicBaseUrl().toString().replaceAll("/$", "");
    }
    String saveUpload(byte[] bytes,String name) throws IOException {
        scanner.scan(bytes,name);
        return storage.save(bytes,name.toLowerCase(java.util.Locale.ROOT).endsWith(".docx")?".docx":".doc");
    }
    static boolean matchesWordFormat(byte[] bytes, boolean docx) {
        if (!docx) {
            return bytes.length>=512 && (bytes[0]&255)==0xD0 && (bytes[1]&255)==0xCF
                && (bytes[2]&255)==0x11 && (bytes[3]&255)==0xE0
                && (bytes[4]&255)==0xA1 && (bytes[5]&255)==0xB1
                && (bytes[6]&255)==0x1A && (bytes[7]&255)==0xE1;
        }
        boolean contentTypes=false, document=false;
        long expanded=0;
        try(ZipInputStream zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry; int count=0; byte[] buffer=new byte[8192];
            while((entry=zip.getNextEntry())!=null) {
                if(++count>2000 || entry.isDirectory()) { if(count>2000) return false; continue; }
                if(entry.getName().equals("[Content_Types].xml")) contentTypes=true;
                if(entry.getName().equals("word/document.xml")) document=true;
                int read; long entrySize=0;
                while((read=zip.read(buffer))!=-1) {
                    expanded+=read; entrySize+=read;
                    if(expanded>150L*1024*1024) return false;
                }
                if(entrySize==0 && (entry.getName().equals("[Content_Types].xml") || entry.getName().equals("word/document.xml"))) return false;
                zip.closeEntry();
            }
            return contentTypes && document;
        } catch(IOException e) { return false; }
    }
    String ensurePdf(WorkflowModels.Document doc) throws Exception {
        if(doc.pdfPath==null) {
            try(InputStream word=storage.open(doc.sourcePath)) {
                Path temporary=converter.convert(word,doc.name);
                try { doc.pdfPath=storage.save(temporary,".pdf"); }
                finally { Files.deleteIfExists(temporary); }
            }
        }
        return doc.pdfPath;
    }
    String signedVersion(WorkflowModels.Document doc,BankUser signer,String code,String verificationMode) throws Exception {
        String source=ensurePdf(doc);
        Path temporary=Files.createTempFile("bank-signed-", ".pdf");
        try(InputStream input=storage.open(source); PDDocument pdf=PDDocument.load(input)) {
            PDPage page=new PDPage(PDRectangle.A4); pdf.addPage(page);
            try(PDPageContentStream canvas=new PDPageContentStream(pdf,page)) {
                canvas.beginText(); canvas.setFont(PDType1Font.HELVETICA_BOLD,16);
                canvas.newLineAtOffset(48,760);
                canvas.showText(verificationMode.equals("DEMO")?"DEMO SIGNATURE - NOT A LEGAL E-SIGNATURE":"SIGNATURE VERIFICATION QR");
                canvas.endText();
                line(canvas,"Document: "+doc.name,48,715);
                line(canvas,"Signed by: "+signer.full_name,48,690);
                line(canvas,"Role: "+signer.position,48,665);
                line(canvas,"Verification code: "+code,48,640);
                line(canvas,"Verify: "+publicBaseUrl+"/verify/"+code,48,615);
                var qr=new QRCodeWriter().encode(publicBaseUrl+"/verify/"+code,BarcodeFormat.QR_CODE,180,180);
                canvas.drawImage(LosslessFactory.createFromImage(pdf,MatrixToImageWriter.toBufferedImage(qr)),48,390,180,180);
                if(verificationMode.equals("DEMO")) {
                    line(canvas,"This page demonstrates the planned QR placement and workflow.",48,350);
                    line(canvas,"Real PKCS#7 validation will be integrated with the bank service.",48,330);
                }
            }
            pdf.save(temporary.toFile());
            return storage.save(temporary,".pdf");
        } finally { Files.deleteIfExists(temporary); }
    }
    boolean exists(String location) { return storage.exists(location); }
    void copyStored(String location,OutputStream output) throws IOException { storage.copy(location,output); }
    String hashStored(String location) throws Exception {
        try(InputStream input=storage.open(location)) { return hash(input); }
    }
    private void line(PDPageContentStream canvas,String value,float x,float y) throws IOException {
        String ascii=value.replaceAll("[^\\x20-\\x7E]", "?");
        if (ascii.length()>95) ascii=ascii.substring(0,95);
        canvas.beginText(); canvas.setFont(PDType1Font.HELVETICA,11);
        canvas.newLineAtOffset(x,y); canvas.showText(ascii); canvas.endText();
    }
    static String hash(Path file) throws Exception {
        try(InputStream input=Files.newInputStream(file)) { return hash(input); }
    }
    private static String hash(InputStream input) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        byte[] buffer=new byte[8192]; int n;
        while((n=input.read(buffer))!=-1) digest.update(buffer,0,n);
        return HexFormat.of().formatHex(digest.digest());
    }
    static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
