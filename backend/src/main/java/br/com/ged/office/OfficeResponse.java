package br.com.ged.office;

import java.time.Instant;

public record OfficeResponse(Long id, String name, boolean enabled, Instant createdAt) {

    public static OfficeResponse from(Office office) {
        return new OfficeResponse(office.getId(), office.getName(), office.isEnabled(), office.getCreatedAt());
    }
}
