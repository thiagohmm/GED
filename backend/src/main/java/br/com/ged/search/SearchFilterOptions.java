package br.com.ged.search;

import br.com.ged.user.UserResponse;

import java.util.List;

public record SearchFilterOptions(List<UserResponse> attorneys, List<UserResponse> reviewers) {
}
