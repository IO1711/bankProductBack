package com.bilolbek.bankProductCreation;

import java.io.InputStream;
import java.nio.file.Path;

/** Converts one Word file to a temporary PDF owned by the caller. */
public interface WordToPdfConverter {
    Path convert(InputStream word,String originalName) throws Exception;
}
