package com.unitracker.command;

import com.unitracker.model.Skill;

/**
 * Immutable snapshot of every editable Skill field, taken before and after
 * an edit so {@link EditSkillCommand} can restore either state on demand.
 *
 * NOTE: this is a record (unlike {@link Skill} itself, which deliberately
 * is NOT a record - see the design note in Skill.java). The two cases are
 * opposites: Skill must be mutable and observable for JavaFX property
 * binding; a snapshot is exactly the kind of plain, immutable, compared-by-
 * value data a record is designed for.
 */
public record SkillSnapshot(
        String name,
        int parentId,
        String structureType,
        String status,
        String colorHex,
        double targetPoints,
        double currentPoints
) {

    public static SkillSnapshot of(Skill skill) {
        return new SkillSnapshot(
                skill.getName(), skill.getParentId(), skill.getStructureType(),
                skill.getStatus(), skill.getColorHex(), skill.getTargetPoints(), skill.getCurrentPoints());
    }

    /** Writes every field in this snapshot back onto the given (live, bound) Skill. */
    public void applyTo(Skill skill) {
        skill.setName(name);
        skill.setParentId(parentId);
        skill.setStructureType(structureType);
        skill.setStatus(status);
        skill.setColorHex(colorHex);
        skill.setTargetPoints(targetPoints);
        skill.setCurrentPoints(currentPoints);
    }
}
