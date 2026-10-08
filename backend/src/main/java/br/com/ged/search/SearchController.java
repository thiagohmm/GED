package br.com.ged.search;

import br.com.ged.document.DocumentStatus;
import br.com.ged.user.Role;
import br.com.ged.user.UserRepository;
import br.com.ged.user.UserResponse;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@Validated
public class SearchController {

    private final SearchGateway searchGateway;
    private final SearchFilterService searchFilterService;
    private final UserRepository userRepository;

    @GetMapping("/api/search")
    public List<SearchResultItem> search(@RequestParam @NotBlank String q,
                                         @RequestParam(required = false) DocumentStatus status,
                                         @RequestParam(required = false) LocalDate createdFrom,
                                         @RequestParam(required = false) LocalDate createdTo,
                                         @RequestParam(required = false) Long attorneyId,
                                         @RequestParam(required = false) Long reviewerId,
                                         @RequestParam(required = false) String tag) {
        List<SearchResultItem> results = searchGateway.search(q.strip(), status);
        return searchFilterService.filter(results, createdFrom, createdTo, attorneyId, reviewerId, tag);
    }

    @GetMapping("/api/search/filters")
    public SearchFilterOptions filterOptions() {
        List<UserResponse> attorneys = userRepository.findByRoleAndEnabledTrueOrderByUsernameAsc(Role.ADVOGADO)
                .stream().map(UserResponse::from).toList();
        List<UserResponse> reviewers = userRepository.findByRoleAndEnabledTrueOrderByUsernameAsc(Role.REVISOR)
                .stream().map(UserResponse::from).toList();
        return new SearchFilterOptions(attorneys, reviewers);
    }
}
