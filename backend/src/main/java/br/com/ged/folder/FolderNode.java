package br.com.ged.folder;

import java.util.ArrayList;
import java.util.List;

public record FolderNode(Long id, String name, Long parentId, Long projectId, String projectName,
                          Long assignedAttorneyId, String assignedAttorneyUsername, List<FolderNode> children) {

    public static FolderNode leaf(Long id, String name, Long parentId, Long projectId, String projectName,
                                  Long assignedAttorneyId, String assignedAttorneyUsername) {
        return new FolderNode(id, name, parentId, projectId, projectName,
                assignedAttorneyId, assignedAttorneyUsername, new ArrayList<>());
    }
}
