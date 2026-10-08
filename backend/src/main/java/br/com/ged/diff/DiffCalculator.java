package br.com.ged.diff;

import com.github.difflib.DiffUtils;
import com.github.difflib.patch.AbstractDelta;
import com.github.difflib.patch.Patch;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class DiffCalculator {

    public List<DiffLine> calculate(List<String> oldLines, List<String> newLines) {
        Patch<String> patch = DiffUtils.diff(oldLines, newLines);
        List<DiffLine> diffLines = new ArrayList<>();
        int sourceIndex = 0;

        for (AbstractDelta<String> delta : patch.getDeltas()) {
            appendUnchangedBefore(delta.getSource().getPosition(), sourceIndex, oldLines, diffLines);
            delta.getSource().getLines().forEach(line -> diffLines.add(new DiffLine(DiffType.REMOVED, line)));
            delta.getTarget().getLines().forEach(line -> diffLines.add(new DiffLine(DiffType.ADDED, line)));
            sourceIndex = delta.getSource().getPosition() + delta.getSource().size();
        }
        appendUnchangedBefore(oldLines.size(), sourceIndex, oldLines, diffLines);
        return diffLines;
    }

    private void appendUnchangedBefore(int untilIndex, int fromIndex, List<String> sourceLines, List<DiffLine> diffLines) {
        for (int index = fromIndex; index < untilIndex; index++) {
            diffLines.add(new DiffLine(DiffType.UNCHANGED, sourceLines.get(index)));
        }
    }
}
