package br.com.ged.review;

import java.util.List;

public record ReviewQueueResponse(List<ReviewQueueItem> pending, List<ReviewQueueItem> recentlyDecided) {
}
