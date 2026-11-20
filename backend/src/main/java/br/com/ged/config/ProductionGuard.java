package br.com.ged.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Fails fast em qualquer ambiente que nao seja explicitamente 'dev' ou 'test' quando os
 * segredos JWT estao ausentes, usam valores padrao de desenvolvimento ou sao identicos.
 */
@Component
public class ProductionGuard implements InitializingBean, EnvironmentAware {

    public static final String DEV_DEFAULT_SECRET = "troque-este-segredo-em-producao-com-no-minimo-32-caracteres";
    public static final String DEV_DEFAULT_EDITOR_SECRET = "troque-este-segredo-do-editor-em-producao-min-32";

    private Environment environment;

    @Value("${ged.jwt.secret:}")
    private String secret;

    @Value("${ged.editor.jwt-secret:}")
    private String editorSecret;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        Set<String> active = Arrays.stream(environment.getActiveProfiles())
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
        String propProfiles = environment.getProperty("spring.profiles.active", "");
        for (String token : propProfiles.split(",")) {
            String trimmed = token.trim();
            if (!trimmed.isEmpty()) {
                active.add(trimmed);
            }
        }

        boolean explicitDevOrTest = !active.isEmpty()
                && !active.contains("prod")
                && active.stream().allMatch(profile -> "dev".equals(profile) || "test".equals(profile));
        if (explicitDevOrTest) {
            return;
        }

        if (secret == null || secret.isBlank() || secret.length() < 32) {
            throw new IllegalStateException(
                    "Producao: GED_JWT_SECRET nao definida ou menor que 32 caracteres. "
                            + "Gere um segredo aleatorio (ex.: openssl rand -hex 32) ou ative explicitamente SPRING_PROFILES_ACTIVE=dev para ambiente local.");
        }
        if (DEV_DEFAULT_SECRET.equals(secret)) {
            throw new IllegalStateException(
                    "Producao: GED_JWT_SECRET esta usando o valor padrao do desenvolvimento. Defina um segredo proprio.");
        }
        if (editorSecret == null || editorSecret.isBlank() || editorSecret.length() < 32) {
            throw new IllegalStateException(
                    "Producao: GED_EDITOR_JWT_SECRET nao definida ou menor que 32 caracteres. "
                            + "Gere um segredo exclusivo para o OnlyOffice (ex.: openssl rand -hex 32).");
        }
        if (DEV_DEFAULT_EDITOR_SECRET.equals(editorSecret)) {
            throw new IllegalStateException(
                    "Producao: GED_EDITOR_JWT_SECRET esta usando o valor padrao do desenvolvimento. Defina um segredo proprio.");
        }
        if (secret.equals(editorSecret)) {
            throw new IllegalStateException(
                    "Producao: GED_EDITOR_JWT_SECRET deve ser diferente de GED_JWT_SECRET.");
        }
    }
}
