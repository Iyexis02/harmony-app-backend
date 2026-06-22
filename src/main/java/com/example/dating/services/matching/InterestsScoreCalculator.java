package com.example.dating.services.matching;

import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.personality.dao.UserPersonality;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Calculates interests compatibility using Jaccard similarity on comma-separated interest tags.
 * Applies synonym normalization to improve matching across different phrasings.
 */
@Component
@Slf4j
public class InterestsScoreCalculator {

    private static final double ZERO_OVERLAP_FLOOR = 10.0;

    private static final Map<String, String> SYNONYM_MAP = Map.ofEntries(
            Map.entry("hike", "hiking"),
            Map.entry("hikes", "hiking"),
            Map.entry("cook", "cooking"),
            Map.entry("travelling", "travel"),
            Map.entry("traveling", "travel"),
            Map.entry("games", "gaming"),
            Map.entry("game", "gaming"),
            Map.entry("gym", "fitness"),
            Map.entry("workout", "fitness"),
            Map.entry("workouts", "fitness"),
            Map.entry("exercising", "fitness"),
            Map.entry("exercise", "fitness"),
            Map.entry("film", "movies"),
            Map.entry("films", "movies"),
            Map.entry("movie", "movies"),
            Map.entry("cinema", "movies"),
            Map.entry("jogging", "running"),
            Map.entry("run", "running"),
            Map.entry("swim", "swimming"),
            Map.entry("dance", "dancing"),
            Map.entry("drawing", "art"),
            Map.entry("painting", "art"),
            Map.entry("sketch", "art"),
            Map.entry("sketching", "art"),
            Map.entry("photograph", "photography"),
            Map.entry("photos", "photography"),
            Map.entry("photo", "photography"),
            Map.entry("book", "reading"),
            Map.entry("books", "reading"),
            Map.entry("read", "reading"),
            Map.entry("write", "writing"),
            Map.entry("sing", "singing"),
            Map.entry("song", "singing"),
            Map.entry("songs", "singing"),
            Map.entry("bike", "cycling"),
            Map.entry("biking", "cycling"),
            Map.entry("cycle", "cycling"),
            Map.entry("camp", "camping"),
            Map.entry("climb", "climbing"),
            Map.entry("boulder", "climbing"),
            Map.entry("bouldering", "climbing"),
            Map.entry("meditate", "meditation"),
            Map.entry("meditating", "meditation"),
            Map.entry("surf", "surfing"),
            Map.entry("ski", "skiing"),
            Map.entry("snowboard", "snowboarding"),
            // Multi-word and domain synonyms
            Map.entry("literature", "reading"),
            Map.entry("live music", "concert"),
            Map.entry("baking", "cooking"),
            Map.entry("video games", "gaming"),
            Map.entry("nature walks", "hiking"),
            Map.entry("mountain biking", "cycling"),
            Map.entry("board games", "gaming"),
            Map.entry("indie films", "movie"),
            Map.entry("sci-fi books", "reading"),
            Map.entry("marathon running", "running"),
            Map.entry("crossfit", "fitness"),
            Map.entry("pilates", "fitness"),
            Map.entry("meal prep", "cooking"),
            Map.entry("jazz clubs", "concert"),
            Map.entry("travel photography", "photography"),
            Map.entry("food", "cooking")
    );

    /**
     * Returns a 0-100 score based on Jaccard similarity of interest sets.
     * Returns 50.0 (neutral) if either user has no interests.
     */
    public double calculate(UserEntity userA, UserEntity userB) {
        Set<String> a = parseInterests(userA);
        Set<String> b = parseInterests(userB);

        if (a.isEmpty() || b.isEmpty()) {
            return 50.0;
        }

        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);

        Set<String> union = new HashSet<>(a);
        union.addAll(b);

        double rawScore = union.isEmpty() ? 0.0 : (intersection.size() / (double) union.size()) * 100.0;
        double score = Math.max(ZERO_OVERLAP_FLOOR, rawScore);

        log.debug("Interests Jaccard: |A|={}, |B|={}, |∩|={}, |∪|={}, raw={}, score={}",
                a.size(), b.size(), intersection.size(), union.size(), rawScore, score);

        return score;
    }

    /**
     * Returns the list of shared interest tags (normalized).
     */
    public List<String> getSharedInterests(UserEntity userA, UserEntity userB) {
        Set<String> a = parseInterests(userA);
        Set<String> b = parseInterests(userB);

        if (a.isEmpty() || b.isEmpty()) {
            return Collections.emptyList();
        }

        Set<String> intersection = new HashSet<>(a);
        intersection.retainAll(b);

        return intersection.stream().sorted().collect(Collectors.toList());
    }

    private Set<String> parseInterests(UserEntity user) {
        UserPersonality personality = user.getPersonality();
        if (personality == null || personality.getInterests() == null || personality.getInterests().isBlank()) {
            return Collections.emptySet();
        }

        return Arrays.stream(personality.getInterests().split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .filter(s -> !s.isEmpty())
                .map(this::normalize)
                .collect(Collectors.toSet());
    }

    private String normalize(String interest) {
        String synonym = SYNONYM_MAP.get(interest);
        if (synonym != null) {
            return synonym;
        }
        // Strip trailing 's' for simple plurals (length > 3, not ending in "ss")
        if (interest.length() > 3 && interest.endsWith("s") && !interest.endsWith("ss")) {
            return interest.substring(0, interest.length() - 1);
        }
        return interest;
    }
}
