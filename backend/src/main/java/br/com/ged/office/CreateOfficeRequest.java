package br.com.ged.office;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateOfficeRequest(@NotBlank @Size(max = 120) String name) {
}
