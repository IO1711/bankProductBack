package com.bilolbek.bankProductCreation;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;

/** Stores document bytes and returns stable locations saved in workflow metadata. */
public interface DocumentFileStorage {
    String save(byte[] content,String extension) throws IOException;
    String save(Path source,String extension) throws IOException;
    InputStream open(String location) throws IOException;
    boolean exists(String location);

    default void copy(String location,OutputStream output) throws IOException {
        try(InputStream input=open(location)) { input.transferTo(output); }
    }
}
