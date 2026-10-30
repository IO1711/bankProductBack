package com.bilolbek.bankProductCreation;

import com.bilolbek.bankProductCreation.config.AppProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Demo file adapter. Existing absolute paths in legacy data remain readable. */
@Component
@ConditionalOnProperty(name="app.document.mode",havingValue="demo",matchIfMissing=true)
public class LocalDocumentFileStorage implements DocumentFileStorage {
    private final Path filesDirectory;

    LocalDocumentFileStorage(AppProperties properties) throws IOException {
        filesDirectory=properties.dataDir().toAbsolutePath().normalize().resolve("files");
        Files.createDirectories(filesDirectory);
    }

    @Override public String save(byte[] content,String extension) throws IOException {
        Path path=newLocation(extension);
        Files.write(path,content,StandardOpenOption.CREATE_NEW);
        deleteOnRollback(path);
        return path.toString();
    }

    @Override public String save(Path source,String extension) throws IOException {
        Path path=newLocation(extension);
        Files.copy(source,path,StandardCopyOption.COPY_ATTRIBUTES);
        deleteOnRollback(path);
        return path.toString();
    }

    @Override public InputStream open(String location) throws IOException {
        return Files.newInputStream(Path.of(location));
    }

    @Override public boolean exists(String location) {
        return location!=null && Files.isRegularFile(Path.of(location));
    }

    private Path newLocation(String extension) {
        if(!extension.matches("\\.[a-z0-9]+")) throw new IllegalArgumentException("Invalid file extension");
        return filesDirectory.resolve(UUID.randomUUID()+extension);
    }

    private void deleteOnRollback(Path path) {
        if(!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if(status!=STATUS_COMMITTED) try { Files.deleteIfExists(path); }
                    catch(IOException ignored) { }
            }
        });
    }
}
