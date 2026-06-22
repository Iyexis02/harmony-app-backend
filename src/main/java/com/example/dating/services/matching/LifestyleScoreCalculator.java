package com.example.dating.services.matching;

import com.example.dating.enums.user.DrinkingHabits;
import com.example.dating.enums.user.KidsPreference;
import com.example.dating.enums.user.RelationshipGoal;
import com.example.dating.enums.user.SmokingHabits;
import com.example.dating.models.user.common.dao.UserEntity;
import com.example.dating.models.user.dating.dao.UserDatingPreferences;
import com.example.dating.models.user.lifestyle.dao.UserLifestyle;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Calculates lifestyle compatibility score between two users.
 * Score is 0-100 and considers: relationship goal, kids preference, smoking, and drinking.
 */
@Component
@Slf4j
public class LifestyleScoreCalculator {

    private static final Set<RelationshipGoal> SERIOUS_GOALS = EnumSet.of(
            RelationshipGoal.MARRIAGE, RelationshipGoal.SERIOUS_RELATIONSHIP);

    private static final Set<RelationshipGoal> CASUAL_GOALS = EnumSet.of(
            RelationshipGoal.CASUAL_DATING, RelationshipGoal.SOMETHING_CASUAL);

    private static final Set<RelationshipGoal> FLEXIBLE_GOALS = EnumSet.of(
            RelationshipGoal.FIGURING_IT_OUT, RelationshipGoal.PREFER_NOT_TO_SAY,
            RelationshipGoal.FRIENDSHIP);

    public double calculate(UserEntity userA, UserEntity userB) {
        UserLifestyle lifestyleA = userA.getLifestyle();
        UserLifestyle lifestyleB = userB.getLifestyle();

        if (lifestyleA == null || lifestyleB == null) {
            return 50.0; // neutral default
        }

        RelationshipGoal goalA = getRelationshipGoal(userA);
        RelationshipGoal goalB = getRelationshipGoal(userB);

        double goalScore = calculateGoalScore(goalA, goalB);
        double kidsScore = calculateKidsScore(lifestyleA.getWantsKids(), lifestyleB.getWantsKids());
        double smokingScore = calculateSmokingScore(lifestyleA.getSmokingHabits(), lifestyleB.getSmokingHabits());
        double drinkingScore = calculateDrinkingScore(lifestyleA.getDrinkingHabits(), lifestyleB.getDrinkingHabits());

        double score = (goalScore * 0.40)
                + (kidsScore * 0.30)
                + (smokingScore * 0.15)
                + (drinkingScore * 0.15);

        log.debug("Lifestyle scores — goal={}, kids={}, smoking={}, drinking={}, total={}",
                goalScore, kidsScore, smokingScore, drinkingScore, score);

        return score;
    }

    private RelationshipGoal getRelationshipGoal(UserEntity user) {
        UserDatingPreferences prefs = user.getDatingPreferences();
        return prefs != null ? prefs.getRelationshipGoal() : null;
    }

    private double calculateGoalScore(RelationshipGoal a, RelationshipGoal b) {
        if (a == null || b == null) return 55.0;
        if (a == b) return 100.0;
        if (SERIOUS_GOALS.contains(a) && SERIOUS_GOALS.contains(b)) return 85.0;
        if (CASUAL_GOALS.contains(a) && CASUAL_GOALS.contains(b)) return 85.0;
        if (FLEXIBLE_GOALS.contains(a) || FLEXIBLE_GOALS.contains(b)) return 55.0;
        // Cross-group: serious vs casual
        return 10.0;
    }

    private double calculateKidsScore(KidsPreference a, KidsPreference b) {
        if (a == null || b == null) return 50.0;
        if (a == b) return 100.0;

        // Hard incompatibility
        if ((a == KidsPreference.WANTS_KIDS && b == KidsPreference.DOESNT_WANT_KIDS)
                || (a == KidsPreference.DOESNT_WANT_KIDS && b == KidsPreference.WANTS_KIDS)) {
            return 5.0;
        }

        // Either side is flexible
        Set<KidsPreference> flexible = EnumSet.of(
                KidsPreference.OPEN_TO_KIDS, KidsPreference.NOT_SURE, KidsPreference.PREFER_NOT_TO_SAY);
        if (flexible.contains(a) || flexible.contains(b)) return 65.0;

        // WANTS_KIDS + HAS_KIDS_WANTS_MORE
        if ((a == KidsPreference.WANTS_KIDS && b == KidsPreference.HAS_KIDS_WANTS_MORE)
                || (a == KidsPreference.HAS_KIDS_WANTS_MORE && b == KidsPreference.WANTS_KIDS)) {
            return 80.0;
        }

        return 50.0;
    }

    private double calculateSmokingScore(SmokingHabits a, SmokingHabits b) {
        if (a == null || b == null) return 70.0;
        if (a == b) return 100.0;
        if (a == SmokingHabits.PREFER_NOT_TO_SAY || b == SmokingHabits.PREFER_NOT_TO_SAY) return 70.0;

        // NON_SMOKER combos
        if ((a == SmokingHabits.NON_SMOKER && b == SmokingHabits.TRYING_TO_QUIT)
                || (a == SmokingHabits.TRYING_TO_QUIT && b == SmokingHabits.NON_SMOKER)) return 65.0;
        if ((a == SmokingHabits.NON_SMOKER && b == SmokingHabits.SOCIAL_SMOKER)
                || (a == SmokingHabits.SOCIAL_SMOKER && b == SmokingHabits.NON_SMOKER)) return 40.0;
        if ((a == SmokingHabits.NON_SMOKER && b == SmokingHabits.REGULAR_SMOKER)
                || (a == SmokingHabits.REGULAR_SMOKER && b == SmokingHabits.NON_SMOKER)) return 10.0;

        // TRYING_TO_QUIT combos
        if ((a == SmokingHabits.TRYING_TO_QUIT && b == SmokingHabits.SOCIAL_SMOKER)
                || (a == SmokingHabits.SOCIAL_SMOKER && b == SmokingHabits.TRYING_TO_QUIT)) return 70.0;
        if ((a == SmokingHabits.TRYING_TO_QUIT && b == SmokingHabits.REGULAR_SMOKER)
                || (a == SmokingHabits.REGULAR_SMOKER && b == SmokingHabits.TRYING_TO_QUIT)) return 55.0;

        // SOCIAL_SMOKER + REGULAR_SMOKER
        if ((a == SmokingHabits.SOCIAL_SMOKER && b == SmokingHabits.REGULAR_SMOKER)
                || (a == SmokingHabits.REGULAR_SMOKER && b == SmokingHabits.SOCIAL_SMOKER)) return 70.0;

        return 50.0;
    }

    private double calculateDrinkingScore(DrinkingHabits a, DrinkingHabits b) {
        if (a == null || b == null) return 65.0;
        if (a == DrinkingHabits.PREFER_NOT_TO_SAY || b == DrinkingHabits.PREFER_NOT_TO_SAY) return 65.0;

        int ordinalA = drinkingOrdinal(a);
        int ordinalB = drinkingOrdinal(b);
        int diff = Math.abs(ordinalA - ordinalB);

        return switch (diff) {
            case 0 -> 100.0;
            case 1 -> 75.0;
            case 2 -> 45.0;
            default -> 20.0;
        };
    }

    /**
     * NON_DRINKER=0, SOCIAL_DRINKER=1, MODERATE_DRINKER=2, REGULAR_DRINKER=3
     */
    private int drinkingOrdinal(DrinkingHabits h) {
        return switch (h) {
            case NON_DRINKER -> 0;
            case SOCIAL_DRINKER -> 1;
            case MODERATE_DRINKER -> 2;
            case REGULAR_DRINKER -> 3;
            default -> -1;
        };
    }
}
