package com.robinhowlett.importer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.robinhowlett.chartparser.charts.pdf.*;
import com.robinhowlett.chartparser.charts.pdf.DistanceSurfaceTrackRecord.RaceDistance;
import com.robinhowlett.chartparser.charts.pdf.running_line.MedicationEquipment;
import com.robinhowlett.chartparser.charts.pdf.running_line.Weight;
import com.robinhowlett.chartparser.charts.pdf.wagering.WagerPayoffPools;
import com.robinhowlett.chartparser.charts.pdf.wagering.WagerPayoffPools.WinPlaceShowPayoffPool;
import com.robinhowlett.chartparser.charts.pdf.wagering.WagerPayoffPools.WinPlaceShowPayoffPool.WinPlaceShowPayoff;
import com.robinhowlett.chartparser.charts.pdf.wagering.WagerPayoffPools.WinPlaceShowPayoffPool.WinPlaceShowPayoff.Win;
import com.robinhowlett.chartparser.charts.pdf.wagering.WagerPayoffPools.WinPlaceShowPayoffPool.WinPlaceShowPayoff.Place;
import com.robinhowlett.chartparser.charts.pdf.wagering.WagerPayoffPools.WinPlaceShowPayoffPool.WinPlaceShowPayoff.Show;
import com.robinhowlett.chartparser.fractionals.FractionalPoint;
import com.robinhowlett.chartparser.fractionals.FractionalPoint.Fractional;
import com.robinhowlett.chartparser.fractionals.FractionalPoint.Split;
import com.robinhowlett.chartparser.points_of_call.PointsOfCall.PointOfCall;
import com.robinhowlett.chartparser.points_of_call.PointsOfCall.PointOfCall.RelativePosition;
import com.robinhowlett.chartparser.points_of_call.PointsOfCall.PointOfCall.RelativePosition.LengthsAhead;
import com.robinhowlett.chartparser.points_of_call.PointsOfCall.PointOfCall.RelativePosition.TotalLengthsBehind;
import com.robinhowlett.chartparser.tracks.Track;
import com.robinhowlett.importer.db.ConnectionPool;
import com.robinhowlett.importer.model.ImportResult;
import com.robinhowlett.importer.pipeline.RaceWriter;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ManualEntryWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: ManualEntryWriter <enriched.json>");
            System.exit(1);
        }

        File inputFile = new File(args[0]);
        if (!inputFile.exists()) {
            System.err.println("File not found: " + args[0]);
            System.exit(1);
        }

        JsonNode root = MAPPER.readTree(inputFile);
        RaceResult raceResult = buildRaceResult(root);

        System.err.printf("Loaded: %s %s R%d (%d starters)%n",
                raceResult.getTrack().getCode(),
                raceResult.getRaceDate(),
                raceResult.getRaceNumber(),
                raceResult.getNumberOfRunners());

        ImportConfig config = ImportConfig.fromEnvironment(Path.of("."));
        try (ConnectionPool pool = ConnectionPool.create(config)) {
            RaceWriter writer = new RaceWriter(pool.getDataSource());
            ImportResult result = writer.write(Path.of(inputFile.getName()), List.of(raceResult));

            if (result.isSuccess()) {
                System.out.println("{\"status\":\"committed\",\"races\":1,\"message\":\"" +
                        raceResult.getTrack().getCode() + " " + raceResult.getRaceDate() +
                        " R" + raceResult.getRaceNumber() + " written to DB\"}");
            } else {
                System.err.println("Write failed: " + result.cause().getMessage());
                System.out.println("{\"status\":\"failed\",\"message\":\"" +
                        result.cause().getMessage().replace("\"", "'").replace("\n", " ") + "\"}");
                System.exit(1);
            }
        }
    }

    static RaceResult buildRaceResult(JsonNode root) {
        Track track = buildTrack(root.path("track"));
        LocalDate raceDate = LocalDate.parse(root.path("raceDate").asText());
        int raceNumber = root.path("raceNumber").asInt();

        // Conditions
        JsonNode cn = root.path("conditions");
        String breed = text(cn, "breed");
        Breed breedEnum;
        try {
            breedEnum = breed != null ? Breed.forCode(breed) : Breed.THOROUGHBRED;
        } catch (Exception e) {
            breedEnum = Breed.THOROUGHBRED;
        }
        RaceTypeNameBlackTypeBreed raceType = new RaceTypeNameBlackTypeBreed(
                text(cn, "type"), text(cn, "name"),
                cn.has("grade") && !cn.path("grade").isNull() ? cn.path("grade").asInt() : null,
                text(cn, "blackType"), breedEnum);

        Purse purse = null;
        JsonNode pn = cn.path("purse");
        if (!pn.isMissingNode()) {
            purse = new Purse(
                    pn.has("value") ? (int) pn.path("value").asLong() : null,
                    text(pn, "text"), null, null, new ArrayList<>());
        }

        RaceConditions raceConditions = new RaceConditions(text(cn, "text"), null, null, raceType, purse);

        // Distance
        JsonNode dn = root.path("distance");
        RaceDistance raceDistance = new RaceDistance(
                text(dn, "text"), text(dn, "compact"),
                !dn.has("exact") || dn.path("exact").asBoolean(),
                dn.has("feet") ? dn.path("feet").asInt() : 0,
                dn.has("runUp") && !dn.path("runUp").isNull() ? dn.path("runUp").asInt() : null,
                dn.has("tempRail") && !dn.path("tempRail").isNull() ? dn.path("tempRail").asInt() : null);

        DistanceSurfaceTrackRecord dst = new DistanceSurfaceTrackRecord(
                raceDistance, text(root, "surface"), text(root, "course"),
                null, null, "Flat", null, text(root, "trackCondition"));

        // Weather
        JsonNode wn = root.path("weather");
        String weatherText = null;
        if (wn.isObject()) weatherText = text(wn, "text");
        else if (wn.isTextual() && !"null".equals(wn.asText())) weatherText = wn.asText();

        // Post time
        PostTimeStartCommentsTimer ptsc = new PostTimeStartCommentsTimer(
                text(root, "postTime"), text(root, "startComments"), null);

        // Fractionals
        List<Fractional> fractionals = buildFractionals(root.path("fractionals"));

        // Splits
        List<Split> splits = buildSplits(root.path("splits"));

        // Build WPS payoff map (program -> WinPlaceShowPayoff) for linking to starters
        JsonNode wagerNode = root.path("wagering");
        Map<String, WinPlaceShowPayoff> wpsMap = buildWpsMap(wagerNode);

        // Starters (with WPS linked, points of call, and individual fractionals)
        List<Starter> starters = buildStarters(root.path("starters"), fractionals, wpsMap);

        // Total WPS pool
        Integer totalWpsPool = null;
        if (root.has("mutuelPool") && !root.path("mutuelPool").isNull()) {
            totalWpsPool = root.path("mutuelPool").asInt();
        }

        // Build WagerPayoffPools for the race level
        WagerPayoffPools wagering = buildWagering(wagerNode, totalWpsPool);

        String footnotes = text(root, "footnotes");

        return new RaceResult(
                Cancellation.notCancelled(), raceDate, track, raceNumber,
                raceConditions, dst,
                weatherText != null ? new RaceResult.Weather(weatherText, null) : null,
                ptsc, false, starters, new ArrayList<>(),
                fractionals, splits, wagering, footnotes, new ArrayList<>());
    }

    static Track buildTrack(JsonNode tn) {
        Track track = new Track();
        track.setCode(text(tn, "code"));
        track.setCanonical(text(tn, "canonical"));
        track.setCountry(text(tn, "country"));
        track.setState(text(tn, "state"));
        track.setCity(text(tn, "city"));
        track.setName(text(tn, "name"));
        return track;
    }

    static List<Fractional> buildFractionals(JsonNode fracNode) {
        List<Fractional> fractionals = new ArrayList<>();
        if (!fracNode.isArray()) return fractionals;
        for (JsonNode f : fracNode) {
            Long millis = f.has("millis") && !f.path("millis").isNull() ? f.path("millis").asLong() : null;
            String time = f.has("time") && !f.path("time").isNull() ? text(f, "time") : null;
            fractionals.add(new Fractional(
                    f.path("point").asInt(), text(f, "text"), text(f, "compact"),
                    f.path("feet").asInt(), time, millis));
        }
        return fractionals;
    }

    static List<Split> buildSplits(JsonNode splitNode) {
        List<Split> splits = new ArrayList<>();
        if (!splitNode.isArray()) return splits;
        for (JsonNode s : splitNode) {
            Long millis = s.has("millis") && !s.path("millis").isNull() ? s.path("millis").asLong() : null;
            String time = s.has("time") && !s.path("time").isNull() ? text(s, "time") : null;
            splits.add(new Split(
                    s.path("point").asInt(), text(s, "text"), text(s, "compact"),
                    s.path("feet").asInt(), time, millis, null, null));
        }
        return splits;
    }

    static Map<String, WinPlaceShowPayoff> buildWpsMap(JsonNode wagerNode) {
        Map<String, WinPlaceShowPayoff> map = new HashMap<>();
        if (wagerNode.isMissingNode()) return map;
        JsonNode wpsNode = wagerNode.path("wps");
        if (!wpsNode.isArray()) return map;

        for (JsonNode w : wpsNode) {
            String pgm = w.has("program") && !w.path("program").isNull()
                    ? String.valueOf(w.path("program").asInt()) : null;
            if (pgm == null) continue;

            Double winVal = w.has("win") && !w.path("win").isNull() ? w.path("win").asDouble() : null;
            Double placeVal = w.has("place") && !w.path("place").isNull() ? w.path("place").asDouble() : null;
            Double showVal = w.has("show") && !w.path("show").isNull() ? w.path("show").asDouble() : null;

            String horse = text(w, "horse");
            Win win = winVal != null ? new Win(winVal) : null;
            Place place = placeVal != null ? new Place(placeVal) : null;
            Show show = showVal != null ? new Show(showVal) : null;

            map.put(pgm, new WinPlaceShowPayoff(pgm, horse != null ? new Horse(horse) : null, win, place, show));
        }
        return map;
    }

    static List<Starter> buildStarters(JsonNode startersNode, List<Fractional> raceFractionals,
                                        Map<String, WinPlaceShowPayoff> wpsMap) {
        List<Starter> starters = new ArrayList<>();
        if (!startersNode.isArray()) return starters;

        // Get finish fractional (last one in the list) for setting on winner
        Fractional finishFrac = raceFractionals.isEmpty() ? null : raceFractionals.get(raceFractionals.size() - 1);

        for (JsonNode sn : startersNode) {
            String horseName = text(sn, "horse");
            Horse horse = new Horse(horseName);

            String jFirst = text(sn, "jockeyFirst");
            String jLast = text(sn, "jockeyLast");
            Jockey jockey = (jFirst != null || jLast != null) ? new Jockey(jFirst, jLast) : null;

            String tFirst = text(sn, "trainerFirst");
            String tLast = text(sn, "trainerLast");
            Trainer trainer = (tFirst != null || tLast != null) ? new Trainer(null, tFirst, tLast) : null;

            String ownerName = text(sn, "owner");
            Owner owner = ownerName != null ? new Owner(null, ownerName) : null;

            Integer weightVal = intOrNull(sn, "weight");
            Weight weight = weightVal != null ? new Weight(weightVal, 0) : null;

            Integer pp = intOrNull(sn, "postPosition");
            Integer finPos = intOrNull(sn, "finishPosition");
            Integer offPos = intOrNull(sn, "officialPosition");
            boolean dq = sn.has("disqualified") && sn.path("disqualified").asBoolean();
            Double odds = sn.has("odds") && !sn.path("odds").isNull() ? sn.path("odds").asDouble() : null;
            boolean winner = offPos != null && offPos == 1;

            String program = pp != null ? String.valueOf(pp) : null;
            String entryProgram = program;

            String med = text(sn, "medication");
            String eq = text(sn, "equipment");
            String meText = ((med != null ? med : "") + (eq != null ? eq : "")).trim();
            MedicationEquipment me = meText.isEmpty() ? null : new MedicationEquipment(meText, null, null);

            String comments = text(sn, "comments");

            // Points of call from positions array
            List<PointOfCall> pointsOfCall = buildPointsOfCall(sn.path("positions"));

            // Individual fractionals from enricher output
            List<Fractional> indivFractionals = new ArrayList<>();
            JsonNode indivFracNode = sn.path("indivFractionals");
            if (indivFracNode.isArray()) {
                for (JsonNode ifn : indivFracNode) {
                    Long ifMillis = ifn.has("millis") && !ifn.path("millis").isNull() ? ifn.path("millis").asLong() : null;
                    String ifTime = ifn.has("time") && !ifn.path("time").isNull() ? text(ifn, "time") : null;
                    indivFractionals.add(new Fractional(
                            ifn.path("point").asInt(), text(ifn, "text"), text(ifn, "compact"),
                            ifn.path("feet").asInt(), ifTime, ifMillis));
                }
            } else if (winner && finishFrac != null) {
                indivFractionals.add(finishFrac);
            }

            // Individual splits from enricher output
            List<Split> indivSplits = new ArrayList<>();
            JsonNode indivSplitNode = sn.path("indivSplits");
            if (indivSplitNode.isArray()) {
                for (JsonNode isn : indivSplitNode) {
                    Long isMillis = isn.has("millis") && !isn.path("millis").isNull() ? isn.path("millis").asLong() : null;
                    String isTime = isn.has("time") && !isn.path("time").isNull() ? text(isn, "time") : null;
                    indivSplits.add(new Split(
                            isn.path("point").asInt(), text(isn, "text"), text(isn, "compact"),
                            isn.path("feet").asInt(), isTime, isMillis, null, null));
                }
            }

            // Link WPS payoff to this starter
            WinPlaceShowPayoff wpsPayoff = program != null ? wpsMap.get(program) : null;

            Starter starter = new Starter(
                    null,           // lastRaced
                    program,
                    entryProgram,
                    false,          // entry
                    horse,
                    jockey,
                    weight,
                    me,
                    pp,
                    odds,
                    odds != null && odds <= 5.0 ? Boolean.TRUE : Boolean.FALSE,
                    comments,
                    pointsOfCall,
                    finPos,
                    offPos != null ? offPos : finPos,
                    false,          // positionDeadHeat
                    offPos != null ? offPos : finPos,
                    trainer,
                    owner,
                    null,           // claim
                    winner,
                    dq ? Boolean.TRUE : Boolean.FALSE,
                    wpsPayoff,
                    new ArrayList<>(),  // ratings
                    indivFractionals,
                    indivSplits,
                    null            // choice
            );
            starters.add(starter);
        }
        return starters;
    }

    static List<PointOfCall> buildPointsOfCall(JsonNode positionsNode) {
        List<PointOfCall> pocs = new ArrayList<>();
        if (!positionsNode.isArray()) return pocs;

        // Map call names to point numbers and distances
        Map<String, int[]> callMap = new HashMap<>();
        callMap.put("Start", new int[]{0, 0});
        callMap.put("1/4", new int[]{1, 1320});
        callMap.put("1/2", new int[]{2, 2640});
        callMap.put("3/4", new int[]{3, 3960});
        callMap.put("Str", new int[]{5, 4620});
        callMap.put("Fin", new int[]{6, 5280});

        for (JsonNode posNode : positionsNode) {
            String call = text(posNode, "call");
            if (call == null) continue;

            int[] info = callMap.get(call);
            if (info == null) continue;

            Integer pos = intOrNull(posNode, "pos");
            String ahead = text(posNode, "ahead");

            Double lengthsAheadValue = parseLengths(ahead);
            LengthsAhead lengthsAhead = lengthsAheadValue != null
                    ? new LengthsAhead(ahead, lengthsAheadValue) : null;

            // Total lengths behind from enricher
            Double tlb = posNode.has("totalLengthsBehind") && !posNode.path("totalLengthsBehind").isNull()
                    ? posNode.path("totalLengthsBehind").asDouble() : null;
            TotalLengthsBehind totalLengthsBehind = tlb != null
                    ? new TotalLengthsBehind(String.valueOf(tlb), tlb) : null;

            RelativePosition relPos = pos != null
                    ? new RelativePosition(pos, lengthsAhead, totalLengthsBehind) : null;

            pocs.add(new PointOfCall(info[0], call, call, info[1], relPos));
        }
        return pocs;
    }

    static Double parseLengths(String text) {
        if (text == null || text.isEmpty()) return null;
        text = text.trim().toLowerCase();

        // Special margin abbreviations
        if (text.equals("ns") || text.equals("nose") || text.equals("no")) return 0.05;
        if (text.equals("nd") || text.equals("nod")) return 0.0;
        if (text.equals("hd") || text.equals("head")) return 0.20;
        if (text.equals("nk") || text.equals("neck")) return 0.25;

        // Fractions: "1/2", "3/4", etc.
        if (text.matches("^\\d+/\\d+$")) {
            String[] parts = text.split("/");
            return Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
        }

        // Mixed: "2 1/2"
        if (text.matches("^\\d+\\s+\\d+/\\d+$")) {
            String[] parts = text.split("\\s+");
            double whole = Double.parseDouble(parts[0]);
            String[] frac = parts[1].split("/");
            return whole + Double.parseDouble(frac[0]) / Double.parseDouble(frac[1]);
        }

        // Plain number
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static WagerPayoffPools buildWagering(JsonNode wagerNode, Integer totalWpsPool) {
        if (wagerNode.isMissingNode()) return null;

        List<WinPlaceShowPayoff> payoffs = new ArrayList<>();
        JsonNode wpsNode = wagerNode.path("wps");
        if (wpsNode.isArray()) {
            for (JsonNode w : wpsNode) {
                String pgm = w.has("program") && !w.path("program").isNull()
                        ? String.valueOf(w.path("program").asInt()) : null;
                String horse = text(w, "horse");
                Double winVal = w.has("win") && !w.path("win").isNull() ? w.path("win").asDouble() : null;
                Double placeVal = w.has("place") && !w.path("place").isNull() ? w.path("place").asDouble() : null;
                Double showVal = w.has("show") && !w.path("show").isNull() ? w.path("show").asDouble() : null;

                payoffs.add(new WinPlaceShowPayoff(pgm, horse != null ? new Horse(horse) : null,
                        winVal != null ? new Win(winVal) : null,
                        placeVal != null ? new Place(placeVal) : null,
                        showVal != null ? new Show(showVal) : null));
            }
        }

        WinPlaceShowPayoffPool wpsPool = payoffs.isEmpty() ? null :
                new WinPlaceShowPayoffPool(totalWpsPool, payoffs);

        return new WagerPayoffPools(wpsPool, new ArrayList<>());
    }

    static String text(JsonNode node, String field) {
        JsonNode val = node.path(field);
        if (val.isMissingNode() || val.isNull()) return null;
        String text = val.asText();
        return text.isEmpty() ? null : text;
    }

    static Integer intOrNull(JsonNode node, String field) {
        JsonNode val = node.path(field);
        if (val.isMissingNode() || val.isNull()) return null;
        return val.asInt();
    }
}
