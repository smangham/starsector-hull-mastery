package starship_legends;

import com.fs.starfarer.api.characters.PersonAPI;
import com.fs.starfarer.api.combat.ShieldAPI;
import com.fs.starfarer.api.combat.ShipAPI;
import com.fs.starfarer.api.combat.ShipVariantAPI;
import com.fs.starfarer.api.combat.WeaponAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.loading.VariantSource;
import com.fs.starfarer.api.loading.WeaponSlotAPI;
import com.fs.starfarer.api.util.WeightedRandomPicker;
import starship_legends.hullmods.Reputation;

import java.util.*;
import java.util.List;

public class RepRecord {
    static final Saved<Map<String, RepRecord>> INSTANCE_REGISTRY = new Saved<Map<String, RepRecord>>("reputationRecords", new HashMap<String, RepRecord>());
    static final float INITIAL_RATING = 0.5f;

    public static boolean existsFor(String shipID) { return INSTANCE_REGISTRY.val.containsKey(shipID); }
    public static boolean existsFor(ShipAPI ship) { return existsFor(ship.getFleetMemberId()); }
    public static boolean existsFor(FleetMemberAPI ship) { return existsFor(ship.getId()); }
    public static RepRecord get(String shipID) { return INSTANCE_REGISTRY.val.get(shipID); }
    public static RepRecord get(ShipAPI ship) { return get(ship.getFleetMemberId()); }
    public static RepRecord get(FleetMemberAPI ship) { return get(ship.getId()); }
    public static void deleteFor(FleetMemberAPI ship) {
        INSTANCE_REGISTRY.val.remove(ship.getId());
        Reputation.removeShipOfNote(ship.getVariant().getHullVariantId());
        Util.removeRepHullmodFromVariant(ship.getVariant());
    }
    public static Trait.Teir getTierFromTraitCount(int count) {
        if(count > 3 * ModPlugin.TRAITS_PER_TIER) return Trait.Teir.Legendary;
        if(count > 2 * ModPlugin.TRAITS_PER_TIER) return Trait.Teir.Famous;
        if(count > 1 * ModPlugin.TRAITS_PER_TIER) return Trait.Teir.Wellknown;
        if(count > 0 * ModPlugin.TRAITS_PER_TIER) return Trait.Teir.Notable;
        else return Trait.Teir.UNKNOWN;
    }
    public static void updateRepHullMod(FleetMemberAPI ship) {
        if(!RepRecord.existsFor(ship)) return;

        Trait.Teir tier = RepRecord.get(ship).getTeir();
        ShipVariantAPI v;

        if(tier == Trait.Teir.UNKNOWN) return;

        if(ship.getVariant().isStockVariant()) {
            v = ship.getVariant().clone();
            v.setSource(VariantSource.REFIT);
            ship.setVariant(v, false, false);
        } else v = ship.getVariant();

        v.setHullVariantId(ModPlugin.VARIANT_PREFIX + ship.getId());

        Util.removeRepHullmodFromVariant(v);
        v.addPermaMod(tier.getHullModID());

        List<String> slots = v.getModuleSlots();

        for(int i = 0; i < slots.size(); ++i) {
            ShipVariantAPI module = v.getModuleVariant(slots.get(i));
            module.setHullVariantId(v.getHullVariantId());
            module.addPermaMod(tier.getHullModID());
        }

        Reputation.addShipOfNote(ship);

        ship.updateStats();
    }
    public static float getAdjustedRating(float initialRating, float adjustmentRating, float adjustmentWeight) {
        adjustmentRating = Math.max(-1, Math.min(2, adjustmentRating));
        return Math.max(-0.004f, initialRating * (1f-adjustmentWeight) + adjustmentRating * adjustmentWeight);
    }
    public static float getXpToGuaranteeNewTrait(FleetMemberAPI ship) {
        if(!RepRecord.existsFor(ship)) return Trait.Teir.Notable.getXpToGuaranteeNewTrait();

        RepRecord rr = RepRecord.get(ship);
        int increase = 1;

        for (RepChange c : CampaignScript.pendingRepChanges.val) if(c.ship == ship && c.trait != null) increase++;

        return getTierFromTraitCount(rr.traits.size() + increase).getXpToGuaranteeNewTrait();
    }
    public static boolean isTraitRelevantForShip(FleetMemberAPI ship, Trait trait, boolean allowBadDefenseTrait,
                                                 boolean allowBadOffenseTrait, boolean allowCrewTrait) {

        if(ship == null || ship.getHullSpec() == null || trait == null) return false;

        ShieldAPI.ShieldType shieldType = ship.getHullSpec().getShieldType();
        TraitType type = trait.getType();
        boolean traitIsBad = trait.getEffectSign() == -1;

        for(String tag : type.getTags()) {
            switch (tag) {
                case TraitType.Tags.CREW:
                    if(!allowCrewTrait) return false;
                    break;
                case TraitType.Tags.CARRIER:
                    if(!ship.isCarrier()) return false;
                    break;
                case TraitType.Tags.SHIELD:
                    if(shieldType != ShieldAPI.ShieldType.FRONT && shieldType != ShieldAPI.ShieldType.OMNI)
                        return false;
                    break;
                case TraitType.Tags.CLOAK:
                    if(shieldType != ShieldAPI.ShieldType.PHASE) return false;
                    break;
                case TraitType.Tags.NO_AI:
                    if(ship.getMinCrew() <= 0) return false;
                    break;
                case TraitType.Tags.DEFENSE:
                    if(traitIsBad && !allowBadDefenseTrait) return false;
                    break;
                case TraitType.Tags.ATTACK:
                    boolean hasWeaponSlot = false;

                    for(WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
                        switch (slot.getWeaponType()) {
                            case SYSTEM: case DECORATIVE: case STATION_MODULE: case LAUNCH_BAY: continue;
                            default: hasWeaponSlot = true; break;
                        }

                        if(hasWeaponSlot) break;
                    }

                    if((traitIsBad && !allowBadOffenseTrait) || !hasWeaponSlot) return false;
                    break;
                case TraitType.Tags.MISSILE:
                    float total = ship.getHullSpec().getFighterBays() * 10, missile = 0;

                    for(WeaponSlotAPI slot : ship.getHullSpec().getAllWeaponSlotsCopy()) {
                        float op = 0;

                        switch (slot.getSlotSize()) {
                            case SMALL: op = 5; break;
                            case MEDIUM: op = 10; break;
                            case LARGE: op = 20; break;
                        }

                        switch (slot.getWeaponType()) {
                            case MISSILE:   missile += op * 1.0f; break;
                            case COMPOSITE: missile += op * 0.8f; break;
                            case SYNERGY:   missile += op * 0.8f; break;
                            case UNIVERSAL: missile += op * 0.6f; break;
                        }

                        total += op;
                    }

                    if(missile / total < 0.2f) return false;
                    break;
            }
        }

        if(!type.getIncompatibleBuiltInHullmods().isEmpty()) {
            for (String mod : ship.getHullSpec().getBuiltInMods()) {
                if (type.getIncompatibleBuiltInHullmods().contains(mod)) return false;
            }
        }

        if(!type.getRequiredBuiltInHullmods().isEmpty()) {
            if (!ship.getHullSpec().getBuiltInMods().containsAll(type.getRequiredBuiltInHullmods())) {
                return false;
            }
        }

        return true;
    }
    public static Trait chooseNewTrait(FleetMemberAPI ship, Random rand, boolean traitIsBad, boolean allowBadDefenseTrait,
                                       boolean allowBadOffenseTrait, boolean allowCrewTrait, WeightedRandomPicker<TraitType> picker) {

        if(ship == null || rand == null) return null;

        RepRecord rep = RepRecord.existsFor(ship) ? RepRecord.get(ship) : null;

        if(rep != null) {
            if (rep.hasMaximumTraits()) return null;

            for (Trait trait : rep.getTraits()) picker.remove(trait.getType());
        }

        for(RepChange change : CampaignScript.pendingRepChanges.val) {
            if(change.ship == ship && change.trait != null) picker.remove(change.trait.getType());
        }

        while(!picker.isEmpty()) {
            TraitType type = picker.pickAndRemove();
            Trait trait = type.getTrait(traitIsBad);

            if(trait == null) continue;

            boolean traitIsRelevant = isTraitRelevantForShip(ship, trait, allowBadDefenseTrait, allowBadOffenseTrait, allowCrewTrait);
            boolean skipCombatLogisticsMismatch = false;

            if(type.getTags().contains(TraitType.Tags.LOGISTICAL)
                    && !ship.getHullSpec().isCivilianNonCarrier()
                    && rand.nextFloat() <= ModPlugin.CHANCE_TO_IGNORE_LOGISTICS_TRAITS_ON_COMBAT_SHIPS) {

                skipCombatLogisticsMismatch = true;
            } else if(!type.getTags().contains(TraitType.Tags.LOGISTICAL)
                    && ship.getHullSpec().isCivilianNonCarrier()
                    && rand.nextFloat() <= ModPlugin.CHANCE_TO_IGNORE_COMBAT_TRAITS_ON_CIVILIAN_SHIPS) {

                skipCombatLogisticsMismatch = true;
            }

            if(trait != null && traitIsRelevant && !skipCombatLogisticsMismatch)
                return trait;
        }

        return null;
    }
    public static Trait chooseNewTrait(FleetMemberAPI ship, Random rand, boolean traitIsBad, float fractionDamageTaken,
                                       float damageDealtRatio, boolean wasDisabled) {

        return chooseNewTrait(ship, rand, traitIsBad, fractionDamageTaken > 0.05f, damageDealtRatio < 1, true,
                TraitType.getPickerCopy(wasDisabled));
    }

    private List<Trait> traits = new ArrayList<>();

    private Map<String, Integer> opinionsOfOfficers = new HashMap<>();
    private float rating = INITIAL_RATING;

    public RepRecord() { }
    public RepRecord(FleetMemberAPI ship) {
        if(ship != null) INSTANCE_REGISTRY.val.put(ship.getId(), this);
    }

    public int getTraitPosition(Trait trait) {
        for(int i = 0; i < traits.size(); ++i) {
            if(traits.get(i) == trait) return i;
        }

        return -1;
    }
    public float getTraitEffect(Trait trait) {
        return getTraitEffect(trait.getType(), 0, ShipAPI.HullSize.DEFAULT);
    }
    public float getTraitEffect(TraitType type) { return getTraitEffect(type, 0, ShipAPI.HullSize.DEFAULT); }
    public float getTraitEffect(String typeID) { return getTraitEffect(TraitType.get(typeID), 0, ShipAPI.HullSize.DEFAULT); }
    public float getTraitEffect(TraitType type, int loyaltyEffectAdjustment, ShipAPI.HullSize size) {
        int traitsLeft = Math.min(getTraits().size(), Trait.getTraitLimit());


        for(Trait trait : getTraits()) {
            if(traitsLeft <= 0) break;

            traitsLeft--;

            if(trait.getType().equals(type)) {
                return trait.getEffect(RepRecord.getTierFromTraitCount(traitsLeft--), loyaltyEffectAdjustment, size);
            }
        }


        return -1;
    }
    public boolean hasMaximumTraits() {
        return getTraits().size() >= Trait.getTraitLimit();
    }
    public boolean hasTrait(Trait trait) {
        for(Trait t : traits) if(t.equals(trait)) return true;

        return false;
    }
    public boolean hasTraitType(String typeID) {
        return hasTraitType(TraitType.get(typeID));
    }
    public boolean hasTraitType(TraitType type) {
        for(Trait t : traits) if(t.getType().equals(type)) return true;

        return false;
    }
    public boolean hasTraitWithTag(String tag) {
        for(Trait t : traits) if(t.getType().getTags().contains(tag)) return true;

        return false;
    }
    public Map<String, Integer> getOpinionsOfOfficers() {
        return opinionsOfOfficers;
    }
    public LoyaltyLevel getLoyalty(PersonAPI officer) {
        int index = opinionsOfOfficers.containsKey(officer.getId())
                ? opinionsOfOfficers.get(officer.getId()) + ModPlugin.LOYALTY_LIMIT
                : ModPlugin.LOYALTY_LIMIT;
        return LoyaltyLevel.values()[index];
    }
    public void adjustLoyalty(PersonAPI officer, int adjustment) {
        int currentVal = opinionsOfOfficers.containsKey(officer.getId()) ? opinionsOfOfficers.get(officer.getId()) : 0;
        int newVal = Math.max(Math.min(currentVal + adjustment, ModPlugin.LOYALTY_LIMIT), -ModPlugin.LOYALTY_LIMIT);

        opinionsOfOfficers.put(officer.getId(), newVal);
    }
    public void setLoyalty(PersonAPI officer, int newOpinionLevel) {
        int newVal = Math.max(Math.min(newOpinionLevel, ModPlugin.LOYALTY_LIMIT), -ModPlugin.LOYALTY_LIMIT);

        opinionsOfOfficers.put(officer.getId(), newVal);
    }
    public List<Trait> getTraits() { return traits; }
    public Trait.Teir getTeir() { return getTierFromTraitCount(traits.size()); }
    public float getRating() {
        return rating;
    }
    public void setRating(float newRating) {
        rating = Math.max(-0.004f, Math.min(1.004f, newRating));
    }
    public float getRatingDiscrepancy() {
        return getFractionOfBonusEffectFromTraits() - getRating();
    }
    public float getFractionOfBonusEffectFromTraits() {
        return getFractionOfBonusEffectFromTraits(false);
    }
    public float getFractionOfBonusEffectFromTraits(boolean ignoreNewestTrait) {
        int traitsLeft = Math.min(getTraits().size(), Trait.getTraitLimit());
        float goodness = 0, total = 0;

        if(ignoreNewestTrait) traitsLeft--;

        for(Trait trait : getTraits()) {
            if (traitsLeft <= 0) break;

            float effect = getTierFromTraitCount(traitsLeft--).getEffectMultiplier();

            total += effect;

            if(trait.effectSign > 0) goodness += effect;
        }

        return total <= 0 ? INITIAL_RATING : goodness / total;
    }
    public float getFractionOfBonusEffectFromTraits(int signOfAdditionalTrait) {
        int traitsLeft = Math.min(getTraits().size(), Trait.getTraitLimit()) + 1;
        float goodness = 0, total = 0;

        for(Trait trait : getTraits()) {
            float effect = getTierFromTraitCount(traitsLeft--).getEffectMultiplier();

            total += effect;

            if(trait.effectSign > 0) goodness += effect;
        }

        if(signOfAdditionalTrait != 0) {
            float effect = getTierFromTraitCount(traitsLeft--).getEffectMultiplier();

            total += effect;

            if (signOfAdditionalTrait > 0) goodness += effect;
        }

        return total <= 0 ? INITIAL_RATING : goodness / total;
    }
    public float getAdjustedRating(float adjustmentRating, float adjustmentWeight) {
        return getAdjustedRating(rating, adjustmentRating, adjustmentWeight);
    }
    public float getLoyaltyBonus(PersonAPI captain) {
        int traitsLeft = Math.min(getTraits().size(), Trait.getTraitLimit());
        int loyaltyEffectAdjustment = (ModPlugin.ENABLE_OFFICER_LOYALTY_SYSTEM && captain != null && !captain.isDefault())
                ? getLoyalty(captain).getTraitAdjustment()
                : 0;

        for(Trait trait : getTraits()) {
            if (traitsLeft <= 0) break;

            traitsLeft--;

            if(trait.getType().equals("loyalty")) {
                return trait.getEffect(RepRecord.getTierFromTraitCount(traitsLeft--), loyaltyEffectAdjustment, null) * 0.01f;
            }
        }

        return 0;
    }
    public int getTraitAdjustSign() {
        return (int)Math.signum(getRating() - getFractionOfBonusEffectFromTraits());
    }
    public Trait[] chooseTraitsToShuffle() {
        return chooseTraitsToShuffle(getTraitAdjustSign() , getRating());
    }
    public Trait[] chooseTraitsToShuffle(int sign) {
        return chooseTraitsToShuffle(sign, getRating());
    }
    public Trait[] chooseTraitsToShuffle(int sign, float rating) {
        if(getTraits().size() <= 2) return null;

        float difNow = Math.abs(rating - getFractionOfBonusEffectFromTraits());
        float difWithoutNewestTrait = Math.abs(rating - getFractionOfBonusEffectFromTraits(true));
        Trait newestTrait = getTraits().get(getTraits().size() - 1);

        if(newestTrait.getEffectSign() == -sign && Math.abs(difNow - difWithoutNewestTrait) > 0.1f) {
            return new Trait[] { newestTrait };
        } else {
            for(int i = getTraits().size() - 1; i >= 1; --i) {
                Trait tUp = getTraits().get(i), tDown = getTraits().get(i - 1);

                if(tUp.getEffectSign() == sign && tDown.getEffectSign() == -sign) return new Trait[] { tUp, tDown };


//                int j = t.getEffectSign() > 0 ? i - sign : i + sign;
//                if(j >= 0 && j < getTraits().size() - 1
//                        && getTraits().get(j).getEffectSign() == -t.getEffectSign()) {
//
//                    return t;
//                }
            }
        }

        return null;
    }
    public boolean applyToShip(FleetMemberAPI ship) {
        if(ship == null) return false;

        INSTANCE_REGISTRY.val.put(ship.getId(), this);

        updateRepHullMod(ship);

        return true;
    }
}
