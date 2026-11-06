package br.com.ged.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Fails fast em producao quando o segredo do JWT esta ausente ou usa o valor padrao do dev.
 * O seed do dev (admin/admin123) tambem nunca roda com o profile prod (seed.enabled=false la).
 */
@Component
public class ProductionGuard implements InitializingBean, EnvironmentAware {

    public static final String DEV_DEFAULT_SECRET = "troque-este-segredo-em-producao-com-no-minimo-32-caracteres";

    private Environment environment;

    @Value("${ged.jwt.secret:}")
    private String secret;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        boolean prod = Arrays.asList(environment.getActiveProfiles()).contains("prod")
                || environment.getProperty("spring.profiles.active", "").contains("prod");
        if (!prod) {
            return;
        }
        if (secret.isBlank()) {
            throw new IllegalStateException(
                    "Producao: GED_JWT_SECRET nao definida. Gere um segredo aleatorio, ex.: openssl rand -hex 32.");
        }
        if (DEV_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "Producao: GED_JWT_SECRET esta usando o valor padrao do desenvolvimento. Defina um segredo proprio.");
        }
    }
}
