package br.com.ged.office;

import br.com.ged.user.UserResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Gestao de escritorios: exclusiva do SUPER_ADMIN (escopo global).
 */
@RestController
@RequestMapping("/api/admin/offices")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class OfficeController {

    private final OfficeService officeService;

    public OfficeController(OfficeService officeService) {
        this.officeService = officeService;
    }

    @GetMapping
    public List<OfficeResponse> list() {
        return officeService.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OfficeResponse create(@Valid @RequestBody CreateOfficeRequest request) {
        return officeService.create(request);
    }

    @PatchMapping("/{id}")
    public OfficeResponse update(@PathVariable Long id, @Valid @RequestBody UpdateOfficeRequest request) {
        return officeService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        officeService.delete(id);
    }

    @PostMapping("/{id}/admin")
    @ResponseStatus(HttpStatus.CREATED)
    public UserResponse createAdmin(@PathVariable Long id, @Valid @RequestBody CreateOfficeAdminRequest request) {
        return officeService.createAdmin(id, request);
    }
}
