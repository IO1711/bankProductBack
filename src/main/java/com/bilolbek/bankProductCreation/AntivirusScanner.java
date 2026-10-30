package com.bilolbek.bankProductCreation;

import java.io.IOException;

/** Rejects an upload before it is stored or added to the workflow. */
public interface AntivirusScanner {
    void scan(byte[] content,String originalName) throws IOException;
}
