package com.bilolbek.bankProductCreation;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
public class ApiController {
    private final ApiService service;
    ApiController(ApiService service) { this.service=service; }
    @GetMapping("/api/me") public Object me(@AuthenticationPrincipal BankUser principal) { return service.me(principal); }
    @GetMapping("/api/departments") public Object departments(@AuthenticationPrincipal BankUser principal) { return service.departments(principal); }
    @GetMapping("/api/departments/{id}/employees") public Object employees(@PathVariable String id,@AuthenticationPrincipal BankUser principal) {
        return service.employees(id,principal);
    }
    @PostMapping("/api/requests") public ResponseEntity<?> create(@Valid @RequestBody CreateProductRequest body,
            @AuthenticationPrincipal BankUser principal,HttpServletRequest http) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(body.type(),principal,http.getRemoteAddr()));
    }
    @GetMapping("/api/requests") public Object list(@RequestParam(defaultValue="mine") String scope,@AuthenticationPrincipal BankUser principal) {
        return service.list(scope,principal);
    }
    @GetMapping("/api/requests/{id}") public Object detail(@PathVariable long id,@AuthenticationPrincipal BankUser principal) {
        return service.detail(id,principal);
    }
    @PostMapping(value="/api/requests/{id}/documents",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> upload(@PathVariable long id,@RequestParam String kind,@RequestParam MultipartFile file,
            @AuthenticationPrincipal BankUser principal,HttpServletRequest http) throws Exception {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.upload(id,kind,file.getOriginalFilename(),file.getBytes(),principal,http.getRemoteAddr()));
    }
    @DeleteMapping("/api/documents/{id}") public ResponseEntity<Void> delete(@PathVariable long id,
            @AuthenticationPrincipal BankUser principal,HttpServletRequest http) {
        service.delete(id,principal,http.getRemoteAddr()); return ResponseEntity.noContent().build();
    }
    @GetMapping("/api/documents/{id}/download") public ResponseEntity<StreamingResponseBody> download(@PathVariable long id,
            @RequestParam(defaultValue="false") boolean original,@AuthenticationPrincipal BankUser principal,HttpServletRequest http) {
        ApiService.Download file=service.download(id,original,principal,http.getRemoteAddr());
        String encoded=java.net.URLEncoder.encode(file.name(),StandardCharsets.UTF_8).replace("+","%20");
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.mediaType()))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename*=UTF-8''"+encoded)
            .body(output->service.copyStored(file.location(),output));
    }
    @PostMapping("/api/requests/{id}/actions/{action}/prepare") public Object prepare(@PathVariable long id,@PathVariable String action,
            @RequestBody(required=false) Map<String,Object> body,@AuthenticationPrincipal BankUser principal) throws Exception {
        return service.prepare(id,action,body==null?Map.of():body,principal);
    }
    @PostMapping("/api/requests/{id}/actions/{action}") public Object action(@PathVariable long id,@PathVariable String action,
            @RequestBody(required=false) Map<String,Object> body,@AuthenticationPrincipal BankUser principal,HttpServletRequest http) throws Exception {
        return service.action(id,action,body==null?Map.of():body,principal,http.getRemoteAddr());
    }
    @GetMapping("/api/notifications/summary") public Object notifications(@AuthenticationPrincipal BankUser principal) {
        return service.notifications(principal);
    }
    @GetMapping("/api/audit") public Object audit(@AuthenticationPrincipal BankUser principal) { return service.audit(principal); }
    @GetMapping("/api/requests/{id}/package") public ResponseEntity<StreamingResponseBody> pack(@PathVariable long id,
            @AuthenticationPrincipal BankUser principal,HttpServletRequest http) {
        var entries=service.packageEntries(id,principal,http.getRemoteAddr());
        StreamingResponseBody body=output->{
            try(ZipOutputStream zip=new ZipOutputStream(output)) {
                for(var entry:entries) {
                    zip.putNextEntry(new ZipEntry(entry.name()));
                    service.copyStored(entry.location(),zip);
                    zip.closeEntry();
                }
            }
        };
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=package-"+id+".zip").body(body);
    }
    @GetMapping(value="/verify/{code}",produces=MediaType.TEXT_HTML_VALUE) public String verify(@PathVariable String code) throws Exception {
        ApiService.Verification result=service.verify(code);
        String status=!result.intact()?"PDF snapshot changed or missing":!result.active()?"Signature superseded by a newer document or cycle":"PDF snapshot intact (current document)";
        String explanation=result.verificationMode().equals("DEMO") || result.verificationMode().equals("LEGACY_DEMO")
            ?"This is a simulated signature. PKCS#7 was not validated."
            :"Signature evidence was checked at signing time. This page checks the saved PDF snapshot hash.";
        return "<!doctype html><html><meta charset='utf-8'><title>Signature verification</title>"+
            "<style>body{font:16px system-ui;max-width:700px;margin:80px auto;padding:24px}b{color:#176c50}</style>"+
            "<h1>Signature verification</h1><p><b>"+escape(status)+"</b></p>"+
            "<p>Signer: "+escape(result.fullName())+"</p><p>Position: "+escape(result.position())+
            "</p><p>Time: "+escape(result.signedAt())+"</p><p>Cycle: "+result.cycle()+
            " (request now in cycle "+result.currentRequestCycle()+")</p><p>SHA-256: <code>"+
            escape(result.sha256())+"</code></p><p>Mode: "+escape(result.verificationMode())+"</p><p>"+escape(explanation)+"</p></html>";
    }
    private String escape(String value) {
        return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }
}
