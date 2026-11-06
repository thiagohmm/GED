package br.com.ged.backup;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Configuracao de backup: pasta de bundles, diretorio dos binarios do Postgres
 * (pg_dump/pg_restore) e banco de manutencao usado para drop/create do banco de dados.
 */
@Component
@ConfigurationProperties(prefix = "ged.backup")
public class BackupProperties {

    private String dir = "backups";
    private String pgBinDir = "";
    private String maintenanceDb = "postgres";
    private String pgHost = "localhost";
    private int pgPort = 5432;
    private String pgDatabase = "ged";
    private String pgUser = "ged";
    private String pgPassword = "";

    public String getDir() {
        return dir;
    }

    public void setDir(String dir) {
        this.dir = dir;
    }

    public String getPgBinDir() {
        return pgBinDir;
    }

    public void setPgBinDir(String pgBinDir) {
        this.pgBinDir = pgBinDir;
    }

    public String getMaintenanceDb() {
        return maintenanceDb;
    }

    public void setMaintenanceDb(String maintenanceDb) {
        this.maintenanceDb = maintenanceDb;
    }

    public String getPgHost() {
        return pgHost;
    }

    public void setPgHost(String pgHost) {
        this.pgHost = pgHost;
    }

    public int getPgPort() {
        return pgPort;
    }

    public void setPgPort(int pgPort) {
        this.pgPort = pgPort;
    }

    public String getPgDatabase() {
        return pgDatabase;
    }

    public void setPgDatabase(String pgDatabase) {
        this.pgDatabase = pgDatabase;
    }

    public String getPgUser() {
        return pgUser;
    }

    public void setPgUser(String pgUser) {
        this.pgUser = pgUser;
    }

    public String getPgPassword() {
        return pgPassword;
    }

    public void setPgPassword(String pgPassword) {
        this.pgPassword = pgPassword;
    }
}
