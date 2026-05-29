package com.robinhowlett.importer.pipeline;

import com.robinhowlett.chartparser.charts.pdf.Horse;
import com.robinhowlett.chartparser.charts.pdf.Starter;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * IMP-T5.5 — countWageringInterests must collapse coupled entries (1 + 1A)
 * and field entries (1 + 1X + 1Y) to a single wagering interest, while
 * leaving uncoupled fields equal to their starter count.
 */
class CountWageringInterestsTest {

    private static Starter starter(String program, String entryProgram, String name) {
        return new Starter(
                /* lastRaced */ null, program, entryProgram,
                /* entry */ !program.equals(entryProgram),
                new Horse(name),
                /* jockey */ null, /* weight */ null, /* medEquip */ null,
                /* postPosition */ null,
                /* odds */ null, /* favorite */ null, /* comments */ null,
                /* pointsOfCall */ null,
                /* finishPosition */ null, /* officialPosition */ null,
                /* positionDeadHeat */ false, /* wageringPosition */ null,
                /* trainer */ null, /* owner */ null, /* claim */ null,
                /* winner */ false, /* disqualified */ false,
                /* wps */ null, /* ratings */ new ArrayList<>(),
                /* fractionals */ new ArrayList<>(), /* splits */ new ArrayList<>(),
                /* choice */ null);
    }

    @Test
    void uncoupled_8HorseField_Returns8() {
        List<Starter> starters = Arrays.asList(
                starter("1", "1", "A"), starter("2", "2", "B"),
                starter("3", "3", "C"), starter("4", "4", "D"),
                starter("5", "5", "E"), starter("6", "6", "F"),
                starter("7", "7", "G"), starter("8", "8", "H"));
        assertEquals(8, RaceWriter.countWageringInterests(starters));
    }

    @Test
    void coupledEntry_1And1A_CollapseToOneInterest() {
        // Classic coupled entry: #1 and #1A run as one wagering interest.
        List<Starter> starters = Arrays.asList(
                starter("1",  "1", "A"),
                starter("1A", "1", "B"),     // coupled with 1
                starter("2",  "2", "C"),
                starter("3",  "3", "D"));
        assertEquals(3, RaceWriter.countWageringInterests(starters),
                "1 + 1A collapses; total interests = 3");
    }

    @Test
    void fieldEntries_1And1XAnd1Y_CollapseToOneInterest() {
        List<Starter> starters = Arrays.asList(
                starter("1",  "1", "A"),
                starter("1X", "1", "B"),
                starter("1Y", "1", "C"),
                starter("2",  "2", "D"));
        assertEquals(2, RaceWriter.countWageringInterests(starters));
    }

    @Test
    void multipleCoupledGroups_CountedSeparately() {
        // Two coupled groups in one race: (1, 1A) and (5, 5A).
        List<Starter> starters = Arrays.asList(
                starter("1",  "1", "A"),
                starter("1A", "1", "B"),
                starter("2",  "2", "C"),
                starter("3",  "3", "D"),
                starter("4",  "4", "E"),
                starter("5",  "5", "F"),
                starter("5A", "5", "G"));
        assertEquals(5, RaceWriter.countWageringInterests(starters));
    }

    @Test
    void emptyList_ReturnsZero() {
        assertEquals(0, RaceWriter.countWageringInterests(Collections.emptyList()));
    }

    @Test
    void nullList_ReturnsZero() {
        assertEquals(0, RaceWriter.countWageringInterests(null));
    }

    @Test
    void starterWithNullEntryProgram_Skipped() {
        List<Starter> starters = Arrays.asList(
                starter("1", "1", "A"),
                starter("2", null, "B"),     // skipped
                starter("3", "3", "C"));
        assertEquals(2, RaceWriter.countWageringInterests(starters));
    }
}
