package com.athenet.notifications.messaging;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public record ResultEnvelope(
        @NotBlank String messageId,
        @NotBlank String occurredAt,
        @NotNull @Pattern(regexp = "resultado\\.registrado") String type,
        @NotNull @Valid ResultData data) {

    public record ResultData(
            @NotNull @Positive Long matchId,
            @NotNull @Positive Long competitionId,
            @NotNull @Positive Long sportId,
            @Positive Long eventId,
            @Min(1) int version,
            @NotNull @Positive Long homeTeamId,
            @NotNull @Positive Long awayTeamId,
            Long homeInstitutionId,
            Long awayInstitutionId,
            @NotNull @Min(0) Integer homeScore,
            @NotNull @Min(0) Integer awayScore,
            boolean simulated,
            @NotNull @Valid Points points,
            @NotBlank @Size(max = 150) String competitionName,
            @NotBlank @Size(max = 150) String homeTeamName,
            @NotBlank @Size(max = 150) String awayTeamName,
            @NotBlank @Size(max = 150) String round) {
        public ResultData {
            competitionName = cleanLabel(competitionName);
            homeTeamName = cleanLabel(homeTeamName);
            awayTeamName = cleanLabel(awayTeamName);
            round = cleanLabel(round);
        }

        @JsonIgnore
        @AssertTrue(message = "El resultado de copa no admite empate")
        public boolean isDecisiveResult() {
            // Los valores ausentes se rechazan mediante @NotNull.
            return homeScore == null || awayScore == null || !homeScore.equals(awayScore);
        }

        public int homePoints() { return homeScore > awayScore ? points.win() : homeScore < awayScore ? points.loss() : points.draw(); }
        public int awayPoints() { return homeScore < awayScore ? points.win() : homeScore > awayScore ? points.loss() : points.draw(); }

        private static String cleanLabel(String value) {
            // Incluye saltos de línea Unicode y controles de formato, además de CR/LF.
            return value == null ? null : value.replaceAll("[\\p{Cc}\\p{Cf}\\p{Z}]", " ").strip();
        }
    }

    public record Points(@NotNull @Min(0) Integer win, @NotNull @Min(0) Integer draw, @NotNull @Min(0) Integer loss) {}
}
