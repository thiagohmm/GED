package br.com.ged.document;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record UpdateDocumentTagsRequest(@Size(max = 20) List<@NotBlank @Size(max = 40) String> tags) {
}
