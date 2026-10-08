package br.com.ged.document;

import br.com.ged.diff.DiffLine;

import java.util.List;

public record DiffResponse(int fromVersion, int toVersion, List<DiffLine> lines) {
}
