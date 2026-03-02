package com.docvault.service;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;

/**
 * Extracts plain text from uploaded documents using Apache Tika.
 * Extracted text is forwarded to Azure AI Search for indexing.
 *
 * Supports: PDF, DOCX, XLSX, PPTX, TXT, CSV, and 1000+ other formats.
 */
@Service
public class TextExtractionService {

    private static final Logger log    = LoggerFactory.getLogger(TextExtractionService.class);
    private static final int    MAX_LEN = 500_000; // Max chars to index
    private final Tika           tika   = new Tika();

    /**
     * Extract plain text from a multipart file.
     *
     * @param file uploaded file
     * @return extracted text (capped at MAX_LEN chars), or empty string on failure
     */
    public String extract(MultipartFile file) {
        try {
            String text = tika.parseToString(file.getInputStream(), new Metadata(), MAX_LEN);
            log.debug("[Tika] Extracted {} chars from {}", text.length(), file.getOriginalFilename());
            return text;
        } catch (IOException | TikaException e) {
            log.warn("[Tika] Extraction failed for {}: {}", file.getOriginalFilename(), e.getMessage());
            return "";
        }
    }

    public String extractFromBytes(byte[] bytes, String filename, String contentType) {
        try {
            Metadata meta = new Metadata();
            if (contentType != null) meta.set(Metadata.CONTENT_TYPE, contentType);
            String text = tika.parseToString(new ByteArrayInputStream(bytes), meta, MAX_LEN);
            log.debug("[Tika] Extracted {} chars from {}", text.length(), filename);
            return text;
        } catch (IOException | TikaException e) {
            log.warn("[Tika] Extraction failed for {}: {}", filename, e.getMessage());
            return "";
        }
    }
}
