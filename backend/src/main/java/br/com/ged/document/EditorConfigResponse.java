package br.com.ged.document;

import java.util.Map;

public record EditorConfigResponse(String documentServerUrl, Map<String, Object> config) {
}
