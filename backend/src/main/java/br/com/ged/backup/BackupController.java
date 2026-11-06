package br.com.ged.backup;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Backup/restore: exclusiva do SUPER_ADMIN (global).
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    @GetMapping("/backups")
    public List<BackupInfo> list() {
        return backupService.list();
    }

    @PostMapping("/backups")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> create() {
        return Map.of("backup", backupService.create());
    }

    @PostMapping("/backups/{name}/verify")
    public Map<String, Object> verify(@PathVariable String name) {
        List<String> failures = backupService.verify(name);
        return Map.of("ok", failures.isEmpty(), "failures", failures);
    }

    @PostMapping("/restore")
    public Map<String, String> restore(@RequestBody Map<String, String> request) {
        String backup = request.get("backup");
        backupService.restore(backup);
        return Map.of("restored", String.valueOf(backup));
    }

    @DeleteMapping("/backups/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String name) {
        backupService.delete(name);
    }
}
