package br.com.ged.backup;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Execucao do backup fora do modo web:
 *   mvn spring-boot:run -Dspring-boot.run.arguments="--spring.main.web-application-type=none,--ged.cli=backup"
 *   ... --ged.cli=restore --ged.cli-path=ged-20250101T000000
 *   ... --ged.cli=verify  --ged.cli-path=ged-20250101T000000
 */
@Component
@Order(0)
public class CliRunner implements ApplicationRunner {

    private final BackupService backupService;
    private final String cli;
    private final String cliPath;

    public CliRunner(BackupService backupService,
                     @Value("${ged.cli:}") String cli,
                     @Value("${ged.cli-path:}") String cliPath) {
        this.backupService = backupService;
        this.cli = cli == null ? "" : cli;
        this.cliPath = cliPath == null ? "" : cliPath;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (cli == null || cli.isBlank()) {
            return;
        }
        try {
            switch (cli.trim().toLowerCase()) {
                case "backup" -> {
                    String name = backupService.create();
                    System.out.println("Backup criado: " + name);
                }
                case "restore" -> {
                    requirePath();
                    backupService.restore(cliPath);
                    System.out.println("Restore concluido: " + cliPath);
                }
                case "verify" -> {
                    requirePath();
                    List<String> failures = backupService.verify(cliPath);
                    if (failures.isEmpty()) {
                        System.out.println("Backup OK: " + cliPath);
                    } else {
                        System.err.println("Falhas de verificacao em " + cliPath + ":");
                        failures.forEach(failure -> System.err.println(" - " + failure));
                        System.exit(1);
                    }
                }
                default -> {
                    System.err.println("Modo CLI invalido: " + cli + " (use backup, restore ou verify).");
                    System.exit(1);
                }
            }
        } catch (RuntimeException exception) {
            System.err.println("Falha na operacao: " + exception.getMessage());
            System.exit(1);
        }
        System.exit(0);
    }

    private void requirePath() {
        if (cliPath == null || cliPath.isBlank()) {
            System.err.println("Informe o bundle com --ged.cli-path=<nome-do-backup>.");
            System.exit(1);
        }
    }
}
