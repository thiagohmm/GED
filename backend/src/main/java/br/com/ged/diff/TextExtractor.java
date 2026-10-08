package br.com.ged.diff;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

public interface TextExtractor {

    Set<String> extensions();

    List<String> extract(Path file);
}
