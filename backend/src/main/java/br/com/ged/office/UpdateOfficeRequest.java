package br.com.ged.office;

import jakarta.validation.constraints.Size;

public record UpdateOfficeRequest(@Size(max = 120) String name, Boolean enabled) {
}
