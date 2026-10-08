package br.com.ged.document;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record StatusChangeRequest(@NotNull DocumentStatus to, @Size(max = 500) String note) {
}
