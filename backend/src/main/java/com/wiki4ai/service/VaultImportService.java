package com.wiki4ai.service;

import org.linguafranca.pwdb.Credentials;
import org.linguafranca.pwdb.Database;
import org.linguafranca.pwdb.Entry;
import org.linguafranca.pwdb.Group;
import org.linguafranca.pwdb.kdbx.KdbxCreds;
import org.linguafranca.pwdb.kdbx.KdbxHeader;
import org.linguafranca.pwdb.kdbx.KdbxSerializer;
import org.linguafranca.pwdb.kdbx.jackson.JacksonDatabase;
import org.springframework.stereotype.Service;

import com.wiki4ai.dto.VaultEntryImportDTO;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

@Service
public class VaultImportService {

    public List<VaultEntryImportDTO> importFromKdbx(java.io.InputStream file, String password) throws IOException {
        byte[] passwordBytes = password.getBytes(StandardCharsets.UTF_8);

        byte[] fileBytes = readAllBytes(file);
        
        try (ByteArrayInputStream bais = new ByteArrayInputStream(fileBytes)) {
            KdbxHeader header = readOuterHeader(bais);
            
            List<Credentials> candidateCredentials = buildCandidateCredentials(passwordBytes, header);
            
            for (Credentials credentials : candidateCredentials) {
                // Reset stream position before each attempt
                bais.reset();
                
                try {
                    Database<?, ?, ?, ?> database = JacksonDatabase.load(credentials, bais);
                    List<VaultEntryImportDTO> entries = new ArrayList<>();
                    extractEntriesRecursive(database.getRootGroup(), "", entries);
                    return entries;
                } catch (Exception ignored) {}
            }

            throw new IllegalArgumentException("Invalid password or corrupted KDBX file");
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to import KDBX file: " + e.getMessage(), e);
        } finally {
            try {
                file.close();
            } catch (IOException ignored) {}
        }
    }

    private List<Credentials> buildCandidateCredentials(byte[] passwordBytes, KdbxHeader header) {
        List<Credentials> candidates = new ArrayList<>();
        
        // Try direct password bytes first (works for older KDBX v3.x formats)
        candidates.add(new KdbxCreds(passwordBytes));
        
        // Also try SHA-256 hash of password (for newer KDBX formats)
        byte[] sha256Hash = hashSha256(passwordBytes);
        candidates.add(new KdbxCreds(sha256Hash));

        return candidates;
    }

    private KdbxHeader readOuterHeader(ByteArrayInputStream bais) throws IOException {
        bais.mark(Integer.MAX_VALUE);
        try {
            KdbxHeader header = new KdbxHeader();
            KdbxSerializer.readOuterHeader(bais, header);
            return header;
        } finally {
            bais.reset();
        }
    }

    private void extractEntriesRecursive(Group<?, ?, ?, ?> group, String parentPath, List<VaultEntryImportDTO> entries) {
        String groupName = fixEncoding(group.getName());
        String currentPath = parentPath.isEmpty() ? groupName : parentPath + "/" + groupName;

        for (Entry<?, ?, ?, ?> entry : group.getEntries()) {
            VaultEntryImportDTO dto = mapEntry(entry);
            if (!currentPath.isEmpty()) {
                dto.setGroupPath(currentPath);
            }
            entries.add(dto);
        }

        for (Group<?, ?, ?, ?> childGroup : group.getGroups()) {
            extractEntriesRecursive(childGroup, currentPath, entries);
        }
    }

    private VaultEntryImportDTO mapEntry(Entry<?, ?, ?, ?> entry) {
        String title = fixEncoding(entry.getProperty(Entry.STANDARD_PROPERTY_NAME_TITLE));
        String username = fixEncoding(entry.getUsername());
        String password = entry.getPassword();
        String url = fixEncoding(entry.getUrl());
        String notes = fixEncoding(entry.getProperty(Entry.STANDARD_PROPERTY_NAME_NOTES));

        return VaultEntryImportDTO.builder()
                .title(title != null ? title : "")
                .username(username)
                .password(password)
                .url(url)
                .notes(notes)
                .build();
    }

    private String fixEncoding(String text) {
        if (text == null || text.isEmpty()) return text;

        long originalQuestionMarks = text.chars().filter(ch -> ch == '?').count();
        if (originalQuestionMarks == 0) return text;

        try {
            byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);

            String fixedUtf8 = new String(bytes, StandardCharsets.UTF_8);
            long utf8Qm = fixedUtf8.chars().filter(ch -> ch == '?').count();
            if (utf8Qm < originalQuestionMarks) {
                return fixedUtf8;
            }

            try {
                String fixedCp1250 = new String(bytes, java.nio.charset.Charset.forName("CP1250"));
                long cp1250Qm = fixedCp1250.chars().filter(ch -> ch == '?').count();
                if (cp1250Qm < originalQuestionMarks) {
                    return fixedCp1250;
                }
            } catch (Exception ignored) {}

        } catch (Exception ignored) {}

        return text;
    }

    private byte[] readAllBytes(java.io.InputStream is) throws IOException {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = is.read(buffer)) != -1) {
            baos.write(buffer, 0, len);
        }
        return baos.toByteArray();
    }

    private byte[] hashSha256(byte[] input) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return sha256.digest(input);
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
}
