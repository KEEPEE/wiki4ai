package com.wiki4ai.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VaultEntryImportDTO {

    private String title;

    private String username;

    private String password;

    private String notes;

    private String url;

    private String groupPath;
}
