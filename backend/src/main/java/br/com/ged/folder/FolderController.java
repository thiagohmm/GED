package br.com.ged.folder;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/folders")
public class FolderController {

    private final FolderService folderService;

    public FolderController(FolderService folderService) {
        this.folderService = folderService;
    }

    @GetMapping("/tree")
    public List<FolderNode> tree() {
        return folderService.tree();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public FolderNode create(@Valid @RequestBody CreateFolderRequest request,
                             @AuthenticationPrincipal String username) {
        return folderService.create(request, username);
    }

    @PatchMapping("/{id}/move")
    public FolderNode move(@PathVariable Long id, @Valid @RequestBody MoveFolderRequest request) {
        return folderService.moveFolder(id, request.targetFolderId());
    }

    @PatchMapping("/{id}")
    public FolderNode rename(@PathVariable Long id, @Valid @RequestBody RenameFolderRequest request) {
        return folderService.rename(id, request);
    }

    @PatchMapping("/{id}/assignment")
    public FolderNode assignRoot(@PathVariable Long id, @Valid @RequestBody AssignRootFolderRequest request) {
        return folderService.assignRoot(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        folderService.delete(id);
    }
}
