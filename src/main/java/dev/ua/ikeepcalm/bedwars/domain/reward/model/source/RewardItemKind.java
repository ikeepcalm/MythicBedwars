package dev.ua.ikeepcalm.bedwars.domain.reward.model.source;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RewardItemKind {
    ACTING_BOTTLE(false),
    SPIRITUALITY_POTION(false),
    ACTING_MULTIPLIER(false),
    SPIRITUALITY_REGEN(false),
    ANTI_CONTROL_CHARM(false),
    RITUAL_BOOK(false),
    INGREDIENT_TOKEN(false),
    RECIPE_TOKEN(true),
    POTION_TOKEN(true),
    UNIVERSAL_RECIPE_TOKEN(true),
    UNIVERSAL_POTION_TOKEN(true),
    PATHWAY_TRANSFER_TOKEN(false);

    private final boolean exchangeToken;
}
