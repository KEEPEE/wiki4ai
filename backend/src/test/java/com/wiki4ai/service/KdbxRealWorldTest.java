package com.wiki4ai.service;

import com.wiki4ai.dto.VaultEntryImportDTO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class KdbxRealWorldTest {

    @Autowired
    private VaultImportService vaultImportService;

    @Test
    void shouldImportFromRealKdbxFile() throws IOException {
        String password = "540413";
        
        try (var inputStream = new ClassPathResource("real-world-sample.kdbx").getInputStream()) {
            List<VaultEntryImportDTO> entries = vaultImportService.importFromKdbx(inputStream, password);
            
            System.out.println("\n=== REAL KDBX FILE ANALYSIS ===");
            System.out.println("Total entries imported: " + entries.size());
            
            // Count by group path
            System.out.println("\n--- Group paths ---");
            entries.stream()
                .map(VaultEntryImportDTO::getGroupPath)
                .distinct()
                .forEach(gp -> {
                    long count = entries.stream().filter(e -> gp.equals(e.getGroupPath())).count();
                    System.out.println("  " + (gp != null && !gp.isEmpty() ? gp : "[root]") + ": " + count + " entries");
                });
            
            // Check field presence
            long withTitle = entries.stream().filter(e -> e.getTitle() != null && !e.getTitle().isEmpty()).count();
            long withoutTitle = entries.size() - withTitle;
            long withUsername = entries.stream().filter(e -> e.getUsername() != null && !e.getUsername().isEmpty()).count();
            long withPassword = entries.stream().filter(e -> e.getPassword() != null && !e.getPassword().isEmpty()).count();
            long withUrl = entries.stream().filter(e -> e.getUrl() != null && !e.getUrl().isEmpty()).count();
            long withNotes = entries.stream().filter(e -> e.getNotes() != null && !e.getNotes().isEmpty()).count();
            
            System.out.println("\n--- Field presence ---");
            System.out.println("  With title: " + withTitle);
            System.out.println("  Without title: " + withoutTitle);
            System.out.println("  With username: " + withUsername);
            System.out.println("  With password: " + withPassword);
            System.out.println("  With URL: " + withUrl);
            System.out.println("  With notes: " + withNotes);
            
            // Show sample entries (without passwords)
            System.out.println("\n--- Sample entries ---");
            entries.stream().limit(10).forEach(e -> {
                String title = e.getTitle() != null && !e.getTitle().isEmpty() ? e.getTitle() : "[NO TITLE]";
                String group = e.getGroupPath() != null && !e.getGroupPath().isEmpty() ? " [" + e.getGroupPath() + "]" : "";
                System.out.println("  Title: " + title + group);
            });
            
            // Assertions
            assertThat(entries).hasSize(35);
            assertThat(withTitle).isEqualTo(35); // all entries have titles
            assertThat(withPassword).isGreaterThan(0); // some entries may not have passwords
            
            // Check for encoding issues in group names (Slovak diacritics)
            boolean hasEncodingIssues = entries.stream()
                .anyMatch(e -> e.getGroupPath() != null && e.getGroupPath().contains("?"));
            System.out.println("  Has potential encoding issues (? chars): " + hasEncodingIssues);
            
            // Check for special characters / UTF-8
            boolean hasSpecialChars = entries.stream()
                .anyMatch(e -> {
                    String allText = (e.getTitle() != null ? e.getTitle() : "") + 
                                    (e.getUsername() != null ? e.getUsername() : "") +
                                    (e.getNotes() != null ? e.getNotes() : "");
                    return allText.codePoints().anyMatch(cp -> cp > 127);
                });
            System.out.println("\n--- Special characters ---");
            System.out.println("  Contains non-ASCII/UTF-8 chars: " + hasSpecialChars);
        }
    }
}
