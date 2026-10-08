package br.com.ged.project;

import br.com.ged.exception.ConflictException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;

    @Transactional(readOnly = true)
    public List<ProjectResponse> list() {
        return projectRepository.findAllByOrderByNameAsc().stream().map(ProjectResponse::from).toList();
    }

    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        String name = request.name().strip();
        if (projectRepository.findByNameIgnoreCase(name).isPresent()) {
            throw new ConflictException("Ja existe um projeto com este nome.");
        }
        return ProjectResponse.from(projectRepository.save(new Project(name)));
    }
}
